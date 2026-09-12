package com.aryan.Logistics.Utils;

import com.aryan.Logistics.models.Location;
import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public class DistancePair implements Comparable<DistancePair> {
    public double distance;
    public Location location;

    @Override
    public int compareTo(DistancePair other) {
        return Double.compare(this.distance, other.distance);
    }
}
