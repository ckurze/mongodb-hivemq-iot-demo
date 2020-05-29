package com.example.demomap;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebInitParam;
import javax.servlet.annotation.WebListener;
import javax.servlet.annotation.WebServlet;

import com.google.common.collect.Lists;
import com.vaadin.server.VaadinServlet;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@WebServlet(
    asyncSupported=false,
    urlPatterns={"/*","/VAADIN/*"},
    initParams={
        @WebInitParam(name="ui", value="com.example.demomap.DemoMapUI")
    })
@WebListener
public class DemoMapServlet extends VaadinServlet implements ServletContextListener {

    private static final @NotNull List<String> requiredEnvs = Lists.newArrayList(
            "BOUND_SW_LON",
            "BOUND_SW_LAT",
            "BOUND_NE_LON",
            "BOUND_NE_LAT",
            "UPDATE_RATE",
            "BROKER",
            "TOPIC"
    );
    private static final @NotNull Logger log = LoggerFactory.getLogger(DemoMapServlet.class);


    @Override
    public void init(ServletConfig servletConfig) throws ServletException {
        super.init(servletConfig);
    }

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        if (!System.getenv().keySet().containsAll(requiredEnvs)) {
            log.error("Environment must contain all envs: {}", requiredEnvs);
            System.exit(1);
        }

        final String brokerAddress = System.getenv("BROKER");
        final String topic = System.getenv("TOPIC");
        Broadcaster.start(brokerAddress, topic);
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        Broadcaster.stop();
    }
}
