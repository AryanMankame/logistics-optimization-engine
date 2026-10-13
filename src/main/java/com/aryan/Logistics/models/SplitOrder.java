package com.aryan.Logistics.models;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Date;

@Data
@AllArgsConstructor
public class SplitOrder implements Comparable<SplitOrder> {
    public String id;
    public Item item;
    public Integer quantity;
    public Date deadline;
    public Date orderCreated;
    public Date orderDelivered;
    public Customer orderedBy;
    public Order partOf;

    @Override
    public int compareTo(SplitOrder other) {
        return this.deadline.compareTo(other.deadline);
    }
}
