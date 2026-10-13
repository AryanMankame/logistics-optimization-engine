package com.aryan.Logistics.service;

import com.aryan.Logistics.analytics.DataCollector;
import com.aryan.Logistics.models.Customer;
import com.aryan.Logistics.models.Item;
import com.aryan.Logistics.models.Location;
import com.aryan.Logistics.models.Order;
import com.aryan.Logistics.models.Vehicle;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tiny-map proof that dispatch drives a real tour:
 * warehouse -> closest house -> ... -> warehouse (return leg included),
 * time = whole tour + 5 min/stop, fee stays quoted on radial distance.
 */
class OrderExecutionServiceTourTest {

    @Test
    void tourCoversLegsPlusReturnAndFeeStaysRadial() throws Exception {
        // Map: warehouse W(0) -- H1(1) -- H2(2) -- H3(3) chain only (no random roads).
        ArrayList<Pair<String, Triple<Double, Double, Double>>> items = new ArrayList<>();
        items.add(Pair.of("Apple", Triple.of(10.0, 1.0, 11.0)));
        ArrayList<Pair<Double, Double>> locs = new ArrayList<>();
        locs.add(Pair.of(0.0, 0.0));   // W
        locs.add(Pair.of(0.0, 0.01));  // H1 ~1.11 km from W
        locs.add(Pair.of(0.0, 0.015)); // H2 ~1.67 km from W (uneven gaps)
        locs.add(Pair.of(0.0, 0.03));  // H3 ~3.34 km from W

        WarehouseService warehouseService = new WarehouseService();
        CityReplicaService city = new CityReplicaService(3, 1, items, locs, warehouseService);
        Date start = new Date(1_700_000_000_000L);
        city.currTime = start;

        Path db = Files.createTempFile("tour-test", ".db");
        DataCollector collector = DataCollector.open(
                db.toString(), "tour-run", 0, "2025-01-01", "2025-01-02", "test");

        ArrayList<Location> centroids = new ArrayList<>();
        centroids.add(warehouseService.getWarehouseLocation(0));
        ClusterService clusters = new ClusterService(1, centroids, collector);
        OrderHandlerService orders = new OrderHandlerService(clusters, collector);

        // Use the real inventory Item instance so subtract() matches by equality,
        // and mirror it into the item dimension (split_orders.item_id FK).
        Item item = warehouseService.getWarehouses().get(0)
                .getInventory().snapshot().keySet().iterator().next();
        collector.writeItem(item);

        long base = start.getTime();
        // Deadlines staggered so all three pop; greedy reorders anyway.
        makeOrder(collector, orders, item, "O1", locs.get(1), base, 3000);
        makeOrder(collector, orders, item, "O2", locs.get(2), base, 2000);
        makeOrder(collector, orders, item, "O3", locs.get(3), base, 1000);
        while (orders.pendingOrderCount() > 0) orders.processOrder();

        VehicleService vehicles = new VehicleService();
        Location whLoc = warehouseService.getWarehouseLocation(0);
        Vehicle van = new Vehicle("V1", whLoc, 100.0, 0.0, 30.0, new Date(0));
        vehicles.addVehicle(new Date(0), van);
        // Dimension rows: split fulfillment FK-references warehouse(id) + vehicle(id).
        collector.writeWarehouse(warehouseService.getWarehouses().get(0));
        collector.writeVehicle(van);

        double feePerKm = 5.0;
        OrderExecutionService exec = new OrderExecutionService(
                vehicles, warehouseService, clusters, orders, city, collector, feePerKm);
        exec.executeOrders(0);

        Location h1 = new Location(0.0, 0.01);
        Location h2 = new Location(0.0, 0.015);
        Location h3 = new Location(0.0, 0.03);
        double wH1 = city.shortestDistanceBetweenTwoLocations(whLoc, h1);
        double h1H2 = city.shortestDistanceBetweenTwoLocations(h1, h2);
        double h2H3 = city.shortestDistanceBetweenTwoLocations(h2, h3);
        double h3W = city.shortestDistanceBetweenTwoLocations(h3, whLoc);
        double wH2 = city.shortestDistanceBetweenTwoLocations(whLoc, h2);
        double wH3 = city.shortestDistanceBetweenTwoLocations(whLoc, h3);
        double expectedTour = wH1 + h1H2 + h2H3 + h3W;
        double sumRadials = wH1 + wH2 + wH3;

        // Tour must be the chained route (legs + return), not the teleport sum.
        // Uneven gaps keep the two totals distinct so the test is meaningful.
        assertNotEquals(sumRadials, expectedTour, 1e-3,
                "fixture sanity: tour and radial sum must differ");
        // Return leg must be counted: tour == outbound chain + return.
        assertEquals(wH1 + h1H2 + h2H3 + h3W, expectedTour, 1e-9);
        assertTrue(h3W > 0, "return leg must be non-zero");

        // Van drove the whole tour.
        assertEquals(expectedTour, van.totalDistanceCovered, 1e-6);

        // Time = whole tour at speed + 5 min per stop.
        double expectedSeconds = (expectedTour / 30.0) * 3600.0 + 3 * 300.0;
        long expectedMillis = (long) (expectedSeconds * 1000);
        assertEquals(start.getTime() + expectedMillis, van.outForDeliveryUntil.getTime(), 2000);

        // Dispatch row + per-split radial fees straight from SQLite.
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = conn.createStatement()) {
            double dispatchKm = 0, dispatchSecs = 0;
            int dispatchRows = 0;
            try (ResultSet rs = st.executeQuery(
                    "SELECT distance_covered_for_shipment, time_taken_for_shipment "
                            + "FROM vehicle_delivery_shipment_info")) {
                while (rs.next()) {
                    dispatchRows++;
                    dispatchKm = rs.getDouble(1);
                    dispatchSecs = rs.getDouble(2);
                }
            }
            assertEquals(1, dispatchRows, "exactly one dispatch for the loaded tour");
            assertEquals(expectedTour, dispatchKm, 1e-6);
            assertEquals(expectedSeconds, dispatchSecs, 2.0);

            Map<String, Double> expectedFees = Map.of(
                    "O10", feePerKm * wH1,
                    "O20", feePerKm * wH2,
                    "O30", feePerKm * wH3);
            try (ResultSet rs = st.executeQuery("SELECT id, delivery_fee FROM split_orders")) {
                int n = 0;
                while (rs.next()) {
                    n++;
                    assertEquals(expectedFees.get(rs.getString(1)), rs.getDouble(2), 1e-6,
                            "fee for " + rs.getString(1) + " must be radial-quoted");
                }
                assertEquals(3, n);
            }
        }

