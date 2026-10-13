package com.aryan.Logistics.simulation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderGeneratorScaleTest {

    private SimulationConfig.OrderGenerationConfig config(Integer target) {
        SimulationConfig.OrderGenerationConfig cfg = new SimulationConfig.OrderGenerationConfig();
        SimulationConfig.HourlyRatesConfig rates = new SimulationConfig.HourlyRatesConfig();
        rates.night = new int[]{0, 2};
        rates.morning = new int[]{2, 5};
        rates.afternoon = new int[]{3, 8};
        rates.evening = new int[]{2, 4};
        cfg.hourlyRates = rates;
        cfg.targetOrdersPerDay = target;
        return cfg;
    }

    @Test
    void testNullTargetKeepsLegacyScale() {
        assertEquals(1.0, OrderGenerator.computeVolumeScale(config(null)), 1e-9);
    }

    @Test
    void testScaleHitsDailyTarget() {
        // midpoints: 1, 3.5, 5.5, 3 over 6h each -> expected 78/day
        double scale = OrderGenerator.computeVolumeScale(config(200));
        assertEquals(200.0 / 78.0, scale, 1e-9);

        double expected = 0;
        SimulationConfig.OrderGenerationConfig cfg = config(200);
        expected += 6 * mid(OrderGenerator.scaledRange(cfg.hourlyRates.night, scale));
        expected += 6 * mid(OrderGenerator.scaledRange(cfg.hourlyRates.morning, scale));
        expected += 6 * mid(OrderGenerator.scaledRange(cfg.hourlyRates.afternoon, scale));
        expected += 6 * mid(OrderGenerator.scaledRange(cfg.hourlyRates.evening, scale));
        assertEquals(200.0, expected, 6.0);
    }

    private static double mid(int[] r) {
        return (r[0] + r[1]) / 2.0;
    }

    @Test
    void testScaledRangeNeverInverts() {
        int[] r = OrderGenerator.scaledRange(new int[]{0, 2}, 0.01);
        assertTrue(r[1] >= r[0]);
        assertTrue(r[0] >= 0);
    }

    @Test
    void testEvenSpacing() {
        assertEquals(java.util.List.of(0, 40, 80, 120, 160),
                SimulationRunner.evenSpacing(5, 201));
        assertEquals(java.util.List.of(0), SimulationRunner.evenSpacing(1, 1000));
        assertTrue(SimulationRunner.evenSpacing(0, 100).isEmpty());
    }
}
