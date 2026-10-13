package com.aryan.Logistics.service;

import com.aryan.Logistics.analytics.DataCollector;
import com.aryan.Logistics.models.Item;
import com.aryan.Logistics.models.Order;
import com.aryan.Logistics.models.SplitOrder;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;

public class OrderHandlerService {
    private Queue<Order> orders;
    private HashMap<Order,ArrayList<SplitOrder>> orderStatus;
    private ClusterService clusterService;
    private DataCollector dataCollector;
    private int completedSplitOrderCount = 0;
    public OrderHandlerService(ClusterService cluster_service, DataCollector data_collector) {
        orders = new LinkedList<>();
        orderStatus = new HashMap<>();
        clusterService = cluster_service;
        dataCollector = data_collector;
    }
    private static ArrayList<SplitOrder> splitOrders(Order order) {
        Integer itr = 0;
        ArrayList<SplitOrder> split_orders = new ArrayList<>();
        for(Pair<Integer,Item> iter:order.itemsRequested) {
            Item it = iter.getRight();
            SplitOrder ord = new SplitOrder(order.id + itr.toString(),it,iter.getLeft(),order.deadline,order.orderCreated,order.orderDelivered,order.orderedBy,order);
            split_orders.add(ord);
            itr += 1;
        }
        return split_orders;
    }
    public boolean addOrder(Order order) {
        return orders.add(order);
    }
    public boolean processOrder() {
        if (orders.isEmpty()) {
            return false;
        }
        try {
            Order order = orders.poll();
            ArrayList<SplitOrder> split_orders = splitOrders(order);
            // split rows first so cluster membership rows can reference them
            for (SplitOrder split_order : split_orders) {
                dataCollector.insertSplitOrder(split_order);
            }
            // pass these split orders one by one to the cluster service which will add them to clusters
            for (SplitOrder split_order : split_orders) {
                clusterService.addOrderToCluster(split_order);
            }
            orderStatus.put(order, split_orders);
            return true;
        } catch (Exception e) {
            System.out.println("ERROR => " + e.getMessage());
            return false;
        }
    }

    public boolean isOrderCompleted(Order order) {
        return orderStatus.containsKey(order);
    }

    public boolean completedSplitOrder(SplitOrder split_order, Date deliveredAt) {
        if(orderStatus.containsKey(split_order.partOf)) {
            boolean removed = orderStatus.get(split_order.partOf).remove(split_order);
            if (removed) {
                completedSplitOrderCount++;
                split_order.orderDelivered = deliveredAt;
                dataCollector.updateSplitDelivered(split_order.id, deliveredAt);
                if (orderStatus.get(split_order.partOf).isEmpty()) {
                    split_order.partOf.orderDelivered = deliveredAt;
                    dataCollector.updateOrderDelivered(split_order.partOf.id, deliveredAt);
                }
            }
            return removed;
        }
        return false;
    }

    public int completedSplitOrdersTotal() {
        return completedSplitOrderCount;
    }

    public int pendingOrderCount() {
        return orders.size();
    }

}
