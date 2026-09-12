package com.aryan.Logistics.models;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Vehicle {
    public String id;
    public Location location;
    public Integer capacity;
    public Integer totalDistanceCovered;
    public Float averageSpeed;
}
