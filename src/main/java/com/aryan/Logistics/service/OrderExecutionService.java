package com.aryan.Logistics.service;
import com.aryan.Logistics.models.SplitOrder;
import com.aryan.Logistics.models.Vehicle;
import com.aryan.Logistics.service.VehicleService;
public class OrderExecutionService {
    private VehicleService vehicleService;
    private WarehouseService warehouseService;
    private ClusterService clusterService;
    private OrderHandlerService orderHandlerService;
    OrderExecutionService(VehicleService vehicle_service,WarehouseService warehouse_service,ClusterService cluster_service,OrderHandlerService order_handler_service) {
        vehicleService = vehicle_service;
        warehouseService = warehouse_service;
        clusterService = cluster_service;
        orderHandlerService = order_handler_service;
    }
    public void executeOrders(int cluster_number) {
        // get vehicle from vehicleService
        // iterate over given cluster -> get order
        // fill the vehicle and push back to vehicle service with time needed to delivery set
        try {
            Vehicle vehicle = vehicleService.getVehicle();
            if(vehicle == null) return ;
            Double itemsWeight = 0.0;
            while(itemsWeight <= vehicle.capacity) {
                SplitOrder order = clusterService.getOrderFromCluster(cluster_number);
                if (order == null) break;
                itemsWeight += order.item.weight;
                Double timeTaken =
                if(itemsWeight <= vehicle.capacity) {

                }
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
