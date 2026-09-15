package co.wethinkcode.healthsafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AlertLevelServiceApp {

    private static final int MIN_LEVEL = 0;
    private static final int MAX_LEVEL = 8;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger level = new AtomicInteger(MIN_LEVEL);

    public static void main(String[] args) {
        Javalin app = Javalin.create().start(7032);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/alert-level", ctx -> ctx.json(Map.of("level", level.get())));

        app.post("/alert-level", ctx -> {
            JsonNode body;
            try {
                body = MAPPER.readTree(ctx.body());
            } catch (Exception e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "invalid JSON body"));
                return;
            }
            JsonNode levelNode = body == null ? null : body.get("level");
            if (levelNode == null || !levelNode.isInt()) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "'level' must be an integer"));
                return;
            }
            int newLevel = levelNode.asInt();
            if (newLevel < MIN_LEVEL || newLevel > MAX_LEVEL) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of(
                        "error", "'level' must be between " + MIN_LEVEL + " and " + MAX_LEVEL));
                return;
            }
            level.set(newLevel);
            ctx.json(Map.of("level", level.get()));
        });
    }
}
