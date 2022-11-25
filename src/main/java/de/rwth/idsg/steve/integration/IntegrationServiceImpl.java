package de.rwth.idsg.steve.integration;

import de.rwth.idsg.steve.integration.dto.ConnectorStatus;
import de.rwth.idsg.steve.integration.dto.EnergyMeterData;
import de.rwth.idsg.steve.repository.TransactionRepository;
import de.rwth.idsg.steve.repository.dto.TransactionDetails;
import ocpp.cs._2015._10.*;
import org.jetbrains.annotations.NotNull;
import org.joda.time.DateTime;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class IntegrationServiceImpl implements IntegrationService {

    private final MqttService mqttService;
    private final TransactionRepository transactionRepository;

    public IntegrationServiceImpl(MqttService mqttService, TransactionRepository transactionRepository) {
        this.mqttService = mqttService;
        this.transactionRepository = transactionRepository;
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
            if (power.isEmpty()) {
                data.setPower(getCalculatedPower(currentImport, voltage));
            } else {
                data.setPower(power.stream().findFirst().get());
            }
            data.setTimestamp(meterValue.getTimestamp().toDate());

            Optional<Double> energy = getEnergy(sampledValues);
            energy.ifPresent(data::setEnergy);

            if (data.eligibleToSend()) {
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

        // Kempower fix, they don't send a phase value at all so we just put it on phase 1
        if (sorted.size() == 1) {
            SampledValue sampledValue = sorted.get(0);
            if (sampledValue.getPhase() == null) {
                sampledValue.setPhase(Phase.L_1);
            }
        }

        for (int i = 0; i < 3; i++) {
            for (SampledValue sample : sorted) {
                if (sample.getPhase().value().contains(Integer.toString(i + 1))) {
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

        // Kempower fix, they don't send a phase value at all so we just put it on phase 1
        if (sorted.size() == 1) {
            SampledValue sampledValue = sorted.get(0);
            if (sampledValue.getPhase() == null) {
                sampledValue.setPhase(Phase.L_1);
            }
        }

        for (int i = 0; i < 3; i++) {
            for (SampledValue sample : sorted) {
                if (sample.getPhase().value().contains(Integer.toString(i + 1))) {
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
        Optional<Double> optionalEnergyInKwh = sampledValues
                .stream()
                .filter(sampledValue -> sampledValue.getMeasurand() == Measurand.ENERGY_ACTIVE_IMPORT_REGISTER)
                .filter(sampledValue -> sampledValue.getUnit() == UnitOfMeasure.K_WH)
                .findFirst()
                .map(sampledValue -> Double.valueOf(sampledValue.getValue()));

        if (optionalEnergyInKwh.isPresent()) {
            Double energyInKwh = optionalEnergyInKwh.get();
            return Optional.of(energyInKwh * 1000);
        }

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

    @Override
    public void onStartTransaction(String chargeBoxIdentity, StartTransactionRequest startTransactionRequest) {
        int connectorId = startTransactionRequest.getConnectorId();
        int meterStart = startTransactionRequest.getMeterStart();
        DateTime timestamp = startTransactionRequest.getTimestamp();

        MeterValuesRequest request = createMeterValuesRequest(timestamp, connectorId, meterStart);
        meterValues(chargeBoxIdentity, request);
    }

    @Override
    public void onStopTransaction(String chargeBoxIdentity, StopTransactionRequest stopTransactionRequest) {
        int transactionId = stopTransactionRequest.getTransactionId();
        int meterStop = stopTransactionRequest.getMeterStop();
        DateTime timestamp = stopTransactionRequest.getTimestamp();

        TransactionDetails details = transactionRepository.getDetailsWithoutMeterValues(transactionId);
        int connectorId = details.getTransaction().getConnectorId();

        MeterValuesRequest request = createMeterValuesRequest(timestamp, connectorId, meterStop);
        meterValues(chargeBoxIdentity, request);
    }

    private MeterValuesRequest createMeterValuesRequest(DateTime timestamp, int connectorId, int energyValue) {
        MeterValuesRequest request = new MeterValuesRequest();
        request.setConnectorId(connectorId);

        SampledValue power = new SampledValue();
        power.setMeasurand(Measurand.POWER_ACTIVE_IMPORT);
        power.setValue("0");
        power.setUnit(UnitOfMeasure.W);

        SampledValue energy = new SampledValue();
        energy.setMeasurand(Measurand.ENERGY_ACTIVE_IMPORT_REGISTER);
        energy.setValue(Integer.toString(energyValue));
        energy.setUnit(UnitOfMeasure.WH);

        MeterValue meterValue = new MeterValue();
        meterValue.withSampledValue(power, energy);
        meterValue.setTimestamp(timestamp);

        request.withMeterValue(meterValue);

        return request;
    }
}
