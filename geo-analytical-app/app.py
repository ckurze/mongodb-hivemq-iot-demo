
# Nice introduction to maps in plotly: https://medium.com/analytics-vidhya/introduction-to-interactive-geoplots-with-plotly-and-mapbox-9249889358eb

import os
import dash
import dash_core_components as dcc
import dash_html_components as html
import dash_bootstrap_components as dbc
from dash.dependencies import Input, Output
import plotly.graph_objects as go
import pandas as pd
import pymongo
import json
import paho.mqtt.client as mqtt

MQTT_HOST = os.environ['MQTT_HOST'] if 'MQTT_HOST' in os.environ else None
if MQTT_HOST == None:
    raise ValueError('No MQTT Broker provided. Will exit.')
    exit(-1)

MONGO_URI = os.environ['MONGO_URI'] if 'MONGO_URI' in os.environ else None
if MONGO_URI == None:
    raise ValueError('No MongoDB Cluster provided. Will exit.')
    exit(-1)

external_stylesheets = ['https://codepen.io/chriddyp/pen/bWLwgP.css', dbc.themes.BOOTSTRAP]

app = dash.Dash(__name__, external_stylesheets=external_stylesheets)
server = app.server

mongo_client = pymongo.MongoClient(MONGO_URI)
db = mongo_client.geotruck
status_coll = db.status
warehouse_coll = db.warehouse

def get_warehouses():
    return pd.DataFrame.from_records(warehouse_coll.aggregate([
        {
            '$project': {
                '_id': 0, 
                'name': '$properties.name', 
                'street': '$properties.addr:street', 
                'zip': '$properties.addr:postcode', 
                'city': '$properties.addr:city', 
                'lon': {
                    '$arrayElemAt': [
                        '$geometry.coordinates', 0
                    ]
                }, 
                'lat': {
                    '$arrayElemAt': [
                        '$geometry.coordinates', 1
                    ]
                }
            }
        }
    ]))

def get_distinct_trucks():
    return list(status_coll.aggregate([
        # Hint to leverage the index
        { '$sort': { 'truck': 1 } }, 
        { '$group': { '_id': '$truck'  } }
    ]))

def get_truck_trace(truck = 'vehicles/trucks/truck-00001/location', route = None):
    print('get_truck_trace')
    print('Truck: ' + str(truck))
    print('route: ' + str(route))

    match = { '$match': {} }
    if truck != None:
        if isinstance(truck, list):
            match['$match']['truck'] = { '$in': truck }
        else:
            match['$match']['truck'] = truck
    if route != None:
        if isinstance(route, list):
            match['$match']['route'] = { '$in': route }
        else:
            match['$match']['route'] = route

    df = pd.DataFrame.from_records(status_coll.aggregate([
        match, 
        {
            '$sort': {
                'truck': 1, 
                'min_ts': 1
            }
        }, {
            '$unwind': {
                'path': '$m'
            }
        }, {
            '$project': {
                '_id': 0, 
                'truck': { '$substrCP': [ '$truck', 22, 5] }, 
                'routeId': '$routeId',
                'ts': '$m.ts', 
                'geo': '$m.geo', 
                'lon': {
                    '$arrayElemAt': [
                        '$m.geo.coordinates', 0
                    ]
                }, 
                'lat': {
                    '$arrayElemAt': [
                        '$m.geo.coordinates', 1
                    ]
                }, 
                'speed': '$m.speed', 
                'speedLimit': '$m.speedLimit', 
                'break': '$m.break'
            }
        }
    ]))

    df.set_index(keys=['truck'], drop=False, inplace=True)

    return df

def get_truck_routes(truck = ['vehicles/trucks/truck-00001/location']):
    return list(status_coll.aggregate([
        { '$match': { 'truck': { '$in': truck } } }, 
        { '$sort': {
            'truck': 1, 
            'routeId': 1, 
            'min_ts': 1
        }},
        { '$unwind': { 'path': '$m' } }, 
        { '$group': {
            '_id': {
                'truck': '$truck', 
                'routeId': '$routeId'
            }, 
            'from': { '$first': '$m' }, 
            'to': { '$last': '$m' }, 
            'lineString': { '$push': '$m.geo.coordinates' }
        }}, 
        { '$project': {
            '_id': 0, 
            'truck': '$_id.truck', 
            'routeId': '$_id.routeId', 
            'min_ts': '$from.ts', 
            'max_ts': '$to.ts', 
            'lon_from': { '$arrayElemAt': [ '$from.geo.coordinates', 0 ] }, 
            'lat_from': { '$arrayElemAt': [ '$from.geo.coordinates', 1 ] }, 
            'lon_to': { '$arrayElemAt': [ '$to.geo.coordinates', 0 ] }, 
            'lat_to': { '$arrayElemAt': [ '$to.geo.coordinates', 1 ] }, 
            'geometry': {
                'type': 'LineString', 
                'coordinates': '$lineString'
            }
        }},
        { '$sort': {
            'truck': 1,
            'min_ts': 1
        }}
    ], allowDiskUse=True))

