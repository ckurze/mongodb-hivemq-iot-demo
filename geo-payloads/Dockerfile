FROM busybox AS builder
RUN mkdir /plugins
COPY geo-payloads-1.0-SNAPSHOT-all.jar /plugins/

FROM sbaier1/device-simulator:develop
COPY --from=builder /plugins /plugins
