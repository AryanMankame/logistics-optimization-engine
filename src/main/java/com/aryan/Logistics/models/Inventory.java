package com.aryan.Logistics.models;

import java.util.HashMap;
import java.util.Map;


public class Inventory {
    public Map<Item,Integer>inventory = new HashMap<>();
    public void subItem(Item item, Integer count) {
        inventory.computeIfPresent(item,(k,v) -> v >= count ? v-count : null);
    }
    public void removeItem(Item item) {
        inventory.remove(item);
    }
    public void addItem(Item item,Integer count) {
        inventory.putIfAbsent(item,0);
        inventory.computeIfPresent(item,(k,v) -> v+count);
    }
}
