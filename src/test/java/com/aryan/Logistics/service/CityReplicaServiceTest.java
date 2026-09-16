package com.aryan.Logistics.service;

import com.aryan.Logistics.models.Item;
import com.aryan.Logistics.models.Location;
import com.aryan.Logistics.models.Road;
import com.aryan.Logistics.models.Warehouse;
import com.aryan.Logistics.Utils.GeoUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CityReplicaServiceTest {

    private CityReplicaService cityReplicaService;
    private WarehouseService warehouseService;
    private GeoUtils geoUtils;

    @BeforeEach
    void setUp() throws Exception {
        ArrayList<Pair<String, Pair<Double, Double>>> itemsList = new ArrayList<>();
        itemsList.add(Pair.of("Apple", Pair.of(1.5, 1.0)));
        itemsList.add(Pair.of("Banana", Pair.of(0.8, 0.5)));
        itemsList.add(Pair.of("Orange", Pair.of(2.0, 1.2)));

        ArrayList<Pair<Double, Double>> locationsList = new ArrayList<>();
        locationsList.add(Pair.of(12.9716, 77.5946)); // Bangalore
        locationsList.add(Pair.of(13.0827, 80.2707)); // Chennai
        locationsList.add(Pair.of(19.0760, 72.8777)); // Mumbai
        locationsList.add(Pair.of(28.7041, 77.1025)); // Delhi

        warehouseService = new WarehouseService();
        cityReplicaService = new CityReplicaService(5, 2, itemsList, locationsList, warehouseService);
        geoUtils = new GeoUtils();
    }

    private ArrayList<Pair<String, Pair<Double, Double>>> makeItemsList() {
        ArrayList<Pair<String, Pair<Double, Double>>> list = new ArrayList<>();
        list.add(Pair.of("Item", Pair.of(1.0, 1.0)));
        return list;
    }

    private ArrayList<Pair<Double, Double>> makeLocationsList(double... coords) {
        ArrayList<Pair<Double, Double>> list = new ArrayList<>();
        for (int i = 0; i < coords.length; i += 2) {
            list.add(Pair.of(coords[i], coords[i + 1]));
        }
        return list;
    }

    @Test
    void testConstructor_CreatesCorrectNumberOfItems() {
        assertEquals(3, getItems().size());
    }

    @Test
    void testConstructor_CreatesCorrectNumberOfWarehouses() {
        assertEquals(2, getWarehouses(warehouseService).size());
    }

    @Test
    void testConstructor_CreatesCorrectNumberOfLocations() {
        assertEquals(4, getLocations().size());
    }

    @Test
    void testConstructor_CreatesAtLeastChainRoads() {
        int n = getLocations().size();
        int minChainRoads = n - 1;
        int expectedMinRoads = minChainRoads + Math.max(0, 5 - minChainRoads);
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
        List<Warehouse> warehouses = getWarehouses(warehouseService);
        List<Location> locations = getLocations();
        for (int i = 0; i < warehouses.size(); i++) {
            assertEquals(locations.get(i), warehouses.get(i).getLocation(),
                    "Warehouse " + i + " should use input location " + i);
        }
    }

    @Test
    void testConstructor_WarehouseInventoryQuantityMatchesFormula() {
        List<Warehouse> warehouses = getWarehouses(warehouseService);
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
    void testConstructor_SingleLocation_ThrowsException() {
        ArrayList<Pair<String, Pair<Double, Double>>> itemsList = makeItemsList();
        ArrayList<Pair<Double, Double>> locationsList = makeLocationsList(12.9716, 77.5946);
        WarehouseService ws = new WarehouseService();

        assertThrows(IllegalArgumentException.class,
                () -> new CityReplicaService(1, 1, itemsList, locationsList, ws),
                "Constructor with single location should throw IllegalArgumentException");
    }

    @Test
    void testConstructor_TwoLocations_Completes() {
        ArrayList<Pair<String, Pair<Double, Double>>> itemsList = makeItemsList();
        ArrayList<Pair<Double, Double>> locationsList = makeLocationsList(12.9716, 77.5946, 13.0827, 80.2707);
        WarehouseService ws = new WarehouseService();

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                () -> new CityReplicaService(2, 1, itemsList, locationsList, ws),
                "Constructor with 2 locations should complete");
    }

    @Test
    void testConstructor_WarehouseCountExceedsLocations_ThrowsIndexOutOfBounds() {
        ArrayList<Pair<String, Pair<Double, Double>>> itemsList = makeItemsList();
        ArrayList<Pair<Double, Double>> locationsList = makeLocationsList(12.9716, 77.5946, 13.0827, 80.2707);
        WarehouseService ws = new WarehouseService();

        assertThrows(IndexOutOfBoundsException.class,
                () -> new CityReplicaService(3, 3, itemsList, locationsList, ws),
                "Constructor with warehouseCnt > locations.size() throws IndexOutOfBoundsException");
    }

    @SuppressWarnings("unchecked")
    private List<Item> getItems() {
        return getField(cityReplicaService, "items");
    }

    @SuppressWarnings("unchecked")
    private List<Warehouse> getWarehouses(WarehouseService ws) {
        return getField(ws, "warehouses");
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
