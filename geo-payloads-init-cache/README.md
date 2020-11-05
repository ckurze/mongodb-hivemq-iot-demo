# Truck payload generator for HiveMQ device simulator - Initialization of Graphhpper Index Files

This project enables you to pre-load the indexes of Graphhopper from a non-containerized environment. It only needs to be called once, before you start up the demo.

## Preparation

### If not done already: Download OSM data

This only needs to be done once, so if you already downloaded the file to a different directory, you can simply reference to it later on.

```bash
wget https://download.geofabrik.de/europe/germany-latest.osm.pbf
```

## Build & Run

You can build the jar file via gradle and execute it. The environment variable OSM_FILE should point to the OpenStreetMap data mentioned above.

NOTE: You should run this locally to initialize the Graphhopper index files. It may take very long in VM-encapsulated containers.

```bash
./gradlew shadowJar
mkdir -p /tmp/graphhopper
export OSM_FILE=./germany-latest.osm.pbf
java -jar build/libs/geo-payloads-init-cache-1.0-SNAPSHOT-all.jar
```

The output should be similar to the following (the warnings can be ignored):

```bash
[main] INFO com.graphhopper.reader.osm.GraphHopperOSM - version 1.0|2020-04-27T16:27:47Z (5,15,4,3,5,6)
[main] INFO com.graphhopper.reader.osm.GraphHopperOSM - graph car|RAM_STORE|2D|no_turn_cost|,,,,, details:edges:0(0MB), nodes:0(0MB), name:(0MB), geo:0(0MB), bounds:1.7976931348623157E308,-1.7976931348623157E308,1.7976931348623157E308,-1.7976931348623157E308
[main] INFO com.graphhopper.reader.osm.GraphHopperOSM - start creating graph from ../geo-payloads/germany-latest.osm.pbf
[main] INFO com.graphhopper.reader.osm.GraphHopperOSM - using car|RAM_STORE|2D|no_turn_cost|,,,,, memory:totalMB:256, usedMB:16
[main] INFO com.graphhopper.reader.osm.OSMReader - 600 000 (preprocess), osmWayMap:2 615 859 totalMB:4083, usedMB:2546
[main] INFO com.graphhopper.reader.osm.OSMReader - creating graph. Found nodes (pillar+tower):42 742 444, totalMB:4083, usedMB:2702
[main] INFO com.graphhopper.reader.osm.OSMReader - 200 000 000, locs:32 601 428 (0) totalMB:4083, usedMB:1617
[main] INFO com.graphhopper.reader.osm.OSMReader - 318 310 419, now parsing ways
[main] WARN com.graphhopper.reader.osm.OSMReader - Pillar node 388583482 is already a tower node and used in loop, see #1533. Fix mapping for way 329748663, nodes:[388583482, 388583482, 1211107504, 1211108275, 1211108524, 1211107941, 1211107146, 1211108338, 1211107592]
[main] WARN com.graphhopper.reader.osm.OSMReader - Pillar node 2201953814 is already a tower node and used in loop, see #1533. Fix mapping for way 376992312, nodes:[2201953814, 2201953814, 2201953823, 4189595494, 2201953850, 2201953855, 2201953858, 2201953857, 2201953856, 2201953854, 2201953848, 2201953845, 2201953843, 2201953847, 2201953837, 2201953830, 2201953832, 2201953835]
[main] INFO com.graphhopper.reader.osm.OSMReader - 370 155 363, now parsing relations
[main] INFO com.graphhopper.reader.osm.OSMReader - finished way processing. nodes: 10402598, osmIdMap.size:42921946, osmIdMap:543MB, nodeFlagsMap.size:179502, relFlagsMap.size:2680263, zeroCounter:177807 totalMB:4096, usedMB:2995
[main] INFO com.graphhopper.reader.osm.OSMReader - time pass1:145s, pass2:207s, total:353s
[main] INFO com.graphhopper.routing.subnetwork.PrepareRoutingSubnetworks - start finding subnetworks (min:200, min one way:0) totalMB:4096, usedMB:2995
[main] INFO com.graphhopper.routing.subnetwork.PrepareRoutingSubnetworks - 224543 subnetworks found for car, totalMB:4096, usedMB:1965
[main] INFO com.graphhopper.routing.subnetwork.PrepareRoutingSubnetworks - optimize to remove subnetworks (224543), unvisited-dead-end-nodes (0), maxEdges/node (13)
[main] INFO com.graphhopper.reader.osm.GraphHopperOSM - edges: 12 332 177, nodes 9 816 893, there were 224 543 subnetworks. removed them => 585 705 less nodes
[main] INFO com.graphhopper.storage.index.LocationIndexTree - location index created in 10.95076s, size:13 116 822, leafs:2 322 054, precision:300, depth:7, checksum:9816893, entries:[16, 16, 16, 16, 16, 4, 4], entriesPerLeaf:5.6488013
[main] INFO com.graphhopper.reader.osm.GraphHopperOSM - flushing graph car|RAM_STORE|2D|no_turn_cost|5,15,4,3,5, details:edges:12 332 177(377MB), nodes:9 816 893(113MB), name:(46MB), geo:47 401 848(181MB), bounds:5.863079559722831,25.196558055204704,47.2780464,60.22003669783555, totalMB:4096, usedMB:2091)
[main] INFO com.graphhopper.reader.osm.GraphHopperOSM - flushed graph totalMB:4096, usedMB:2233)
[main] INFO com.hivemq.generator.GraphPreprocessor - Loaded OSM file at ../geo-payloads/germany-latest.osm.pbf in 398943ms
```

Now you have the Graphhopper cache preloaded - everything is setup to run the demo.
