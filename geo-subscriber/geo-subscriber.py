##########################################################################################
#
# This is an example subcriber to an MQTT Broker like HiveMQ.
#
# Note: It is not intended for production use since no authentication
#	   and encrytpion is in place.
#
# For more information, please have a look at:
#		MongoDB Atlas: https://cloud.mongodb.com
#		MongoDB Client for python: https://api.mongodb.com/python/current/
#		HiveMQ MQTT Broker: https://www.hivemq.com/
#		Eclipse Paho MQTT Client for python: https://www.eclipse.org/paho/clients/python/ 
#											 and https://pypi.org/project/paho-mqtt/
#
###########################################################################################

import os
import json
import paho.mqtt.client as mqtt
from datetime import datetime 
import pymongo

BATCH_SIZE_MESSAGES = 10
MONGO_BUCKET_SIZE = 60
message_batch = []

MQTT_HOST = os.environ['MQTT_HOST'] if 'MQTT_HOST' in os.environ else None
if MQTT_HOST == None:
	raise ValueError('No MQTT Broker provided. Will exit.')
	exit(-1)

MONGO_URI = os.environ['MONGO_URI'] if 'MONGO_URI' in os.environ else None
if MONGO_URI == None:
	raise ValueError('No MongoDB Cluster provided. Will exit.')
	exit(-1)

# The callback for when the client receives a CONNACK response from the MQTT server.
def on_connect(client, userdata, flags, rc):
	print('Connected to MQTT broker with result code ' + str(rc))

	# Subscribing in on_connect() means that if we lose the connection and
	# reconnect then subscriptions will be renewed.
	# We want to subscribe to the status topic of all stations
	client.subscribe('vehicles/trucks/#')

# The callback for when a PUBLISH message is received from the MQTT server.
# Optimization: As we receive many messages in one shot, the results should be processed in a batched manner.
def on_message(client, userdata, message):
	# print('Received message ' + str(message.payload) + ' on topic ' + message.topic + ' with QoS ' + str(message.qos))

	# We generate a timestamp here, this should be included by the trucks themselves so that we have both 
	# timestamps: captured ts, and written ts
	ts = datetime.today()
	payload = json.loads(message.payload)

	#  {"location":{"lat":7.628477821925232,"lon":51.48995060174505},"routeId":"5bd3c108-2681-4135-80fb-1c73632b98fd","speed":140.01392213036448,"speedLimit":120.0,"break":false}
	message_batch.append(pymongo.UpdateOne(
		{
			'truck': message.topic,
			'routeId': payload['routeId'],
			'bktSize': { '$lt': MONGO_BUCKET_SIZE }
		},
		{
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
			'$max': { 'max_ts': ts },
			'$min': { 'min_ts': ts },
			'$inc': { 'bktSize': 1 }
		},
		upsert=True))
	write_batch(batch=message_batch, collection=status_collection, batch_size=BATCH_SIZE_MESSAGES, full_batch_required=True)

def write_batch(batch, collection, batch_size=100, full_batch_required=False):
	'''
	Writes batch of pymongo Bulk operations into the provided collection.
	Full_batch_required can be used to write smaller amounts of data, e.g. the last batch that does not fill the batch_size
	'''

	if len(batch) > 0 and ((full_batch_required and len(batch) >= batch_size) or not full_batch_required):
		try:
			result = collection.bulk_write(batch)
			print(str(datetime.today()) + ' Wrote ' + str(len(batch)) + ' to MongoDB (' + str(collection.name) + ').')
			batch.clear()
		except pymongo.errors.BulkWriteError as err:
			print(str(datetime.today()) + ' ERROR Writing to MongoDB: ' + str(err.details))



# Setup MQTT broker connectionci
client = mqtt.Client(client_id='geo-subscriber')
client.on_connect = on_connect
client.on_message = on_message
client.connect(MQTT_HOST, 1883, 60)

# Setup MongoDB connection
mongo_client = pymongo.MongoClient(MONGO_URI)
db = mongo_client.geotruck
status_collection = db.status

# Efficient queries per device and timespan
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

# Start to listen to the HiveMQ Broker
client.loop_forever()
