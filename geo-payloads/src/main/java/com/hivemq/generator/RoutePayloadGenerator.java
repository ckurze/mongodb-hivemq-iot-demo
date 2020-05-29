package com.hivemq.generator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.PathWrapper;
import com.graphhopper.config.ProfileConfig;
import com.graphhopper.reader.osm.GraphHopperOSM;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.util.InstructionList;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.GHPoint;
import com.graphhopper.util.shapes.GHPoint3D;
import com.hivemq.generator.geo.RouteInterpolator;
import com.hivemq.model.CarData;
import com.hivemq.model.Location;
import com.hivemq.model.RoutePayloadConfig;
import com.hivemq.simulator.plugin.sdk.load.generators.PluginPayloadGenerator;
import com.hivemq.simulator.plugin.sdk.load.generators.PluginPayloadGeneratorInput;
import org.geojson.Feature;
import org.geojson.FeatureCollection;
import org.geojson.LngLatAlt;
import org.geojson.Point;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * A payload generator that simulates vehicles on actual roads based on a list of points to navigate between.
 * Uses real OpenStreetMap data to generate the paths.
 */
public class RoutePayloadGenerator implements PluginPayloadGenerator {
    @NotNull
    private static final Logger log = LoggerFactory.getLogger(RoutePayloadGenerator.class);

    @NotNull
    public static final ByteBuffer FAILURE_PAYLOAD = ByteBuffer.wrap("{}".getBytes());

    /**
     * Minimum estimated (air-line) distance for routes between warehouses
     */
    public static final double MINIMUM_DISTANCE_KM = 200D;

    @NotNull
    public static final String OSM_FILE_ENV = "OSM_FILE";

    @NotNull
    private final static GraphHopper hopper;
    @NotNull
    private final Random random;
    @NotNull
    private final ObjectMapper mapper;

    /* Central static location cache for all payload generators */
    @NotNull
    private final static Cache<String, List<GHPoint>> locationCache = CacheBuilder.newBuilder().build();


    @NotNull
    private final static Cache<String, RoutePayloadConfig> configCache = CacheBuilder.newBuilder().build();


    // We don't want to load this for every generator that's created, also won't implement proper dependency injection for now.
    static {
        final String path = System.getenv(OSM_FILE_ENV);
        hopper = new GraphHopperOSM()
                .forServer();
        hopper.setDataReaderFile(path);
        hopper.setGraphHopperLocation("/tmp/graphhopper");
        final EncodingManager car = EncodingManager.create("car");
        final ProfileConfig profileConfig = new ProfileConfig("car");
        profileConfig.setWeighting("fastest").setVehicle("car").setTurnCosts(false);
        hopper.setProfiles(profileConfig);
        hopper.setEncodingManager(car);
        final long startTime = System.currentTimeMillis();
        hopper.importOrLoad();
        log.info("Loaded OSM file at {} in {}ms", path, System.currentTimeMillis() - startTime);
    }


    private PathWrapper currentRoute;
    private String routeId;


    /* Pause state */
    /* pause end timestamp, only set if a pause is in progress */
    private Long pauseUntil;
    private Coordinate pauseLocation;


    /**
     * The instructions local to this instance (a single "vehicle")
     */
    @Nullable
    private InstructionList instructions;

    /**
     * The current index of the instruction within the current {@link #instructions} we are handling
     */
    private int currentInstruction;
    private Double currentSegmentTime;
    private RouteInterpolator interpolator;
    private boolean breakTaken = false;

    /**
     * A payload generator is instantiated for each client publishing to the broker.
     * The payload generators are instantiated each time a publish command in the scenario is executed.
     */
    public RoutePayloadGenerator() {
        this.mapper = new ObjectMapper();
        this.random = new Random();
    }

