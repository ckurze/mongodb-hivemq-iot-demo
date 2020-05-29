package com.hivemq.generator;

import com.hivemq.generator.geo.GeoEdge;
import com.hivemq.simulator.plugin.sdk.load.generators.PluginPayloadGenerator;
import com.hivemq.simulator.plugin.sdk.load.generators.PluginPayloadGeneratorInput;
import net.sf.geographiclib.Geodesic;
import net.sf.geographiclib.GeodesicData;
import net.sf.geographiclib.GeodesicLine;
import net.sf.geographiclib.GeodesicMask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.AStarShortestPath;
import org.jgrapht.graph.Multigraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generates lat,lon formatted, interpolated coordinate pairs which simulate a moving car.
 * Dumb implementation that can only work on a small bounding box with few streets (i.e. within cities)
 */
public class GeoPayloadGenerator implements PluginPayloadGenerator {
    public static final int EARTH_RADIUS = 6371;

    private static final Logger log = LoggerFactory.getLogger(GeoPayloadGenerator.class);

    // distance in m per publish to travel
    public static final double STEP_DISTANCE = 8D;

    private final int agentOffset;
    private final Geodesic geodesic;
    private GraphPath<LinePoint, GeoEdge> path3;
    private Multigraph<LinePoint, GeoEdge> graph;
    private GraphPath<LinePoint, GeoEdge> path;
    private GraphPath<LinePoint, GeoEdge> path2;

    private AtomicInteger iterator;
    private AtomicInteger edgeIterator;
    private LinePoint previousPoint;
    private AtomicBoolean reverseTraversal;

    //FIXME: the constructor must be no-arg; have to initialize elsewhere, probably statically.
    public GeoPayloadGenerator(final @NotNull String message, final int agentOffset, Multigraph<LinePoint, GeoEdge> graph, List<List<LinePoint>> linesList) {
        this.agentOffset = agentOffset;
        this.iterator = new AtomicInteger(0);
        this.edgeIterator = new AtomicInteger(0);
        this.reverseTraversal = new AtomicBoolean(false);
        this.graph = graph;

        geodesic = Geodesic.WGS84;

        final AStarShortestPath<LinePoint, GeoEdge> astarPath = new AStarShortestPath<>(this.graph, (firstPoint, secondPoint) -> distance(
                firstPoint.lat, firstPoint.lon,
                secondPoint.lat, secondPoint.lon));

        double randomIndexStart = Math.random() * linesList.size();
        double randomIndexEnd = Math.random() * linesList.size();
        log.info("Generating path for indices {}, {}", randomIndexStart, randomIndexEnd);
        path = astarPath.getPath(linesList.get((int) randomIndexStart).get(0), linesList.get((int) randomIndexEnd).get(0));
        while (path == null || path.getEdgeList().size() < 4 || path2 == null || path2.getEdgeList().size() < 3 || path3 == null || path3.getEdgeList().size() < 2) {
            randomIndexStart = Math.random() * linesList.size();
            randomIndexEnd = Math.random() * linesList.size();
            log.info("Generating initial path for indices {}, {}", randomIndexStart, randomIndexEnd);
            path = astarPath.getPath(linesList.get((int) randomIndexStart).get(0), linesList.get((int) randomIndexEnd).get(0));
            LinePoint middlePoint = linesList.get((int) (Math.random() * linesList.size())).get(0);
            middlePoint = linesList.get((int) (Math.random() * linesList.size())).get(0);
            log.info("Generating secondary path for indices {}, {}", randomIndexEnd, middlePoint);
            path2 = astarPath.getPath(linesList.get((int) randomIndexEnd).get(0), middlePoint);

            log.info("Generating tertiary path for indices {}, {}", middlePoint, randomIndexStart);
            path3 = astarPath.getPath(middlePoint, linesList.get((int) randomIndexStart).get(0));
        }
        log.info("Initial path: {}", path);

        // Determine initial traversal direction
        final GeoEdge firstEdge = path.getEdgeList().get(0);
        final GeoEdge secondEdge = path.getEdgeList().get(1);
        reverseTraversal.set(
                distance(firstEdge.getStart(), secondEdge.
                        getStart()) < distance(firstEdge.getEnd(), secondEdge.getStart()));
    }

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

    public static double distance(@Nullable LinePoint o1, @Nullable LinePoint o2) {
        if (o1 == null || o2 == null) {
            return Double.MAX_VALUE;
        }
        return Math.abs(distance(o1.lat, o1.lon, o2.lat, o2.lon));
    }

    public static double haversin(double val) {
        return Math.pow(Math.sin(val / 2), 2);
    }

    @Override
    public @NotNull ByteBuffer nextPayload(@NotNull PluginPayloadGeneratorInput pluginPayloadGeneratorInput) {
        final String topic = pluginPayloadGeneratorInput.getTopic();
        try {
            final List<GeoEdge> edgeList = Stream.of(path.getEdgeList(), path2.getEdgeList(), path3.getEdgeList())
                    .flatMap(Collection::stream)
                    .collect(Collectors.toList());
            if(edgeIterator.get() >= edgeList.size()) {
                edgeIterator.set(0);
                log.info("Rolling over edge list for topic {}", topic);
            }
            final GeoEdge currentEdge = edgeList.get(edgeIterator.get());

            final int iteratorValue = iterator.getAndIncrement();
            final GeodesicLine line = geodesic.InverseLine(currentEdge.getStart().getLat(), currentEdge.getStart().getLon(), currentEdge.getEnd().getLat(), currentEdge.getEnd().getLon());
            final double currentPosition = reverseTraversal.get() ? line.Distance() - STEP_DISTANCE * iteratorValue : STEP_DISTANCE * iteratorValue;
            final GeodesicData position = line.Position(currentPosition,
                    GeodesicMask.LATITUDE |
                            GeodesicMask.LONGITUDE);
            LinePoint nextPoint = new LinePoint(position.lat2, position.lon2);
            if (STEP_DISTANCE * (iteratorValue + 1) > line.Distance()) {
                iterator.set(0);
                final int nextEdgeIteration = edgeIterator.incrementAndGet();
                final GeoEdge nextEdge = edgeList.get(nextEdgeIteration % edgeList.size());
                log.debug("Next edge: {}, current edge: {}, reverse traversal: {}", nextEdge, currentEdge, reverseTraversal.get());
                if (distance(nextPoint, nextEdge.getStart()) < distance(nextPoint, nextEdge.getEnd())) {
                    reverseTraversal.set(false);
                } else {
                    reverseTraversal.set(true);
                }
            }
            //log.info("Generating publish for topic {} using generator {}", topic, this);
            previousPoint = nextPoint;
            return ByteBuffer.wrap(Objects.requireNonNullElse(nextPoint, "none").toString().getBytes());
        } catch (Exception ex) {
            log.error("Exception generating payload:", ex);
        }
        log.warn("Returning empty payload");
        return ByteBuffer.wrap("none".getBytes());
    }

    public static class LinePoint {
        public double lat;
        public double lon;

        public LinePoint(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }

        public double getLat() {
            return lat;
        }

        public double getLon() {
            return lon;
        }

        /**
         * @return expected coordinate format for the frontend
         */
        @Override
        public String toString() {
            return lat + "," + lon;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            LinePoint linePoint = (LinePoint) o;
            return Double.compare(linePoint.lat, lat) == 0 &&
                    Double.compare(linePoint.lon, lon) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(lat, lon);
        }
    }
}