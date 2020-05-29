package com.hivemq.loader;

import com.google.common.collect.Lists;
import com.hivemq.generator.GeoPayloadGenerator;
import com.hivemq.generator.geo.GeoEdge;
import org.jetbrains.annotations.NotNull;
import org.jgrapht.graph.Multigraph;
import org.jgrapht.graph.builder.GraphBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.hivemq.generator.GeoPayloadGenerator.distance;

public class Loader {
    private static final @NotNull Logger log = LoggerFactory.getLogger(Loader.class);

    /* Store the graph for geo payloads */
    AtomicReference<Multigraph<GeoPayloadGenerator.LinePoint, GeoEdge>> graph;
    private AtomicReference<List<List<GeoPayloadGenerator.LinePoint>>> linesList;

    @NotNull
    private List<List<GeoPayloadGenerator.LinePoint>> initializeLines(String message) {
        log.info("Initializing lines from message {}", message);
        final String[] lines = message.split("_");
        List<List<GeoPayloadGenerator.LinePoint>> linesList = Lists.newArrayList();
        for (String line : lines) {
            final String[] splitString = line.split(";");
            List<GeoPayloadGenerator.LinePoint> pointsCurrent = Lists.newArrayList();
            for (String coordinates : splitString) {
                if (!coordinates.isEmpty()) {
                    final String[] coordinatesSplit = coordinates.split(",");
                    try {
                        final double lat = Double.parseDouble(coordinatesSplit[0]);
                        final double lon = Double.parseDouble(coordinatesSplit[1]);
                        pointsCurrent.add(new GeoPayloadGenerator.LinePoint(lat, lon));
                    } catch (NumberFormatException ex) {
                        log.error("Failed to parse line point: {}", coordinates, ex);
                    }
                }
            }
            linesList.add(pointsCurrent);
        }
        return linesList;
    }


    private void initializeGraph(List<List<GeoPayloadGenerator.LinePoint>> linesList) {
        final GraphBuilder<GeoPayloadGenerator.LinePoint, GeoEdge, ? extends Multigraph<GeoPayloadGenerator.LinePoint, GeoEdge>> builder = Multigraph.createBuilder(GeoEdge.class);

        // Add nodes
        for (List<GeoPayloadGenerator.LinePoint> line : linesList) {
            for (int i = 0; i < line.size(); ++i) {
                builder.addVertex(line.get(i));
            }
        }

        // Add initial line edges from the GeoJSON
        for (List<GeoPayloadGenerator.LinePoint> line : linesList) {
            for (int i = 0; i < line.size() - 1; ++i) {
                final GeoPayloadGenerator.LinePoint firstPoint = line.get(i);
                final GeoPayloadGenerator.LinePoint secondPoint = line.get(i + 1);
                final double distance = distance(firstPoint.lat, firstPoint.lon, secondPoint.lat, secondPoint.lon);
                builder.addEdge(firstPoint, secondPoint, new GeoEdge(firstPoint, secondPoint, distance));
            }
        }
        graph.compareAndSet(null, builder.build());

        // Add junctions between lines where applicable (e.g. less than 1m distance between nodes)
        log.info("Generating junctions");
        for (int i = 0; i < linesList.size(); ++i) {
            for (int k = 0; k < linesList.size() && k != i; ++k) {
                final List<GeoPayloadGenerator.LinePoint> firstPoint = linesList.get(i);
                final List<GeoPayloadGenerator.LinePoint> secondPoint = linesList.get(k);
                for (GeoPayloadGenerator.LinePoint firstActualPoint : firstPoint) {
                    for (GeoPayloadGenerator.LinePoint secondActualPoint : secondPoint) {
                        final double distance = distance(firstActualPoint.lat, firstActualPoint.lon, secondActualPoint.lat, secondActualPoint.lon);
                        // Distances are in km
                        if (distance < 0.003D) {
                            // Avoid loops
                            if (!firstActualPoint.equals(secondActualPoint)) {
                                graph.get().addEdge(firstActualPoint, secondActualPoint, new GeoEdge(firstActualPoint, secondActualPoint, distance));
                            }
                        }
                    }
                }
            }
        }
    }


}
