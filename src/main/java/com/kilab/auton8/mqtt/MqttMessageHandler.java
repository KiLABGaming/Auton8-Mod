package com.kilab.auton8.mqtt;

@FunctionalInterface
public interface MqttMessageHandler {
    void handle(String topic, String message);
}