# Data for real-time map
current_truck_locations = {} #pd.DataFrame(columns=['truck', 'lat', 'lon', 'routeId', 'speed', 'speedLimit', 'break'])
#current_truck_locations.set_index(keys=['truck'], drop=False, inplace=True)

# The callback for when the client receives a CONNACK response from the MQTT server.
def on_connect(client, userdata, flags, rc):
    # print('Connected to MQTT broker with result code ' + str(rc))

    # Subscribing in on_connect() means that if we lose the connection and
    # reconnect then subscriptions will be renewed.
    # We want to subscribe to the status topic of all stations
    client.subscribe('vehicles/trucks/#')

# The callback for when a PUBLISH message is received from the MQTT server.
# Optimization: As we receive many messages in one shot, the results should be processed in a batched manner.
def on_message(client, userdata, message):
    global current_truck_locations
    # print('Received message ' + str(message.payload) + ' on topic ' + message.topic + ' with QoS ' + str(message.qos))

    payload = json.loads(message.payload)
    truckId = message.topic[22:27]
    
    # Did we mix up lon/lat here?
    payload['lat'] = payload['location']['lon']
    payload['lon'] = payload['location']['lat']
    payload.pop('location')
    payload['truck'] = truckId

    current_truck_locations[truckId] = payload

# Setup MQTT broker connectionci
client = mqtt.Client(client_id='geo-subscriber-realtime-map')
client.on_connect = on_connect
client.on_message = on_message
client.connect(MQTT_HOST, 1883, 60)

warehouses = get_warehouses()
trucks = get_distinct_trucks()
current_truck_trace = get_truck_trace()

# The Map to visualize the routes
data = None # mapbox_chart_data()
layout = go.Layout(autosize=True,
                   mapbox= dict(zoom=5,
                                center= dict(lat=51.5368,
                                             lon=10.5685),
                                style="open-street-map"),
                    width=750,
                    height=900)
fig = go.Figure(layout=layout, data=data)

# Real-Time Map
data_realtime = None
layout_realtime = layout
fig_realtime = go.Figure(layout=layout_realtime, data=data_realtime)

app.layout = html.Div([

    html.H1(children='Fleet Manager - Analytics Dashboard', className='display-1 col-12 text-center'),

    html.Div(className='container', children=[
        html.Div(className='row', children=[
            html.H3(className='col-12 text-center', children='Real-time Map')
        ]),
        html.Div(className='row', children=[
            dcc.Graph(id='realtime-map', figure=fig_realtime, className='col-12 text-center')
        ]),

        html.Div(className='row', children=[
            html.H3(className='col-12 text-center', id='trucks-in-break-header', children='Trucks currently taking a break')
        ]),        
        html.Div(className='row', children=[
            html.Div(id='trucks-in-break', children='')
        ]),

        html.Div(className='row', children=[
            html.Div(className='col-12 text-center', children='')
        ]),
        html.Div(className='row', children=[
            html.H3(className='col-12 text-center', children='Choose individual Trucks')
        ]),
        html.Div(className='row', children=[
            html.Div(className='col-12', children=dcc.Dropdown(
                id='truck-dropdown',
                options=[ { 'label': truck['_id'][22:27], 'value': truck['_id'] } for truck in trucks ],
                value=['vehicles/trucks/truck-00001/location'],
                multi=True
            ))
        ]),
        html.Div(className='row', children=[
            dcc.Graph(id='main-map', figure=fig, className='col-12 text-center'),
            
        ]),
        html.Div(className='row', children=[
            html.Div(id='main-map-metadata', className='col-12', children='')
        ]),

        html.Div(className='row', children=[
            html.H3(className='col-12 text-center', children='Route Information')
        ]),
        html.Div(className='row', children=[
            html.Div(id='truck-routes-analysis', className='col-12', children='')
        ]),

        # Interval for real-time refresh of map
        dcc.Interval(
            id='realtime-refresh-interval',
            interval=3000, # in milliseconds
            n_intervals=0
        )
    ]),
])

# Handle Changes of Truck Dropdown
@app.callback(
    [Output('main-map', 'figure'),
     Output('main-map-metadata', 'children'),
     Output('truck-routes-analysis', 'children')],
    [Input('truck-dropdown', 'value')])
