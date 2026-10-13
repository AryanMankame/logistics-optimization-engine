package com.aryan.Logistics.analytics;

import com.aryan.Logistics.simulation.SimulationConfig.EconomicsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class AnalyticsServiceTest {

    private Path dbFile;
    private AnalyticsService analytics;
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final EconomicsConfig economics = new EconomicsConfig();

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempDirectory("analytics-test").resolve("test.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE meta (run_id TEXT PRIMARY KEY, seed INTEGER, start_date TEXT, end_date TEXT)");
            stmt.execute("INSERT INTO meta VALUES ('run_test', 42, '2025-09-01', '2025-10-01')");
            stmt.execute("CREATE TABLE location (id TEXT PRIMARY KEY, latitude REAL, longitude REAL)");
            stmt.execute("INSERT INTO location VALUES ('Loc_0', 19.0, 72.8), ('Loc_1', 19.1, 72.9), ('Loc_2', 20.5, 74.0)");
            stmt.execute("CREATE TABLE item (id TEXT PRIMARY KEY, name TEXT, cost REAL, weight REAL, price REAL)");
            stmt.execute("INSERT INTO item VALUES ('Item_0', 'Widget', 10.0, 0.5, 12.0), ('Item_1', 'Gadget', 20.0, 0.3, 24.0)");
            stmt.execute("CREATE TABLE customer (id TEXT PRIMARY KEY, name TEXT, location_id TEXT)");
            stmt.execute("INSERT INTO customer VALUES ('Customer_0', 'Alice', 'Loc_0'), ('Customer_1', 'Bob', 'Loc_2')");
            stmt.execute("CREATE TABLE warehouse (id TEXT PRIMARY KEY, location_id TEXT)");
            stmt.execute("INSERT INTO warehouse VALUES ('Warehouse_0', 'Loc_0'), ('Warehouse_1', 'Loc_1')");
            stmt.execute("CREATE TABLE vehicle (id TEXT PRIMARY KEY, location_id TEXT, capacity REAL, "
                    + "total_distance_covered REAL, average_speed REAL, out_for_delivery_until TEXT)");
            stmt.execute("INSERT INTO vehicle VALUES ('Vehicle_0', 'Loc_0', 100.0, 50.0, 40.0, NULL), "
                    + "('Vehicle_1', 'Loc_1', 100.0, 30.0, 40.0, NULL)");
            stmt.execute("CREATE TABLE orders (id TEXT PRIMARY KEY, total_amount REAL, deadline TEXT, "
                    + "order_created TEXT, order_delivered TEXT, customer_id TEXT)");
            stmt.execute("CREATE TABLE split_orders (id TEXT PRIMARY KEY, item_id TEXT, qnt INTEGER, "
                    + "order_created TEXT, order_delivered TEXT, order_id TEXT, warehouse_id TEXT, vehicle_id TEXT, delivery_fee REAL)");
            stmt.execute("CREATE TABLE vehicle_delivery_shipment_info (dispatch_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "hour TEXT, vehicle_id TEXT, warehouse_id TEXT, distance_covered_for_shipment REAL, "
                    + "time_taken_for_shipment REAL)");
        }
        seedRows();
        analytics = AnalyticsService.open(dbFile.toString(), economics);
    }

    private void seedRows() throws Exception {
        // Order_0: 2x Item_0 + 1x Item_1 = 40 revenue, delivered on time, 10h
        // Order_1: 3x Item_1 = 60 revenue, delivered late, 20h
        Date created0 = fmt.parse("2025-09-01 08:00:00");
        Date deadline0 = fmt.parse("2025-09-03 08:00:00");
        Date delivered0 = fmt.parse("2025-09-01 18:00:00");
        Date created1 = fmt.parse("2025-09-01 08:00:00");
        Date deadline1 = fmt.parse("2025-09-02 08:00:00");
        Date delivered1 = fmt.parse("2025-09-02 12:00:00");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO orders VALUES ('Order_0', 48.0, '2025-09-03 08:00:00', "
                    + "'2025-09-01 08:00:00', '2025-09-01 18:00:00', 'Customer_0')");
            stmt.execute("INSERT INTO orders VALUES ('Order_1', 72.0, '2025-09-02 08:00:00', "
                    + "'2025-09-01 08:00:00', '2025-09-02 12:00:00', 'Customer_1')");
            stmt.execute("INSERT INTO split_orders VALUES ('Order_00', 'Item_0', 2, '2025-09-01 08:00:00', "
                    + "'2025-09-01 18:00:00', 'Order_0', 'Warehouse_0', 'Vehicle_0', 10.0)");
            stmt.execute("INSERT INTO split_orders VALUES ('Order_01', 'Item_1', 1, '2025-09-01 08:00:00', "
                    + "'2025-09-01 18:00:00', 'Order_0', 'Warehouse_0', 'Vehicle_0', 5.0)");
            stmt.execute("INSERT INTO split_orders VALUES ('Order_10', 'Item_1', 3, '2025-09-01 08:00:00', "
                    + "'2025-09-02 12:00:00', 'Order_1', 'Warehouse_1', 'Vehicle_1', 8.0)");
            stmt.execute("INSERT INTO vehicle_delivery_shipment_info (hour, vehicle_id, warehouse_id, "
                    + "distance_covered_for_shipment, time_taken_for_shipment) VALUES "
                    + "('2025-09-01 08:00:00', 'Vehicle_0', 'Warehouse_0', 25.0, 3600.0), "
                    + "('2025-09-01 08:00:00', 'Vehicle_1', 'Warehouse_1', 15.0, 1800.0)");
        }
    }

    @AfterEach
    void tearDown() {
        analytics.close();
    }

    @Test
    void testKpis() {
        Report r = analytics.generate();
        assertEquals(2, r.kpis.orders);
        assertEquals(120.0, r.kpis.revenue, 1e-6);
        assertEquals(2, r.kpis.deliveredOrders);
        assertEquals(6.4, r.kpis.totalFuelLiters, 1e-6); // (50+30) km * 0.08
        assertEquals(6.4 * 102.5, r.kpis.totalFuelCost, 1e-6);
        assertEquals(19.0, r.kpis.avgDeliveryHours, 1e-6); // (10+28)/2
    }

    @Test
    void testQ1ItemDemandPerWarehouse() {
        Report r = analytics.generate();
        assertEquals(2, r.q1.warehouses.size());
        Report.ItemDemand.WarehouseItems w0 = r.q1.warehouses.get(0);
        assertEquals("Warehouse_0", w0.warehouseId);
        assertEquals("Widget", w0.top.get(0).itemName);
        assertEquals(2, w0.top.get(0).qty);
    }

    @Test
    void testQ2RevenuePerWarehouse() {
        Report r = analytics.generate();
        assertEquals(2, r.q2.size());
        Report.WarehouseFinancials w0 = r.q2.get(0);
        assertEquals(48.0, w0.revenue, 1e-6);
        assertEquals(15.0 * 0.08 * 102.5, r.q2.get(1).fuelCost, 1e-6);
    }

    @Test
    void testQ3FuelPerVehicle() {
        Report r = analytics.generate();
        assertEquals(80.0, r.q3.totalDistanceKm, 1e-6);
        assertEquals(2, r.q3.perVehicle.size());
        assertEquals(50.0 * 0.08, r.q3.perVehicle.get(0).liters, 1e-6);
    }

    @Test
    void testQ4HoursOut() {
        Report r = analytics.generate();
        assertEquals(2, r.q4.size());
        assertEquals(1.0, r.q4.get(0).hoursOut, 1e-6);
        assertEquals(1, r.q4.get(0).dispatches);
    }

    @Test
    void testQ5IdleByHour() {
        Report r = analytics.generate();
        assertEquals(24, r.q5.size());
        // 08:00 has 2 busy vehicles out of 2 -> 0% idle
        assertEquals(0.0, r.q5.get(8).idlePct, 1e-6);
        // 12:00 has 0 busy -> 100% idle
        assertEquals(100.0, r.q5.get(12).idlePct, 1e-6);
    }

    @Test
    void testQ6Deadline() {
        Report r = analytics.generate();
        assertEquals(1, r.q6.onTime);
        assertEquals(1, r.q6.late);
        assertEquals(0, r.q6.undelivered);
        assertEquals(50.0, r.q6.onTimePct, 1e-6);
    }

    @Test
    void testQ7BasketSize() {
        Report r = analytics.generate();
        assertEquals(1.5, r.q7.avgItemsPerOrder, 1e-6); // (2+1)/2
        assertEquals(3.0, r.q7.avgUnitsPerOrder, 1e-6); // (3+3)/2
    }

    @Test
    void testQ8DailyDeliveries() {
        Report r = analytics.generate();
        assertEquals(2, r.q8.size());
        assertEquals("2025-09-01", r.q8.get(0).day);
        assertEquals(1, r.q8.get(0).orders);
        assertEquals("2025-09-02", r.q8.get(1).day);
        assertEquals(1, r.q8.get(1).orders);
    }

    @Test
    void testQ9FurthestCustomers() {
        Report r = analytics.generate();
        assertEquals(2, r.q9.size());
        // Customer_1 at (20.5, 74.0) is furthest from any warehouse
        assertEquals("Customer_1", r.q9.get(0).customerId);
        assertTrue(r.q9.get(0).distanceKm > r.q9.get(1).distanceKm);
    }

    @Test
    void testQ10CostBreakdown() {
        Report r = analytics.generate();
        // maintenance: 2 warehouses * 30 days * 5000 = 300000
        assertEquals(300000.0, r.q10.maintenance, 1e-6);
        assertEquals(80.0 * 0.08 * 102.5, r.q10.fuel, 1e-6);
        assertEquals(50.0, r.q10.packaging, 1e-6); // 2 orders * 25
        assertEquals(300000.0 + 80.0 * 0.08 * 102.5 + 50.0, r.q10.total, 1e-6);
    }

    @Test
    void testRequiredFee() {
        Report r = analytics.generate();
        // totalCost = cogs 100 + opex (300000 + 656 + 50); target 12%
        double totalCost = 100.0 + r.q10.total;
        double needed = 1.12 * totalCost - 120.0 - 23.0;
        assertEquals(0.12, r.requiredFee.targetReturn, 1e-9);
        assertEquals(totalCost, r.requiredFee.totalCost, 1e-6);
        assertEquals(needed, r.requiredFee.incomeNeeded, 1e-6);
        assertEquals(needed / 40.0, r.requiredFee.perKm, 1e-6); // dispatch km 25+15
        assertEquals(needed / 2, r.requiredFee.perOrder, 1e-6);
        // Two layers: fuel floor 0.08*102.5 = 8.2/km, rest is top-up.
        assertEquals(8.2, r.requiredFee.fuelFloorPerKm, 1e-6);
        assertEquals(Math.max(0, needed / 40.0 - 8.2), r.requiredFee.topUpPerKm, 1e-6);
        assertEquals(Math.max(8.2, needed / 40.0), r.requiredFee.recommendedPerKm, 1e-6);
        assertEquals(23.0 - 80.0 * 0.08 * 102.5, r.requiredFee.deliveryMargin, 1e-6);
        assertEquals(20.0, r.requiredFee.productMargin, 1e-6);
        assertEquals((20.0 + 23.0 - r.q10.total) / (100.0 + r.q10.total),
                r.requiredFee.currentReturn, 1e-9);
    }

    @Test
    void testWarehouseAndVehicleFeeAttribution() {
        Report r = analytics.generate();
        assertEquals(15.0, r.q2.get(0).feeIncome, 1e-6);
        assertEquals(8.0, r.q2.get(1).feeIncome, 1e-6);
        assertEquals(48.0 + 15.0 - r.q2.get(0).totalCost, r.q2.get(0).profit, 1e-6);
        assertEquals(15.0, r.q15.get(0).feeIncome + r.q15.get(1).feeIncome - 8.0, 1e-6);
    }

    @Test
    void testQ11ProfitSummary() {
        Report r = analytics.generate();
        assertEquals(120.0, r.q11.revenue, 1e-6);
        assertEquals(100.0, r.q11.cogs, 1e-6); // 2*10+1*20+3*20
        assertEquals(20.0, r.q11.grossProfit, 1e-6);
        assertEquals(r.q10.total, r.q11.operationalCost, 1e-6);
        assertEquals(23.0, r.q11.feeIncome, 1e-6);
        assertEquals(20.0 + 23.0 - r.q10.total, r.q11.netProfit, 1e-6);
    }

    @Test
    void testQ13DeliveryTimeStats() {
        Report r = analytics.generate();
        assertEquals(2, r.q13.sampleCount);
        assertEquals(19.0, r.q13.meanHours, 1e-6); // (10+28)/2
        assertEquals(81.0, r.q13.varianceHours2, 1e-6); // ((10-19)^2+(28-19)^2)/2
        assertEquals(9.0, r.q13.stdDevHours, 1e-6);
        assertEquals(19.0, r.q13.medianHours, 1e-6);
    }

    @Test
    void testQ14WarehouseEfficiency() {
        Report r = analytics.generate();
        assertEquals(2, r.q14.size());
        Report.WarehouseEfficiency w1 = r.q14.get(1);
        assertEquals("Warehouse_1", w1.warehouseId);
        assertEquals(72.0, w1.revenue, 1e-6);
        assertEquals(1, w1.ordersFulfilled);
        assertEquals(28.0, w1.avgDeliveryHours, 1e-6);
        assertEquals(0.0, w1.onTimePct, 1e-6); // delivered late
    }

    @Test
    void testQ15VehicleFinancials() {
        Report r = analytics.generate();
        assertEquals(2, r.q15.size());
        Report.VehicleFinancials v0 = r.q15.get(0);
        assertEquals("Vehicle_1", v0.vehicleId);
        assertEquals(72.0, v0.revenue, 1e-6);
        assertEquals(30.0 * 0.08 * 102.5, v0.fuelCost, 1e-6);
        assertEquals(72.0 + 8.0 - 60.0 - 30.0 * 0.08 * 102.5, v0.profit, 1e-6);
        assertEquals(1, v0.splitsDelivered);
    }

    @Test
    void testOldSchemaRejected() throws Exception {
        Path oldDb = Files.createTempDirectory("analytics-old").resolve("old.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + oldDb.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE meta (run_id TEXT PRIMARY KEY, seed INTEGER, start_date TEXT, end_date TEXT)");
            stmt.execute("CREATE TABLE split_orders (id TEXT PRIMARY KEY, item_id TEXT, qnt INTEGER, "
                    + "order_created TEXT, order_delivered TEXT, order_id TEXT)");
        }
        AnalyticsService old = AnalyticsService.open(oldDb.toString(), economics);
        IllegalStateException ex = assertThrows(IllegalStateException.class, old::generate);
        assertTrue(ex.getMessage().contains("schema"));
        old.close();
    }
}
