# Map frontend

## Build

really simple source2image with jib:

```bash
./gradlew jibDockerBuild
```

## Run

```bash
docker run -p 8080:8080 -e BOUND_SW_LON=5.205146 -e BOUND_SW_LAT=47.543008 \
  -e BOUND_NE_LON=14.427292 -e BOUND_NE_LAT=53.958021 \
  -e BROKER=broker.hivemq.com -e TOPIC=vehicles/trucks/+/location \
  -e UPDATE_RATE=1000 \
    sbaier1/car-demo-webapp
```