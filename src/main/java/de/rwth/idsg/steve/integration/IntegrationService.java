package de.rwth.idsg.steve.integration;

import ocpp.cs._2015._10.MeterValuesRequest;

public interface IntegrationService {
    void meterValues(String chargeBoxIdentity, MeterValuesRequest request);
}
