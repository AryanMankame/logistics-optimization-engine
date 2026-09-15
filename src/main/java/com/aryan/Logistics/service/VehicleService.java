package com.aryan.Logistics.service;

import com.aryan.Logistics.models.Vehicle;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Date;
import java.util.PriorityQueue;
import java.util.Queue;

public class VehicleService {
    private PriorityQueue<Pair<Date,Vehicle>>vehicles;
    VehicleService() {
        vehicles = new PriorityQueue<>();
    }
    public boolean addVehicle(Date curr,Vehicle vh) {
        return vehicles.add(Pair.of(curr,vh));
    }
    public Vehicle getVehicle() {
        if(!vehicles.isEmpty()) {
            Date nowTime = new Date();
            if(vehicles.peek().getRight().outForDeliveryUntil.compareTo(nowTime) <= 0) {
                return vehicles.poll().getRight();
            }
        }
        return null;
    }
}
