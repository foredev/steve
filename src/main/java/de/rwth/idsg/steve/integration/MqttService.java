package de.rwth.idsg.steve.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import de.rwth.idsg.steve.SteveConfiguration;
import de.rwth.idsg.steve.integration.dto.ConnectorStatus;
import de.rwth.idsg.steve.integration.dto.EnergyMeterData;
import de.rwth.idsg.steve.ocpp.ws.JsonObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
@Slf4j
public class MqttService {
    private static IMqttClient mqttClient;

    private final ObjectMapper mapper = JsonObjectMapper.INSTANCE.getMapper();

    public MqttService() {
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setDateFormat(new StdDateFormat().withColonInTimeZone(true));

        SteveConfiguration.Mqtt mqttConfig = SteveConfiguration.CONFIG.getMqtt();

        try {
            MemoryPersistence memoryPersistence = new MemoryPersistence();

            mqttClient = new MqttClient(mqttConfig.getUrl(), UUID.randomUUID().toString(), memoryPersistence);

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

    public void publishEnergyMeterData(String chargeBoxId, String connector, EnergyMeterData data) {
        JsonNode payloadNode;
        payloadNode = mapper.valueToTree(data);
        sendToMqttBroker("ocpp/" + chargeBoxId + "/" + connector + "/em", payloadNode);
    }

    public void publishChargeBoxStatus(String chargeBoxId, String connector, ConnectorStatus status){
        JsonNode payloadNode;
        payloadNode = mapper.valueToTree(status);
        sendToMqttBroker("ocpp/" + chargeBoxId + "/" + connector + "/status", payloadNode);
    }

    public void sendToMqttBroker(String path, JsonNode payload) {
        try {

            MqttMessage mqttMessage = new MqttMessage(payload.toString().getBytes(StandardCharsets.UTF_8));
            mqttMessage.setQos(0);
            mqttMessage.setRetained(false);

            mqttClient.publish(path, mqttMessage);
        } catch (IllegalArgumentException e) {
            log.error("Failed to serialize energy meter data", e);
        } catch (MqttException e) {
            log.error("Failed to publish energy meter data to mqtt broker", e);
        }
    }
}
