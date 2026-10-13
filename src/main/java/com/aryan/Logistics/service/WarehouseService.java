package com.aryan.Logistics.service;

import com.aryan.Logistics.models.Inventory;
import com.aryan.Logistics.models.Item;
import com.aryan.Logistics.models.Location;
import com.aryan.Logistics.models.Warehouse;

import java.util.ArrayList;

public class WarehouseService {
    private ArrayList<Warehouse> warehouses;
    public WarehouseService() {
        warehouses = new ArrayList<>();
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
            return warehouses.get(warehouse_id).getInventory().subItem(item,qnt);
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
    public Location getWarehouseLocation(int warehouse_id) {
        try {
            return warehouses.get(warehouse_id).location;
        } catch (Exception e) {
            System.out.println("ERROR ==> " + e.getMessage());
            return null;
        }
    }

    public void addWarehouse(int id,ArrayList<Item> items,Location location) {
        Inventory inventory = new Inventory();
        String warehouseId = "Warehouse_" + id;
        int amountEachItem = 1000000;
        for(Item it:items) {
            inventory.addItem(it,(int) (amountEachItem/ it.getCost()));
        }
        Warehouse wh = new Warehouse(warehouseId,location,inventory);
        warehouses.add(wh);
    }

    public ArrayList<Warehouse> getWarehouses() {
        return warehouses;
    }

}