    @Override
    public @NotNull ByteBuffer nextPayload(@NotNull PluginPayloadGeneratorInput pluginPayloadGeneratorInput) {
        try {
            final RoutePayloadConfig config = configCache.get(pluginPayloadGeneratorInput.getMessage(), () -> mapper.readValue(new File(pluginPayloadGeneratorInput.getMessage()), RoutePayloadConfig.class));
            final String locationFile = config.getLocationFile();
            final String topic = pluginPayloadGeneratorInput.getTopic();
            final long rateNanos = pluginPayloadGeneratorInput.getRate();

            // Is the truck on a break?
            if (pauseUntil != null) {
                final long currentTime = System.currentTimeMillis();
                if (currentTime < pauseUntil) {
                    log.info("Truck {} is on a break at location {}, {}ms real-time left", topic, pauseLocation, pauseUntil - currentTime);
                    final CarData carData = new CarData();
                    final Location location = new Location();
                    location.setLon(pauseLocation.y);
                    location.setLat(pauseLocation.x);
                    carData.setLocation(location);
                    carData.setSpeed(0D);
                    carData.setSpeedLimit(0D);
                    carData.setBreak(true);
                    carData.setRouteId(routeId);
                    return ByteBuffer.wrap(mapper.writeValueAsBytes(carData));
                } else {
                    log.info("Break for truck {} is over, resuming operation", topic);
                    pauseUntil = null;
                }
            }

            // Initial route plot
            if (instructions == null) {
                if (nextRoute(config, null, topic)) {
                    return nextLocation(rateNanos, config, topic);
                } else {
                    log.error("Failed to generate a route.");
                    return FAILURE_PAYLOAD;
                }
            } else {
                return nextLocation(rateNanos, config, topic);
            }
        } catch (Exception ex) {
            log.error("Unexpected error occurred while generating payload", ex);
        }
        log.error("Failed to generate location payload");
        return FAILURE_PAYLOAD;
    }

    @NotNull
    private ByteBuffer nextLocation(long rateNanos,
                                    final RoutePayloadConfig config,
                                    final @NotNull String topic) {
        final String locationFile = config.getLocationFile();
        assert instructions != null;
        // We use the absolute current time to calculate the position in case processing takes longer sometimes
        final double curTime = System.currentTimeMillis();
        if (currentSegmentTime == null) {
            currentSegmentTime = curTime;
        }
        // plotted time for completing the entire route
        final double actualTime = ((long) (currentRoute.getTime() * config.getTimeMultiplier()));

        final double temporalOffset = curTime - currentSegmentTime;
        double percentageTime = temporalOffset / actualTime;

        boolean routeEnd = false;
        if (percentageTime > 1) {
            routeEnd = true;
            percentageTime = 1;
        }

        // walk the instructions.
        //  Use elapsed time (record it in the object state?),
        //  "walk" along all route segments,
        //  find the target segment and interpolate along it to find the current position
        //  FIXME interpolate sanely (maybe based on angles between points?) to model sane driving behavior (no 90 degree turn at 130km/h)
        final RouteInterpolator.InterpResult result = interpolator.getPoint(percentageTime);


        // 10% chance the truck driver will 'hammer it' without taking a break at half time
        if (random.nextDouble() > 0.1D && !breakTaken && percentageTime > 0.4D && percentageTime < 0.5D) {
            log.info("Truck driver {} is not taking a break", topic);
            if (result.getLocation() != null) {
                pauseTruck(config, new GHPoint(result.getLocation().y, result.getLocation().x), 60);
            } else {
                log.error("Location not found");
            }
            breakTaken = true;
        }

        final Coordinate point = result.getLocation();
        if (point != null) {
            try {
                log.debug("Current point at percentage {}: '{}', speed: '{}km/h', estimated limit: '{}km/h'", percentageTime, point, result.getSpeed(), result.getSpeedLimit());
                if (routeEnd) {
                    final GHPoint3D lastPoint = currentRoute.getPoints().get(currentRoute.getPoints().size() - 1);
                    final GHPoint startPoint = new GHPoint(lastPoint.getLat(), lastPoint.getLon());
                    final double distance = GeoPayloadGenerator.distance(startPoint.lat, startPoint.lon, point.y, point.x);
                    log.info("Route ended, returning final point and generating next route. Starting point: {}. Distance from last point to actual position: {}", lastPoint, distance);
                    nextRoute(config, new GHPoint(point.y, point.x), topic);
                }
                return payloadForPoint(result);
            } catch (JsonProcessingException e) {
                log.error("Error writing car payload", e);
            }
        }
        log.error("Failed to generate location payload");
        return FAILURE_PAYLOAD;
    }

