package com.aryan.Logistics.service;

import ch.qos.logback.core.joran.sanity.Pair;
import com.aryan.Logistics.Utils.GeoUtils;
import com.aryan.Logistics.models.*;
import com.aryan.Logistics.Utils.DistancePair;
import jakarta.validation.constraints.Null;

import java.lang.reflect.Array;
import java.util.*;

public class CityReplicaService {
    // from .properties read:
    // items -> create all the given items
    // warehouse -> create n warehouse objects with different locations -> initialize its inventory -> have inventory to include randomly at least 70% of all items in its inventory.
    // vehicle -> create n vehicles
    // Roads -> create N roads
    private Integer warehouseCnt;
    private ArrayList<Item> items = new ArrayList<Item>();
    private ArrayList<Warehouse> warehouses = new ArrayList<Warehouse>();
    private ArrayList<Location> locations = new ArrayList<Location>();
    private static GeoUtils geoUtils = new GeoUtils();
    private ArrayList<Road> roads = new ArrayList<Road>();
    private Map<Location,ArrayList<Road>> cityGrid = new HashMap<Location, ArrayList<Road>>();
    private Map<Location,Boolean> visited;
    private PriorityQueue<DistancePair> pq;
    private Map<Location,Double> dist;
    CityReplicaService(int roads_cnt,int warehouse_cnt, ArrayList<Pair<String,Double>> itm, ArrayList<Pair<Double,Double>> locs) {
        warehouseCnt = warehouse_cnt;
        int iter = 0;
        // create an item
        for(Pair<String,Double> pair: itm) {
            String item_id = "Item_" + iter;
            Item it = new Item(item_id,pair.first,pair.second);
            items.add(it);
        }
        // creating all locations
        for(Pair<Double,Double> l: locs) {
            Location lc = new Location(l.first,l.second);
            locations.add(lc);
        }
        // creating warehouses
        for(int i=0;i<warehouseCnt;i++) {
            Inventory inventory = new Inventory();
            String warehouseId = "Warehouse_" + i;
            int amountEachItem = 1000000;
            for(Item it:items) {
                inventory.addItem(it,(int) (amountEachItem/ it.getCost()));
            }
            Warehouse wh = new Warehouse(warehouseId,locations.get(i),inventory);
            warehouses.add(wh);
        }
        // building roads -> creating a city grid.
        // first create at least loc-1 roads
        int i=0;
        Set<Road> uniqueRoads = new HashSet<Road>();
        for(;i<locs.size()-1;i++) {
            String roadId = "road_" + i;
            Location l1 = locations.get(i);
            Location l2 = locations.get(i+1);
            Double distance = geoUtils.distance(l1,l2);
            Road road_from_to = new Road(roadId,l1,l2,distance,true,1.0);
            Road road_to_from = new Road(roadId,l2,l1,distance,true,1.0);
            if (!cityGrid.containsKey(l1)) {
                cityGrid.put(l1, new ArrayList<Road>());
            }
            if(!cityGrid.containsKey(l2)) {
                cityGrid.put(l2, new ArrayList<Road>());
            }
            cityGrid.get(l1).add(road_from_to);
            cityGrid.get(l2).add(road_to_from);
            roads.add(road_from_to);
            uniqueRoads.add(road_from_to);
        }
        while(uniqueRoads.size() < roads_cnt) {
            String roadId = "road_" + i;
            Random random = new Random();
            int size = locs.size();
            if (size < 2) {
                throw new IllegalArgumentException("Need at least 2 locations");
            }
            int i1 = random.nextInt(size);
            int i2 = random.nextInt(size - 1);
            if (i2 >= i1) {
                i2++;  // shift to avoid i1
            }
            Location l1 = locations.get(i1);
            Location l2 = locations.get(i2);
            Double distance = geoUtils.distance(l1, l2);
            Road road_from_to = new Road(roadId, l1, l2, distance, true, 1.0);
            Road road_to_from = new Road(roadId, l2, l1, distance, true, 1.0);
            if (!cityGrid.containsKey(l1)) {
                cityGrid.put(l1, new ArrayList<Road>());
            }
            if(!cityGrid.containsKey(l2)) {
                cityGrid.put(l2, new ArrayList<Road>());
            }
            cityGrid.get(l1).add(road_from_to);
            cityGrid.get(l2).add(road_to_from);
            roads.add(road_from_to);
            uniqueRoads.add(road_from_to);
        }
    }
    public Double shortestDistanceBetweenTwoLocations(Location start,Location end) {
        visited = new HashMap<Location,Boolean>();
        pq = new PriorityQueue<DistancePair>();
        dist = new HashMap<Location,Double>();
        dist.put(start,0.0);
        pq.add(new DistancePair(0.0,start));
        while(!pq.isEmpty()) {
            DistancePair top = pq.poll();
            visited.put(top.location,true);
            for(Road rd:cityGrid.get(top.location)) {
                if(rd.open) {
                    if(!visited.containsKey(rd.to)) {
                        if(!dist.containsKey(rd.to)) {
                            dist.put(rd.to,Double.MAX_VALUE);
                        }
                        Double dt = rd.distance + dist.get(rd.from);
                        if(dist.get(rd.to) > dt) {
                            dist.put(rd.to,dt);
                            pq.add(new DistancePair(dt,rd.to));
                        }
                    }
                }
            }
        }
        return dist.get(end);
    }
}
