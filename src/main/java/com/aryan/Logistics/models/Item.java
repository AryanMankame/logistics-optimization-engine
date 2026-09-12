package com.aryan.Logistics.models;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Item {
    public String id;
    public String name;
    public Float cost;
}
