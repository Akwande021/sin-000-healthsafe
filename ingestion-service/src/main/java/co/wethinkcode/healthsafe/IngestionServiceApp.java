package co.wethinkcode.healthsafe;

import com.opencsv.CSVReader;
import io.javalin.Javalin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

public class IngestionServiceApp {

    public static void main(String[] args) throws Exception {
        List<WardRecord> cleanedWards = loadAndClean();

        Javalin app = Javalin.create().start(7030);

        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/wards", ctx -> ctx.json(cleanedWards));

        // TODO: read and clean src/main/resources/wards-outdated.csv (wards, wings, specialist departments data —
        // trim whitespace, fix casing, normalize dates/booleans) and expose the
        // cleaned records here for the other services to consume.
    }
    private static List<WardRecord> loadAndClean() throws Exception {
        Map<String, WardRecord> byId = new LinkedHashMap<>();

        try (InputStream in = IngestionServiceApp.class.getClassLoader()
                .getResourceAsStream("wards-outdated.csv");
             CSVReader reader = new CSVReader(new InputStreamReader(in))) {

            reader.readNext(); // skip header
            String[] row;
            while ((row = reader.readNext()) != null) {
                mergeInto(byId, cleanRow(row));
            }
        }
        return new ArrayList<>(byId.values());
    }

    private static void mergeInto(Map<String, WardRecord> byId, Object o) {
    }

    private static Object cleanRow(String[] row) {
        return null;
    }

}
