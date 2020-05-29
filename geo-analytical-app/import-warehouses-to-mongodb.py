import os
import pymongo
import json
from datetime import datetime

MONGO_URI = os.environ['MONGO_URI'] if 'MONGO_URI' in os.environ else None
if MONGO_URI == None:
	raise ValueError('No MongoDB Cluster provided. Will exit.')
	exit(-1)

mongo_client = pymongo.MongoClient(MONGO_URI)
db = mongo_client.geotruck
warehouse_coll = db.warehouse

def main():
	json_file = open('../warehouses_de.geojson')
	data = json.load(json_file)
	batch = []
	for w in data['features']:
		batch.append(pymongo.InsertOne(w))
		write_batch(batch=batch, collection=warehouse_coll, full_batch_required=True)

	write_batch(batch=batch, collection=warehouse_coll, full_batch_required=False)

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

if __name__ == '__main__':
	main()
