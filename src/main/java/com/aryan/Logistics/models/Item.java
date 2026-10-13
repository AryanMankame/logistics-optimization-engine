package com.aryan.Logistics.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@AllArgsConstructor
public class Item {
    public String id;
    public String name;
    public Double cost;
    public Double weight;
    /**
     * Customer-facing selling price. Excluded from equality so that item
     * copies built independently (SimulationRunner vs CityReplicaService)
     * still match as inventory keys.
     */
    @EqualsAndHashCode.Exclude
    public Double price;

    /** Backward-compatible constructor: zero-margin fallback (price = cost). */
    public Item(String id, String name, Double cost, Double weight) {
        this(id, name, cost, weight, cost);
    }

    /** Unit margin in currency units (never null when price is set). */
    public double margin() {
        return (price == null ? cost : price) - cost;
    }
}
