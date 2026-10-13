package com.aryan.Logistics.analytics;

import com.aryan.Logistics.models.Customer;
import com.aryan.Logistics.models.Item;
import com.aryan.Logistics.models.Location;
import com.aryan.Logistics.models.Order;
import com.aryan.Logistics.models.Road;
import com.aryan.Logistics.models.SplitOrder;
import com.aryan.Logistics.models.Vehicle;
import com.aryan.Logistics.models.Warehouse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes simulation state to a per-run SQLite file (WAL mode).
 *
 * Dimension tables (location, item, customer, road, warehouse, inventory, vehicle)
 * are written at setup; orders / split orders / cluster membership / dispatches are
 * written as events happen; road, inventory and vehicle values are refreshed with a
 * final snapshot when the run ends.
 */
public class DataCollector implements AutoCloseable {

    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

    private final Connection conn;
    private final Map<String, PreparedStatement> statements = new HashMap<>();
    private final Map<String, String> locationIds = new LinkedHashMap<>();
    private final SimpleDateFormat fmt = new SimpleDateFormat(DATE_FORMAT);

    private DataCollector(Connection conn) {
        this.conn = conn;
    }

    public static DataCollector open(String dbPath, String runId, int seed,
                                     String startDate, String endDate, String configPath) {
        try {
            Path path = Paths.get(dbPath).toAbsolutePath();
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            DataCollector collector = new DataCollector(conn);
            collector.exec("PRAGMA journal_mode=WAL");
            collector.exec("PRAGMA synchronous=NORMAL");
            collector.exec("PRAGMA foreign_keys=ON");
            collector.createSchema();
            collector.insertMeta(runId, seed, startDate, endDate, configPath);
            return collector;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open data collector at " + dbPath, e);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to create directory for " + dbPath, e);
        }
    }

    private void createSchema() {
        exec("CREATE TABLE IF NOT EXISTS meta ("
                + "run_id TEXT PRIMARY KEY,"
                + "seed INTEGER,"
                + "start_date TEXT,"
                + "end_date TEXT,"
                + "config_path TEXT,"
                + "created_at TEXT)");
        exec("CREATE TABLE IF NOT EXISTS location ("
                + "id TEXT PRIMARY KEY,"
                + "latitude REAL NOT NULL,"
                + "longitude REAL NOT NULL)");
        exec("CREATE TABLE IF NOT EXISTS item ("
                + "id TEXT PRIMARY KEY,"
                + "name TEXT,"
                + "cost REAL,"
                + "weight REAL,"
                + "price REAL)");
        exec("CREATE TABLE IF NOT EXISTS customer ("
                + "id TEXT PRIMARY KEY,"
                + "name TEXT,"
                + "location_id TEXT REFERENCES location(id))");
        exec("CREATE TABLE IF NOT EXISTS road ("
                + "id TEXT PRIMARY KEY,"
                + "from_location_id TEXT NOT NULL REFERENCES location(id),"
                + "to_location_id TEXT NOT NULL REFERENCES location(id),"
                + "distance REAL,"
                + "open INTEGER,"
                + "traffic_multiplier REAL)");
        exec("CREATE TABLE IF NOT EXISTS vehicle ("
                + "id TEXT PRIMARY KEY,"
                + "location_id TEXT REFERENCES location(id),"
                + "capacity REAL,"
                + "total_distance_covered REAL,"
                + "average_speed REAL,"
                + "out_for_delivery_until TEXT)");
        exec("CREATE TABLE IF NOT EXISTS warehouse ("
                + "id TEXT PRIMARY KEY,"
                + "location_id TEXT REFERENCES location(id))");
        exec("CREATE TABLE IF NOT EXISTS inventory ("
                + "inventory_id TEXT PRIMARY KEY,"
                + "warehouse_id TEXT NOT NULL REFERENCES warehouse(id),"
                + "item_id TEXT NOT NULL REFERENCES item(id),"
                + "qnt INTEGER,"
                + "UNIQUE(warehouse_id, item_id))");
        exec("CREATE TABLE IF NOT EXISTS orders ("
                + "id TEXT PRIMARY KEY,"
                + "total_amount REAL,"
                + "deadline TEXT,"
                + "order_created TEXT,"
                + "order_delivered TEXT,"
                + "customer_id TEXT NOT NULL REFERENCES customer(id))");
        exec("CREATE TABLE IF NOT EXISTS split_orders ("
                + "id TEXT PRIMARY KEY,"
                + "item_id TEXT NOT NULL REFERENCES item(id),"
                + "qnt INTEGER,"
                + "order_created TEXT,"
                + "order_delivered TEXT,"
                + "order_id TEXT NOT NULL REFERENCES orders(id),"
                + "warehouse_id TEXT REFERENCES warehouse(id),"
                + "vehicle_id TEXT REFERENCES vehicle(id),"
                + "delivery_fee REAL)");
        exec("CREATE TABLE IF NOT EXISTS cluster ("
                + "cluster_id TEXT,"
                + "split_order_id TEXT REFERENCES split_orders(id),"
                + "centroid_lat REAL,"
                + "centroid_lng REAL,"
                + "radius REAL,"
                + "PRIMARY KEY (cluster_id, split_order_id))");
        exec("CREATE TABLE IF NOT EXISTS vehicle_delivery_shipment_info ("
                + "dispatch_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "hour TEXT,"
                + "vehicle_id TEXT NOT NULL REFERENCES vehicle(id),"
                + "warehouse_id TEXT REFERENCES warehouse(id),"
                + "distance_covered_for_shipment REAL,"
                + "time_taken_for_shipment REAL)");
        exec("CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders(customer_id)");
        exec("CREATE INDEX IF NOT EXISTS idx_split_orders_order ON split_orders(order_id)");
        exec("CREATE INDEX IF NOT EXISTS idx_cluster_split ON cluster(split_order_id)");
        exec("CREATE INDEX IF NOT EXISTS idx_inventory_warehouse ON inventory(warehouse_id)");
    }

    // ------------------------------------------------------------------
    // Location ids: assigned in registration order, keyed by coordinates so
    // that Location objects created independently for the same physical
    // point (SimulationRunner vs CityReplicaService) share one id.
    // ------------------------------------------------------------------

    public void registerLocations(List<Location> locations) {
        for (Location location : locations) {
            locationId(location);
        }
    }

    public String locationId(Location location) {
        String key = location.latitude + ":" + location.longitude;
        String id = locationIds.get(key);
        if (id == null) {
            id = "Loc_" + locationIds.size();
            locationIds.put(key, id);
            update("INSERT INTO location (id, latitude, longitude) VALUES (?,?,?)",
                    id, location.latitude, location.longitude);
        }
        return id;
    }

    // ------------------------------------------------------------------
    // Dimension tables (written at setup)
    // ------------------------------------------------------------------

    public void writeItem(Item item) {
        update("INSERT OR REPLACE INTO item (id, name, cost, weight, price) VALUES (?,?,?,?,?)",
                item.id, item.name, item.cost, item.weight, item.price);
    }

    public void writeCustomer(Customer customer) {
        update("INSERT OR REPLACE INTO customer (id, name, location_id) VALUES (?,?,?)",
                customer.id, customer.name, locationId(customer.location));
    }

    public void writeRoads(List<Road> roads) {
        for (Road road : roads) {
            update("INSERT OR REPLACE INTO road "
                            + "(id, from_location_id, to_location_id, distance, open, traffic_multiplier) "
                            + "VALUES (?,?,?,?,?,?)",
                    road.id, locationId(road.from), locationId(road.to),
                    road.distance, road.open ? 1 : 0, road.trafficMultiplier);
        }
    }

    public void writeVehicle(Vehicle vehicle) {
        update("INSERT OR REPLACE INTO vehicle "
                        + "(id, location_id, capacity, total_distance_covered, average_speed, out_for_delivery_until) "
                        + "VALUES (?,?,?,?,?,?)",
                vehicle.id, locationId(vehicle.location), vehicle.capacity,
                vehicle.totalDistanceCovered, vehicle.averageSpeed, fmt.format(vehicle.outForDeliveryUntil));
    }

    public void writeWarehouse(Warehouse warehouse) {
        update("INSERT OR REPLACE INTO warehouse (id, location_id) VALUES (?,?)",
                warehouse.id, locationId(warehouse.location));
        for (Map.Entry<Item, Integer> entry : warehouse.getInventory().snapshot().entrySet()) {
            update("INSERT OR REPLACE INTO inventory (inventory_id, warehouse_id, item_id, qnt) VALUES (?,?,?,?)",
                    warehouse.id + "|" + entry.getKey().id, warehouse.id,
                    entry.getKey().id, entry.getValue());
        }
    }

    // ------------------------------------------------------------------
    // Event tables (written while the simulation runs)
    // ------------------------------------------------------------------

    public void insertOrder(Order order) {
        update("INSERT INTO orders (id, total_amount, deadline, order_created, order_delivered, customer_id) "
                        + "VALUES (?,?,?,?,?,?)",
                order.id, order.totalAmount.doubleValue(), fmt.format(order.deadline),
                fmt.format(order.orderCreated), null, order.orderedBy.id);
    }

    public void insertSplitOrder(SplitOrder splitOrder) {
        update("INSERT INTO split_orders (id, item_id, qnt, order_created, order_delivered, order_id) "
                        + "VALUES (?,?,?,?,?,?)",
                splitOrder.id, splitOrder.item.id, splitOrder.quantity,
                fmt.format(splitOrder.orderCreated), null, splitOrder.partOf.id);
    }

    public void updateSplitDelivered(String splitOrderId, Date deliveredAt) {
        update("UPDATE split_orders SET order_delivered = ? WHERE id = ?",
                fmt.format(deliveredAt), splitOrderId);
    }

    public void updateSplitFulfillment(String splitOrderId, String warehouseId, String vehicleId, double deliveryFee) {
        update("UPDATE split_orders SET warehouse_id = ?, vehicle_id = ?, delivery_fee = ? WHERE id = ?",
                warehouseId, vehicleId, deliveryFee, splitOrderId);
    }

    public void updateOrderDelivered(String orderId, Date deliveredAt) {
        update("UPDATE orders SET order_delivered = ? WHERE id = ?",
                fmt.format(deliveredAt), orderId);
    }

    public void insertCluster(String clusterId, SplitOrder splitOrder, Location centroid, double radius) {
        update("INSERT OR REPLACE INTO cluster "
                        + "(cluster_id, split_order_id, centroid_lat, centroid_lng, radius) VALUES (?,?,?,?,?)",
                clusterId, splitOrder.id, centroid.latitude, centroid.longitude, radius);
    }

    public void insertClusterMarker(String clusterId, Location centroid) {
        update("INSERT OR REPLACE INTO cluster "
                        + "(cluster_id, split_order_id, centroid_lat, centroid_lng, radius) VALUES (?,?,?,?,?)",
                clusterId, null, centroid.latitude, centroid.longitude, 0.0);
    }

    public void insertDispatch(Date hour, String vehicleId, String warehouseId, double distance, double timeSeconds) {
        update("INSERT INTO vehicle_delivery_shipment_info "
                        + "(hour, vehicle_id, warehouse_id, distance_covered_for_shipment, time_taken_for_shipment) "
                        + "VALUES (?,?,?,?,?)",
                fmt.format(hour), vehicleId, warehouseId, distance, timeSeconds);
    }

    // ------------------------------------------------------------------
    // Final snapshot (run end)
    // ------------------------------------------------------------------

    public void finalSnapshot(List<Road> roads, List<Warehouse> warehouses, List<Vehicle> vehicles) {
        for (Road road : roads) {
            update("UPDATE road SET open = ?, traffic_multiplier = ? WHERE id = ?",
                    road.open ? 1 : 0, road.trafficMultiplier, road.id);
        }
        for (Warehouse warehouse : warehouses) {
            for (Map.Entry<Item, Integer> entry : warehouse.getInventory().snapshot().entrySet()) {
                update("UPDATE inventory SET qnt = ? WHERE inventory_id = ?",
                        entry.getValue(), warehouse.id + "|" + entry.getKey().id);
            }
        }
        for (Vehicle vehicle : vehicles) {
            update("UPDATE vehicle SET total_distance_covered = ?, out_for_delivery_until = ? WHERE id = ?",
                    vehicle.totalDistanceCovered, fmt.format(vehicle.outForDeliveryUntil), vehicle.id);
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private void insertMeta(String runId, int seed, String startDate, String endDate, String configPath) {
        update("INSERT INTO meta (run_id, seed, start_date, end_date, config_path, created_at) VALUES (?,?,?,?,?,?)",
                runId, seed, startDate, endDate, configPath, fmt.format(new Date()));
    }

    private void exec(String sql) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to execute: " + sql, e);
        }
    }

    private void update(String sql, Object... params) {
        try {
            PreparedStatement ps = statements.computeIfAbsent(sql, s -> {
                try {
                    return conn.prepareStatement(s);
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to prepare: " + s, e);
                }
            });
            for (int i = 0; i < params.length; i++) {
                Object p = params[i];
                if (p == null) {
                    ps.setNull(i + 1, java.sql.Types.NULL);
                } else if (p instanceof String s) {
                    ps.setString(i + 1, s);
                } else if (p instanceof Integer n) {
                    ps.setInt(i + 1, n);
                } else if (p instanceof Long n) {
                    ps.setLong(i + 1, n);
                } else if (p instanceof Double d) {
                    ps.setDouble(i + 1, d);
                } else if (p instanceof Float f) {
                    ps.setFloat(i + 1, f);
                } else if (p instanceof Boolean b) {
                    ps.setInt(i + 1, b ? 1 : 0);
                } else {
                    ps.setObject(i + 1, p);
                }
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to execute update: " + sql, e);
        }
    }

    public long count(String table) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count rows of " + table, e);
        }
    }

    public List<String> tableNames() {
        List<String> tables = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
            return tables;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list tables", e);
        }
    }

    @Override
    public void close() {
        try {
            for (PreparedStatement ps : statements.values()) {
                ps.close();
            }
            statements.clear();
            conn.close();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to close data collector", e);
        }
    }
}
