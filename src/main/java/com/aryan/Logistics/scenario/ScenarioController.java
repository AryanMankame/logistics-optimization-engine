package com.aryan.Logistics.scenario;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Scenario API: tune levers in the UI, run the simulation, fetch the dashboard.
 */
@RestController
@RequestMapping("/api/scenario")
public class ScenarioController {

    private final ScenarioService service = new ScenarioService();

    @GetMapping("/defaults")
    public ScenarioService.ScenarioRequest defaults() {
        return service.defaults();
    }

    @PostMapping("/run")
    public Map<String, String> run(@RequestBody ScenarioService.ScenarioRequest req) {
        try {
            ScenarioService.Job job = service.submit(req);
            return Map.of("jobId", job.id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/status/{id}")
    public Map<String, Object> status(@PathVariable String id) {
        ScenarioService.Job job = service.jobs().get(id);
        if (job == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown job");
        return Map.of(
                "state", job.state.name(),
                "dayDone", job.dayDone,
                "totalDays", job.totalDays,
                "dashboard", job.dashboardPath == null ? "" : job.dashboardPath,
                "error", job.error == null ? "" : job.error);
    }

    @GetMapping("/artifact/{id}/{file}")
    public ResponseEntity<Resource> artifact(@PathVariable String id, @PathVariable String file) {
        ScenarioService.Job job = service.jobs().get(id);
        if (job == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown job");
        if (!file.endsWith(".html") && !file.endsWith(".metrics.json"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "only .html and .metrics.json are served");
        Path base = Paths.get("simulation_runs").toAbsolutePath().normalize();
        Path resolved = base.resolve(file).normalize();
        if (!resolved.startsWith(base) || !Files.exists(resolved))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "artifact not found");
        Resource resource = new FileSystemResource(resolved);
        MediaType type = file.endsWith(".html") ? MediaType.TEXT_HTML : MediaType.APPLICATION_JSON;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .contentType(type)
                .body(resource);
    }
}
