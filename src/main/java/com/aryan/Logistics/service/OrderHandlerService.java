package com.aryan.Logistics.service;

import com.aryan.Logistics.models.Item;
import com.aryan.Logistics.models.Order;
import com.aryan.Logistics.models.SplitOrder;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;

public class OrderHandlerService {
    private Queue<Order> orders;
    private HashMap<Order,ArrayList<SplitOrder>> orderStatus;
    OrderHandlerService() {
        orders = new LinkedList<>();
        orderStatus = new HashMap<>();
    }
    private static ArrayList<SplitOrder> splitOrders(Order order) {
        Integer itr = 0;
        ArrayList<SplitOrder> split_orders = new ArrayList<>();
        for(Pair<Integer,Item> iter:order.itemsRequested) {
            Item it = iter.getRight();
            SplitOrder ord = new SplitOrder(order.id + itr.toString(),it,iter.getLeft(),order.deadline,order.orderCreated,order.orderDelivered,order.orderedBy);
            split_orders.add(ord);
            itr += 1;
        }
        return split_orders;
    }
    public boolean addOrder(Order order) {
        return orders.add(order);
    }
    public boolean processOrder() {
        try {
            Order order = orders.poll();
            ArrayList<SplitOrder> split_orders = splitOrders(order);
            // pass these split orders one by one to the cluster service which will add them to clusters
            for (SplitOrder split_order : split_orders) {
                continue;
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

}
