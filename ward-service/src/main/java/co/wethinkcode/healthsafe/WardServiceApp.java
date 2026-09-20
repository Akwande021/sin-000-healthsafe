package co.wethinkcode.healthsafe;

import co.wethinkcode.healthsafe.mq.MqConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class WardServiceApp {

    private static final String INGESTION_WARDS_URL = "http://localhost:7030/wards";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final AtomicReference<List<Ward>> cache = new AtomicReference<>(List.of());

    // Latest staffing-events-topic broadcast per ward, keyed by uppercased wardId.
    private static final Map<String, JsonNode> staffingUpdates = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        refreshWards();
        subscribeToStaffingUpdates();

        Javalin app = Javalin.create().start(7031);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/wards", ctx -> ctx.json(wardsOrRefresh()));

        app.get("/wards/{id}", ctx -> {
            Ward match = findWard(ctx.pathParam("id"));
            if (match == null) {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "unknown ward: " + ctx.pathParam("id")));
            } else {
                ctx.json(match);
            }
        });

        app.get("/departments", ctx -> {
            var departments = new TreeSet<String>();
            for (Ward w : wardsOrRefresh()) departments.add(w.department());
            ctx.json(departments);
        });

        // Reacts to staffing-service's broadcasts on staffing-events-topic instead of
        // polling staffing-service directly — see subscribeToStaffingUpdates().
        app.get("/wards/{id}/staffing", ctx -> {
            String id = ctx.pathParam("id").trim().toUpperCase();
            JsonNode update = staffingUpdates.get(id);
            if (update == null) {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "no staffing update received yet for ward: " + id));
            } else {
                ctx.json(update);
            }
        });

        // Reports an equipment failure on a ward. There's no equipment/sensor data
        // source anywhere in this repo, so this is a manual trigger standing in for
        // "detecting" one. Delivery is guaranteed via a persistent queue message
        // (see publishEquipmentFailure) — unlike the topic broadcast above, a publish
        // failure here is a real failure of the guarantee, so it's a 502, not a soft flag.
        app.post("/wards/{id}/equipment-failure", ctx -> {
            Ward ward = findWard(ctx.pathParam("id"));
            if (ward == null) {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "unknown ward: " + ctx.pathParam("id")));
                return;
            }

            String equipment = "unspecified equipment";
            String description = null;
            try {
                JsonNode body = ctx.body().isBlank() ? null : MAPPER.readTree(ctx.body());
                if (body != null && body.hasNonNull("equipment")) equipment = body.get("equipment").asText();
                if (body != null && body.hasNonNull("description")) description = body.get("description").asText();
            } catch (Exception ignored) {
                // malformed body just falls back to the defaults above
            }

            Map<String, Object> alert = new LinkedHashMap<>();
            alert.put("wardId", ward.wardId());
            alert.put("department", ward.department());
            alert.put("equipment", equipment);
            alert.put("description", description);
            alert.put("reportedAt", Instant.now().toString());

            if (publishEquipmentFailure(alert)) {
                ctx.status(HttpStatus.ACCEPTED).json(alert);
            } else {
                ctx.status(HttpStatus.BAD_GATEWAY).json(Map.of("error", "could not deliver equipment failure alert: broker unavailable"));
            }
        });
    }

    private static Ward findWard(String id) {
        String trimmed = id.trim();
        return wardsOrRefresh().stream()
                .filter(w -> w.wardId().equalsIgnoreCase(trimmed))
                .findFirst()
                .orElse(null);
    }

    // Persistent delivery mode + a short-lived connection per publish: the queue
    // guarantees the message survives a broker restart and reaches exactly one
    // consumer, even if equipment-alert-service is briefly down when this fires.
    private static boolean publishEquipmentFailure(Map<String, Object> alert) {
        String url = "failover:(" + MqConfig.BROKER_URL + ")";
        try (Connection connection = new ActiveMQConnectionFactory(url).createConnection()) {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue(MqConfig.QUEUE);
            MessageProducer producer = session.createProducer(queue);
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);
            producer.send(session.createTextMessage(MAPPER.writeValueAsString(alert)));
            return true;
        } catch (Exception e) {
            System.err.println("Failed to publish equipment failure to " + MqConfig.QUEUE + ": " + e.getMessage());
            return false;
        }
    }

    // failover: keeps retrying in the background instead of throwing if the broker
    // isn't up yet, so a slow/late broker doesn't stop this service from starting.
    private static void subscribeToStaffingUpdates() {
        try {
            Connection connection = new ActiveMQConnectionFactory("failover:(" + MqConfig.BROKER_URL + ")").createConnection();
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Topic topic = session.createTopic(MqConfig.TOPIC);
            session.createConsumer(topic).setMessageListener(message -> {
                try {
                    JsonNode update = MAPPER.readTree(((TextMessage) message).getText());
                    String wardId = update.get("wardId").asText().toUpperCase();
                    staffingUpdates.put(wardId, update);
                    System.out.println("Received staffing update for ward " + wardId);
                } catch (Exception e) {
                    System.err.println("Failed to process staffing update: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            System.err.println("Failed to subscribe to " + MqConfig.TOPIC + ": " + e.getMessage());
        }
    }

    private static List<Ward> wardsOrRefresh() {
        List<Ward> wards = cache.get();
        return wards.isEmpty() ? refreshWards() : wards;
    }

    private static List<Ward> refreshWards() {
        try {
            HttpResponse<String> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(INGESTION_WARDS_URL)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("ingestion-service returned " + response.statusCode() + " for " + INGESTION_WARDS_URL);
                return cache.get();
            }
            List<Ward> wards = List.of(MAPPER.readValue(response.body(), Ward[].class));
            cache.set(wards);
            return wards;
        } catch (Exception e) {
            System.err.println("Failed to fetch wards from ingestion-service: " + e.getMessage());
            return cache.get();
        }
    }
}
