package com.aryan.Logistics.simulation;

import com.aryan.Logistics.models.*;
import com.aryan.Logistics.analytics.DataCollector;
import com.aryan.Logistics.service.OrderHandlerService;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;

public class OrderGenerator {
    private final SimulationConfig.OrderGenerationConfig orderConfig;
    private final List<Item> items;
    private final List<Customer> customers;
    private final OrderHandlerService orderHandlerService;
    private final DataCollector dataCollector;
    private final Random random;
    private int orderCounter = 0;
    /** Multiplier applied to hourly ranges so expected daily volume hits the target. */
    private final double volumeScale;

    public OrderGenerator(SimulationConfig.OrderGenerationConfig orderConfig,
                          List<Item> items,
                          List<Customer> customers,
                          OrderHandlerService orderHandlerService,
                          DataCollector dataCollector,
                          Random random) {
        this.orderConfig = orderConfig;
        this.items = items;
        this.customers = customers;
        this.orderHandlerService = orderHandlerService;
        this.dataCollector = dataCollector;
        this.random = random;
        this.volumeScale = computeVolumeScale(orderConfig);
    }

    /**
     * Hourly ranges keep their day-part shape; the whole profile is scaled so the
     * expected daily total equals {@code targetOrdersPerDay}. Null target = 1.0 (legacy).
     */
    static double computeVolumeScale(SimulationConfig.OrderGenerationConfig cfg) {
        if (cfg.targetOrdersPerDay == null || cfg.targetOrdersPerDay <= 0) return 1.0;
        double expected = 0;
        expected += 6 * mid(cfg.hourlyRates.night);
        expected += 6 * mid(cfg.hourlyRates.morning);
        expected += 6 * mid(cfg.hourlyRates.afternoon);
        expected += 6 * mid(cfg.hourlyRates.evening);
        if (expected <= 0) return 1.0;
        return cfg.targetOrdersPerDay / expected;
    }

    private static double mid(int[] range) {
        return (range[0] + range[1]) / 2.0;
    }

    static int[] scaledRange(int[] range, double scale) {
        int lo = Math.max(0, (int) Math.round(range[0] * scale));
        int hi = Math.max(lo, (int) Math.round(range[1] * scale));
        return new int[]{lo, hi};
    }

    public int generateOrders(Date currentTime) {
        int hour = currentTime.getHours();
        int[] rateRange = scaledRange(getRateRange(hour), volumeScale);
        int orderCount = rateRange[0] + random.nextInt(rateRange[1] - rateRange[0] + 1);

        for (int i = 0; i < orderCount; i++) {
            Order order = createOrder(currentTime);
            dataCollector.insertOrder(order);
            orderHandlerService.addOrder(order);
        }
        return orderCount;
    }

    private Order createOrder(Date currentTime) {
        Customer customer = customers.get(random.nextInt(customers.size()));

        int itemCount = 1 + random.nextInt(orderConfig.maxItemsPerOrder);
        ArrayList<Pair<Integer, Item>> itemsRequested = new ArrayList<>();
        float totalAmount = 0f;

        Set<Integer> pickedIndices = new HashSet<>();
        for (int i = 0; i < itemCount; i++) {
            int itemIdx;
            do {
                itemIdx = random.nextInt(items.size());
            } while (pickedIndices.contains(itemIdx) && pickedIndices.size() < items.size());
            pickedIndices.add(itemIdx);

            Item item = items.get(itemIdx);
            int quantity = 1 + random.nextInt(orderConfig.maxQuantityPerItem);
            itemsRequested.add(Pair.of(quantity, item));
            totalAmount += item.price * quantity;
        }

        Date deadline = new Date(currentTime.getTime() + (long) orderConfig.deadlineDays * 24 * 3600_000);
        String orderId = "Order_" + orderCounter++;

        return new Order(orderId, new ArrayList<>(), itemsRequested, totalAmount,
                deadline, currentTime, null, customer);
    }

    private int[] getRateRange(int hour) {
        if (hour >= 0 && hour < 6) {
            return orderConfig.hourlyRates.night;
        } else if (hour >= 6 && hour < 12) {
            return orderConfig.hourlyRates.morning;
        } else if (hour >= 12 && hour < 18) {
            return orderConfig.hourlyRates.afternoon;
        } else {
            return orderConfig.hourlyRates.evening;
        }
    }
}
