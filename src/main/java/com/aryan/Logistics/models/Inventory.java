package com.aryan.Logistics.models;

import java.util.HashMap;
import java.util.Map;


public class Inventory {
    private Map<Item,Integer>inventory = new HashMap<>();
    /**
     * Removes up to count units. Returns false (leaving stock untouched) when
     * fewer than count units are available, so a shortfall can never silently
     * deliver phantom stock.
     */
    public boolean subItem(Item item, Integer count) {
        Integer v = inventory.get(item);
        if (v == null || v < count) return false;
        inventory.put(item, v - count);
        return true;
    }
    public void removeItem(Item item) {
        inventory.remove(item);
    }
    public void addItem(Item item,Integer count) {
        inventory.putIfAbsent(item,0);
        inventory.computeIfPresent(item,(k,v) -> v+count);
    }
    public int checkQuantity(Item item) {
        if(inventory.containsKey(item)) {
            return inventory.get(item);
        }
        return 0;
    }

    public Map<Item, Integer> snapshot() {
        return new HashMap<>(inventory);
    }
}
