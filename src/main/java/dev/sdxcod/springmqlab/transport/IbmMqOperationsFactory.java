package dev.sdxcod.springmqlab.transport;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import dev.sdxcod.springmqlab.config.LabProperties;
import dev.sdxcod.springmqlab.config.QueueSettings;
import org.springframework.jms.connection.SingleConnectionFactory;

public final class IbmMqOperationsFactory implements MqOperationsFactory {
    private final LabProperties properties;

    public IbmMqOperationsFactory(LabProperties properties) {
        this.properties = properties;
    }

    @Override
    public MqOperations open(QueueSettings settings) throws Exception {
        var raw = new MQConnectionFactory();
        raw.setHostName(settings.host());
        raw.setPort(settings.port());
        raw.setQueueManager(settings.queueManager());
        raw.setChannel(settings.channel());
        raw.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        raw.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        raw.setClientReconnectOptions(WMQConstants.WMQ_CLIENT_RECONNECT_DISABLED);
        raw.setStringProperty(WMQConstants.USERID, properties.user());
        raw.setStringProperty(WMQConstants.PASSWORD, properties.resolvePassword());
        // Spring owns one physical connection per cycle. Each message gets its own transaction/session.
        var shared = new SingleConnectionFactory(raw);
        shared.setReconnectOnException(false);
        return new SpringMqOperations(shared, settings.receiveTimeoutMs());
    }
}
