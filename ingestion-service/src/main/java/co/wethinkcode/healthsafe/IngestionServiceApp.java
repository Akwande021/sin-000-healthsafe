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

    private static WardRecord cleanRow(String[] row) {
        String wardId = normalizeWhitespace(row[0]).toUpperCase();
        String wing = titleCase(normalizeWhitespace(row[1]));
        String department = normalizeDepartment(normalizeWhitespace(row[2]));

        String rawBeds = normalizeWhitespace(row[3]);
        String lower = rawBeds.toLowerCase();
        Integer beds = null;
        String note = null;

        if (rawBeds.isEmpty() || lower.equals("n/a") || lower.equals("tbd")
                || lower.equals("unknown") || rawBeds.equals("-") || lower.equals("nan")) {
            note = "bedsAvailable missing ('" + rawBeds + "') — flagged for follow-up";
        } else {
            try {
                int value = Integer.parseInt(rawBeds);
                if (value < 0 || value > 200) {
                    note = "bedsAvailable out of realistic range ('" + rawBeds + "') — flagged for follow-up";
                } else {
                    beds = value;
                }
            } catch (NumberFormatException e) {
                note = "bedsAvailable was non-numeric ('" + rawBeds + "') — flagged for follow-up";
            }
        }
        return new WardRecord(wardId, wing, department, beds, note);
    }

    private static void mergeInto(Map<String, WardRecord> byId, WardRecord incoming) {
        WardRecord existing = byId.get(incoming.getWardId());
        if (existing == null) {
            byId.put(incoming.getWardId(), incoming);
            return;
        }
        WardRecord keep = (existing.getBedsAvailable() != null) ? existing : incoming;
        String mergedNote = "merged duplicate record for " + incoming.getWardId();
        if (keep.getNotes() != null) mergedNote = keep.getNotes() + "; " + mergedNote;

        byId.put(incoming.getWardId(), new WardRecord(
                keep.getWardId(), keep.getWing(), keep.getDepartment(), keep.getBedsAvailable(), mergedNote));
    }

    private static String normalizeWhitespace(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    private static String titleCase(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.split(" ")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    private static String normalizeDepartment(String s) {
        String titled = titleCase(s);
        return titled.equalsIgnoreCase("Pediatrics") ? "Paediatrics" : titled;
    }

}
