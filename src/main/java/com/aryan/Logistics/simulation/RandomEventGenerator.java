package com.aryan.Logistics.simulation;

import com.aryan.Logistics.Utils.GeoUtils;
import com.aryan.Logistics.analytics.DataCollector;
import com.aryan.Logistics.models.*;
import com.aryan.Logistics.service.*;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;

public class RandomEventGenerator {
    private final SimulationConfig.RandomEventsConfig eventsConfig;
    private final CityReplicaService cityReplicaService;
    private final WarehouseService warehouseService;
    private final VehicleService vehicleService;
    private final List<Item> items;
    private final List<Location> warehouseLocations;
    private final Random random;
    private final DataCollector dataCollector;
    private final ClusterService clusterService;
    private final GeoUtils geoUtils = new GeoUtils();
    private int extraVehicleCounter = 0;

    public RandomEventGenerator(SimulationConfig.RandomEventsConfig eventsConfig,
                                CityReplicaService cityReplicaService,
                                WarehouseService warehouseService,
                                VehicleService vehicleService,
                                List<Item> items,
                                List<Location> warehouseLocations,
                                Random random,
                                DataCollector dataCollector,
                                ClusterService clusterService) {
        this.eventsConfig = eventsConfig;
        this.cityReplicaService = cityReplicaService;
        this.warehouseService = warehouseService;
        this.vehicleService = vehicleService;
        this.items = items;
        this.warehouseLocations = warehouseLocations;
        this.random = random;
        this.dataCollector = dataCollector;
        this.clusterService = clusterService;
    }

    public List<String> triggerEvents(Date currentTime) {
        List<String> events = new ArrayList<>();

        if (random.nextDouble() < eventsConfig.refillProbability) {
            refillWarehouse();
            events.add("WAREHOUSE_REFILL");
        }

        if (random.nextDouble() < eventsConfig.addWarehouseProbability) {
            addWarehouse(currentTime);
            events.add("NEW_WAREHOUSE");
        }

        if (random.nextDouble() < eventsConfig.addVehicleProbability) {
            addVehicle(currentTime);
            events.add("NEW_VEHICLE");
        }

        if (random.nextDouble() < eventsConfig.roadClosureProbability) {
            closeRoad();
            events.add("ROAD_CLOSED");
        }

        if (random.nextDouble() < eventsConfig.roadReopenProbability) {
            reopenRoad();
            events.add("ROAD_REOPENED");
        }

        if (random.nextDouble() < eventsConfig.trafficSpikeProbability) {
            trafficSpike();
            events.add("TRAFFIC_SPIKE");
        }

        if (random.nextDouble() < eventsConfig.trafficClearProbability) {
            trafficClear();
            events.add("TRAFFIC_CLEARED");
        }

        return events;
    }

    private void refillWarehouse() {
        ArrayList<Road> roads = cityReplicaService.getRoads();
        if (roads.isEmpty()) return;
        int idx = random.nextInt(warehouseLocations.size());
        for (Item item : items) {
            warehouseService.addItemToWarehouse(idx, item, 500);
        }
    }

    private void addWarehouse(Date currentTime) {
        ArrayList<Location> allLocations = cityReplicaService.getLocations();
        Location newLoc = allLocations.get(random.nextInt(allLocations.size()));
        warehouseLocations.add(newLoc);
        int whId = warehouseLocations.size() - 1;
        warehouseService.addWarehouse(whId, new ArrayList<>(items), newLoc);
        dataCollector.writeWarehouse(warehouseService.getWarehouses().get(warehouseService.getWarehouses().size() - 1));
        clusterService.addCluster(newLoc);
        cityReplicaService.getLocations().add(newLoc);
    }

    private void addVehicle(Date currentTime) {
        ArrayList<Location> allLocations = cityReplicaService.getLocations();
        Location startLoc = allLocations.get(random.nextInt(allLocations.size()));
        double capacity = 100 + random.nextInt(200);
        double speed = 30 + random.nextInt(50);
        Vehicle v = new Vehicle("Vehicle_extra_" + extraVehicleCounter++, startLoc, capacity, 0.0, speed, currentTime);
        vehicleService.addVehicle(currentTime, v);
        dataCollector.writeVehicle(v);
    }

    private void closeRoad() {
        ArrayList<Road> roads = cityReplicaService.getRoads();
        List<Road> openRoads = roads.stream().filter(r -> r.open).toList();
        if (openRoads.isEmpty()) return;
        Road road = openRoads.get(random.nextInt(openRoads.size()));
        road.open = false;
    }

    private void reopenRoad() {
        ArrayList<Road> roads = cityReplicaService.getRoads();
        List<Road> closedRoads = roads.stream().filter(r -> !r.open).toList();
        if (closedRoads.isEmpty()) return;
        Road road = closedRoads.get(random.nextInt(closedRoads.size()));
        road.open = true;
    }

    private void trafficSpike() {
        ArrayList<Road> roads = cityReplicaService.getRoads();
        if (roads.isEmpty()) return;
        Road road = roads.get(random.nextInt(roads.size()));
        road.trafficMultiplier = 2.0 + random.nextDouble() * 3.0;
    }

    private void trafficClear() {
        ArrayList<Road> roads = cityReplicaService.getRoads();
        List<Road> congested = roads.stream().filter(r -> r.trafficMultiplier > 1.0).toList();
        if (congested.isEmpty()) return;
        Road road = congested.get(random.nextInt(congested.size()));
        road.trafficMultiplier = 1.0;
    }
}
