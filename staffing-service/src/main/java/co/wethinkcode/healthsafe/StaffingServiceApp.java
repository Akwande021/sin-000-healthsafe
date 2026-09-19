package co.wethinkcode.healthsafe;

import co.wethinkcode.healthsafe.mq.MqConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import javax.jms.Connection;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.Topic;
import org.apache.activemq.ActiveMQConnectionFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StaffingServiceApp {

    private static final String WARD_SERVICE_URL = "http://localhost:7031";
    private static final String ALERT_LEVEL_SERVICE_URL = "http://localhost:7032";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    // Invented sample roster — no doctor data source exists elsewhere in this repo.
    // On-call headcount scales with Emergency Status (0-8): low/med/high tiers below.
    private static final List<String> ON_CALL_POOL = List.of(
            "Dr. Adams", "Dr. Baloyi", "Dr. Chen");

    public static void main(String[] args) {
        Javalin app = Javalin.create().start(7033);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/on-call/{wardId}", ctx -> {
            Map<String, Object> schedule = computeSchedule(ctx, ctx.pathParam("wardId"));
            if (schedule != null) ctx.json(schedule);
        });

        // Computes the same schedule as the GET above, but also broadcasts it on
        // staffing-events-topic — this is the "schedule/status change" event stage 3
        // asks for. Broadcast is fire-and-forget: a broker/consumer hiccup doesn't
        // fail the request, it's just reported via "broadcast": false.
        app.post("/on-call/{wardId}", ctx -> {
            Map<String, Object> schedule = computeSchedule(ctx, ctx.pathParam("wardId"));
            if (schedule == null) return;
            boolean broadcast = publishScheduleUpdate(schedule);
            Map<String, Object> response = new LinkedHashMap<>(schedule);
            response.put("broadcast", broadcast);
            ctx.json(response);
        });
    }

    private static Map<String, Object> computeSchedule(Context ctx, String wardId) {
        JsonNode ward = fetchWard(ctx, wardId);
        if (ward == null) return null; // error response already written

        Integer alertLevel = fetchAlertLevel(ctx);
        if (alertLevel == null) return null; // error response already written

        List<String> onCall = ON_CALL_POOL.subList(0, Math.min(onCallCount(alertLevel), ON_CALL_POOL.size()));

        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("wardId", ward.get("wardId").asText());
        schedule.put("department", ward.get("department").asText());
        schedule.put("alertLevel", alertLevel);
        schedule.put("onCallDoctors", onCall);
        return schedule;
    }

    private static JsonNode fetchWard(Context ctx, String wardId) {
        try {
            HttpResponse<String> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(WARD_SERVICE_URL + "/wards/" + wardId)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "unknown ward: " + wardId));
                return null;
            }
            if (response.statusCode() != 200) {
                ctx.status(HttpStatus.BAD_GATEWAY).json(Map.of("error", "ward-service returned " + response.statusCode()));
                return null;
            }
            return MAPPER.readTree(response.body());
        } catch (Exception e) {
            ctx.status(HttpStatus.BAD_GATEWAY).json(Map.of("error", "ward-service unavailable: " + e.getMessage()));
            return null;
        }
    }

    private static Integer fetchAlertLevel(Context ctx) {
        try {
            HttpResponse<String> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(ALERT_LEVEL_SERVICE_URL + "/alert-level")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                ctx.status(HttpStatus.BAD_GATEWAY).json(Map.of("error", "alert-level-service returned " + response.statusCode()));
                return null;
            }
            return MAPPER.readTree(response.body()).get("level").asInt();
        } catch (Exception e) {
            ctx.status(HttpStatus.BAD_GATEWAY).json(Map.of("error", "alert-level-service unavailable: " + e.getMessage()));
            return null;
        }
    }

    private static int onCallCount(int alertLevel) {
        if (alertLevel >= 6) return 3;
        if (alertLevel >= 3) return 2;
        return 1;
    }

    private static boolean publishScheduleUpdate(Map<String, Object> schedule) {
        String url = "failover:(" + MqConfig.BROKER_URL + ")";
        try (Connection connection = new ActiveMQConnectionFactory(url).createConnection()) {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Topic topic = session.createTopic(MqConfig.TOPIC);
            MessageProducer producer = session.createProducer(topic);
            producer.send(session.createTextMessage(MAPPER.writeValueAsString(schedule)));
            return true;
        } catch (Exception e) {
            System.err.println("Failed to publish staffing update to " + MqConfig.TOPIC + ": " + e.getMessage());
            return false;
        }
    }
}
