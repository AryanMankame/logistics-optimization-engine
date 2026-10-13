package com.aryan.Logistics.analytics;

import com.aryan.Logistics.models.Customer;
import com.aryan.Logistics.models.Inventory;
import com.aryan.Logistics.models.Item;
import com.aryan.Logistics.models.Location;
import com.aryan.Logistics.models.Order;
import com.aryan.Logistics.models.Road;
import com.aryan.Logistics.models.SplitOrder;
import com.aryan.Logistics.models.Vehicle;
import com.aryan.Logistics.models.Warehouse;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DataCollectorTest {

    private Path dbFile;
    private DataCollector collector;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempDirectory("collector-test").resolve("test.db");
        collector = DataCollector.open(dbFile.toString(), "run_test", 42,
                "2025-09-15", "2025-10-15", "classpath:simulation.json");
    }

    @AfterEach
    void tearDown() {
        collector.close();
    }

    @Test
    void testSchemaContainsAllTables() {
        List<String> expected = List.of("meta", "location", "item", "customer", "road",
                "vehicle", "warehouse", "inventory", "orders", "split_orders", "cluster",
                "vehicle_delivery_shipment_info");
        List<String> actual = collector.tableNames();
        assertTrue(actual.containsAll(expected), "Missing tables: " +
                expected.stream().filter(t -> !actual.contains(t)).toList());
    }

    @Test
    void testMetaRowWritten() throws Exception {
        try (Connection conn = openReadOnly(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT run_id, seed, start_date, end_date FROM meta")) {
            assertTrue(rs.next());
            assertEquals("run_test", rs.getString("run_id"));
            assertEquals(42, rs.getInt("seed"));
            assertEquals("2025-09-15", rs.getString("start_date"));
            assertEquals("2025-10-15", rs.getString("end_date"));
            assertFalse(rs.next());
        }
    }

    @Test
    void testLocationIdsStableByCoordinates() {
        Location a = new Location(19.1394, 72.7558);
        Location b = new Location(19.1394, 72.7558);
        Location c = new Location(18.9973, 72.8013);

        String idA = collector.locationId(a);
        String idC = collector.locationId(c);
        assertEquals("Loc_0", idA);
        assertEquals("Loc_1", idC);
        assertEquals(idA, collector.locationId(b), "same coordinates must map to same location id");
        assertEquals(2, collector.count("location"));
    }

    @Test
    void testOrderSplitClusterDispatchRoundTrip() throws Exception {
        Location loc = new Location(19.0, 72.8);
        collector.registerLocations(List.of(loc));
        Item item = new Item("Item_0", "Widget", 1.5, 0.4);
        collector.writeItem(item);
        Customer customer = new Customer();
        customer.id = "Customer_0";
        customer.name = "Alice";
        customer.location = loc;
        collector.writeCustomer(customer);

        Date created = new Date(1757904000000L);
        Date deadline = new Date(created.getTime() + 7 * 24 * 3600_000L);
        Date delivered = new Date(created.getTime() + 2 * 3600_000L);

        Order order = new Order("Order_0", new ArrayList<>(),
                new ArrayList<>(List.of(Pair.of(2, item))), 3.0f, deadline, created, null, customer);
        collector.insertOrder(order);

        Vehicle vehicle = new Vehicle("Vehicle_7", loc, 150.0, 0.0, 40.0, created);
        collector.writeVehicle(vehicle);

        Inventory inventory = new Inventory();
        inventory.addItem(item, 100);
        Warehouse warehouse = new Warehouse("Warehouse_0", loc, inventory);
        collector.writeWarehouse(warehouse);

        SplitOrder split = new SplitOrder("Order_00", item, 2, deadline, created, null, customer, order);
        collector.insertSplitOrder(split);
        collector.insertClusterMarker("cluster_3", loc);
        collector.insertCluster("cluster_3", split, loc, 12.5);
        collector.insertDispatch(created, "Vehicle_7", "Warehouse_0", 14.25, 1800.0);
        collector.updateSplitDelivered(split.id, delivered);
        collector.updateSplitFulfillment(split.id, "Warehouse_0", "Vehicle_7", 12.5);
        collector.updateOrderDelivered(order.id, delivered);

        try (Connection conn = openReadOnly(); Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT total_amount, order_created, order_delivered, customer_id FROM orders")) {
                assertTrue(rs.next());
                assertEquals(3.0, rs.getDouble("total_amount"), 1e-9);
                assertEquals("Customer_0", rs.getString("customer_id"));
                assertNotNull(rs.getString("order_created"));
                assertNotNull(rs.getString("order_delivered"), "H1 hook must persist delivered timestamp");
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT id, name, cost, weight, price FROM item WHERE id = 'Item_0'")) {
                assertTrue(rs.next());
                assertEquals(1.5, rs.getDouble("cost"), 1e-9);
                assertEquals(1.5, rs.getDouble("price"), 1e-9);
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT item_id, qnt, order_delivered FROM split_orders")) {
                assertTrue(rs.next());
                assertEquals("Item_0", rs.getString("item_id"));
                assertEquals(2, rs.getInt("qnt"));
                assertNotNull(rs.getString("order_delivered"));
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT cluster_id, centroid_lat, radius FROM cluster WHERE split_order_id IS NOT NULL")) {
                assertTrue(rs.next());
                assertEquals("cluster_3", rs.getString("cluster_id"));
                assertEquals(19.0, rs.getDouble("centroid_lat"), 1e-9);
                assertEquals(12.5, rs.getDouble("radius"), 1e-9);
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT cluster_id, split_order_id, radius FROM cluster WHERE split_order_id IS NULL")) {
                assertTrue(rs.next());
                assertEquals("cluster_3", rs.getString("cluster_id"));
                assertEquals(0.0, rs.getDouble("radius"), 1e-9);
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT hour, vehicle_id, warehouse_id, distance_covered_for_shipment, time_taken_for_shipment "
                            + "FROM vehicle_delivery_shipment_info")) {
                assertTrue(rs.next());
                assertEquals("Vehicle_7", rs.getString("vehicle_id"));
                assertEquals("Warehouse_0", rs.getString("warehouse_id"));
                assertEquals(14.25, rs.getDouble("distance_covered_for_shipment"), 1e-9);
                assertEquals(1800.0, rs.getDouble("time_taken_for_shipment"), 1e-9);
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT warehouse_id, vehicle_id, delivery_fee FROM split_orders WHERE id = 'Order_00'")) {
                assertTrue(rs.next());
                assertEquals("Warehouse_0", rs.getString("warehouse_id"));
                assertEquals("Vehicle_7", rs.getString("vehicle_id"));
                assertEquals(12.5, rs.getDouble("delivery_fee"), 1e-9);
            }
        }
    }

    @Test
    void testFinalSnapshotUpdatesRoadInventoryVehicle() throws Exception {
        Location loc = new Location(19.0, 72.8);
        collector.registerLocations(List.of(loc));
        Item item = new Item("Item_0", "Widget", 1.5, 0.4);
        collector.writeItem(item);

        Road road = new Road("road_0", loc, loc, 5.0, true, 1.0);
        collector.writeRoads(List.of(road));
        road.open = false;
        road.trafficMultiplier = 2.5;

        Inventory inventory = new Inventory();
        inventory.addItem(item, 100);
        Warehouse warehouse = new Warehouse("Warehouse_0", loc, inventory);
        collector.writeWarehouse(warehouse);
        inventory.subItem(item, 40);

        Vehicle vehicle = new Vehicle("Vehicle_0", loc, 150.0, 0.0, 40.0, new Date(0));
        collector.writeVehicle(vehicle);
        vehicle.totalDistanceCovered = 99.5;
        vehicle.outForDeliveryUntil = new Date(1757904000000L);

        collector.finalSnapshot(List.of(road), List.of(warehouse), List.of(vehicle));

        try (Connection conn = openReadOnly(); Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT open, traffic_multiplier FROM road WHERE id = 'road_0'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt("open"));
                assertEquals(2.5, rs.getDouble("traffic_multiplier"), 1e-9);
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT qnt FROM inventory WHERE warehouse_id = 'Warehouse_0' AND item_id = 'Item_0'")) {
                assertTrue(rs.next());
                assertEquals(60, rs.getInt("qnt"));
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT total_distance_covered FROM vehicle WHERE id = 'Vehicle_0'")) {
                assertTrue(rs.next());
                assertEquals(99.5, rs.getDouble("total_distance_covered"), 1e-9);
            }
        }
    }

    @Test
    void testForeignKeysEnforcedForOrders() {
        Location loc = new Location(19.0, 72.8);
        Customer ghost = new Customer();
        ghost.id = "Customer_ghost";
        ghost.name = "Ghost";
        ghost.location = loc;
        Order order = new Order("Order_9", new ArrayList<>(), new ArrayList<>(),
                1.0f, new Date(0), new Date(0), null, ghost);

        assertThrows(RuntimeException.class, () -> collector.insertOrder(order),
                "orders.customer_id must reference an existing customer row");
    }

    private Connection openReadOnly() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
    }
}
