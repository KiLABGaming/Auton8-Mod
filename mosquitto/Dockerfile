FROM debian:13-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends mosquitto mosquitto-clients \
    && rm -rf /var/lib/apt/lists/*
