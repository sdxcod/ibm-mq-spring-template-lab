package dev.sdxcod.springmqlab.transport;

import com.ibm.mq.jakarta.jms.MQQueue;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import jakarta.jms.*;
import org.springframework.jms.connection.JmsTransactionManager;
import org.springframework.jms.connection.SingleConnectionFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.support.JmsUtils;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.IllegalStateException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One connection scope, Spring-managed per-message transactions, no listener container.
 */
public final class SpringMqOperations implements MqOperations {
    private final SingleConnectionFactory connection;
    private final JmsTemplate jms;
    private final TransactionTemplate transactions;
    private final Thread owner = Thread.currentThread();
    private boolean closed, poisoned;

    public SpringMqOperations(SingleConnectionFactory connection, long receiveTimeoutMs) {
        this.connection = connection;
        jms = new JmsTemplate(connection);

        jms.setSessionTransacted(true);
        jms.setReceiveTimeout(receiveTimeoutMs == 0 ? JmsTemplate.RECEIVE_TIMEOUT_NO_WAIT : receiveTimeoutMs);
        jms.setExplicitQosEnabled(true);
        jms.setDeliveryMode(DeliveryMode.PERSISTENT);

        var manager = new JmsTransactionManager(connection);

        manager.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_ALWAYS);
        transactions = new TransactionTemplate(manager);
    }

    private void ready() {
        if (closed || poisoned || Thread.currentThread() != owner)
            throw new IllegalStateException("Connection scope is unavailable; restart shell after an unknown send outcome");
    }

    private static Queue destination(String name) {
        if (name == null || !name.matches("[A-Za-z0-9._/%]{1,48}"))
            throw new IllegalArgumentException("Invalid queue name");
        try {
            var q = new MQQueue(name);
            q.setTargetClient(WMQConstants.WMQ_CLIENT_NONJMS_MQ);
            q.setCCSID(1208);
            return q;
        } catch (JMSException e) {
            throw JmsUtils.convertJmsAccessException(e);
        }
    }

    private static void validateText(String text) {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > 1048576)
            throw new IllegalArgumentException("Text must fit 1 MiB UTF-8");
    }

    @Override
    public SendResult send(String queue, String text, boolean rollback) {
        ready();
        validateText(text);

        var destination = destination(queue);
        var attempted = new AtomicBoolean();
        var completion = new AtomicInteger(TransactionSynchronization.STATUS_UNKNOWN);
        try {
            return transactions.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int value) {
                        completion.set(value);
                    }
                });

                attempted.set(true);
                var message = new AtomicReference<TextMessage>();

                jms.send(destination, session -> {
                    var m = session.createTextMessage(text);
                    message.set(m);
                    return m;
                });

                String id;
                try {
                    id = Objects.requireNonNull(message.get()).getJMSMessageID();
                } catch (JMSException e) {
                    throw JmsUtils.convertJmsAccessException(e);
                }

                if (rollback)
                    status.setRollbackOnly();
                return new SendResult(id, !rollback);
            });
            // Spring commits here, before the caller sees a successful result.
        } catch (RuntimeException e) {
            SendFailure.Kind kind = !attempted.get() ||
                    completion.get() == TransactionSynchronization.STATUS_ROLLED_BACK
                    ? SendFailure.Kind.RETRYABLE : SendFailure.Kind.UNKNOWN;

            if (kind == SendFailure.Kind.UNKNOWN)
                poisoned = true;
            throw new SendFailure(kind, e);
        }
    }

    @Override
    public Optional<ReceivedMessage> receive(String queue,
                                             String id,
                                             Consumer<ReceivedMessage> handler,
                                             boolean rollback) {
        ready();
        var destination = destination(queue);
        String selector = MessageIds.selector(id);

        return transactions.execute(status -> {
            Message message = selector == null ? jms.receive(destination) : jms.receiveSelected(destination, selector);

            if (message == null)
                return Optional.empty();
            try {
                if (!(message instanceof TextMessage text))
                    throw new IllegalArgumentException("This lab accepts TextMessage only");
                String payload = text.getText();
                validateText(payload);

                var received = new ReceivedMessage(message.getJMSMessageID(), payload, message.getJMSRedelivered(),
                        message.getIntProperty("JMSXDeliveryCount"),
                        message.getJMSDeliveryMode() == DeliveryMode.PERSISTENT);
                handler.accept(received); // Inside the same transaction as receive.

                if (rollback)
                    status.setRollbackOnly();
                return Optional.of(received);
            } catch (JMSException e) {
                throw JmsUtils.convertJmsAccessException(e);
            }
        });
    }

    @Override
    public int browse(String queue, String id, int limit) {
        ready();

        if (limit < 1 || limit > 5000)
            throw new IllegalArgumentException("Browse limit must be 1..5000");

        Integer result = jms.browseSelected(destination(queue), MessageIds.selector(id),
                (session, browser) -> {
                    var enumeration = browser.getEnumeration();
                    int count = 0;

                    while (count < limit && enumeration.hasMoreElements()) {
                        enumeration.nextElement();
                        count++;
                    }

                    return count;
                });

        if (result == null) {
            throw new IllegalStateException(
                    "Browse callback unexpectedly returned null");
        }

        return result;
    }

    @Override
    public void close() {
        if (!closed) {
            if (Thread.currentThread() != owner)
                throw new IllegalStateException("Wrong thread");
            closed = true;
            connection.destroy();
        }
    }
}
