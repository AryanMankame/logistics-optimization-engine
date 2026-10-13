package com.aryan.Logistics.service;
import com.aryan.Logistics.analytics.DataCollector;
import com.aryan.Logistics.models.Location;
import com.aryan.Logistics.models.SplitOrder;
import com.aryan.Logistics.models.Vehicle;
import com.aryan.Logistics.service.VehicleService;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class OrderExecutionService {
    private VehicleService vehicleService;
    private WarehouseService warehouseService;
    private ClusterService clusterService;
    private OrderHandlerService orderHandlerService;
    private CityReplicaService cityReplicaService;
    private DataCollector dataCollector;
    private double deliveryFeePerKm;
    public OrderExecutionService(VehicleService vehicle_service,WarehouseService warehouse_service,ClusterService cluster_service,OrderHandlerService order_handler_service,CityReplicaService city_replica_service,DataCollector data_collector,double delivery_fee_per_km) {
        vehicleService = vehicle_service;
        warehouseService = warehouse_service;
        clusterService = cluster_service;
        orderHandlerService = order_handler_service;
        cityReplicaService = city_replica_service;
        dataCollector = data_collector;
        deliveryFeePerKm = delivery_fee_per_km;
    }
    public void executeOrders(int cluster_number) {
        // Real tour dispatch: load van, then drive greedy nearest-neighbor
        // warehouse -> closest house -> next closest ... -> warehouse (return).
        // Fuel/time use the full tour distance. Customer fee stays quoted on
        // the radial warehouse->house distance.
        try {
            Vehicle vehicle = vehicleService.getVehicle(cityReplicaService.currTime);
            if(vehicle == null) return ;
            String warehouseId = "Warehouse_" + cluster_number;
            Location warehouseLocation = warehouseService.getWarehouseLocation(cluster_number);
            if (warehouseLocation == null) {
                vehicleService.addVehicle(cityReplicaService.currTime, vehicle);
                return;
            }

            // Phase 1: load van (capacity check + stock subtract).
            List<SplitOrder> loaded = new ArrayList<>();
            Double itemsWeight = 0.0;
            while(itemsWeight <= vehicle.capacity) {
                SplitOrder peeked = clusterService.peekOrderFromCluster(cluster_number);
                if (peeked == null) break;
                if (itemsWeight + peeked.item.weight > vehicle.capacity) break;
                SplitOrder order = clusterService.getOrderFromCluster(cluster_number);
                if (order == null) break;
                if(!warehouseService.subtractItemFromWarehouse(cluster_number,order.item,order.quantity)) {
                    // Stock-out mid-load: nothing was booked yet, so put every
                    // popped order back and send the vehicle home empty.
                    clusterService.requeueOrderToCluster(cluster_number, order);
                    for (SplitOrder back : loaded) {
                        clusterService.requeueOrderToCluster(cluster_number, back);
                    }
                    vehicleService.addVehicle(cityReplicaService.currTime, vehicle);
                    return ;
                }
                itemsWeight += order.item.weight;
                loaded.add(order);
            }
            if (loaded.isEmpty()) {
                vehicleService.addVehicle(cityReplicaService.currTime, vehicle);
                return;
            }

            // Phase 2: greedy nearest-neighbor tour + return leg.
            List<SplitOrder> unvisited = new ArrayList<>(loaded);
            Location current = warehouseLocation;
            double tourDistance = 0.0;
            while (!unvisited.isEmpty()) {
                SplitOrder nearest = null;
                double nearestDist = Double.MAX_VALUE;
                for (SplitOrder candidate : unvisited) {
                    Double d = cityReplicaService.shortestDistanceBetweenTwoLocations(
                            current, candidate.orderedBy.location);
                    double dv = (d == null) ? Double.MAX_VALUE : d;
                    if (dv < nearestDist) {
                        nearestDist = dv;
                        nearest = candidate;
                    }
                }
                if (nearest == null) break; // all remaining unreachable
                tourDistance += (nearestDist == Double.MAX_VALUE) ? 0.0 : nearestDist;
                current = nearest.orderedBy.location;
                unvisited.remove(nearest);
            }
            Double returnLeg = cityReplicaService.shortestDistanceBetweenTwoLocations(
                    current, warehouseLocation);
            if (returnLeg != null) tourDistance += returnLeg;

            // Phase 3: book-keeping. Fee = radial quote, cost/time = tour.
            for (SplitOrder order : loaded) {
                Double radial = cityReplicaService.shortestDistanceBetweenTwoLocations(
                        warehouseLocation, order.orderedBy.location);
                double radialKm = (radial == null) ? 0.0 : radial;
                orderHandlerService.completedSplitOrder(order, cityReplicaService.currTime);
                dataCollector.updateSplitFulfillment(order.id, warehouseId, vehicle.id,
                        deliveryFeePerKm * radialKm);
            }
            double speed = (vehicle.averageSpeed == null || vehicle.averageSpeed <= 0)
                    ? 0.0 : vehicle.averageSpeed;
            double tourHours = (speed > 0) ? tourDistance / speed : 0.0;
            int additionalMillis =
                    (int) ((tourHours * 60 + loaded.size() * 5) * 60 * 1000);
            Date newTime = new Date(cityReplicaService.currTime.getTime() + additionalMillis);
            vehicle.outForDeliveryUntil = newTime;
            vehicle.totalDistanceCovered = (vehicle.totalDistanceCovered == null ? 0.0 : vehicle.totalDistanceCovered)
                    + tourDistance;
            if(!vehicleService.addVehicle(newTime,vehicle)) return ;
            dataCollector.insertDispatch(cityReplicaService.currTime, vehicle.id,
                    warehouseId, tourDistance, additionalMillis / 1000.0);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
