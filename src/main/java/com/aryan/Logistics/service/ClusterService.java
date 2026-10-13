package com.aryan.Logistics.service;

import org.apache.commons.lang3.tuple.Pair;
import com.aryan.Logistics.Utils.GeoUtils;
import com.aryan.Logistics.analytics.DataCollector;
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
    private DataCollector dataCollector;

    public ClusterService(int number_of_clusters,ArrayList<Location>locations, DataCollector data_collector) {
        orderClusters = new ArrayList<>();
        clusterCentroid = new ArrayList<>();
        geoUtils = new GeoUtils();
        dataCollector = data_collector;
        for(int i=0;i<number_of_clusters;i++) {
            PriorityQueue<Pair<Date,SplitOrder>> pq = new PriorityQueue<>();
            orderClusters.add(pq);
            clusterCentroid.add(locations.get(i));
            dataCollector.insertClusterMarker("cluster_" + i, locations.get(i));
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
            double radius = geoUtils.distance(newCentroid, order.orderedBy.location);
            dataCollector.insertCluster("cluster_" + closestCluster, order, newCentroid, radius);
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

    /**
     * Returns a popped order to its cluster queue (e.g. dispatch load aborted
     * on stock-out). Membership row and centroid are untouched: the order
     * never left logically, so unlike addOrderToCluster this writes nothing.
     */
    public void requeueOrderToCluster(int cluster_number, SplitOrder order) {
        orderClusters.get(cluster_number).add(Pair.of(order.deadline, order));
    }

    public void addCluster(Location location) {
        PriorityQueue<Pair<Date, SplitOrder>> pq = new PriorityQueue<>();
        orderClusters.add(pq);
        clusterCentroid.add(location);
        dataCollector.insertClusterMarker("cluster_" + (orderClusters.size() - 1), location);
    }

    public int size() {
        return orderClusters.size();
    }
}
