package com.aryan.Logistics.scenario;

import com.aryan.Logistics.simulation.SimulationConfig;
import com.aryan.Logistics.simulation.SimulationRunner;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs UI-driven scenarios: merges form overrides into the base config and
 * executes the simulation as a background job with day-level progress.
 */
public class ScenarioService {

    public enum State { RUNNING, DONE, FAILED }

    public static class Job {
        public final String id;
        public volatile State state = State.RUNNING;
        public volatile int dayDone;
        public volatile int totalDays;
        public volatile String dbPath;
        public volatile String dashboardPath;
        public volatile String error;

        Job(String id) {
            this.id = id;
        }
    }

    public static class ScenarioRequest {
        public Integer warehouseCount;
        public Integer vehicleCount;
        public Integer customerCount;
        public Double maintenancePerDay;
        public Integer targetOrdersPerDay;
        public Double targetReturnOnCost;
        public Double deliveryFeePerKm;
        public String startDate;
        public String endDate;
        /** Fuel settings, shown live in the form (fee must clear fuel/km). Tunable. */
        public Double fuelLitersPerKm;
        public Double fuelPricePerLiter;
    }

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public Map<String, Job> jobs() {
        return jobs;
    }

    /** Current config defaults, served to the form so inputs start from reality. */
    public ScenarioRequest defaults() {
        try {
            SimulationConfig base = SimulationConfig.load(null);
            ScenarioRequest d = new ScenarioRequest();
            d.warehouseCount = base.warehouseCount;
            d.vehicleCount = base.vehicleCount;
            d.customerCount = base.customerCount;
            d.maintenancePerDay = base.economics.warehouseMaintenancePerDay;
            d.targetOrdersPerDay = base.orderGeneration.targetOrdersPerDay;
            d.targetReturnOnCost = base.economics.targetReturnOnCost;
            d.deliveryFeePerKm = base.economics.deliveryFeePerKm;
            d.startDate = base.simulation.startDate;
            d.endDate = base.simulation.endDate;
            d.fuelLitersPerKm = base.economics.fuelLitersPerKm;
            d.fuelPricePerLiter = base.economics.fuelPricePerLiter;
            return d;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load base config", e);
        }
    }

    /** Merges non-null request fields into a loaded config. Pure logic, unit-tested. */
    static void applyOverrides(SimulationConfig config, ScenarioRequest req) {
        if (req.warehouseCount != null) config.warehouseCount = req.warehouseCount;
        if (req.vehicleCount != null) config.vehicleCount = req.vehicleCount;
        if (req.customerCount != null) config.customerCount = req.customerCount;
        if (req.maintenancePerDay != null) config.economics.warehouseMaintenancePerDay = req.maintenancePerDay;
        if (req.targetOrdersPerDay != null) config.orderGeneration.targetOrdersPerDay = req.targetOrdersPerDay;
        if (req.targetReturnOnCost != null) config.economics.targetReturnOnCost = req.targetReturnOnCost;
        if (req.deliveryFeePerKm != null) config.economics.deliveryFeePerKm = req.deliveryFeePerKm;
        if (req.fuelLitersPerKm != null) config.economics.fuelLitersPerKm = req.fuelLitersPerKm;
        if (req.fuelPricePerLiter != null) config.economics.fuelPricePerLiter = req.fuelPricePerLiter;
        if (req.startDate != null) config.simulation.startDate = req.startDate;
        if (req.endDate != null) config.simulation.endDate = req.endDate;
    }

    /** Throws IllegalArgumentException describing the first invalid field. */
    static void validate(ScenarioRequest req) {
        checkCount("warehouseCount", req.warehouseCount);
        checkCount("vehicleCount", req.vehicleCount);
        checkCount("customerCount", req.customerCount);
        if (req.maintenancePerDay != null && req.maintenancePerDay < 0)
            throw new IllegalArgumentException("maintenancePerDay must be >= 0");
        if (req.targetOrdersPerDay != null
                && (req.targetOrdersPerDay < 1 || req.targetOrdersPerDay > 10000))
            throw new IllegalArgumentException("targetOrdersPerDay must be 1..10000");
        if (req.targetReturnOnCost != null
                && (req.targetReturnOnCost < 0 || req.targetReturnOnCost > 5))
            throw new IllegalArgumentException("targetReturnOnCost must be 0..5");
        if (req.deliveryFeePerKm != null && req.deliveryFeePerKm < 0)
            throw new IllegalArgumentException("deliveryFeePerKm must be >= 0");
        if (req.fuelLitersPerKm != null && req.fuelLitersPerKm <= 0)
            throw new IllegalArgumentException("fuelLitersPerKm must be > 0");
        if (req.fuelPricePerLiter != null && req.fuelPricePerLiter <= 0)
            throw new IllegalArgumentException("fuelPricePerLiter must be > 0");
        if ((req.startDate == null) != (req.endDate == null))
            throw new IllegalArgumentException("startDate and endDate must be set together");
        if (req.startDate != null) {
            LocalDate start;
            LocalDate end;
            try {
                start = LocalDate.parse(req.startDate);
                end = LocalDate.parse(req.endDate);
            } catch (Exception e) {
                throw new IllegalArgumentException("dates must be yyyy-MM-dd");
            }
            long days = ChronoUnit.DAYS.between(start, end);
            if (days < 1) throw new IllegalArgumentException("endDate must be after startDate");
            if (days > 366) throw new IllegalArgumentException("run length capped at 366 days");
        }
    }

    private static void checkCount(String name, Integer value) {
        if (value != null && (value < 1 || value > 10000))
            throw new IllegalArgumentException(name + " must be 1..10000");
    }

    public Job submit(ScenarioRequest req) {
        validate(req);
        String id = UUID.randomUUID().toString().substring(0, 8);
        Job job = new Job(id);
        jobs.put(id, job);
        Thread worker = new Thread(() -> runJob(job, req), "scenario-" + id);
        worker.setDaemon(true);
        worker.start();
        return job;
    }

    private void runJob(Job job, ScenarioRequest req) {
        try {
            SimulationConfig config = SimulationConfig.load(null);
            applyOverrides(config, req);
            long days = ChronoUnit.DAYS.between(
                    LocalDate.parse(config.simulation.startDate),
                    LocalDate.parse(config.simulation.endDate));
            job.totalDays = (int) days;
            String runId = config.simulation.startDate + "_" + config.simulation.endDate
                    + "_seed" + config.simulation.randomSeed + "_ui" + job.id;
            job.dbPath = "simulation_runs/run_" + runId + ".db";
            Path existing = Paths.get(job.dbPath);
            java.nio.file.Files.deleteIfExists(existing);

            SimulationRunner runner = new SimulationRunner(config, job.dbPath, "ui");
            runner.setProgressListener(day -> job.dayDone = day);
            runner.run();
            job.dashboardPath = job.dbPath.substring(0, job.dbPath.length() - 3) + ".html";
            job.dayDone = job.totalDays;
            job.state = State.DONE;
        } catch (Exception e) {
            job.error = e.getMessage() == null ? e.toString() : e.getMessage();
            job.state = State.FAILED;
        }
    }
}
