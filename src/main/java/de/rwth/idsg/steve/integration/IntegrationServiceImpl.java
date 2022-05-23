package de.rwth.idsg.steve.integration;

import de.rwth.idsg.steve.integration.dto.ConnectorStatus;
import de.rwth.idsg.steve.integration.dto.EnergyMeterData;
import ocpp.cs._2015._10.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class IntegrationServiceImpl implements IntegrationService {

    private final MqttService mqttService;

    public IntegrationServiceImpl(MqttService mqttService) {
        this.mqttService = mqttService;
    }

    public void meterValues(String chargeBoxIdentity, MeterValuesRequest request) {
        List<MeterValue> meterValues = request.getMeterValue();

        for (MeterValue meterValue : meterValues) {
            List<SampledValue> sampledValues = meterValue.getSampledValue();

            List<Double> currentImport = getCurrentImport(sampledValues);
            List<Double> voltage = getVoltage(sampledValues);

            EnergyMeterData data = new EnergyMeterData();
            data.setVoltage(voltage);
            data.setCurrent(currentImport);
            List<Double> power = getPower(sampledValues);
            if(power.isEmpty()) {
                data.setPower(getCalculatedPower(currentImport, voltage));
            } else {
                data.setPower(power.stream().findFirst().get());
            }
            data.setTimestamp(meterValue.getTimestamp().toDate());

            Optional<Double> energy = getEnergy(sampledValues);
            energy.ifPresent(data::setEnergy);

            if(data.eligibleToSend()) {
                mqttService.publishEnergyMeterData(chargeBoxIdentity, Integer.toString(request.getConnectorId()), data);
            }
        }
    }

    private List<Double> getCurrentImport(@NotNull List<SampledValue> sampledValues) {
        List<Double> returnValues = Arrays.asList(0.0, 0.0, 0.0);
        List<SampledValue> sorted = sampledValues
                .stream()
                .filter(sampledValue -> sampledValue.getMeasurand() == Measurand.CURRENT_IMPORT)
                .filter(sampledValue -> sampledValue.getUnit() == UnitOfMeasure.A)
                .sorted(Comparator.comparing(SampledValue::getPhase))
                .collect(Collectors.toList());

        for(int i = 0; i<3; i++) {
            for(SampledValue sample : sorted) {
                if(sample.getPhase().value().contains(Integer.toString(i+1))) {
                    returnValues.set(i, Double.valueOf(sample.getValue()));
                }
            }
        }
        return returnValues;
    }

    public List<Double> getVoltage(@NotNull List<SampledValue> sampledValues) {
        List<Double> returnValues = Arrays.asList(0.0, 0.0, 0.0);
        List<SampledValue> sorted = sampledValues
                .stream()
                .filter(sampledValue -> sampledValue.getMeasurand() == Measurand.VOLTAGE)
                .filter(sampledValue -> sampledValue.getUnit() == UnitOfMeasure.V)
                .sorted(Comparator.comparing(SampledValue::getPhase))
                .collect(Collectors.toList());

        for(int i = 0; i<3; i++) {
            for(SampledValue sample : sorted) {
                if(sample.getPhase().value().contains(Integer.toString(i+1))) {
                    returnValues.set(i, Double.valueOf(sample.getValue()));
                }
            }
        }
        return returnValues;
    }

    public List<Double> getPower(List<SampledValue> sampledValues) {
        return sampledValues.stream()
                .filter(sampledValue -> sampledValue.getMeasurand() == Measurand.POWER_ACTIVE_IMPORT)
                .filter(sampledValue -> Double.valueOf(sampledValue.getValue()) > 0)
                .map(sampledValue -> Double.valueOf(sampledValue.getValue()))
                .collect(Collectors.toList());
    }

    /*
     * Assumes that the charge box only returns current and voltage, not power
     */
    public double getCalculatedPower(List<Double> current, List<Double> voltage) {
        if (current.size() != voltage.size()) {
            return 0;
        }

        double power = 0;
        for (int i = 0; i < current.size(); i++) {
            power += current.get(i) * voltage.get(i);
        }

        return power;
    }

    public Optional<Double> getEnergy(List<SampledValue> sampledValues) {
        return sampledValues
                .stream()
                .filter(sampledValue -> sampledValue.getMeasurand() == Measurand.ENERGY_ACTIVE_IMPORT_REGISTER)
                .filter(sampledValue -> sampledValue.getUnit() == UnitOfMeasure.WH)
                .findFirst()
                .map(sampledValue -> Double.valueOf(sampledValue.getValue()));
    }

    public void chargingBoxStatus(String chargeBoxIdentity, int connectorIdentity, ConnectorStatus status) {
        mqttService.publishChargeBoxStatus(chargeBoxIdentity, Integer.toString(connectorIdentity), status);
    }
}
