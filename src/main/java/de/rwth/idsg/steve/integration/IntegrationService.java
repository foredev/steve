package de.rwth.idsg.steve.integration;

import de.rwth.idsg.steve.integration.dto.ConnectorStatus;
import ocpp.cs._2015._10.MeterValuesRequest;

public interface IntegrationService {
    void meterValues(String chargeBoxIdentity, MeterValuesRequest request);
    void chargingBoxStatus(String chargeBoxIdentity, int connectorIdentity, ConnectorStatus status);
}
