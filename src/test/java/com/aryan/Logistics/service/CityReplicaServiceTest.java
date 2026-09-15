package com.aryan.Logistics.service;

import com.aryan.Logistics.models.Item;
import com.aryan.Logistics.models.Location;
import com.aryan.Logistics.models.Road;
import com.aryan.Logistics.models.Warehouse;
import com.aryan.Logistics.Utils.GeoUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CityReplicaServiceTest {

    private CityReplicaService cityReplicaService;
    private GeoUtils geoUtils;
    private Class<?> pairClass;
    private Constructor<?> pairConstructor;

    @BeforeEach
    void setUp() throws Exception {
        pairClass = Class.forName("ch.qos.logback.core.joran.sanity.Pair");
        pairConstructor = pairClass.getDeclaredConstructor(Object.class, Object.class);
        pairConstructor.setAccessible(true);

        ArrayList itemsList = new ArrayList();
        itemsList.add(pairConstructor.newInstance("Apple", 1.5f));
        itemsList.add(pairConstructor.newInstance("Banana", 0.8f));
        itemsList.add(pairConstructor.newInstance("Orange", 2.0f));

        ArrayList locationsList = new ArrayList();
        locationsList.add(pairConstructor.newInstance(12.9716f, 77.5946f)); // Bangalore
        locationsList.add(pairConstructor.newInstance(13.0827f, 80.2707f)); // Chennai
        locationsList.add(pairConstructor.newInstance(19.0760f, 72.8777f)); // Mumbai
        locationsList.add(pairConstructor.newInstance(28.7041f, 77.1025f)); // Delhi

        cityReplicaService = new CityReplicaService(5, 2, itemsList, locationsList);
        geoUtils = new GeoUtils();
    }

    private ArrayList makeItemsList() throws Exception {
        ArrayList list = new ArrayList();
        list.add(pairConstructor.newInstance("Item", 1.0f));
        return list;
    }

    private ArrayList makeLocationsList(float... coords) throws Exception {
        ArrayList list = new ArrayList();
        for (int i = 0; i < coords.length; i += 2) {
            list.add(pairConstructor.newInstance(coords[i], coords[i + 1]));
        }
        return list;
    }

    @Test
    void testConstructor_CreatesCorrectNumberOfItems() {
        assertEquals(3, getItems().size());
    }

    @Test
    void testConstructor_CreatesCorrectNumberOfWarehouses() {
        assertEquals(2, getWarehouses().size());
    }

    @Test
    void testConstructor_CreatesCorrectNumberOfLocations() {
        assertEquals(4, getLocations().size());
    }

    @Test
    void testConstructor_CreatesAtLeastChainRoads() {
        // Chain creates (n-1) forward roads; random adds (roads_cnt - (n-1)) more forward roads
        int n = getLocations().size();
        int minChainRoads = n - 1;
        int expectedMinRoads = minChainRoads + Math.max(0, 5 - minChainRoads); // roads_cnt=5
        assertTrue(getRoads().size() >= expectedMinRoads,
                "Should have at least chain roads + random roads: " + expectedMinRoads);
    }

    @Test
    void testConstructor_NoSelfLoopRoads() {
        for (Road road : getRoads()) {
            assertNotEquals(road.getFrom(), road.getTo(), "Road should not connect a location to itself");
        }
    }

    @Test
    void testConstructor_RoadDistanceMatchesGeoUtils() {
        GeoUtils geoUtils = new GeoUtils();
        for (Road road : getRoads()) {
            double expected = geoUtils.distance(road.getFrom(), road.getTo());
            assertEquals(expected, road.getDistance(), 1e-9,
                    "Road distance should match GeoUtils calculation");
        }
    }

    @Test
    void testConstructor_EveryRoadHasReverseInCityGrid() {
        // Reverse roads exist in cityGrid, not necessarily in roads list
        Map<Location, List<Road>> cityGrid = getCityGrid();
        for (Road road : getRoads()) {
            Location from = road.getFrom();
            Location to = road.getTo();
            List<Road> reverseRoads = cityGrid.get(to);
            assertNotNull(reverseRoads, "Reverse location should exist in cityGrid");
            boolean hasReverse = reverseRoads.stream().anyMatch(r ->
                    r.getFrom().equals(to) &&
                    r.getTo().equals(from) &&
                    r.getDistance().equals(road.getDistance()));
            assertTrue(hasReverse, "Every road should have a reverse counterpart in cityGrid");
        }
    }

    @Test
    void testConstructor_WarehousesUseDistinctLocationsFromInput() {
        List<Warehouse> warehouses = getWarehouses();
        List<Location> locations = getLocations();
        for (int i = 0; i < warehouses.size(); i++) {
            assertEquals(locations.get(i), warehouses.get(i).getLocation(),
                    "Warehouse " + i + " should use input location " + i);
        }
    }

    @Test
    void testConstructor_WarehouseInventoryQuantityMatchesFormula() {
        List<Warehouse> warehouses = getWarehouses();
        List<Item> items = getItems();
        for (Warehouse warehouse : warehouses) {
            for (Item item : items) {
                int expectedQty = (int) (1_000_000 / item.getCost());
                int actualQty = warehouse.getInventory().checkQuantity(item);
                assertEquals(expectedQty, actualQty,
                        "Inventory quantity for " + item.getName() + " should be 1_000_000 / cost");
            }
        }
    }

    @Test
    void testConstructor_CityGridContainsAllRoadEndpoints() {
        Map<Location, List<Road>> cityGrid = getCityGrid();
        for (Road road : getRoads()) {
            assertTrue(cityGrid.containsKey(road.getFrom()), "cityGrid should contain road's from location");
            assertTrue(cityGrid.containsKey(road.getTo()), "cityGrid should contain road's to location");
            assertTrue(cityGrid.get(road.getFrom()).contains(road),
                    "road's from location should reference this road");
        }
    }

    @Test
    void testShortestDistance_SameLocationReturnsZero() {
        Location loc = getLocations().get(0);
        Double distance = cityReplicaService.shortestDistanceBetweenTwoLocations(loc, loc);
        assertEquals(0.0, distance);
    }

    @Test
    void testShortestDistance_AdjacentChainPairEqualsRoadDistance() {
        Location loc0 = getLocations().get(0);
        Location loc1 = getLocations().get(1);
        double expected = geoUtils.distance(loc0, loc1);
        Double actual = cityReplicaService.shortestDistanceBetweenTwoLocations(loc0, loc1);
        assertNotNull(actual);
        assertEquals(expected, actual, 1e-9);
    }

    @Test
    void testShortestDistance_TriangleInequality() {
        Location loc0 = getLocations().get(0);
        Location loc1 = getLocations().get(1);
        Location loc2 = getLocations().get(2);

        Double d01 = cityReplicaService.shortestDistanceBetweenTwoLocations(loc0, loc1);
        Double d12 = cityReplicaService.shortestDistanceBetweenTwoLocations(loc1, loc2);
        Double d02 = cityReplicaService.shortestDistanceBetweenTwoLocations(loc0, loc2);

        assertNotNull(d01);
        assertNotNull(d12);
        assertNotNull(d02);
        assertTrue(d02 <= d01 + d12 + 1e-9, "Triangle inequality violated");
    }

    @Test
    void testShortestDistance_Symmetry() {
        Location loc0 = getLocations().get(0);
        Location loc1 = getLocations().get(1);
        Double d01 = cityReplicaService.shortestDistanceBetweenTwoLocations(loc0, loc1);
        Double d10 = cityReplicaService.shortestDistanceBetweenTwoLocations(loc1, loc0);
        assertEquals(d01, d10);
    }

    @Test
    void testShortestDistance_UnreachableReturnsNull() {
        Location isolatedLoc = new Location(0.0, 0.0);
        Location loc0 = getLocations().get(0);
        Double distance = cityReplicaService.shortestDistanceBetweenTwoLocations(loc0, isolatedLoc);
        assertNull(distance);
    }

    @Test
    void testShortestDistance_StateResetBetweenCalls() {
        Location loc0 = getLocations().get(0);
        Location loc1 = getLocations().get(1);
        Double d1 = cityReplicaService.shortestDistanceBetweenTwoLocations(loc0, loc1);
        Double d2 = cityReplicaService.shortestDistanceBetweenTwoLocations(loc0, loc1);
        assertNotNull(d1);
        assertNotNull(d2);
        assertEquals(d1, d2, "Consecutive calls should return identical results");
    }

    @Test
    void testConstructor_SingleLocation_ThrowsException() throws Exception {
        ArrayList itemsList = makeItemsList();
        ArrayList locationsList = makeLocationsList(12.9716f, 77.5946f);

        assertThrows(IllegalArgumentException.class,
                () -> new CityReplicaService(1, 1, itemsList, locationsList),
                "Constructor with single location should throw IllegalArgumentException");
    }

    @Test
    void testConstructor_TwoLocations_Completes() throws Exception {
        ArrayList itemsList = makeItemsList();
        ArrayList locationsList = makeLocationsList(12.9716f, 77.5946f, 13.0827f, 80.2707f);

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                () -> new CityReplicaService(2, 1, itemsList, locationsList),
                "Constructor with 2 locations should complete");
    }

    @Test
    void testConstructor_WarehouseCountExceedsLocations_ThrowsIndexOutOfBounds() throws Exception {
        ArrayList itemsList = makeItemsList();
        ArrayList locationsList = makeLocationsList(12.9716f, 77.5946f, 13.0827f, 80.2707f);

        assertThrows(IndexOutOfBoundsException.class,
                () -> new CityReplicaService(3, 3, itemsList, locationsList),
                "Constructor with warehouseCnt > locations.size() throws IndexOutOfBoundsException");
    }

    @SuppressWarnings("unchecked")
    private List<Item> getItems() {
        return getField(cityReplicaService, "items");
    }

    @SuppressWarnings("unchecked")
    private List<Warehouse> getWarehouses() {
        return getField(cityReplicaService, "warehouses");
    }

    @SuppressWarnings("unchecked")
    private List<Location> getLocations() {
        return getField(cityReplicaService, "locations");
    }

    @SuppressWarnings("unchecked")
    private List<Road> getRoads() {
        return getField(cityReplicaService, "roads");
    }

    @SuppressWarnings("unchecked")
    private Map<Location, List<Road>> getCityGrid() {
        return getField(cityReplicaService, "cityGrid");
    }

    private <T> T getField(Object object, String fieldName) {
        try {
            Field field = object.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return (T) field.get(object);
        } catch (Exception e) {
            throw new RuntimeException("Failed to access field: " + fieldName, e);
        }
    }
}