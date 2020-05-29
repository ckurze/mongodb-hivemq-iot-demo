# Dashboard of Trucks

This dashboard provides a real-time overview of the trucks on a map as well as a detailed analysis of individual trucks, i.e. visualizes their routes and provides more details about the routes.

## Dependencies

Required Python packages: `dnspython pymongo paho-mqtt dash dash-bootstrap-components pandas`

## Configuration and Setup

Environment variables:
- MQTT_HOST: MQTT hostname/IP to listen on port 1883
- MONGO_URI: Valid [MongoDB Connection String in URI format](https://docs.mongodb.com/manual/reference/connection-string/)

You can automate this step via `source ../set_env.sh` on the command line.

Import the warehouses into MongoDB:

`python import-warehouses-to-mongodb.py`

## Startup

`python app.py`

TOOD: Containerize

## Used Aggregation Pipelines

**Full Trace of Trucks**

Allows to filter for certain trucks and provides all data points for this truck.

Note: This can get a large amount of data points! After a certain runtime, we talk about a few hundred thousand data points for multiple trucks.

```
[
{$match: { truck: { $in: ["vehicles/trucks/truck-00038/location"] }}}, 
{$sort: { truck: 1, min_ts: 1 }}, 
{$unwind: { path: "$m" }}, 
{$project: {
  _id: 0,
  truck: { $substrCP: [ "$truck", 22, 5] },
  ts: "$m.ts",
  geo: "$m.geo",
  lon: { '$arrayElemAt': [ '$m.geo.coordinates', 0 ] }, 
  lat: { '$arrayElemAt': [ '$m.geo.coordinates', 1 ] },
  speed: "$m.speed",
  speedLimit: "$m.speedLimit",
  break: "$m.break"
}}
]
```

**Routes per Truck**

Gets all routes of a truck and calculates its start and end location as well as timestamp for each route. To visualize each individual route, a GeoJSON LineString is created.  

```
[
{$match: { truck: "vehicles/trucks/truck-00000/location" }}, 
{$sort: { truck: 1, routeId: 1, min_ts: 1 }}, 
{$unwind: { path: "$m" }}, 
{$group: {
  _id: { truck: "$truck", routeId: "$routeId" },
  from: { $first: "$m" },
  to: { $last: "$m" },
  lineString: { $push: "$m.geo.coordinates" }
}}, 
{$project: {
  _id: 0,
  truck: "$_id.truck",
  routeId: "$_id.routeId",
  min_ts: "$from.ts",
  max_ts: "$to.ts",
  lon_from: { $arrayElemAt: ["$from.geo.coordinates",0] },
  lat_from: { $arrayElemAt: ["$from.geo.coordinates",1] },
  lon_to: { $arrayElemAt: ["$to.geo.coordinates",0] },
  lat_to: { $arrayElemAt: ["$to.geo.coordinates",1] },
  geometry: { type: "LineString", coordinates: "$lineString" }
}}, 
{$sort: { truck: 1, min_ts: 1 }}
]
```

**Speed Limit Violations**

Shows the violations of the speed limit.

```
[
{$unwind: { path: "$m" }}, 
{$match: { $expr: { $gt: [ { $subtract:[ "$m.speed", 5 ] }, "$m.speedLimit" ] }}}, 
{$project: {
  _id: 0,
  truck: { $substrCP: [ "$truck", 22, 5 ] },
  routeId: 1,
  ts: "$m.ts",
  speedViolation: { $subtract: [ "$m.speed", "$m.speedLimit" ] }
}}
]
```