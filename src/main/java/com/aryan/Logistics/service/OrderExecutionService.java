package com.aryan.Logistics.service;
import com.aryan.Logistics.models.Location;
import com.aryan.Logistics.models.SplitOrder;
import com.aryan.Logistics.models.Vehicle;
import com.aryan.Logistics.service.VehicleService;

import java.util.Date;

public class OrderExecutionService {
    private VehicleService vehicleService;
    private WarehouseService warehouseService;
    private ClusterService clusterService;
    private OrderHandlerService orderHandlerService;
    private CityReplicaService cityReplicaService;
    OrderExecutionService(VehicleService vehicle_service,WarehouseService warehouse_service,ClusterService cluster_service,OrderHandlerService order_handler_service,CityReplicaService city_replica_service) {
        vehicleService = vehicle_service;
        warehouseService = warehouse_service;
        clusterService = cluster_service;
        orderHandlerService = order_handler_service;
        cityReplicaService = city_replica_service;
    }
    public void executeOrders(int cluster_number) {
        // get vehicle from vehicleService
        // iterate over given cluster -> get order
        // fill the vehicle and push back to vehicle service with time needed to delivery set
        try {
            Vehicle vehicle = vehicleService.getVehicle();
            if(vehicle == null) return ;
            Double itemsWeight = 0.0;
            Double mxTime = Double.MAX_VALUE;
            int ordersAdded = 0;
            while(itemsWeight <= vehicle.capacity) {
                SplitOrder order = clusterService.getOrderFromCluster(cluster_number);
                if (order == null) break;
                itemsWeight += order.item.weight;
                Location warehouseLocation = warehouseService.getWarehouseLocation(cluster_number);
                if(itemsWeight <= vehicle.capacity) {
                    ordersAdded += 1;
                    Double timeTaken = (cityReplicaService.shortestDistanceBetweenTwoLocations(warehouseLocation,order.orderedBy.location)) / vehicle.averageSpeed;
                    mxTime = Math.max(mxTime,timeTaken);
                    if(!warehouseService.subtractItemFromWarehouse(cluster_number,order.item,order.quantity)) return ;
                    orderHandlerService.completedSplitOrder(order);
                }
            }
            int additionalMillis =
                    (int) ((mxTime * 60 + ordersAdded * 300) * 60 * 1000);
            Date newTime = new Date(cityReplicaService.currTime.getTime() + additionalMillis);
            vehicle.outForDeliveryUntil = newTime;
            if(!vehicleService.addVehicle(newTime,vehicle)) return ;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
