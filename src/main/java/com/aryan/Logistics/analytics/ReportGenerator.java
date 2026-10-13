package com.aryan.Logistics.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Renders an analytics {@link Report} into two artifacts next to the run's
 * SQLite file: {@code <run>.metrics.json} (machine-readable) and
 * {@code <run>.html} (self-contained Chart.js dashboard).
 */
public class ReportGenerator {

    private static final String TEMPLATE_PLACEHOLDER = "__DATA__";
    private static final String RUN_ID_PLACEHOLDER = "__RUNID__";
    private static final String SCHEMA_PLACEHOLDER = "__SCHEMAV__";

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public Path[] generate(String dbPath, Report report) {
        Path db = Paths.get(dbPath).toAbsolutePath();
        String base = db.toString().endsWith(".db")
                ? db.toString().substring(0, db.toString().length() - 3)
                : db.toString();
        Path jsonPath = Paths.get(base + ".metrics.json");
        Path htmlPath = Paths.get(base + ".html");
        try {
            String json = mapper.writeValueAsString(report);
            Files.writeString(jsonPath, json);
            Files.writeString(htmlPath, renderHtml(json, report));
        } catch (IOException e) {
            throw new RuntimeException("Failed to write report artifacts for " + dbPath, e);
        }
        return new Path[]{jsonPath, htmlPath};
    }

    private String renderHtml(String json, Report report) {
        String template = loadTemplate();
        return template
                .replace(TEMPLATE_PLACEHOLDER, json)
                .replace(RUN_ID_PLACEHOLDER, report.meta.runId)
                .replace(SCHEMA_PLACEHOLDER, String.valueOf(Report.SCHEMA_VERSION));
    }

    private String loadTemplate() {
        try (InputStream is = ReportGenerator.class.getClassLoader()
                .getResourceAsStream("dashboard-template.html")) {
            if (is == null) {
                throw new IllegalStateException("dashboard-template.html not found on classpath");
            }
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load dashboard template", e);
        }
    }
}
