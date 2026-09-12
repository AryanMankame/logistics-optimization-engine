package com.aryan.Logistics.models;
import com.aryan.Logistics.models.Location;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Warehouse {
    public String id;
    public Location location;
    public Inventory inventory;
}
