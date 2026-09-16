package com.aryan.Logistics.service;

import org.apache.commons.lang3.tuple.Pair;
import com.aryan.Logistics.Utils.GeoUtils;
import com.aryan.Logistics.models.Location;
import com.aryan.Logistics.models.Order;
import com.aryan.Logistics.models.SplitOrder;


import java.util.ArrayList;
import java.util.Date;
import java.util.PriorityQueue;

public class ClusterService {
    private ArrayList<PriorityQueue<Pair<Date, SplitOrder>>> orderClusters;
    private ArrayList<Location> clusterCentroid;
    private GeoUtils geoUtils;

    ClusterService(int number_of_clusters,ArrayList<Location>locations) {
        orderClusters = new ArrayList<>();
        clusterCentroid = new ArrayList<>();
        for(int i=0;i<number_of_clusters;i++) {
            PriorityQueue<Pair<Date,SplitOrder>> pq = new PriorityQueue<>();
            orderClusters.add(pq);
            clusterCentroid.add(locations.get(i));
        }
    }

    public SplitOrder peekOrderFromCluster(int cluster_number) {
        if (orderClusters.get(cluster_number) != null && !orderClusters.get(cluster_number).isEmpty()) {
            return orderClusters.get(cluster_number).peek().getRight();
        }
        return null;
    }

    public boolean addOrderToCluster(SplitOrder order) {
        try{
            int closestCluster = 0;
            Double distanceToClosestCluster = Double.MAX_VALUE;
            for(int i=0;i<clusterCentroid.size();i++) {
                Double temp = geoUtils.distance(order.orderedBy.location,clusterCentroid.get(i));
                if(temp < distanceToClosestCluster) {
                    distanceToClosestCluster = temp;
                    closestCluster = i;
                }
            }
            orderClusters.get(closestCluster).add(Pair.of(order.deadline,order));
            Location prevCentroid = clusterCentroid.get(closestCluster);
            Double newLatitude = (prevCentroid.latitude + order.orderedBy.location.latitude) / 2;
            Double newLongitude = (prevCentroid.longitude + order.orderedBy.location.longitude) / 2;
            Location newCentroid = new Location(newLatitude,newLongitude);
            clusterCentroid.set(closestCluster,newCentroid);
            return true;
        } catch (Exception e) {
            System.out.println("ERROR => " + e.getMessage());
            return false;
        }
    }

    public SplitOrder getOrderFromCluster(int cluster_number) {
        if(orderClusters.get(cluster_number) != null) {
            return orderClusters.get(cluster_number).poll().getRight();
        }
        return null;
    }

    public void addClutser(Location location) {
        PriorityQueue<Pair<Date,SplitOrder>> pq = new PriorityQueue<>();
        orderClusters.add(pq);
        clusterCentroid.add(location);
    }
}
