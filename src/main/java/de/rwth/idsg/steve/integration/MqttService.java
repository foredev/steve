package de.rwth.idsg.steve.integration;

import com.fasterxml.jackson.databind.JsonNode;
import de.rwth.idsg.steve.integration.dto.ConnectorStatus;
import de.rwth.idsg.steve.integration.dto.EnergyMeterData;

public interface MqttService {

    void publishEnergyMeterData(String chargeBoxId, String connector, EnergyMeterData data);

    void publishChargeBoxStatus(String chargeBoxId, String connector, ConnectorStatus status);

    void sendToMqttBroker(String path, JsonNode payload);
}
