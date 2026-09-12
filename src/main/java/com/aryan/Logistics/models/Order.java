package com.aryan.Logistics.models;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;

@Data
@AllArgsConstructor
public class Order {
    public String id;
    public ArrayList<SplitOrder> splitOrders;
    public Float totalAmount;
    public Date deadline;
    public Date orderCreated;
    public Date orderDelivered;
    public Customer orderedBy;
}
