package com.aryan.Logistics.simulation;

import com.aryan.Logistics.Utils.GeoUtils;
import com.aryan.Logistics.analytics.AnalyticsService;
import com.aryan.Logistics.analytics.DataCollector;
import com.aryan.Logistics.analytics.Report;
import com.aryan.Logistics.analytics.ReportGenerator;
import com.aryan.Logistics.models.*;
import com.aryan.Logistics.service.*;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;

public class SimulationRunner {
    private final SimulationConfig config;
    private final CityReplicaService cityReplicaService;
    private final VehicleService vehicleService;
    private final WarehouseService warehouseService;
    private final ClusterService clusterService;
    private final OrderHandlerService orderHandlerService;
    private final OrderExecutionService orderExecutionService;
    private final DataCollector dataCollector;
    private final String dbPath;

    private final ArrayList<Location> allLocations = new ArrayList<>();
    private final ArrayList<Location> warehouseLocations = new ArrayList<>();
    private final ArrayList<Location> customerLocations = new ArrayList<>();
    private final ArrayList<Item> allItems = new ArrayList<>();
    private final ArrayList<Customer> allCustomers = new ArrayList<>();

    private final OrderGenerator orderGenerator;
    private final RandomEventGenerator randomEventGenerator;
    private final Random random;
    private final Date simStart;
    private final Date simEnd;
    private java.util.function.IntConsumer progressListener;

