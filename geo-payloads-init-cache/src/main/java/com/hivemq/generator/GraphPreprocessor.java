package com.hivemq.generator;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.graphhopper.GraphHopper;
import com.graphhopper.config.ProfileConfig;
import com.graphhopper.reader.osm.GraphHopperOSM;
import com.graphhopper.routing.util.EncodingManager;

public class GraphPreprocessor {
	
	@NotNull
    private static final Logger log = LoggerFactory.getLogger(GraphPreprocessor.class);

	@NotNull
    public static final String OSM_FILE_ENV = "OSM_FILE";
    
    public static void main(String[] args) {
		initCache();
	}
	
	private static void initCache() {
        final String path = System.getenv(OSM_FILE_ENV);
        GraphHopper hopper = new GraphHopperOSM().forServer();
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
}
