package co.wethinkcode.healthsafe;

import co.wethinkcode.healthsafe.mq.MqConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class EquipmentAlertServiceApp {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Alerts that have been fully processed and acknowledged — exposed only so
    // GET /alerts can demonstrate/verify guaranteed delivery manually.
    private static final List<JsonNode> receivedAlerts = new CopyOnWriteArrayList<>();

    public static void main(String[] args) {
        Javalin app = Javalin.create().start(7034);

        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/alerts", ctx -> ctx.json(receivedAlerts));

        consumeEquipmentFailures();
    }

    // CLIENT_ACKNOWLEDGE (not AUTO_ACKNOWLEDGE, and not just a bare listener): the
    // message is only acknowledged after it's durably recorded here. If processing
    // throws first, it's left unacknowledged and ActiveMQ will redeliver it — that's
    // the guaranteed-delivery contract a queue exists for, in contrast to the
    // fire-and-forget topic in ward-service/staffing-service.
    private static void consumeEquipmentFailures() {
        try {
            Connection connection = new ActiveMQConnectionFactory("failover:(" + MqConfig.BROKER_URL + ")").createConnection();
            connection.start();
            Session session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
            Queue queue = session.createQueue(MqConfig.QUEUE);
            session.createConsumer(queue).setMessageListener(EquipmentAlertServiceApp::handle);
        } catch (Exception e) {
            System.err.println("Failed to subscribe to " + MqConfig.QUEUE + ": " + e.getMessage());
        }
    }

    private static void handle(Message message) {
        try {
            JsonNode alert = MAPPER.readTree(((TextMessage) message).getText());
            receivedAlerts.add(alert);
            System.out.println("Equipment failure alert received and recorded: " + alert);
            message.acknowledge();
        } catch (Exception e) {
            System.err.println("Failed to process equipment failure alert, leaving unacknowledged for redelivery: " + e.getMessage());
        }
    }
}
