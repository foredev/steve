package de.rwth.idsg.steve.integration;

import de.rwth.idsg.steve.SteveConfiguration;
import org.eclipse.paho.client.mqttv3.*;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
public class MqttService {
    private static IMqttClient mqttClient;

    public MqttService() {
        SteveConfiguration.Mqtt mqttConfig = SteveConfiguration.CONFIG.getMqtt();

        try {
            mqttClient = new MqttClient(mqttConfig.getUrl(), UUID.randomUUID().toString());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            options.setUserName(mqttConfig.getUsername());
            options.setPassword(mqttConfig.getPassword().toCharArray());

            if (!mqttClient.isConnected()) {
                mqttClient.connect(options);
            }
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    public void publishMessage(String topic, String message) {
        MqttMessage mqttMessage = new MqttMessage(message.getBytes(StandardCharsets.UTF_8));
        mqttMessage.setQos(0);
        mqttMessage.setRetained(false);

        try {
            mqttClient.publish(topic, mqttMessage);
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }
}
