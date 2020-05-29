package com.example.demomap;

import com.vaadin.annotations.Theme;
import com.vaadin.server.VaadinRequest;
import com.vaadin.shared.communication.PushMode;
import com.vaadin.ui.UI;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vaadin.addon.leaflet.LCircleMarker;
import org.vaadin.addon.leaflet.LMap;
import org.vaadin.addon.leaflet.LMarker;
import org.vaadin.addon.leaflet.LOpenStreetMapLayer;
import org.vaadin.addon.leaflet.shared.Bounds;
import org.vaadin.addon.leaflet.shared.Point;

import java.util.Map;
import java.util.concurrent.*;

@Theme("DemoMap")
public class DemoMapUI extends UI implements Broadcaster.BroadcastListener {
    private static final @NotNull Logger log = LoggerFactory.getLogger(DemoMapUI.class);

    private LMap map;

    private ExecutorService executor;

    private Map<String, LCircleMarker> markerMap;

    private Map<String, Point> markerPoints;

    @Override
    protected void init(VaadinRequest request) {

        markerPoints = new ConcurrentHashMap<>();
        Broadcaster.register(this);
        final Bounds bounds = new Bounds();
        final double swLon = Double.parseDouble(System.getenv("BOUND_SW_LON"));
        final double swLat = Double.parseDouble(System.getenv("BOUND_SW_LAT"));
        final double neLon = Double.parseDouble(System.getenv("BOUND_NE_LON"));
        final double neLat = Double.parseDouble(System.getenv("BOUND_NE_LAT"));
        bounds.setSouthWestLon(swLon);
        bounds.setSouthWestLat(swLat);
        bounds.setNorthEastLon(neLon);
        bounds.setNorthEastLat(neLat);


        final String updateRate = System.getenv("UPDATE_RATE");

        final String centerLatString = System.getenv("CENTER_LAT");
        final String centerLonString = System.getenv("CENTER_LON");

        getUI().getPushConfiguration().setPushMode(PushMode.MANUAL);

        final double centerLat;
        final double centerLon;

        if (centerLatString != null && centerLonString != null) {
            log.info("Adding center marker");
            centerLat = Double.parseDouble(centerLatString);
            centerLon = Double.parseDouble(centerLonString);
            getUI().access(() -> map.addComponent(new LMarker(centerLat, centerLon)));
        } else {
            centerLon = (swLon + neLon) / 2;
            centerLat = (swLat + neLat) / 2;
        }
        map = new LMap();
        markerMap = new ConcurrentHashMap<>();
        executor = Executors.newSingleThreadExecutor();
        final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

        final LOpenStreetMapLayer osmTiles = new LOpenStreetMapLayer();
        osmTiles.setAttributionString("OpenStreetMap Contributors");
        map.addBaseLayer(osmTiles, "OSM");
        map.setCenter(centerLat, centerLon);
        map.zoomToContent();
        map.zoomToExtent(bounds);
        map.addMoveEndListener(event -> log.debug("New bounds: {}", map.getBounds()));
        setContent(map);

        // Initialize background task for marker updates
        scheduledExecutorService.scheduleAtFixedRate(() -> {
                    // Update marker locations
                    final UI ui = getUI();
                    log.debug("Updating UI");
                    ui.access(() -> {
                        for (Map.Entry<String, Point> stringPointEntry : markerPoints.entrySet()) {
                            final String key = stringPointEntry.getKey();
                            final Point newPoint = stringPointEntry.getValue();
                            if (markerMap.containsKey(key)) {
                                final LCircleMarker lCircleMarker = markerMap.get(key);
                                final Point oldPoint = lCircleMarker.getPoint();
                                lCircleMarker.setPoint(newPoint);
                            } else {
                                final LCircleMarker lCircleMarker = new LCircleMarker(newPoint, 3);
                                int rgb = (int) (Math.random() * (1 << 24));
                                final String colorString = "#" + Integer.toHexString(rgb);
                                lCircleMarker.setColor(colorString);
                                lCircleMarker.setFillColor(colorString);
                                lCircleMarker.setFillOpacity(1D);
                                // FIXME broken in v-leaflet API lCircleMarker.setStyleName("leaflet-marker-pane");
                                log.debug("New marker");
                                markerMap.put(key, lCircleMarker);
                                map.addComponent(lCircleMarker);
                            }
                        }
                        ui.push();
                    });
                },
                1000,
                Integer.parseInt(updateRate), TimeUnit.MILLISECONDS);
    }


    public static final int EARTH_RADIUS = 6371;

    public static double distance(double startLat, double startLong,
                                  double endLat, double endLong) {
        double dLat = Math.toRadians((endLat - startLat));
        double dLong = Math.toRadians((endLong - startLong));

        startLat = Math.toRadians(startLat);
        endLat = Math.toRadians(endLat);

        double a = haversin(dLat) + Math.cos(startLat) * Math.cos(endLat) * haversin(dLong);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS * c; // <-- d
    }

    public static double haversin(double val) {
        return Math.pow(Math.sin(val / 2), 2);
    }


    @Override
    public void receiveBroadcast(String topic, Point marker) {
        markerPoints.put(topic, marker);
    }
}