package com.hivemq.generator;

import com.graphhopper.GHRequest;
import com.graphhopper.GraphHopper;
import com.graphhopper.PathWrapper;
import com.graphhopper.config.ProfileConfig;
import com.graphhopper.reader.osm.GraphHopperOSM;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.util.shapes.GHPoint;
import com.hivemq.generator.geo.RouteInterpolator;
import org.junit.Before;
import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;

public class RouteInterpolationTest {

    private GraphHopper hopper;

    @Before
    public void setUp() {
        hopper = new GraphHopperOSM()
                .forServer();
        hopper.setDataReaderFile("germany-latest.osm.pbf");
        hopper.setGraphHopperLocation("/tmp/graphhopper");
        final EncodingManager car = EncodingManager.create("car");
        final ProfileConfig profileConfig = new ProfileConfig("car");
        profileConfig.setWeighting("fastest").setVehicle("car").setTurnCosts(false);
        hopper.setProfiles(profileConfig);
        hopper.setEncodingManager(car);
        hopper.importOrLoad();
    }

    @Test
    public void testEndPoint() {
        final GHPoint start = new GHPoint(52.80853606959265, 9.972516833126475);
        final GHPoint end = new GHPoint( 51.9588245,10.6659214);

        final GHRequest ghRequest = new GHRequest(start, end);
        ghRequest.setProfile("car");
        final PathWrapper best = hopper.route(ghRequest).getBest();

        final RouteInterpolator routeInterpolator = new RouteInterpolator(best);
        final RouteInterpolator.InterpResult point = routeInterpolator.getPoint(1);
        final Coordinate location = point.getLocation();
        System.out.println(location);
    }
}
