package co.wethinkcode.healthsafe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

public class WardServiceApp {

    private static final String INGESTION_WARDS_URL = "http://localhost:7030/wards";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final AtomicReference<List<Ward>> cache = new AtomicReference<>(List.of());

    public static void main(String[] args) {
        refreshWards();

        Javalin app = Javalin.create().start(7031);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/wards", ctx -> ctx.json(wardsOrRefresh()));

        app.get("/wards/{id}", ctx -> {
            String id = ctx.pathParam("id").trim();
            Ward match = wardsOrRefresh().stream()
                    .filter(w -> w.wardId().equalsIgnoreCase(id))
                    .findFirst()
                    .orElse(null);
            if (match == null) {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "unknown ward: " + id));
            } else {
                ctx.json(match);
            }
        });

        app.get("/departments", ctx -> {
            var departments = new TreeSet<String>();
            for (Ward w : wardsOrRefresh()) departments.add(w.department());
            ctx.json(departments);
        });

        // MQ TODO: subscribes to ActiveMQ topic MqConfig.TOPIC at MqConfig.BROKER_URL (see co.wethinkcode.healthsafe.mq.MqConfig)
        // MQ TODO: publishes to ActiveMQ queue MqConfig.QUEUE when it detects an equipment failure on one of its wards.
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