        collector.close();
        Files.deleteIfExists(db);
    }

    @Test
    void emptyClusterReturnsVehicleWithNoDispatch() throws Exception {
        ArrayList<Pair<String, Triple<Double, Double, Double>>> items = new ArrayList<>();
        items.add(Pair.of("Apple", Triple.of(10.0, 1.0, 11.0)));
        ArrayList<Pair<Double, Double>> locs = new ArrayList<>();
        locs.add(Pair.of(0.0, 0.0));
        locs.add(Pair.of(0.0, 0.01));

        WarehouseService warehouseService = new WarehouseService();
        CityReplicaService city = new CityReplicaService(1, 1, items, locs, warehouseService);
        Date start = new Date(1_700_000_000_000L);
        city.currTime = start;

        Path db = Files.createTempFile("tour-empty", ".db");
        DataCollector collector = DataCollector.open(
                db.toString(), "tour-empty", 0, "2025-01-01", "2025-01-02", "test");
        ArrayList<Location> centroids = new ArrayList<>();
        centroids.add(warehouseService.getWarehouseLocation(0));
        ClusterService clusters = new ClusterService(1, centroids, collector);
        OrderHandlerService orders = new OrderHandlerService(clusters, collector);

        VehicleService vehicles = new VehicleService();
        Vehicle van = new Vehicle("V1", warehouseService.getWarehouseLocation(0),
                100.0, 0.0, 30.0, new Date(0));
        vehicles.addVehicle(new Date(0), van);

        new OrderExecutionService(
                vehicles, warehouseService, clusters, orders, city, collector, 5.0)
                .executeOrders(0);

        assertEquals(0.0, van.totalDistanceCovered, 1e-9);
        assertEquals(1, vehicles.allVehicles().size(), "vehicle must be returned to the pool");
        assertEquals(0, collector.count("vehicle_delivery_shipment_info"));

        collector.close();
        Files.deleteIfExists(db);
    }

    @Test
    void midLoadStockOutRequeuesOrdersAndLosesNothing() throws Exception {
        ArrayList<Pair<String, Triple<Double, Double, Double>>> items = new ArrayList<>();
        items.add(Pair.of("Apple", Triple.of(10.0, 1.0, 11.0)));
        ArrayList<Pair<Double, Double>> locs = new ArrayList<>();
        locs.add(Pair.of(0.0, 0.0));
        locs.add(Pair.of(0.0, 0.01));
        locs.add(Pair.of(0.0, 0.02));

        WarehouseService warehouseService = new WarehouseService();
        CityReplicaService city = new CityReplicaService(2, 1, items, locs, warehouseService);
        Date start = new Date(1_700_000_000_000L);
        city.currTime = start;

        Path db = Files.createTempFile("tour-stockout", ".db");
        DataCollector collector = DataCollector.open(
                db.toString(), "tour-stockout", 0, "2025-01-01", "2025-01-02", "test");
        ArrayList<Location> centroids = new ArrayList<>();
        centroids.add(warehouseService.getWarehouseLocation(0));
        ClusterService clusters = new ClusterService(1, centroids, collector);
        OrderHandlerService orders = new OrderHandlerService(clusters, collector);

        Item item = warehouseService.getWarehouses().get(0)
                .getInventory().snapshot().keySet().iterator().next();
        collector.writeItem(item);
        // Drain stock to exactly 1 unit: first split takes it, second must fail.
        assertTrue(warehouseService.subtractItemFromWarehouse(0, item, 99999));
        assertEquals(1, warehouseService.checkItemQuantity(0, item));

        long base = start.getTime();
        makeOrder(collector, orders, item, "S1", locs.get(1), base, 2000);
        makeOrder(collector, orders, item, "S2", locs.get(2), base, 1000);
        while (orders.pendingOrderCount() > 0) orders.processOrder();

        VehicleService vehicles = new VehicleService();
        Vehicle van = new Vehicle("V1", warehouseService.getWarehouseLocation(0),
                100.0, 0.0, 30.0, new Date(0));
        vehicles.addVehicle(new Date(0), van);
        collector.writeWarehouse(warehouseService.getWarehouses().get(0));
        collector.writeVehicle(van);

        new OrderExecutionService(
                vehicles, warehouseService, clusters, orders, city, collector, 5.0)
                .executeOrders(0);

        // Nothing booked: no dispatch, van home with zero distance, stock untouched at 0.
        assertEquals(0, collector.count("vehicle_delivery_shipment_info"));
        assertEquals(0.0, van.totalDistanceCovered, 1e-9);
        assertEquals(1, vehicles.allVehicles().size());
        assertEquals(0, warehouseService.checkItemQuantity(0, item));
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM split_orders WHERE order_delivered IS NOT NULL")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "no split may be marked delivered");
        }
        // Both splits must still be in the cluster (nothing lost).
        assertNotNull(clusters.getOrderFromCluster(0));
        assertNotNull(clusters.getOrderFromCluster(0));

        collector.close();
        Files.deleteIfExists(db);
    }

    private static void makeOrder(DataCollector collector, OrderHandlerService orders,
                                  Item item, String id,
                                  Pair<Double, Double> coord, long base, long deadlineOffset) {
        Customer c = new Customer();
        c.id = "C_" + id;
        c.name = "Cust " + id;
        c.location = new Location(coord.getLeft(), coord.getRight());
        ArrayList<Pair<Integer, Item>> requested = new ArrayList<>();
        requested.add(Pair.of(1, item));
        Order o = new Order(id, null, requested, 11.0f,
                new Date(base + deadlineOffset), new Date(base), null, c);
        // Parent rows first: split_orders.order_id FK-references orders(id),
        // which FK-references customer(id). processOrder() only writes splits.
        collector.writeCustomer(c);
        collector.insertOrder(o);
        orders.addOrder(o);
    }
}
