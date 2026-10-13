package com.aryan.Logistics.models;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Date;

@Data
@AllArgsConstructor
public class Vehicle implements Comparable<Vehicle> {
    public String id;
    public Location location;
    public Double capacity;
    public Double totalDistanceCovered;
    public Double averageSpeed;
    public Date outForDeliveryUntil;

    @Override
    public int compareTo(Vehicle other) {
        return this.capacity.compareTo(other.capacity);
    }
}
