package co.wethinkcode.healthsafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
            String wardId = ctx.pathParam("wardId");

            JsonNode ward = fetchWard(ctx, wardId);
            if (ward == null) return; // error response already written

            Integer alertLevel = fetchAlertLevel(ctx);
            if (alertLevel == null) return; // error response already written

            List<String> onCall = ON_CALL_POOL.subList(0, Math.min(onCallCount(alertLevel), ON_CALL_POOL.size()));

            ctx.json(Map.of(
                    "wardId", ward.get("wardId").asText(),
                    "department", ward.get("department").asText(),
                    "alertLevel", alertLevel,
                    "onCallDoctors", onCall
            ));
        });

        // MQ TODO: publishes to ActiveMQ topic MqConfig.TOPIC at MqConfig.BROKER_URL (see co.wethinkcode.healthsafe.mq.MqConfig)
    }

    private static JsonNode fetchWard(io.javalin.http.Context ctx, String wardId) {
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

    private static Integer fetchAlertLevel(io.javalin.http.Context ctx) {
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
}
