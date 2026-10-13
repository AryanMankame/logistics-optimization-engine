package com.aryan.Logistics.scenario;

import com.aryan.Logistics.simulation.SimulationConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioServiceTest {

    @Test
    void testApplyOverridesOnlyNonNull() {
        SimulationConfig config = new SimulationConfig();
        config.simulation = new SimulationConfig.SimulationParams();
        config.simulation.startDate = "2025-09-15";
        config.simulation.endDate = "2025-10-15";
        config.economics = new SimulationConfig.EconomicsConfig();
        config.orderGeneration = new SimulationConfig.OrderGenerationConfig();

        ScenarioService.ScenarioRequest req = new ScenarioService.ScenarioRequest();
        req.warehouseCount = 8;
        req.deliveryFeePerKm = 15.0;
        ScenarioService.applyOverrides(config, req);

        assertEquals(8, config.warehouseCount);
        assertEquals(15.0, config.economics.deliveryFeePerKm, 1e-9);
        assertNull(config.vehicleCount);
        assertEquals(5000.0, config.economics.warehouseMaintenancePerDay, 1e-9);
    }

    @Test
    void testValidationRejectsBadInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> ScenarioService.validate(reqWith(r -> r.warehouseCount = 0)));
        assertThrows(IllegalArgumentException.class,
                () -> ScenarioService.validate(reqWith(r -> r.warehouseCount = 10001)));
        assertThrows(IllegalArgumentException.class,
                () -> ScenarioService.validate(reqWith(r -> r.startDate = "2025-09-15")));
        assertThrows(IllegalArgumentException.class, () -> ScenarioService.validate(reqWith(r -> {
            r.startDate = "2025-10-15";
            r.endDate = "2025-09-15";
        })));
        assertThrows(IllegalArgumentException.class,
                () -> ScenarioService.validate(reqWith(r -> r.targetReturnOnCost = -0.1)));
    }

    private static ScenarioService.ScenarioRequest reqWith(
            java.util.function.Consumer<ScenarioService.ScenarioRequest> init) {
        ScenarioService.ScenarioRequest req = new ScenarioService.ScenarioRequest();
        init.accept(req);
        return req;
    }

    @Test
    void testValidationAcceptsSaneScenario() {
        ScenarioService.ScenarioRequest req = new ScenarioService.ScenarioRequest();
        req.warehouseCount = 8;
        req.vehicleCount = 20;
        req.customerCount = 100;
        req.maintenancePerDay = 4000.0;
        req.targetOrdersPerDay = 200;
        req.targetReturnOnCost = 0.12;
        req.deliveryFeePerKm = 10.0;
        req.startDate = "2025-09-15";
        req.endDate = "2025-09-18";
        ScenarioService.validate(req);
    }
}
