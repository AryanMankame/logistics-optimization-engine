package com.aryan.Logistics.analytics;

import com.aryan.Logistics.simulation.SimulationConfig.EconomicsConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only analytics over one run's SQLite file. Answers the L1 consulting
 * questions (Q1-Q15) and produces a {@link Report} that is serialized to
 * metrics.json and embedded into the static dashboard HTML.
 */
public class AnalyticsService implements AutoCloseable {

    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    private static final double EARTH_RADIUS_KM = 6371.0;

    private final Connection conn;
    private final EconomicsConfig economics;
    private final SimpleDateFormat fmt = new SimpleDateFormat(DATE_FORMAT);

    private AnalyticsService(Connection conn, EconomicsConfig economics) {
        this.conn = conn;
        this.economics = economics;
    }

    public static AnalyticsService open(String dbPath, EconomicsConfig economics) {
        try {
            Connection conn = DriverManager.getConnection(
                    "jdbc:sqlite:file:" + new java.io.File(dbPath).getAbsolutePath() + "?mode=ro");
            return new AnalyticsService(conn, economics);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open analytics connection to " + dbPath, e);
        }
    }

    public Report generate() {
        verifySchema();
        Report r = new Report();
        loadMeta(r);
        computeKpis(r);
        computeQ1(r);
        computeQ2AndQ14(r);
        computeQ3(r);
        computeQ4(r);
        computeQ5(r);
        computeQ6(r);
        computeQ7(r);
        computeQ8(r);
        computeQ9(r);
        computeQ10(r);
        computeQ11(r);
        computeQ12(r);
        computeQ13(r);
        computeQ15(r);
        return r;
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    private void verifySchema() {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(split_orders)")) {
            boolean hasWarehouse = false;
            while (rs.next()) {
                if ("warehouse_id".equals(rs.getString("name"))) hasWarehouse = true;
            }
            if (!hasWarehouse) {
                throw new IllegalStateException(
                        "This run's schema predates analytics (no split_orders.warehouse_id). "
                                + "Re-run the simulation to regenerate the data file.");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to inspect schema", e);
        }
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(item)")) {
            boolean hasPrice = false;
            while (rs.next()) {
                if ("price".equals(rs.getString("name"))) hasPrice = true;
            }
            if (!hasPrice) {
                throw new IllegalStateException(
                        "This run's schema predates per-item margins (no item.price). "
                                + "Re-run the simulation to regenerate the data file.");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to inspect schema", e);
        }
    }

    private void loadMeta(Report r) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT run_id, seed, start_date, end_date FROM meta")) {
            rs.next();
            r.meta.runId = rs.getString("run_id");
            r.meta.seed = rs.getInt("seed");
            r.meta.startDate = rs.getString("start_date");
            r.meta.endDate = rs.getString("end_date");
            long days = (parseDate(r.meta.endDate + " 00:00:00").getTime()
                    - parseDate(r.meta.startDate + " 00:00:00").getTime()) / (24 * 3600_000L);
            r.meta.runDays = (int) days;
        } catch (SQLException | ParseException e) {
            throw new RuntimeException("Failed to read run meta", e);
        }
    }

    // ------------------------------------------------------------------
    // KPIs (header)
    // ------------------------------------------------------------------

    private void computeKpis(Report r) {
        try (Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*), COALESCE(SUM(total_amount),0), "
                            + "COALESCE(AVG(CASE WHEN order_delivered IS NOT NULL AND order_created IS NOT NULL "
                            + "THEN (julianday(order_delivered) - julianday(order_created)) * 24.0 END),0) "
                            + "FROM orders")) {
                rs.next();
                r.kpis.orders = rs.getInt(1);
                r.kpis.revenue = rs.getDouble(2);
                r.kpis.avgDeliveryHours = rs.getDouble(3);
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM orders WHERE order_delivered IS NOT NULL")) {
                rs.next();
                r.kpis.deliveredOrders = rs.getInt(1);
            }
            r.kpis.undeliveredOrders = r.kpis.orders - r.kpis.deliveredOrders;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT 100.0 * SUM(CASE WHEN order_delivered IS NOT NULL AND order_delivered <= deadline "
                            + "THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0) FROM orders")) {
                rs.next();
                r.kpis.onTimePct = rs.getDouble(1);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM warehouse")) {
                rs.next();
                r.kpis.warehouseCount = rs.getInt(1);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM vehicle")) {
                rs.next();
                r.kpis.vehicleCount = rs.getInt(1);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM split_orders")) {
                rs.next();
                r.kpis.splitCount = rs.getInt(1);
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COALESCE(SUM(total_distance_covered),0) FROM vehicle")) {
                rs.next();
                double km = rs.getDouble(1);
                r.kpis.totalFuelLiters = km * economics.fuelLitersPerKm;
                r.kpis.totalFuelCost = r.kpis.totalFuelLiters * economics.fuelPricePerLiter;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to compute KPIs", e);
        }
    }

    // ------------------------------------------------------------------
    // Q1 — Most & least ordered items per warehouse
    // ------------------------------------------------------------------

    private void computeQ1(Report r) {
        Map<String, List<Report.ItemDemand.ItemQty>> byWarehouse = new LinkedHashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT s.warehouse_id, i.id, i.name, SUM(s.qnt) AS qty "
                             + "FROM split_orders s JOIN item i ON i.id = s.item_id "
                             + "WHERE s.warehouse_id IS NOT NULL "
                             + "GROUP BY s.warehouse_id, i.id ORDER BY s.warehouse_id, qty DESC")) {
            while (rs.next()) {
                Report.ItemDemand.ItemQty q = new Report.ItemDemand.ItemQty();
                q.itemId = rs.getString("id");
                q.itemName = rs.getString("name");
                q.qty = rs.getInt("qty");
                byWarehouse.computeIfAbsent(rs.getString("warehouse_id"), k -> new ArrayList<>()).add(q);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Q1 query failed", e);
        }
        for (Map.Entry<String, List<Report.ItemDemand.ItemQty>> e : byWarehouse.entrySet()) {
            Report.ItemDemand.WarehouseItems w = new Report.ItemDemand.WarehouseItems();
            w.warehouseId = e.getKey();
            List<Report.ItemDemand.ItemQty> items = e.getValue();
            w.top = items.subList(0, Math.min(5, items.size()));
            w.bottom = items.subList(Math.max(0, items.size() - 5), items.size());
            r.q1.warehouses.add(w);
        }
    }

    // ------------------------------------------------------------------
    // Q2 — Revenue & profit per warehouse (feeds Q14 too)
    // ------------------------------------------------------------------

    private Map<String, Report.WarehouseFinancials> computeWarehouseFinancials(Report r) {
        Map<String, Report.WarehouseFinancials> out = new LinkedHashMap<>();
        // Start from ALL warehouses so zero-activity ones still report their maintenance cost
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id FROM warehouse ORDER BY id")) {
            while (rs.next()) {
                Report.WarehouseFinancials w = new Report.WarehouseFinancials();
                w.warehouseId = rs.getString("id");
                out.put(w.warehouseId, w);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Q2 warehouse query failed", e);
        }
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT s.warehouse_id, SUM(i.price * s.qnt) AS revenue, "
                             + "SUM(i.cost * s.qnt) AS cogs, "
                             + "COALESCE(SUM(s.delivery_fee),0) AS fees, "
                             + "COUNT(DISTINCT s.order_id) AS orders_fulfilled, "
                             + "COUNT(*) AS splits_fulfilled "
                             + "FROM split_orders s JOIN item i ON i.id = s.item_id "
                             + "WHERE s.warehouse_id IS NOT NULL GROUP BY s.warehouse_id")) {
            while (rs.next()) {
                Report.WarehouseFinancials w = out.get(rs.getString("warehouse_id"));
                if (w != null) {
                    w.revenue = rs.getDouble("revenue");
                    w.cogs = rs.getDouble("cogs");
                    w.feeIncome = rs.getDouble("fees");
                    w.packagingCost = rs.getInt("orders_fulfilled") * economics.packagingCostPerOrder;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Q2 revenue query failed", e);
        }
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT warehouse_id, COALESCE(SUM(distance_covered_for_shipment),0) AS km "
                             + "FROM vehicle_delivery_shipment_info "
                             + "WHERE warehouse_id IS NOT NULL GROUP BY warehouse_id")) {
            while (rs.next()) {
                Report.WarehouseFinancials w = out.get(rs.getString("warehouse_id"));
                if (w != null) {
                    w.fuelCost = rs.getDouble("km") * economics.fuelLitersPerKm * economics.fuelPricePerLiter;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Q2 fuel query failed", e);
        }
        for (Report.WarehouseFinancials w : out.values()) {
            w.maintenanceCost = r.meta.runDays * economics.warehouseMaintenancePerDay;
            w.totalCost = w.cogs + w.maintenanceCost + w.fuelCost + w.packagingCost;
            w.profit = w.revenue + w.feeIncome - w.totalCost;
        }
        return out;
    }

    private void computeQ2AndQ14(Report r) {
        Map<String, Report.WarehouseFinancials> fin = computeWarehouseFinancials(r);
        r.q2 = List.copyOf(fin.values());

        // Q14 adds efficiency metrics per warehouse
        Map<String, double[]> deliveryHours = new HashMap<>(); // [sumHours, count, onTime]
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT s.warehouse_id, "
                             + "(julianday(s.order_delivered) - julianday(s.order_created)) * 24.0 AS hrs, "
                             + "CASE WHEN s.order_delivered <= o.deadline THEN 1 ELSE 0 END AS on_time "
                             + "FROM split_orders s JOIN orders o ON o.id = s.order_id "
                             + "WHERE s.warehouse_id IS NOT NULL AND s.order_delivered IS NOT NULL")) {
            while (rs.next()) {
                double[] agg = deliveryHours.computeIfAbsent(rs.getString("warehouse_id"),
                        k -> new double[3]);
                agg[0] += rs.getDouble("hrs");
                agg[1] += 1;
                agg[2] += rs.getInt("on_time");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Q14 delivery query failed", e);
        }
        List<Report.WarehouseEfficiency> eff = new ArrayList<>();
        for (Report.WarehouseFinancials w : fin.values()) {
            Report.WarehouseEfficiency e2 = new Report.WarehouseEfficiency();
            e2.warehouseId = w.warehouseId;
            e2.revenue = w.revenue;
            e2.totalCost = w.totalCost;
            e2.profit = w.profit;
            int ordersFulfilled = (int) Math.round(w.packagingCost / economics.packagingCostPerOrder);
            e2.ordersFulfilled = ordersFulfilled;
            e2.splitsFulfilled = ordersFulfilled > 0 ? countSplitsForWarehouse(w.warehouseId) : 0;
            double[] agg = deliveryHours.get(w.warehouseId);
            if (agg != null && agg[1] > 0) {
                e2.avgDeliveryHours = agg[0] / agg[1];
                e2.onTimePct = 100.0 * agg[2] / agg[1];
            }
            e2.profitPerOrder = ordersFulfilled > 0 ? w.profit / ordersFulfilled : 0;
            eff.add(e2);
        }
        r.q14 = eff;
    }

    private int countSplitsForWarehouse(String warehouseId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM split_orders WHERE warehouse_id = ?")) {
            ps.setString(1, warehouseId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Q14 split count failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Q3 — Fuel consumption total & per vehicle
    // ------------------------------------------------------------------

    private void computeQ3(Report r) {
        List<Report.FuelReport.VehicleFuel> perVehicle = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, COALESCE(total_distance_covered,0) AS km FROM vehicle ORDER BY id")) {
            while (rs.next()) {
                Report.FuelReport.VehicleFuel f = new Report.FuelReport.VehicleFuel();
                f.vehicleId = rs.getString("id");
                f.distanceKm = rs.getDouble("km");
                f.liters = f.distanceKm * economics.fuelLitersPerKm;
                f.cost = f.liters * economics.fuelPricePerLiter;
                perVehicle.add(f);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Q3 query failed", e);
        }
        r.q3.perVehicle = perVehicle;
        for (Report.FuelReport.VehicleFuel f : perVehicle) {
            r.q3.totalDistanceKm += f.distanceKm;
            r.q3.totalLiters += f.liters;
            r.q3.totalCost += f.cost;
        }
    }

    // ------------------------------------------------------------------
    // Q4 — Hours each vehicle spent out for delivery
    // ------------------------------------------------------------------

    private void computeQ4(Report r) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT vehicle_id, SUM(time_taken_for_shipment) / 3600.0 AS hrs, COUNT(*) "
                             + "FROM vehicle_delivery_shipment_info GROUP BY vehicle_id ORDER BY hrs DESC")) {
            while (rs.next()) {
                Report.VehicleHours v = new Report.VehicleHours();
                v.vehicleId = rs.getString("vehicle_id");
                v.hoursOut = rs.getDouble("hrs");
                v.dispatches = rs.getInt(3);
                r.q4.add(v);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Q4 query failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Q5 — Time of day when vehicles are mostly idle
    // ------------------------------------------------------------------

    private void computeQ5(Report r) {
        int totalVehicles = r.kpis.vehicleCount;
        int[] busy = new int[24];
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT hour, time_taken_for_shipment FROM vehicle_delivery_shipment_info")) {
            while (rs.next()) {
                java.util.Date start = fmt.parse(rs.getString("hour"));
                double seconds = rs.getDouble("time_taken_for_shipment");
                int startHour = start.getHours();
                int spanHours = (int) Math.ceil(seconds / 3600.0);
                for (int i = 0; i < spanHours; i++) {
                    busy[(startHour + i) % 24]++;
                }
            }
        } catch (SQLException | ParseException e) {
            throw new RuntimeException("Q5 query failed", e);
        }
        for (int h = 0; h < 24; h++) {
            Report.HourIdle hi = new Report.HourIdle();
            hi.hourOfDay = h;
            hi.busyVehicles = busy[h];
            hi.idleVehicles = Math.max(0, totalVehicles - busy[h]);
            hi.idlePct = totalVehicles > 0 ? 100.0 * hi.idleVehicles / totalVehicles : 0;
            r.q5.add(hi);
        }
    }

    // ------------------------------------------------------------------
    // Q6 — Orders delivered before/after deadline
    // ------------------------------------------------------------------

    private void computeQ6(Report r) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT "
                             + "SUM(CASE WHEN order_delivered IS NOT NULL AND order_delivered <= deadline THEN 1 ELSE 0 END), "
                             + "SUM(CASE WHEN order_delivered IS NOT NULL AND order_delivered > deadline THEN 1 ELSE 0 END), "
                             + "SUM(CASE WHEN order_delivered IS NULL THEN 1 ELSE 0 END) FROM orders")) {
            rs.next();
            r.q6.onTime = rs.getInt(1);
            r.q6.late = rs.getInt(2);
            r.q6.undelivered = rs.getInt(3);
            int total = r.q6.onTime + r.q6.late + r.q6.undelivered;
            r.q6.onTimePct = total > 0 ? 100.0 * r.q6.onTime / total : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Q6 query failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Q7 — Average basket size per order
    // ------------------------------------------------------------------

    private void computeQ7(Report r) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT order_id, COUNT(*) AS items, SUM(qnt) AS units FROM split_orders GROUP BY order_id")) {
            int n = 0;
            double itemsSum = 0;
            double unitsSum = 0;
            int[] buckets = new int[6]; // 1,2,3,4,5,6+
            while (rs.next()) {
                n++;
                int items = rs.getInt("items");
                itemsSum += items;
                unitsSum += rs.getInt("units");
                buckets[Math.min(items, 6) - 1]++;
            }
            r.q7.avgItemsPerOrder = n > 0 ? itemsSum / n : 0;
            r.q7.avgUnitsPerOrder = n > 0 ? unitsSum / n : 0;
            String[] labels = {"1 item", "2 items", "3 items", "4 items", "5 items", "6+ items"};
            for (int i = 0; i < 6; i++) {
                Report.BasketReport.Bucket b = new Report.BasketReport.Bucket();
                b.label = labels[i];
                b.orders = buckets[i];
                r.q7.itemsHistogram.add(b);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Q7 query failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Q8 — Orders delivered day-wise
    // ------------------------------------------------------------------

    private void computeQ8(Report r) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT date(order_delivered) AS day, COUNT(*) AS orders "
                             + "FROM orders WHERE order_delivered IS NOT NULL "
                             + "GROUP BY day ORDER BY day")) {
            while (rs.next()) {
                Report.DayCount d = new Report.DayCount();
                d.day = rs.getString("day");
                d.orders = rs.getInt("orders");
                r.q8.add(d);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Q8 query failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Q9 — Furthest ordering locations (customer -> nearest warehouse)
    // ------------------------------------------------------------------

    private void computeQ9(Report r) {
        Map<String, double[]> locations = new LinkedHashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, latitude, longitude FROM location")) {
            while (rs.next()) {
                locations.put(rs.getString("id"),
                        new double[]{rs.getDouble("latitude"), rs.getDouble("longitude")});
            }
        } catch (SQLException e) {
            throw new RuntimeException("Q9 location query failed", e);
        }
        List<String> warehouseIds = new ArrayList<>();
        Map<String, double[]> warehouseLocs = new HashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, location_id FROM warehouse")) {
            while (rs.next()) {
                warehouseIds.add(rs.getString("id"));
                warehouseLocs.put(rs.getString("id"), locations.get(rs.getString("location_id")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Q9 warehouse query failed", e);
        }
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, name, location_id FROM customer ORDER BY id")) {
            while (rs.next()) {
                double[] loc = locations.get(rs.getString("location_id"));
                if (loc == null) continue;
                double best = Double.MAX_VALUE;
                String bestWh = null;
                for (String wh : warehouseIds) {
                    double[] wloc = warehouseLocs.get(wh);
                    if (wloc == null) continue;
                    double d = haversine(loc[0], loc[1], wloc[0], wloc[1]);
                    if (d < best) {
                        best = d;
                        bestWh = wh;
                    }
                }
                Report.FarCustomer c = new Report.FarCustomer();
                c.customerId = rs.getString("id");
                c.customerName = rs.getString("name");
                c.distanceKm = best;
                c.warehouseId = bestWh;
                r.q9.add(c);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Q9 customer query failed", e);
        }
        r.q9.sort(Comparator.comparingDouble((Report.FarCustomer c) -> c.distanceKm).reversed());
        if (r.q9.size() > 10) {
            r.q9 = r.q9.subList(0, 10);
        }
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // ------------------------------------------------------------------
    // Q10 — Total operational cost
    // ------------------------------------------------------------------

    private void computeQ10(Report r) {
        r.q10.maintenance = r.kpis.warehouseCount * r.meta.runDays * economics.warehouseMaintenancePerDay;
        r.q10.fuel = r.kpis.totalFuelCost;
        r.q10.packaging = r.kpis.orders * economics.packagingCostPerOrder;
        r.q10.total = r.q10.maintenance + r.q10.fuel + r.q10.packaging;
    }

    // ------------------------------------------------------------------
    // Q11 — Gross & net profit
    // ------------------------------------------------------------------

    private void computeQ11(Report r) {
        r.q11.revenue = r.kpis.revenue;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COALESCE(SUM(i.cost * s.qnt),0), COALESCE(SUM(s.delivery_fee),0) "
                             + "FROM split_orders s JOIN item i ON i.id = s.item_id")) {
            rs.next();
            r.q11.cogs = rs.getDouble(1);
            r.q11.feeIncome = rs.getDouble(2);
        } catch (SQLException e) {
            throw new RuntimeException("Q11 COGS query failed", e);
        }
        r.q11.grossProfit = r.kpis.revenue - r.q11.cogs;
        r.q11.operationalCost = r.q10.total;
        r.q11.netProfit = r.q11.grossProfit + r.q11.feeIncome - r.q11.operationalCost;
        r.kpis.feeIncome = r.q11.feeIncome;
        r.kpis.grossProfit = r.q11.grossProfit;
        r.kpis.netProfit = r.q11.netProfit;
        computeRequiredFee(r);
    }

    /**
     * Prescriptive readout: how much delivery income is still needed to reach the
     * configured target return on total cost (net profit / total cost).
     */
    private void computeRequiredFee(Report r) {
        double totalCost = r.q11.cogs + r.q11.operationalCost;
        double t = economics.targetReturnOnCost;
        double needed = Math.max(0, (1 + t) * totalCost - r.q11.revenue - r.q11.feeIncome);
        double dispatchKm = 0;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COALESCE(SUM(distance_covered_for_shipment),0) "
                             + "FROM vehicle_delivery_shipment_info")) {
            rs.next();
            dispatchKm = rs.getDouble(1);
        } catch (SQLException e) {
            throw new RuntimeException("Required-fee distance query failed", e);
        }
        r.requiredFee.targetReturn = t;
        r.requiredFee.totalCost = totalCost;
        r.requiredFee.incomeNeeded = needed;
        r.requiredFee.perKm = dispatchKm > 0 ? needed / dispatchKm : 0;
        r.requiredFee.perOrder = r.kpis.deliveredOrders > 0 ? needed / r.kpis.deliveredOrders : 0;
        // Two-layer framing: job 1 = cover fuel, job 2 = top up to target.
        r.requiredFee.fuelFloorPerKm = economics.fuelLitersPerKm * economics.fuelPricePerLiter;
        r.requiredFee.topUpPerKm = Math.max(0, r.requiredFee.perKm - r.requiredFee.fuelFloorPerKm);
        r.requiredFee.recommendedPerKm =
                Math.max(r.requiredFee.fuelFloorPerKm, r.requiredFee.perKm);
        r.requiredFee.deliveryMargin = r.q11.feeIncome - r.q10.fuel;
        r.requiredFee.productMargin = r.q11.grossProfit;
        r.requiredFee.currentReturn = totalCost > 0 ? r.q11.netProfit / totalCost : 0;
    }

    // ------------------------------------------------------------------
    // Q12 — Average profit per order
    // ------------------------------------------------------------------

    private void computeQ12(Report r) {
        r.q12AvgProfitPerOrder = r.kpis.deliveredOrders > 0
                ? r.q11.netProfit / r.kpis.deliveredOrders : 0;
        r.kpis.profitPerOrder = r.q12AvgProfitPerOrder;
    }

    // ------------------------------------------------------------------
    // Q13 — Average delivery time with std dev & variance
    // ------------------------------------------------------------------

    private void computeQ13(Report r) {
        List<Double> hours = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT (julianday(order_delivered) - julianday(order_created)) * 24.0 AS hrs "
                             + "FROM orders WHERE order_delivered IS NOT NULL AND order_created IS NOT NULL")) {
            while (rs.next()) {
                hours.add(rs.getDouble("hrs"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Q13 query failed", e);
        }
        int n = hours.size();
        r.q13.sampleCount = n;
        if (n == 0) return;
        double sum = 0;
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        for (double h : hours) {
            sum += h;
            min = Math.min(min, h);
            max = Math.max(max, h);
        }
        double mean = sum / n;
        double varSum = 0;
        for (double h : hours) {
            varSum += (h - mean) * (h - mean);
        }
        r.q13.meanHours = mean;
        r.q13.varianceHours2 = varSum / n;
        r.q13.stdDevHours = Math.sqrt(r.q13.varianceHours2);
        r.q13.minHours = min;
        r.q13.maxHours = max;
        List<Double> sorted = new ArrayList<>(hours);
        Collections.sort(sorted);
        r.q13.medianHours = n % 2 == 0
                ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2
                : sorted.get(n / 2);
        double[][] ranges = {{0, 6}, {6, 12}, {12, 24}, {24, 48}, {48, 72}, {72, Double.MAX_VALUE}};
        String[] labels = {"< 6h", "6-12h", "12-24h", "24-48h", "48-72h", "72h+"};
        for (int i = 0; i < ranges.length; i++) {
            int count = 0;
            for (double h : hours) {
                if (h >= ranges[i][0] && h < ranges[i][1]) count++;
            }
            Report.DeliveryTimeReport.Bucket b = new Report.DeliveryTimeReport.Bucket();
            b.label = labels[i];
            b.orders = count;
            r.q13.histogram.add(b);
        }
    }

    // ------------------------------------------------------------------
    // Q15 — Vehicle-wise revenue & profit
    // ------------------------------------------------------------------

    private void computeQ15(Report r) {
        Map<String, Double> revenue = new HashMap<>();
        Map<String, Double> cogs = new HashMap<>();
        Map<String, Double> fees = new HashMap<>();
        Map<String, Integer> splits = new HashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT s.vehicle_id, SUM(i.price * s.qnt) AS rev, "
                             + "SUM(i.cost * s.qnt) AS cogs, COALESCE(SUM(s.delivery_fee),0) AS fees, "
                             + "COUNT(*) AS cnt "
                             + "FROM split_orders s JOIN item i ON i.id = s.item_id "
                             + "WHERE s.vehicle_id IS NOT NULL GROUP BY s.vehicle_id")) {
            while (rs.next()) {
                revenue.put(rs.getString("vehicle_id"), rs.getDouble("rev"));
                cogs.put(rs.getString("vehicle_id"), rs.getDouble("cogs"));
                fees.put(rs.getString("vehicle_id"), rs.getDouble("fees"));
                splits.put(rs.getString("vehicle_id"), rs.getInt("cnt"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Q15 revenue query failed", e);
        }
        Map<String, Double> distance = new HashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, COALESCE(total_distance_covered,0) AS km FROM vehicle")) {
            while (rs.next()) {
                distance.put(rs.getString("id"), rs.getDouble("km"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Q15 distance query failed", e);
        }
        Map<String, Report.VehicleHours> hoursMap = new HashMap<>();
        for (Report.VehicleHours v : r.q4) hoursMap.put(v.vehicleId, v);
        List<Report.VehicleFinancials> out = new ArrayList<>();
        for (String vehicleId : distance.keySet()) {
            Report.VehicleFinancials v = new Report.VehicleFinancials();
            v.vehicleId = vehicleId;
            v.distanceKm = distance.get(vehicleId);
            v.revenue = revenue.getOrDefault(vehicleId, 0.0);
            v.cogs = cogs.getOrDefault(vehicleId, 0.0);
            v.feeIncome = fees.getOrDefault(vehicleId, 0.0);
            v.fuelCost = v.distanceKm * economics.fuelLitersPerKm * economics.fuelPricePerLiter;
            v.profit = v.revenue + v.feeIncome - v.cogs - v.fuelCost;
            Report.VehicleHours h = hoursMap.get(vehicleId);
            if (h != null) {
                v.hoursOut = h.hoursOut;
                v.dispatches = h.dispatches;
            }
            v.splitsDelivered = splits.getOrDefault(vehicleId, 0);
            out.add(v);
        }
        out.sort(Comparator.comparingDouble((Report.VehicleFinancials v) -> v.profit).reversed());
        r.q15 = out;
    }

    private java.util.Date parseDate(String value) throws ParseException {
        return fmt.parse(value);
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to close analytics connection", e);
        }
    }
}
