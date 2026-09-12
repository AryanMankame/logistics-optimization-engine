package com.aryan.Logistics.Utils;

import com.aryan.Logistics.models.Location;
import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public class TimePair {
    public Double time;
    public Location location;
}
