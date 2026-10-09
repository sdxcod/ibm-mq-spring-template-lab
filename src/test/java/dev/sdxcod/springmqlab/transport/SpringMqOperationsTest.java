package dev.sdxcod.springmqlab.transport;

import jakarta.jms.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jms.connection.SingleConnectionFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpringMqOperationsTest {
    private ConnectionFactory raw;
    private Connection connection;
    private Session session;
    private MessageProducer producer;
    private MessageConsumer consumer;
    private TextMessage message;
    private SingleConnectionFactory shared;
    private SpringMqOperations ops;
    private final String id = "ID:" + "A1".repeat(24);

    @BeforeEach
    void setup() throws Exception {
        raw = mock(ConnectionFactory.class);
        connection = mock(Connection.class);
        session = mock(Session.class);
        producer = mock(MessageProducer.class);
        consumer = mock(MessageConsumer.class);
        message = mock(TextMessage.class);
        when(raw.createConnection()).thenReturn(connection);
        when(connection.createSession(anyBoolean(), anyInt())).thenReturn(session);
        when(session.getTransacted()).thenReturn(true);
        when(session.createProducer(any(Destination.class))).thenReturn(producer);
        when(session.createConsumer(any(Destination.class), nullable(String.class))).thenReturn(consumer);
        when(session.createConsumer(any(Destination.class))).thenReturn(consumer);
        when(session.createTextMessage(anyString())).thenReturn(message);
        when(message.getJMSMessageID()).thenReturn(id);
        when(message.getText()).thenReturn("سلام");
        when(message.getIntProperty("JMSXDeliveryCount")).thenReturn(1);
        when(message.getJMSDeliveryMode()).thenReturn(DeliveryMode.PERSISTENT);
        shared = new SingleConnectionFactory(raw);
        ops = new SpringMqOperations(shared, 1000);
    }

    @AfterEach
    void close() {
        ops.close();
        assertFalse(TransactionSynchronizationManager.hasResource(shared));
    }

    @Test
    void sendCommitsViaSpringAndClosesConnectionOnlyAtScopeEnd() throws Exception {
        assertEquals(new SendResult(id, true), ops.send("DEV.LAB.OUT", "hello", false));
        var order = inOrder(producer, session);
        order.verify(producer).send(same(message), eq(DeliveryMode.PERSISTENT), anyInt(), anyLong());
        order.verify(session).commit();
        verify(session, never()).rollback();
        verify(connection, never()).close();
        ops.close();
        verify(connection).close();
    }

    @Test
    void rollbackOnlySendDoesNotCommit() throws Exception {
        assertFalse(ops.send("DEV.LAB.OUT", "discard", true).committed());
        verify(session).rollback();
        verify(session, never()).commit();
    }

    @Test
    void handlerRunsInsideReceiveTransactionBeforeCommit() throws Exception {
        when(consumer.receive(1000)).thenReturn(message);
        var handled = new AtomicBoolean();
        ops.receive("DEV.LAB.IN", null, m -> {
            assertTrue(TransactionSynchronizationManager.hasResource(shared));
            handled.set(true);
        }, false);
        assertTrue(handled.get());
        var order = inOrder(consumer, session);
        order.verify(consumer).receive(1000);
        order.verify(session).commit();
        verify(message, never()).acknowledge();
    }

    @Test
    void handlerFailureRollsBackAndEscapes() throws Exception {
        when(consumer.receive(1000)).thenReturn(message);
        assertThrows(IllegalArgumentException.class, () -> ops.receive("DEV.LAB.IN", null, m -> {
            throw new IllegalArgumentException("reject");
        }, false));
        verify(session).rollback();
        verify(session, never()).commit();
    }

    @Test
    void unsupportedMessageIsNotLost() throws Exception {
        when(consumer.receive(1000)).thenReturn(mock(BytesMessage.class));
        assertThrows(IllegalArgumentException.class, () -> ops.receive("DEV.LAB.IN", null, m -> {
        }, false));
        verify(session).rollback();
    }

    @Test
    void zeroTimeoutUsesReceiveNoWaitAndEmptyReceiveSkipsHandler() throws Exception {
        ops.close();
        ops = new SpringMqOperations(new SingleConnectionFactory(raw), 0);
        assertTrue(ops.receive("DEV.LAB.IN", null, m -> {
            fail("empty receive must not invoke handler");
        }, false).isEmpty());
        verify(consumer).receiveNoWait();
        verify(consumer, never()).receive(anyLong());
    }

    @Test
    void genericCommitFailureIsUnknownAndBlocksScopeReuse() throws Exception {
        doThrow(new JMSException("commit lost")).when(session).commit();
        var failure = assertThrows(SendFailure.class, () -> ops.send("DEV.LAB.OUT", "hello", false));
        assertEquals(SendFailure.Kind.UNKNOWN, failure.kind());
        assertThrows(java.lang.IllegalStateException.class, () -> ops.send("DEV.LAB.OUT", "retry", false));
    }

    @Test
    void sendFailureWithConfirmedRollbackIsRetryable() throws Exception {
        doThrow(new JMSException("put failed")).when(producer).send(any(Message.class), anyInt(), anyInt(), anyLong());
        var failure = assertThrows(SendFailure.class, () -> ops.send("DEV.LAB.OUT", "hello", false));
        assertEquals(SendFailure.Kind.RETRYABLE, failure.kind());
        verify(session).rollback();
    }

    @Test
    void beginFailureDoesNotAttemptSendAndIsRetryable() throws Exception {
        doThrow(new JMSException("cannot connect")).when(raw).createConnection();
        var failure = assertThrows(SendFailure.class, () -> ops.send("DEV.LAB.OUT", "hello", false));
        assertEquals(SendFailure.Kind.RETRYABLE, failure.kind());
        verifyNoInteractions(producer);
    }

    @Test
    void twoMessagesReuseConnectionButCompleteTwoTransactions() throws Exception {
        ops.send("DEV.LAB.OUT", "one", false);
        ops.send("DEV.LAB.OUT", "two", false);
        verify(raw, times(1)).createConnection();
        verify(session, times(2)).commit();
    }

    @Test
    void selectorIsValidatedBeforeProviderAndNormalized() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ops.receive("DEV.LAB.IN", "x' OR TRUE", m -> {
        }, false));
        verifyNoInteractions(consumer);
        ops.receive("DEV.LAB.IN", "a1".repeat(24), m -> {
        }, false);
        verify(session).createConsumer(any(Destination.class), eq("JMSMessageID = '" + id + "'"));
    }

    @Test
    void unreadableBodyRollsBack() throws Exception {
        when(consumer.receive(1000)).thenReturn(message);
        when(message.getText()).thenThrow(new JMSException("decode failed"));
        assertThrows(RuntimeException.class, () -> ops.receive("DEV.LAB.IN", null, m -> {
        }, false));
        verify(session).rollback();
    }

    @Test
    void browserDoesNotConsumeMessages() throws Exception {
        QueueBrowser browser = mock(QueueBrowser.class);
        when(session.createBrowser(any(Queue.class), nullable(String.class))).thenReturn(browser);
        when(browser.getEnumeration()).thenReturn(Collections.enumeration(java.util.List.of(message, message, message)));
        assertEquals(2, ops.browse("DEV.LAB.IN", null, 2));
        verify(browser).close();
        verifyNoInteractions(consumer);
    }
}