def update_main_map(selection):
    global current_truck_trace

    current_truck_trace = get_truck_trace(truck=selection)
    
    data = [
        go.Scattermapbox(
            name='Warehouses',
            lat= warehouses['lat'] ,
            lon= warehouses['lon'],
            customdata = warehouses['city'],
            mode='markers',
            marker=dict(
                size= 9,
                color = 'gold',
                opacity = .2,
            ),
          )]

    current_trucks = current_truck_trace['truck'].unique().tolist()

    for t in current_trucks:
        df_t = current_truck_trace.loc[current_truck_trace.truck==t]
        df_t['text'] = '<b>Truck</b> ' + df_t['truck'] + '<br /><b>RouteID</b> ' + df_t['routeId'] + '<br /><b>Timestamp</b> ' + df_t['ts'].astype(str) + '<br /><b>Current Speed</b> ' + df_t['speed'].astype(str) + '<br /><b>Takes Break</b> ' + df_t['break'].astype(str)
        data.append(
            go.Scattermapbox(
            name='Truck ' + t,
            lat= df_t['lat'] ,
            lon= df_t['lon'],
            customdata = df_t['truck'],
            text = df_t['text'],
            mode='markers',
            marker=dict(
                size= 9,
                opacity = .8,
            )
          ))


    current_truck_routes = get_truck_routes(truck=selection)
    #print(current_truck_routes)
    
    route_analysis_rows = []

    for rt in current_truck_routes:
        route_analysis_rows.append(
            html.Tr([
                html.Td(rt['truck']),
                html.Td(rt['routeId']),
                html.Td('(' + str(rt['lat_from']) + ',' + str(rt['lon_from']) + ')'),
                html.Td('(' + str(rt['lat_to']) + ',' + str(rt['lon_to']) + ')'), 
                html.Td('(' + str(rt['min_ts'])),
                html.Td('(' + str(rt['max_ts'])),
            ]))

    route_analysis_rows.insert(0, 
        html.Tr([
            html.Th('Truck'), 
            html.Th('Route ID'), 
            html.Th('From'), 
            html.Th('To'), 
            html.Th('Start Time'), 
            html.Th('Arrival Time')
        ]))

    route_analysis_table = html.Table(children=route_analysis_rows)


#    df_route_analysis = current_truck_trace.sort_values(by=['routeId','ts'])
#    df_route_analysis['time_diff'] = df_route_analysis['ts'].diff()
#    df_route_analysis.loc[df_route_analysis.routeId != df_route_analysis.routeId.shift(), 'time_diff'] = None
#    print(df_route_analysis)
    
    return go.Figure(layout=layout, data=data), 'Visualizing ' + str(len(current_truck_trace)) + ' data points.', route_analysis_table

# Realtime refresh of map every second
@app.callback(Output('realtime-map', 'figure'),
              [Input('realtime-refresh-interval', 'n_intervals')])
def update_graph_live(n):
    global current_truck_locations

    df_locations = pd.DataFrame.from_records(list(current_truck_locations.values()))
    if not 'truck' in df_locations.columns:
        print('truck not in df')
        return go.Figure(layout=layout_realtime, data=[])

    df_locations.sort_values(by=['truck'], inplace=True)
    df_locations['text'] = '<b>Truck</b> ' + df_locations['truck'] + '<br /><b>Current Speed</b> ' + df_locations['speed'].astype(str) + '<br /><b>Takes Break</b> ' + df_locations['break'].astype(str)
    #print(df_locations)

    data = []    
    current_trucks = df_locations['truck'].unique().tolist()
    # print(current_trucks)
    for t in current_trucks:
        df_t = df_locations.loc[df_locations.truck==t]
        data.append(
            go.Scattermapbox(
            name='Truck ' + t,
            lat= df_t['lat'] ,
            lon= df_t['lon'],
            text = df_t['text'],
            mode='markers',
            marker=dict(
                size= 9,
                opacity = .8,
            )
          ))
    
    return go.Figure(layout=layout_realtime, data=data)


# Current Trucks taking a break
@app.callback([Output('trucks-in-break-header', 'children'),
               Output('trucks-in-break', 'children')],
              [Input('realtime-refresh-interval', 'n_intervals')])
def update_trucks_break(n):
    global current_truck_locations

    trucks_in_break = []

    for cl in current_truck_locations.values():
        if cl['break'] == True:
            trucks_in_break.append(cl['truck'])

    trucks_in_break.sort()

    return str(len(trucks_in_break)) + ' Trucks currently taking a break', ', '.join(trucks_in_break)

if __name__ == '__main__':
    client.loop_start()
    app.run_server(debug=True)
