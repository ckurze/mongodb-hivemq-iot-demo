# Geo payload generators for HiveMQ device simulator

## Preparation

### Download OSM data

```bash
wget https://download.geofabrik.de/europe/germany-latest.osm.pbf
```

## Build & Run

NOTE: You should run the `InitMain` class locally from your IDE first to initialize the Graphhopper index files. It may take very long in VM-encapsulated containers. As an alternative, use the `geo-payloads-init-cache` project if you need to execute the cache initialization in a remote environment.

```bash
./gradlew shadowJar
mkdir -p /tmp/graphhopper
docker run --rm --name device-simulator \
  -e JAVA_TOOL_OPTIONS="-XX:+UnlockExperimentalVMOptions -XX:InitialRAMPercentage=30 -XX:MaxRAMPercentage=80 -XX:MinRAMPercentage=30" \
  -e LOG_LEVEL=DEBUG \
  -e SIMULATOR_COMMANDER_AGENTS=127.0.0.1:9000 \
  -e SIMULATOR_AGENT_BIND_ADDRESS=0.0.0.0 \
  -e SIMULATOR_AGENT_BIND_PORT=9000 \
  -e SIMULATOR_SCENARIO_PATH=/scenario.xml \
  -e SIMULATOR_PLUGIN_PATH=/plugins \
  -e OSM_FILE=/map.osm.pbf \
  -e CONFIG_FILE=/config.json \
  -v $(pwd)/../scenario.xml:/scenario.xml \
  -v $(pwd)/../warehouses_de.geojson:/warehouses_de.geojson \
  -v /tmp/graphhopper:/tmp/graphhopper \
  -v $(pwd)/germany-latest.osm.pbf:/map.osm.pbf \
  -v $(pwd)/build/libs:/plugins \
  -v $(pwd)/config.json:/config.json \
    sbaier1/device-simulator:develop
```
