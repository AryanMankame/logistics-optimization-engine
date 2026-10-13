package com.aryan.Logistics.analytics;

import java.util.ArrayList;
import java.util.List;

/**
 * Analytics report for one simulation run: answers to the L1 consulting
 * questions (Q1-Q15) plus the KPI header. Serialized to metrics.json and
 * embedded into the static dashboard HTML.
 */
public class Report {

    public static final int SCHEMA_VERSION = 2;

    public int schemaVersion = SCHEMA_VERSION;
    public Meta meta = new Meta();
    public Kpi kpis = new Kpi();
    public ItemDemand q1 = new ItemDemand();
    public List<WarehouseFinancials> q2 = new ArrayList<>();
    public FuelReport q3 = new FuelReport();
    public List<VehicleHours> q4 = new ArrayList<>();
    public List<HourIdle> q5 = new ArrayList<>();
    public DeadlineReport q6 = new DeadlineReport();
    public BasketReport q7 = new BasketReport();
    public List<DayCount> q8 = new ArrayList<>();
    public List<FarCustomer> q9 = new ArrayList<>();
    public CostBreakdown q10 = new CostBreakdown();
    public ProfitSummary q11 = new ProfitSummary();
    public RequiredFee requiredFee = new RequiredFee();
    public double q12AvgProfitPerOrder;
    public DeliveryTimeReport q13 = new DeliveryTimeReport();
    public List<WarehouseEfficiency> q14 = new ArrayList<>();
    public List<VehicleFinancials> q15 = new ArrayList<>();

    public static class Meta {
        public String runId;
        public int seed;
        public String startDate;
        public String endDate;
        public int runDays;
    }

    public static class Kpi {
        public double revenue;
        public double feeIncome;
        public double grossProfit;
        public double netProfit;
        public double profitPerOrder;
        public int orders;
        public int deliveredOrders;
        public int undeliveredOrders;
        public double onTimePct;
        public double avgDeliveryHours;
        public double totalFuelLiters;
        public double totalFuelCost;
        public int warehouseCount;
        public int vehicleCount;
        public int splitCount;
    }

    public static class ItemDemand {
        public List<WarehouseItems> warehouses = new ArrayList<>();

        public static class WarehouseItems {
            public String warehouseId;
            public List<ItemQty> top = new ArrayList<>();
            public List<ItemQty> bottom = new ArrayList<>();
        }

        public static class ItemQty {
            public String itemId;
            public String itemName;
            public int qty;
        }
    }

    public static class WarehouseFinancials {
        public String warehouseId;
        public double revenue;
        public double cogs;
        public double feeIncome;
        public double maintenanceCost;
        public double fuelCost;
        public double packagingCost;
        public double totalCost;
        public double profit;
    }

    public static class FuelReport {
        public double totalDistanceKm;
        public double totalLiters;
        public double totalCost;
        public List<VehicleFuel> perVehicle = new ArrayList<>();

        public static class VehicleFuel {
            public String vehicleId;
            public double distanceKm;
            public double liters;
            public double cost;
        }
    }

    public static class VehicleHours {
        public String vehicleId;
        public double hoursOut;
        public int dispatches;
    }

    public static class HourIdle {
        public int hourOfDay;
        public int busyVehicles;
        public int idleVehicles;
        public double idlePct;
    }

    public static class DeadlineReport {
        public int onTime;
        public int late;
        public int undelivered;
        public double onTimePct;
    }

    public static class BasketReport {
        public double avgItemsPerOrder;
        public double avgUnitsPerOrder;
        public List<Bucket> itemsHistogram = new ArrayList<>();

        public static class Bucket {
            public String label;
            public int orders;
        }
    }

    public static class DayCount {
        public String day;
        public int orders;
    }

    public static class FarCustomer {
        public String customerId;
        public String customerName;
        public double distanceKm;
        public String warehouseId;
    }

    public static class CostBreakdown {
        public double maintenance;
        public double fuel;
        public double packaging;
        public double total;
    }

    public static class ProfitSummary {
        public double revenue;
        public double cogs;
        public double grossProfit;
        public double feeIncome;
        public double operationalCost;
        public double netProfit;
    }

    public static class RequiredFee {
        /** Configured target return on total cost, e.g. 0.12. */
        public double targetReturn;
        public double totalCost;
        /** Additional delivery income needed to hit the target (never negative). */
        public double incomeNeeded;
        /** incomeNeeded per dispatch km. */
        public double perKm;
        /** incomeNeeded per delivered order. */
        public double perOrder;
        /** Job 1: fee must at least cover fuel — fuel cost per km at current settings. */
        public double fuelFloorPerKm;
        /** Job 2: required rate above the floor (perKm - floor, never negative). */
        public double topUpPerKm;
        /** Recommended rate: the higher of floor and required (delivery sustains itself). */
        public double recommendedPerKm;
        /** Delivery operation margin: fee income - fuel cost. */
        public double deliveryMargin;
        /** Product operation margin: turnover - goods cost (gross profit). */
        public double productMargin;
        /** Current net profit / total cost. */
        public double currentReturn;
    }

    public static class DeliveryTimeReport {
        public int sampleCount;
        public double meanHours;
        public double varianceHours2;
        public double stdDevHours;
        public double minHours;
        public double maxHours;
        public double medianHours;
        public List<Bucket> histogram = new ArrayList<>();

        public static class Bucket {
            public String label;
            public int orders;
        }
    }

    public static class WarehouseEfficiency {
        public String warehouseId;
        public double revenue;
        public double totalCost;
        public double profit;
        public double profitPerOrder;
        public int ordersFulfilled;
        public int splitsFulfilled;
        public double avgDeliveryHours;
        public double onTimePct;
    }

    public static class VehicleFinancials {
        public String vehicleId;
        public double revenue;
        public double cogs;
        public double feeIncome;
        public double distanceKm;
        public double fuelCost;
        public double profit;
        public double hoursOut;
        public int dispatches;
        public int splitsDelivered;
    }
}
