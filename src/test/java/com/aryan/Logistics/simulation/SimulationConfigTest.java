package com.aryan.Logistics.simulation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimulationConfigTest {

    @Test
    void testLoadFromClasspath() throws Exception {
        SimulationConfig config = SimulationConfig.load(null);

        assertNotNull(config.simulation);
        assertEquals("Mumbai", config.simulation.cityName);
        assertEquals("2025-09-15", config.simulation.startDate);
        assertEquals("2025-10-15", config.simulation.endDate);
        assertEquals(42, config.simulation.randomSeed);

        assertEquals(10000, config.locations.size());
        assertEquals(300, config.items.size());
        assertEquals(200, config.customers.size());
        assertEquals(50, config.vehicles.size());
        assertEquals(20, config.warehouseLocationIndices.size());
        assertEquals(20, config.clusters.count);

        assertEquals(20, config.warehouseCount);
        assertEquals(50, config.vehicleCount);
        assertEquals(200, config.customerCount);
        assertEquals(200, config.orderGeneration.targetOrdersPerDay);
        assertEquals(0.12, config.economics.targetReturnOnCost, 1e-9);
        assertEquals(0.0, config.economics.deliveryFeePerKm, 1e-9);
        assertEquals(0.0, config.randomEvents.addWarehouseProbability, 1e-9);
        assertEquals(0.0, config.randomEvents.addVehicleProbability, 1e-9);

        assertEquals(7, config.orderGeneration.deadlineDays);
        assertEquals(4, config.orderGeneration.maxItemsPerOrder);
        assertEquals(5, config.orderGeneration.maxQuantityPerItem);

        assertEquals(0, config.orderGeneration.hourlyRates.night[0]);
        assertEquals(2, config.orderGeneration.hourlyRates.night[1]);

        assertTrue(config.randomEvents.refillProbability > 0);
        assertTrue(config.randomEvents.refillProbability < 1);
    }
}
