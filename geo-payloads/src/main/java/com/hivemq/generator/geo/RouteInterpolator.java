package com.hivemq.generator.geo;

import com.graphhopper.PathWrapper;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.Instruction;
import com.graphhopper.util.InstructionList;
import com.graphhopper.util.PointList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LinearLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Random;

public class RouteInterpolator {
    private static final @NotNull Logger log = LoggerFactory.getLogger(RouteInterpolator.class);


    @NotNull
    private final LineString line;
    @NotNull
    private final DistanceCalcEarth calc;
    /* Total distance covered by line */
    private final double distance;

    /* Distance already traversed */
    private final double traversed;
    private final int currentPoint;
    private final PathWrapper route;

    @NotNull
    private final Random random;

    public RouteInterpolator(PathWrapper currentRoute) {
        line = currentRoute.getPoints().toLineString(false);
        //distance = currentRoute.getDistance();
        route = currentRoute;
        this.currentPoint = 0;
        this.calc = new DistanceCalcEarth();
        distance = route.getPoints().calcDistance(calc);
        this.traversed = 0D;
        this.random = new Random();
    }

    /**
     * Stateless traversal to an exact point in the line
     *
     * @param percentage percentage in the line to travel to (0..1)
     * @return coordinate at the given percentage of distance or {@code null} if invalid input or out of bounds
     */
    public InterpResult getPoint(final double percentage) {
        final CoordinateSequence coordinateSequence = line.getCoordinateSequence();
        //final int size = coordinateSequence.size();
        final double distanceToTraverse = distance * percentage;
        double distanceAcc = 0D;
        final PointList points = route.getPoints();
        for (int i = 0; i < points.size() - 1; ++i) {
            final double prevLat = points.getLat(i);
            final double prevLon = points.getLon(i);

            final double nextLat = points.getLat(i + 1);
            final double nextLon = points.getLon(i + 1);


            // length of the current segment
            final double segmentLength = calc.calcDist(prevLat, prevLon, nextLat, nextLon);
            // Are we already in the target segment?
            if (distanceAcc < distanceToTraverse) {
                distanceAcc += segmentLength;
            }
            if (distanceAcc >= distanceToTraverse) {
                // We reached the target segment, get the part of the line we're travelling to
                final double segDistance;
                if (distanceAcc > distanceToTraverse) {
                    if (i == 0) {
                        // First segment used
                        segDistance = distanceToTraverse;
                    } else {
                        segDistance = distanceAcc - distanceToTraverse;
                    }
                } else {
                    segDistance = distanceToTraverse - distanceAcc;
                }
                // Percentage of the current/target segment to traverse
                final double segPercentage = segDistance / segmentLength;

                // Get the average speed and estimated speed limit in this segment
                int offsetAcc = 0;
                final InstructionList instructions = route.getInstructions();
                double speed = 0D;
                double speedLimit = 0D;
                for (int k = 0; k < instructions.size(); ++k) {
                    final Instruction currentInstr = instructions.get(k);
                    offsetAcc += currentInstr.getLength();
                    // We are in the target instruction
                    if (offsetAcc >= i) {
                        final double randomFactor = Math.pow(random.nextInt(40) - 10, 3) / 1000;
                        log.debug("Random factor: {}", randomFactor);
                        final double actualSpeed = (currentInstr.getDistance() / 1000D) / ((double) currentInstr.getTime() / 1000 / 60 / 60);
                        speed = actualSpeed + randomFactor;
                        // Just round roughly for the speed limit
                        speedLimit = (Math.round(actualSpeed / 10)) * 10;
                        break;
                    }
                }
                if (segPercentage > 1) {
                    // Start a new route, we have reached the end.
                    log.debug("Route end was reached");
                    return new InterpResult(0, null, 0);
                }

                final Coordinate currentPoint = new Coordinate(prevLon, prevLat);
                final Coordinate nextPoint = new Coordinate(nextLon, nextLat);
                return new InterpResult(speed, LinearLocation.pointAlongSegmentByFraction(currentPoint, nextPoint, segPercentage), speedLimit);
            }
        }
        log.warn("Could not generate point at percentage {} for coordinate sequence of size {}." +
                        " Final distance accumulator {}, distance to traverse: {}, total route distance: {}",
                percentage, coordinateSequence.size(),
                distanceAcc, distanceToTraverse, route.getDistance());
        return new InterpResult(0, null, 0);
    }

    public static final class InterpResult {
        final double speed;
        final @Nullable Coordinate location;
        final double speedLimit;

        public InterpResult(double speed, @NotNull Coordinate location, double speedLimit) {
            this.speed = speed;
            this.location = location;
            this.speedLimit = speedLimit;
        }

        public double getSpeed() {
            return speed;
        }

        @Nullable
        public Coordinate getLocation() {
            return location;
        }

        public double getSpeedLimit() {
            return speedLimit;
        }
    }
}