    public SimulationRunner(SimulationConfig config, String dbPath, String configPath) throws Exception {
        this.config = config;
        this.dbPath = dbPath;

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        this.simStart = sdf.parse(config.simulation.startDate);
        this.simEnd = sdf.parse(config.simulation.endDate);

        // Convert config locations to Location objects
        for (SimulationConfig.LocationConfig lc : config.locations) {
            allLocations.add(new Location(lc.latitude, lc.longitude));
        }

        // Separate warehouse and customer locations.
        // Count-based selection (even spacing) wins when the counts are set;
        // otherwise fall back to the explicit config lists (legacy).
        List<Integer> warehouseIndices = config.warehouseCount != null
                ? evenSpacing(config.warehouseCount, allLocations.size())
                : config.warehouseLocationIndices;
        for (int idx : warehouseIndices) {
            warehouseLocations.add(allLocations.get(idx));
        }

        // Customer locations = all locations minus warehouse locations
        Set<Integer> warehouseIdxSet = new HashSet<>(warehouseIndices);
        for (int i = 0; i < allLocations.size(); i++) {
            if (!warehouseIdxSet.contains(i)) {
                customerLocations.add(allLocations.get(i));
            }
        }

        // Build items in the format CityReplicaService expects: Pair<name, Triple<cost, weight, price>>
        ArrayList<Pair<String, Triple<Double, Double, Double>>> itemPairs = new ArrayList<>();
        int itemIdx = 0;
        for (SimulationConfig.ItemConfig ic : config.items) {
            double price = ic.price != null ? ic.price
                    : ic.cost * (1.0 + (ic.marginPct != null ? ic.marginPct : config.economics.defaultMarginPct));
            allItems.add(new Item("Item_" + itemIdx, ic.name, ic.cost, ic.weight, price));
            itemPairs.add(Pair.of(ic.name, Triple.of(ic.cost, ic.weight, price)));
            itemIdx++;
        }

        // Build customers: count-based (even spacing over free locations, skipping
        // warehouses) when customerCount is set, else the explicit config list.
        if (config.customerCount != null) {
            List<Location> free = new ArrayList<>(customerLocations);
            List<Integer> picks = evenSpacing(Math.min(config.customerCount, free.size()), free.size());
            for (int i = 0; i < picks.size(); i++) {
                Customer c = new Customer();
                c.id = "Customer_" + i;
                c.name = "Customer " + i;
                c.location = free.get(picks.get(i));
                allCustomers.add(c);
            }
        } else {
            int custIdx = 0;
            for (SimulationConfig.CustomerConfig cc : config.customers) {
                Customer c = new Customer();
                c.id = "Customer_" + custIdx;
                c.name = cc.name;
                c.location = allLocations.get(cc.locationIndex);
                allCustomers.add(c);
                custIdx++;
            }
        }

        // Build locations in Pair format for CityReplicaService
        ArrayList<Pair<Double, Double>> locPairs = new ArrayList<>();
        for (Location loc : allLocations) {
            locPairs.add(Pair.of(loc.latitude, loc.longitude));
        }

        // Open the run's SQLite file and write the setup-time dimension tables
        String runId = config.simulation.startDate + "_" + config.simulation.endDate
                + "_seed" + config.simulation.randomSeed;
        dataCollector = DataCollector.open(dbPath, runId, config.simulation.randomSeed,
                config.simulation.startDate, config.simulation.endDate,
                configPath == null ? "classpath:simulation.json" : configPath);
        dataCollector.registerLocations(allLocations);
        for (Item item : allItems) {
            dataCollector.writeItem(item);
        }
        for (Customer customer : allCustomers) {
            dataCollector.writeCustomer(customer);
        }

        // Wire services (city warehouses and cluster count follow the same indices;
        // cluster centroids start at the warehouse spots: cluster i == warehouse i).
        warehouseService = new WarehouseService();
        cityReplicaService = new CityReplicaService(
                config.locations.size() * 3,
                warehouseIndices,
                itemPairs, locPairs, warehouseService);
        vehicleService = new VehicleService();
        clusterService = new ClusterService(warehouseIndices.size(), warehouseLocations, dataCollector);
        orderHandlerService = new OrderHandlerService(clusterService, dataCollector);
        orderExecutionService = new OrderExecutionService(
                vehicleService, warehouseService, clusterService,
                orderHandlerService, cityReplicaService, dataCollector,
                config.economics.deliveryFeePerKm);

        // Roads and warehouses exist once the city is built
        dataCollector.writeRoads(cityReplicaService.getRoads());
        for (Warehouse warehouse : warehouseService.getWarehouses()) {
            dataCollector.writeWarehouse(warehouse);
        }

        // Add vehicles: first-N of the configured types, cycling past the list end
        int vehicleTotal = config.vehicleCount != null ? config.vehicleCount : config.vehicles.size();
        for (int i = 0; i < vehicleTotal; i++) {
            SimulationConfig.VehicleConfig vc = config.vehicles.get(i % config.vehicles.size());
            Location startLoc = warehouseLocations.get(i % warehouseLocations.size());
            Vehicle v = new Vehicle("Vehicle_" + i, startLoc, (double) vc.capacity, 0.0, vc.averageSpeed, simStart);
            vehicleService.addVehicle(simStart, v);
            dataCollector.writeVehicle(v);
        }

        // Order generation
        random = new Random(config.simulation.randomSeed);
        orderGenerator = new OrderGenerator(config.orderGeneration, allItems, allCustomers, orderHandlerService, dataCollector, random);

        // Random events
        randomEventGenerator = new RandomEventGenerator(
                config.randomEvents, cityReplicaService, warehouseService,
                vehicleService, allItems, warehouseLocations, random, dataCollector, clusterService);
    }

    /**
     * Evenly spaced indices over [0, total): index i maps to i*total/n.
     * Deterministic geographic spread for count-based selection.
     */
    static List<Integer> evenSpacing(int n, int total) {
        List<Integer> out = new ArrayList<>();
        if (n <= 0 || total <= 0) return out;
        n = Math.min(n, total);
        for (int i = 0; i < n; i++) {
            out.add((int) ((long) i * total / n));
        }
        return out;
    }

    /** Days completed (1-based) are reported here; used by the scenario UI for progress. */
    public void setProgressListener(java.util.function.IntConsumer listener) {
        this.progressListener = listener;
    }

