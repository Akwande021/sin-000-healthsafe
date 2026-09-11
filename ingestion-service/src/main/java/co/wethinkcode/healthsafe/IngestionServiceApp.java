package co.wethinkcode.healthsafe;

import io.javalin.Javalin;
import java.util.*;

public class IngestionServiceApp {

    public static void main(String[] args) throws Exception {
        List<WardRecord> cleanedWards = loadAndClean();

        Javalin app = Javalin.create().start(7030);

        app.get("/health", ctx -> ctx.result("OK"));



        // TODO: read and clean src/main/resources/wards-outdated.csv (wards, wings, specialist departments data —
        // trim whitespace, fix casing, normalize dates/booleans) and expose the
        // cleaned records here for the other services to consume.
    }
    private static List<WardRecord> loadAndClean() throws Exception {



        return List.of();
    }

}
