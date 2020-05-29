package com.hivemq;

import com.hivemq.generator.RoutePayloadGenerator;
import com.hivemq.simulator.plugin.sdk.load.generators.PluginPayloadGeneratorInput;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;

/* Running this can be used to bootstrap graph hopper's index file for faster navigation. */
public class InitMain {

    @NotNull
    public static final String CONFIG_FILE_ENV = "CONFIG_FILE";
    
    public static void main(String[] args) throws Exception{
        // Note: this will take a good while first time because graphhopper will load the entire map and write an index to the graphhopper data directory first.
        final RoutePayloadGenerator routePayloadGenerator = new RoutePayloadGenerator();
        for (int i = 0; i < 10; ++i) {
            final ByteBuffer byteBuffer = routePayloadGenerator.nextPayload(new PluginPayloadGeneratorInput() {
                @Override
                public @NotNull String getTopic() {
                    return "topic";
                }

                @Override
                public long getRate() {
                    return 0;
                }

                @Override
                public long getCount() {
                    return 1;
                }

                @Override
                public @NotNull String getMessage() {
                    // NOTE: run this with the root of the repo as working directory, e.g. /config.json
                	final String path = System.getenv(CONFIG_FILE_ENV);
                    return path;
                }
            });
            Thread.sleep(500);
            System.out.println(new String(byteBuffer.array()));
        }
    }

}

