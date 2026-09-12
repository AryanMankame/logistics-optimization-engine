package com.aryan.Logistics.models;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Road {
    public String id;
    public Location from;
    public Location to;
    public Double distance;
    public Boolean open;
    public Double trafficMultiplier;
}
