package com.aryan.Logistics.service;

import com.aryan.Logistics.models.Item;
import com.aryan.Logistics.models.Warehouse;

import java.util.ArrayList;

public class WarehouseService {
    private ArrayList<Warehouse> warehouses;
    WarehouseService() {
        warehouses = new ArrayList<>();
    }
    public boolean addWarehouse(Warehouse warehouse) {
        return warehouses.add(warehouse);
    }
    public Integer checkItemQuantity(int warehouse_id, Item item) {
        try{
            return warehouses.get(warehouse_id).getInventory().checkQuantity(item);
        }
        catch (Exception e) {
            System.out.println("ERROR => " + e.getMessage());
            return 0;
        }
    }
    public boolean subtractItemFromWarehouse(int warehouse_id, Item item,Integer qnt) {
        try {
            warehouses.get(warehouse_id).getInventory().subItem(item,qnt);
            return true;
        } catch (Exception e) {
            System.out.println("ERROR => " + e.getMessage());
            return false;
        }
    }
    public boolean addItemToWarehouse(int warehouse_id, Item item, Integer qnt) {
        try {
            warehouses.get(warehouse_id).getInventory().addItem(item,qnt);
            return true;
        } catch (Exception e) {
            System.out.println("ERROR => " + e.getMessage());
            return false;
        }
    }
}
