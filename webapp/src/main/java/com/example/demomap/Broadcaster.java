package com.example.demomap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.lifecycle.MqttClientAutoReconnect;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import com.hivemq.model.CarData;
import com.hivemq.model.Location;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vaadin.addon.leaflet.shared.Point;

import java.io.IOException;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Broadcaster implements Serializable {
    private static final @NotNull Logger log = LoggerFactory.getLogger(Broadcaster.class);

    private static final @NotNull ObjectMapper mapper = new ObjectMapper();

    private static Mqtt3AsyncClient mqttClient;

    static ExecutorService executorService =
            Executors.newSingleThreadExecutor();

    public interface BroadcastListener {
        void receiveBroadcast(String topic, Point marker);
    }

    private static LinkedList<BroadcastListener> listeners =
            new LinkedList<BroadcastListener>();

    public static synchronized void register(
            BroadcastListener listener) {
        listeners.add(listener);
    }

    public static synchronized void unregister(
            BroadcastListener listener) {
        listeners.remove(listener);
    }

    public static synchronized void start(final String broker, final String topic) {
        log.info("Starting point broadcaster");

        executorService.submit(() -> {
            mqttClient = MqttClient.builder()
                    .useMqttVersion3()
                    .identifier(UUID.randomUUID().toString())
                    .serverHost(broker)
                    .serverPort(1883)
                    .automaticReconnect(MqttClientAutoReconnect.builder().build())
                    .buildAsync();
            mqttClient.connectWith()
                    .send()
                    .whenComplete((mqtt3ConnAck, throwable) -> {
                        if (throwable != null) {
                            // handle failure
                            log.error("Failed to connect", throwable);
                        } else {
                            log.info("Connected to MQTT broker");
                            mqttClient.subscribeWith()
                                    .addSubscription().topicFilter(topic)
                                    .qos(MqttQos.AT_MOST_ONCE)
                                    .applySubscription()
                                    .callback(Broadcaster::publishCallback)
                                    .send()
                                    .whenComplete((suback, thr) -> {
                                        if (thr == null) {
                                            log.info("Subscribed successfully");
                                        } else {
                                            thr.printStackTrace();
                                        }
                                    });
                        }
                    });
            while (mqttClient.toRx().getConfig().getState().isConnected()) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            log.error("Lost connection to broker");
        });
        /*for (final BroadcastListener listener: listeners)
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    listener.receiveBroadcast(marker);
                }
            });*/
    }

    public static synchronized void stop() {
        if (mqttClient != null) {
            try {
                mqttClient.disconnect().get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
        executorService.shutdownNow();
    }


    private static void publishCallback(final @NotNull Mqtt3Publish publish) {
        //log.info("Publish received {}", publish.toString());
        final String key = publish.getTopic().toString();
        try {
            final Point newPoint = getPointFromPublish(publish);
            if (newPoint != null &&
                    key != null) {
                for (BroadcastListener listener : listeners) {
                    listener.receiveBroadcast(key, newPoint);
                }
            }
        } catch (NumberFormatException ex) {
            log.error("Could not parse publish payload {}", publish.toString());
        }
    }

    private static Point getPointFromPublish(Mqtt3Publish publish) throws NumberFormatException {
        try {
            final CarData carData = mapper.readValue(publish.getPayloadAsBytes(), CarData.class);
            final Location location = carData.getLocation();
            if(location != null) {
                return new Point(location.getLon(), location.getLat());
            }
            log.warn("Location was not set in payload. Ignoring. Original payload: {}", new String(publish.getPayloadAsBytes()));
            return null;
        } catch (IOException e) {
            log.error("Failed to read car info payload. Invalid format? Payload: {}, error:", new String(publish.getPayloadAsBytes()), e);
            return null;
        }
    }
}