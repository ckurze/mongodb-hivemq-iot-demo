package com.hivemq.generator.geo;

import com.hivemq.generator.GeoPayloadGenerator;
import org.apache.commons.lang3.Range;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GeoEdge {
    private static final @NotNull Logger log = LoggerFactory.getLogger(GeoEdge.class);


    private final GeoPayloadGenerator.LinePoint start;
    private final GeoPayloadGenerator.LinePoint end;

    private final double distance;

    public GeoEdge(GeoPayloadGenerator.LinePoint start, GeoPayloadGenerator.LinePoint end, double distance) {
        this.start = start;
        this.end = end;
        this.distance = distance;
    }

    public GeoPayloadGenerator.LinePoint getStart() {
        return start;
    }

    public GeoPayloadGenerator.LinePoint getEnd() {
        return end;
    }

    public double getDistance() {
        return distance;
    }

    /**
     * Linearly interpolate between the two edge points
     * TODO implement parabola based accelerate-decelerate interpolator? more realistic at junctions etc. maybe add junction indicator in this class also
     *
     * @param iteration  which iteration in the interval to return
     * @param resolution distance per step in km for this traversal
     * @param reverse    revert the interpolation direction
     * @return new point between the vertices
     */
    public GeoPayloadGenerator.LinePoint traverse(int iteration, double resolution, boolean reverse) {
        // FIXME: how to determine the correct direction? pass the target vertex to this method??
        // TODO: use distance between the nodes to adjust the segment length and resolution, so we can keep a constant speed across edges instead of slowing down on small edges
        // TODO: client offsets / car offset based on topic

        // FIXME initialize most things here in the constructor

        final double x0 = start.getLat();
        final double x1 = end.getLat();
        final double y0 = start.getLon();
        final double y1 = end.getLon();

        final double segmentCount = distance / resolution;
        final double distLat = Math.abs(x1 - x0);

        final double segmentLength = distLat / segmentCount;

        // TODO: there are still some cases where the interpolation is falsely inverted
        // FIXME: also figure out why the locations of the generators converge at some point. they should be completely random.
        final int actualIteration = reverse ? (int) (segmentCount - iteration) : iteration;
        //final int actualIteration = iteration;
        double xStart = reverse ? x1 : x0;
        final double x = xStart + (segmentLength * (actualIteration));

        // Ensure x is in the closed range (x0, x1)
        final Range<Double> range = Range.between(x0, x1);
        if (!range.contains(x)) {
            log.error("interpolated value is out of range: {}, parameters: segment length: {}, step: {}", x, segmentLength, actualIteration);
        }
        final double y = y0 + (x - x0) * ((y1 - y0) / (x1 - x0));
        // TODO: make sure we don't go out of bounds on short segments using min/max
        log.info("Returning new point. coordinates: ({}, {}), reverse: {}, actual: {}, iteration: {}, segment count: {}", x, y, reverse, actualIteration, iteration, segmentCount);
        return new GeoPayloadGenerator.LinePoint(x, y);
    }

    @Override
    public String toString() {
        return "GeoEdge{" +
                "start=" + start +
                ", end=" + end +
                ", distance=" + distance +
                '}';
    }
}