    @NotNull
    private ByteBuffer payloadForPoint(RouteInterpolator.InterpResult result) throws JsonProcessingException {
        final Coordinate point = result.getLocation();
        final double speed = result.getSpeed();

        final CarData value = new CarData();
        final Location location = new Location();
        location.setLat(point.x);
        location.setLon(point.y);
        value.setLocation(location);
        value.setRouteId(routeId);
        // average km/h speed
        value.setSpeed(speed);
        value.setSpeedLimit(result.getSpeedLimit());
        return ByteBuffer.wrap(mapper.writeValueAsBytes(value));
    }

    /**
     * Plots the next route for this vehicle.
     *
     * @param config       message passed to the payload generator. Path to GeoJSON file containing points to travel between.
     * @param prevLocation point to start from or null if this is the first route.
     * @param topic
     * @return true if generation succeeded, {@code false} if there was an error (e.g. file not found, file contains too few locations)
     */
    @NotNull
    private boolean nextRoute(final RoutePayloadConfig config, final @Nullable GHPoint prevLocation, String topic) {
        final String locationFile = config.getLocationFile();
        try {
            final List<GHPoint> locations = locationCache.get(locationFile, () -> {
                // Map the GeoJSON file's points to GHPoints for graph hopper
                final List<Feature> features = mapper.readValue(new File(locationFile), FeatureCollection.class).getFeatures();
                return features.stream()
                        .map(f -> {
                            final LngLatAlt point = ((Point) f.getGeometry()).getCoordinates();
                            return new GHPoint(point.getLatitude(), point.getLongitude());
                        })
                        .collect(Collectors.toList());
            });

            log.info("Choosing a route from {} locations", locations.size());

            if (locations.size() < 2) {
                log.error("Location file must contain at least 2 locations");
                return false;
            }

            final GHPoint startLocation;
            final int firstPoint;
            if (prevLocation == null) {
                firstPoint = random.nextInt(locations.size());
                startLocation = locations.get(firstPoint);
            } else {
                // Doesn't matter in this case
                firstPoint = -1;
                startLocation = prevLocation;
            }

            // Ensure second point is somewhere else
            int secondPoint = random.nextInt(locations.size());
            GHPoint endLocation = locations.get(secondPoint);
            while (secondPoint == firstPoint || distance(startLocation, endLocation, MINIMUM_DISTANCE_KM)) {
                secondPoint = random.nextInt(locations.size());
                endLocation = locations.get(secondPoint);
            }

            final GHRequest ghRequest = new GHRequest(startLocation, endLocation);
            ghRequest.setProfile("car");
            final GHResponse route = hopper.route(ghRequest);

            if (route.getErrors().size() > 0) {
                log.warn("Errors in route planning: {}", route.getErrors());
                log.debug("Retrying route planning");
                // Will terminate at some point due to randomness
                return nextRoute(config, prevLocation, topic);
            }

            final PathWrapper bestPath = route.getBest();
            instructions = bestPath.getInstructions();

            currentRoute = bestPath;
            // store the interpolator
            interpolator = new RouteInterpolator(currentRoute);
            routeId = UUID.randomUUID().toString();
            currentSegmentTime = (double) System.currentTimeMillis();
            currentInstruction = 0;

            // Pause for a bit after a trip, simulate a 30 minute break
            if (prevLocation != null) {
                pauseTruck(config, prevLocation, 30);
                breakTaken = false;
            }
            log.info("Chose a new route for topic {} from start location {} to end location {}. Distance: {}km, Time (real-time): {}min", topic, startLocation, endLocation, (long) bestPath.getDistance() / 1000, TimeUnit.MINUTES.convert(bestPath.getTime(), TimeUnit.MILLISECONDS));
        } catch (ExecutionException e) {
            log.error("Failed to load file {} into cache:", config, e);
            return false;
        }
        return true;
    }

    private void pauseTruck(RoutePayloadConfig config, @NotNull GHPoint pauseLocation, final int duration) {
        pauseUntil = System.currentTimeMillis() + (long) ((double) TimeUnit.MILLISECONDS.convert(duration, TimeUnit.MINUTES) * config.getTimeMultiplier());
        this.pauseLocation = new Coordinate(pauseLocation.lon, pauseLocation.lat);
    }

    private boolean distance(GHPoint start, GHPoint end, double minimum) {
        return GeoPayloadGenerator.distance(start.lat, start.lon, end.lat, end.lon) > minimum;
    }

}
