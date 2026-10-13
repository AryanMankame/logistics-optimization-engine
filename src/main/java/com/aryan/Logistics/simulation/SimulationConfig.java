package com.aryan.Logistics.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.InputStream;
import java.util.List;

public class SimulationConfig {
    public SimulationParams simulation;
    public List<LocationConfig> locations;
    public List<ItemConfig> items;
    public List<CustomerConfig> customers;
    public List<Integer> warehouseLocationIndices;
    public List<VehicleConfig> vehicles;
    public ClusterConfig clusters;
    public OrderGenerationConfig orderGeneration;
    public RandomEventsConfig randomEvents;
    public EconomicsConfig economics;
    /** Count-based scenario inputs. When set, they override the explicit lists. */
    public Integer warehouseCount;
    public Integer vehicleCount;
    public Integer customerCount;

    public static SimulationConfig load(String path) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        if (path != null) {
            File file = new File(path);
            if (file.exists()) {
                return mapper.readValue(file, SimulationConfig.class);
            }
        }
        InputStream is = SimulationConfig.class.getClassLoader().getResourceAsStream("simulation.json");
        if (is == null) {
            throw new RuntimeException("simulation.json not found at " + path + " or in classpath");
        }
        return mapper.readValue(is, SimulationConfig.class);
    }

    public static class SimulationParams {
        public String cityName;
        public String startDate;
        public String endDate;
        public int randomSeed;
    }

    public static class LocationConfig {
        public String name;
        public double latitude;
        public double longitude;
    }

    public static class ItemConfig {
        public String name;
        public double cost;
        public double weight;
        /** Optional explicit selling price (overrides marginPct when set). */
        public Double price;
        /** Optional per-item margin fraction, e.g. 0.12 for 12%. */
        public Double marginPct;
    }

    public static class CustomerConfig {
        public String name;
        public int locationIndex;
    }

    public static class VehicleConfig {
        public int capacity;
        public double averageSpeed;
    }

    public static class ClusterConfig {
        public int count;
    }

    public static class OrderGenerationConfig {
        public HourlyRatesConfig hourlyRates;
        public int deadlineDays;
        public int maxItemsPerOrder;
        public int maxQuantityPerItem;
        /** Rough expected orders per day; hourly ranges are scaled to hit it. Null = legacy unscaled. */
        public Integer targetOrdersPerDay;
    }

    public static class HourlyRatesConfig {
        public int[] night;
        public int[] morning;
        public int[] afternoon;
        public int[] evening;
    }

    public static class RandomEventsConfig {
        public double refillProbability;
        public double addWarehouseProbability;
        public double addVehicleProbability;
        public double roadClosureProbability;
        public double roadReopenProbability;
        public double trafficSpikeProbability;
        public double trafficClearProbability;
    }

    public static class EconomicsConfig {
        public String currency = "INR";
        public double fuelPricePerLiter = 102.5;
        public double fuelLitersPerKm = 0.08;
        public double warehouseMaintenancePerDay = 5000.0;
        public double packagingCostPerOrder = 25.0;
        /** Fallback margin fraction applied to items without price/marginPct. */
        public double defaultMarginPct = 0.10;
        /** Target return on total cost (net profit ÷ total cost), e.g. 0.12. Drives the required-fee readout. */
        public double targetReturnOnCost = 0.12;
        /** Per-km delivery fee charged at fulfillment. 0 = free delivery. */
        public double deliveryFeePerKm = 0.0;
        /** @deprecated Replaced by per-item margins; kept so old configs still load. */
        @Deprecated
        public double cogsRatio = 0.70;
    }
}