    public void printSetup() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        System.out.println("========================================");
        System.out.println("  SIMULATION SETUP");
        System.out.println("========================================");
        System.out.println("City:              " + config.simulation.cityName);
        System.out.println("Period:            " + config.simulation.startDate + " to " + config.simulation.endDate);
        System.out.println("Total days:        " + ((simEnd.getTime() - simStart.getTime()) / (24 * 3600_000)));
        System.out.println("----------------------------------------");
        System.out.println("Locations:         " + allLocations.size());
        System.out.println("  Warehouses:      " + warehouseLocations.size());
        System.out.println("  Customer sites:  " + customerLocations.size());
        System.out.println("Items:             " + allItems.size());
        System.out.println("Customers:         " + allCustomers.size());
        System.out.println("Vehicles:          " + config.vehicles.size());
        System.out.println("Clusters:          " + clusterService.size());
        System.out.println("----------------------------------------");
        System.out.println("Order deadline:    " + config.orderGeneration.deadlineDays + " days");
        System.out.println("Max items/order:   " + config.orderGeneration.maxItemsPerOrder);
        System.out.println("Random seed:       " + config.simulation.randomSeed);
        System.out.println("Data file:         " + dbPath);
        System.out.println("========================================");
    }

    public void run() {
        SimpleDateFormat hourFormat = new SimpleDateFormat("yyyy-MM-dd HH:00");
        Date current = new Date(simStart.getTime());
        long totalHours = (simEnd.getTime() - simStart.getTime()) / (3600_000);
        long hourCount = 0;
        int totalOrdersGenerated = 0;
        int totalOrdersProcessed = 0;
        int totalSplitOrdersDelivered = 0;
        int totalEvents = 0;
        long genMillis = 0, procMillis = 0, execMillis = 0, eventMillis = 0;
        long lastPathCalls = 0, lastPathMillis = 0;

        System.out.println("\nSIMULATION START: " + hourFormat.format(current));
        System.out.println("----------------------------------------");

        try {
            while (current.before(simEnd)) {
                cityReplicaService.currTime = current;

                // Step 5: Generate orders
                long t0 = System.nanoTime();
                int ordersThisHour = orderGenerator.generateOrders(current);
                genMillis += (System.nanoTime() - t0) / 1_000_000;
                totalOrdersGenerated += ordersThisHour;

                // Step 6: Process all pending orders into clusters
                t0 = System.nanoTime();
                int processed = 0;
                while (orderHandlerService.processOrder()) {
                    processed++;
                }
                procMillis += (System.nanoTime() - t0) / 1_000_000;
                totalOrdersProcessed += processed;

                // Step 7: Execute orders — try to deliver from each cluster
                t0 = System.nanoTime();
                for (int c = 0; c < clusterService.size(); c++) {
                    int before = orderHandlerService.completedSplitOrdersTotal();
                    orderExecutionService.executeOrders(c);
                    totalSplitOrdersDelivered += (orderHandlerService.completedSplitOrdersTotal() - before);
                }
                execMillis += (System.nanoTime() - t0) / 1_000_000;

                // Step 8: Random events
                t0 = System.nanoTime();
                List<String> events = randomEventGenerator.triggerEvents(current);
                eventMillis += (System.nanoTime() - t0) / 1_000_000;
                totalEvents += events.size();
                for (String event : events) {
                    System.out.println(hourFormat.format(current) + " EVENT: " + event);
                }

                hourCount++;
                current = new Date(current.getTime() + 3600_000);

                if (hourCount % 24 == 0) {
                    long day = hourCount / 24;
                    System.out.println("Day " + day + " — generated: " + totalOrdersGenerated + " processed: " + totalOrdersProcessed + " delivered: " + totalSplitOrdersDelivered + " events: " + totalEvents);
                    long pathCalls = cityReplicaService.shortestPathCalls - lastPathCalls;
                    long pathMs = cityReplicaService.shortestPathMillis - lastPathMillis;
                    lastPathCalls = cityReplicaService.shortestPathCalls;
                    lastPathMillis = cityReplicaService.shortestPathMillis;
                    System.out.println("  [perf] gen=" + genMillis + "ms proc=" + procMillis + "ms exec=" + execMillis
                            + "ms events=" + eventMillis + "ms | dijkstra: " + pathCalls + " calls, " + pathMs + "ms");
                    if (progressListener != null) progressListener.accept((int) day);
                }
            }
        } finally {
            try {
                dataCollector.finalSnapshot(
                        cityReplicaService.getRoads(),
                        warehouseService.getWarehouses(),
                        vehicleService.allVehicles());
            } finally {
                dataCollector.close();
            }
        }

        System.out.println("----------------------------------------");
        System.out.println("SIMULATION END: " + hourFormat.format(current));
        System.out.println("Total hours simulated: " + hourCount);
        System.out.println("Total orders generated: " + totalOrdersGenerated);
        System.out.println("Total orders processed: " + totalOrdersProcessed);
        System.out.println("Total split orders delivered: " + totalSplitOrdersDelivered);
        System.out.println("Total random events: " + totalEvents);
        System.out.println("Data written to: " + dbPath);

        generateReport();
    }

    private void generateReport() {
        try (AnalyticsService analytics = AnalyticsService.open(dbPath, config.economics)) {
            Report report = analytics.generate();
            Path[] artifacts = new ReportGenerator().generate(dbPath, report);
            System.out.println("Metrics written to: " + artifacts[0]);
            System.out.println("Dashboard written to: " + artifacts[1]);
        }
    }

    public static void mainReport(String dbPath, String configPath) throws Exception {
        SimulationConfig config = SimulationConfig.load(configPath);
        try (AnalyticsService analytics = AnalyticsService.open(dbPath, config.economics)) {
            Report report = analytics.generate();
            Path[] artifacts = new ReportGenerator().generate(dbPath, report);
            System.out.println("Metrics written to: " + artifacts[0]);
            System.out.println("Dashboard written to: " + artifacts[1]);
        }
    }

    public static void main(String[] args) throws Exception {
        String configPath = null;
        String dbPath = null;
        String tag = null;
        Integer seed = null;
        String start = null;
        String end = null;
        boolean overwrite = false;
        boolean reportOnly = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> configPath = args[++i];
                case "--db" -> dbPath = args[++i];
                case "--seed" -> seed = Integer.parseInt(args[++i]);
                case "--start" -> start = args[++i];
                case "--end" -> end = args[++i];
                case "--tag" -> tag = args[++i];
                case "--overwrite" -> overwrite = true;
                case "--report" -> reportOnly = true;
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }

        if (reportOnly) {
            if (dbPath == null) {
                throw new IllegalArgumentException("--report requires --db <path>");
            }
            mainReport(dbPath, configPath);
            return;
        }

        SimulationConfig config = SimulationConfig.load(configPath);
        if (seed != null) config.simulation.randomSeed = seed;
        if (start != null) config.simulation.startDate = start;
        if (end != null) config.simulation.endDate = end;

        if (dbPath == null) {
            String runId = config.simulation.startDate + "_" + config.simulation.endDate
                    + "_seed" + config.simulation.randomSeed
                    + (tag == null || tag.isEmpty() ? "" : "_" + tag);
            dbPath = "simulation_runs/run_" + runId + ".db";
        }

        java.io.File dbFile = new java.io.File(dbPath);
        if (dbFile.exists() && !overwrite) {
            throw new IllegalStateException("Data file already exists: " + dbPath
                    + " (use --overwrite to replace it, or pass a different --db/--tag)");
        }
        if (overwrite) {
            dbFile.delete();
        }

        SimulationRunner runner = new SimulationRunner(config, dbPath, configPath);
        runner.printSetup();
        runner.run();
    }
}
