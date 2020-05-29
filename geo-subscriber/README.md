# MQTT Subscriber that writes to MongoDB

Simple subscriper written in Python that listens to the truck topics and writes the data into MongoDB leveraging the [size-based bucketing approach for timeseries data](https://www.mongodb.com/collateral/time-series-best-practices).

**Note:** This approach does not provide high availability. We have chosen Python to demonstrate how to write to MongoDB, in production settings, you should go for extensions in HiveMQ or use the Java Client as it offers capabilities out of the box. For example, [MQTT 5.0 allows a $share subscription](https://www.hivemq.com/blog/mqtt5-essentials-part7-shared-subscriptions/) that automatically distributes workload.

## Dependencies

Required Python packages: `dnspython pymongo paho-mqtt`

## Configuration and Setup

Environment variables:
- MQTT_HOST: MQTT hostname/IP to listen on port 1883
- MONGO_URI: Valid [MongoDB Connection String in URI format](https://docs.mongodb.com/manual/reference/connection-string/)

## Startup

`python geo-subscriber.py`

TOOD: Containerize

## Bucketing Approach in MongoDB

A common pattern to store and retrieve time series data is to leverage the document model with the so called bucketing schema pattern. Instead of storing each measurement into a single document, multiple measurements are stored into one single document. This provides the benefits of:
- Reducing Storage space (as less data is stored multiple times, e.g. device id and other metadata, as well as better compression ratios on larger documents)
- Reduce Index sizes (by bucket size), larger parts of the index will fit into memory and increase performance
- Reduce IO by less documents (reading time series at scale is usually IO-bound load)

The necessary update statement is simple:

```
status_collection.update_one(
	{
		# The truck and route to be updated
		'truck': message.topic,
		'routeId': payload['routeId'],
		# For this truck and route, find a document where we still have space in the bucket
		'bktSize': { '$lt': MONGO_BUCKET_SIZE }
	},
	{
		# Push the new measurement into the array called "m"
		'$push': { 
			'm': {
				'ts': ts,
				'geo': {
					'type': 'Point',
					'coordinates': [ payload['location']['lat'], payload['location']['lon'] ]
				},
				'speed': payload['speed'],
				'speedLimit': payload['speedLimit'],
				'break': payload['break']
			}
		},
		# Keep min and max timestamps for efficient querying
		'$max': { 'max_ts': ts },
		'$min': { 'min_ts': ts },
		# Increment the bucket size by one
		'$inc': { 'bktSize': 1 }
	},
	# Automatically add a new document, in case we have a new truck/route or cannot find an empty bucket
	upsert=True))
```
A proper indexing strategy is key for efficient querying of data. The first index is mandatory for efficient time series queries in historical data. The second one is needed for efficient retreival of the current, i.e. open, bucket for each device. If all device types have the same bucket size, it can be created as a partial index - this will only keep the open buckets in the index. For varying bucket sizes, e.g. per device type, the type could be added to the index. The savings can be huge for large implementations.

```
# Efficient queries per truck and timespan
status_collection.create_index([('truck',pymongo.ASCENDING),
                        ('min_ts',pymongo.ASCENDING),
                        ('max_ts',pymongo.ASCENDING)])

# Efficient queries per truck, route and timespan
status_collection.create_index([('truck',pymongo.ASCENDING),
                        ('routeId',pymongo.ASCENDING),
                        ('min_ts',pymongo.ASCENDING),
                        ('max_ts',pymongo.ASCENDING)])

# Efficient retreival of open buckets per device
status_collection.create_index([('truck', pymongo.ASCENDING),
						('routeId', pymongo.ASCENDING),
						('bktSize', pymongo.ASCENDING) ], 
						partialFilterExpression={'bktSize': { '$lt': MONGO_BUCKET_SIZE }})
```

With Aggregation Pipelines it is easy to query, filter, and format the data. This is the query pattern for time series. The sort should use the full index prefix in order to be executed on the index and not in memory.

```
result = collection.aggregate([
    { "$match": { "truck": 4711 } },
    # Efficient sort as we use the index
    { "$sort": { "truck": 1, "min_ts": 1 } },
    { "$unwind": "$m" },
    { "$sort": { "m.ts": 1 } },
    { "$project": { "_id": 0, "truck": 1, "ts": "$m.ts", ... } }
])

for doc in result:
    print(doc)
```

In order to query for a certain timeframe, the following $match stage can be used to search for a certain timeframe (please replace LOWER_BOUND and UPPER_BOUND with appropriate ISODate values).

```
LOWER_BOUND = datetime.datetime(2020, 4, 20, 13, 26, 43, 18000)
UPPER_BOUND = datetime.datetime(2020, 4, 20, 13, 30, 26, 130000) 

result = collection.aggregate([
    { "$match": { "truck": 4711, "min_ts": { "$lte": UPPER_BOUND }, "max_ts": { "$gte": LOWER_BOUND } } },
    # Efficient sort as we use the index
    { "$sort": { "truck": 1, "min_ts": 1 } },
    { "$unwind": "$m" },
    # Remove the data from the bucket we are not interested in
    { "$match": { "$and": [ { "m.ts": { "$lte": UPPER_BOUND } }, { "m.ts": { "$gte": LOWER_BOUND } } ] } },
    { "$sort": { "m.ts": 1 } },
    { "$project": { "_id": 0, "truck": 1, "ts": "$m.ts", ... } }
])
```

