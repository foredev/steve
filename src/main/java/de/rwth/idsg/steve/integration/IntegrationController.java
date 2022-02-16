package de.rwth.idsg.steve.integration;

import de.rwth.idsg.steve.integration.dto.EnergyMeterData;
import de.rwth.idsg.steve.integration.dto.LimitPowerRequest;
import de.rwth.idsg.steve.integration.dto.LimitPowerResponse;
import de.rwth.idsg.steve.ocpp.OcppTransport;
import de.rwth.idsg.steve.repository.ChargePointRepository;
import de.rwth.idsg.steve.repository.ChargingProfileRepository;
import de.rwth.idsg.steve.repository.TransactionRepository;
import de.rwth.idsg.steve.repository.dto.ChargePointSelect;
import de.rwth.idsg.steve.service.ChargePointHelperService;
import de.rwth.idsg.steve.service.ChargePointService12_Client;
import de.rwth.idsg.steve.service.ChargePointService16_Client;
import de.rwth.idsg.steve.web.dto.ChargingProfileForm;
import de.rwth.idsg.steve.web.dto.ocpp.RemoteStartTransactionParams;
import de.rwth.idsg.steve.web.dto.ocpp.RemoteStopTransactionParams;
import de.rwth.idsg.steve.web.dto.ocpp.SetChargingProfileParams;
import lombok.extern.slf4j.Slf4j;
import ocpp.cp._2015._10.ChargingProfileKindType;
import ocpp.cp._2015._10.ChargingProfilePurposeType;
import ocpp.cp._2015._10.ChargingRateUnitType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Controller
@ResponseBody
@RequestMapping(value = "/api/chargepoints", produces = MediaType.APPLICATION_JSON_VALUE)
public class IntegrationController {

    private final ChargePointRepository chargePointRepository;
    private final ChargingProfileRepository chargingProfileRepository;
    private final ChargePointHelperService chargePointHelperService;
    private final TransactionRepository transactionRepository;
    private final ChargePointService12_Client client12;
    private final ChargePointService16_Client client16;
    private final MqttService mqttService;

    public IntegrationController(ChargePointRepository chargePointRepository, ChargingProfileRepository chargingProfileRepository, ChargePointHelperService chargePointHelperService, TransactionRepository transactionRepository, @Qualifier("ChargePointService12_Client") ChargePointService12_Client client12, @Qualifier("ChargePointService16_Client") ChargePointService16_Client client16, MqttService mqttService) {
        this.chargePointRepository = chargePointRepository;
        this.chargingProfileRepository = chargingProfileRepository;
        this.chargePointHelperService = chargePointHelperService;
        this.transactionRepository = transactionRepository;
        this.client12 = client12;
        this.client16 = client16;
        this.mqttService = mqttService;
    }

    @RequestMapping(value = "/{chargePointId}", method = RequestMethod.POST)
    public ResponseEntity<String> addChargePoint(@PathVariable String chargePointId) {
        Optional<String> registrationStatus = chargePointRepository.getRegistrationStatus(chargePointId);
        if (registrationStatus.isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        chargePointRepository.addChargePointList(Collections.singletonList(chargePointId));
        chargePointHelperService.removeUnknown(chargePointId);
        return ResponseEntity.ok(chargePointId);
    }

    @RequestMapping(value = "/mqtt-test", method = RequestMethod.POST)
    public ResponseEntity<Boolean> mqttTest() {
        EnergyMeterData energyMeterData = new EnergyMeterData();
        energyMeterData.setEnergy(10000);
        energyMeterData.setPower(3700);
        energyMeterData.setFrequency(49.985);
        energyMeterData.setCurrent(List.of(16.0, 16.0, 16.0));
        energyMeterData.setVoltage(List.of(220.0, 220.0, 220.0));

        mqttService.publishEnergyMeterData("9082359785", "1", energyMeterData);
        return ResponseEntity.ok(true);
    }

    @RequestMapping(value = "/{chargeBoxId}/limitpower", method = RequestMethod.POST)
    public ResponseEntity<LimitPowerResponse> limitPower(@PathVariable String chargeBoxId, @RequestBody LimitPowerRequest request) {

        boolean connected = chargePointHelperService.isConnected(chargeBoxId);
        if (!connected) {
            log.warn("Charge box " + chargeBoxId + " is not connected");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new LimitPowerResponse(false, "Charge box " + chargeBoxId + " is not connected"));
        }

        List<Integer> activeTransactionIds = transactionRepository.getActiveTransactionIds(chargeBoxId);
        if (activeTransactionIds.isEmpty()) {
            log.warn("No active transaction for chargeBoxId " + chargeBoxId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new LimitPowerResponse(false, "No active transaction for chargeBoxId " + chargeBoxId));
        }

        int chargingProfileId = addChargingProfile(request);

        ChargePointSelect chargePointSelect = new ChargePointSelect(OcppTransport.JSON, chargeBoxId);

        List<ChargePointSelect> chargePointSelectList = new ArrayList<>();
        chargePointSelectList.add(chargePointSelect);

        SetChargingProfileParams params = new SetChargingProfileParams();
        params.setChargePointSelectList(chargePointSelectList);
        params.setConnectorId(1);
        params.setChargingProfilePk(chargingProfileId);

        client16.setChargingProfile(params);

        return ResponseEntity.ok(new LimitPowerResponse(true));
    }

    private int addChargingProfile(LimitPowerRequest request) {
        ChargingProfileForm form = new ChargingProfileForm();
        form.setStackLevel(1);
        form.setChargingProfilePurpose(ChargingProfilePurposeType.TX_PROFILE);
        form.setChargingProfileKind(ChargingProfileKindType.ABSOLUTE);
        form.setChargingRateUnit(ChargingRateUnitType.W);

        ChargingProfileForm.SchedulePeriod schedulePeriod = new ChargingProfileForm.SchedulePeriod();
        schedulePeriod.setStartPeriodInSeconds(0);
        schedulePeriod.setPowerLimit(BigDecimal.valueOf(request.getPowerLimitInWatt()));

        Map<String, ChargingProfileForm.SchedulePeriod> schedulePeriodMap = new HashMap<>();
        schedulePeriodMap.put("first", schedulePeriod);

        form.setSchedulePeriodMap(schedulePeriodMap);

        return chargingProfileRepository.add(form);
    }

    @RequestMapping(value = "/{chargePointId}/transaction", method = RequestMethod.DELETE)
    public ResponseEntity<Boolean> stopActiveTransaction(@PathVariable String chargePointId) {
        List<Integer> activeTransactionIds = transactionRepository.getActiveTransactionIds(chargePointId);

        if (activeTransactionIds.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        RemoteStopTransactionParams params = new RemoteStopTransactionParams();
        Integer transactionId = activeTransactionIds.get(0);
        params.setTransactionId(transactionId);
        int taskId = client12.remoteStopTransaction(params);

        log.debug("[chargeBoxId={}, transactionId={}, taskId={}] Remote stop transaction", chargePointId, transactionId, taskId);
        return ResponseEntity.ok(true);
    }

    @RequestMapping(value = "/{chargePointId}/transaction", method = RequestMethod.PUT)
    public ResponseEntity<Boolean> startTransaction(@PathVariable String chargePointId) {
        RemoteStartTransactionParams params = new RemoteStartTransactionParams();
        params.setConnectorId(1);
        params.setIdTag("999999"); // default id tag on the charge amps box

        int taskId = client12.remoteStartTransaction(params);

        log.debug("[chargeBoxId={}, connectionId={}, idTag={}, taskId={}] Remote start transaction", chargePointId, 1, 999999, taskId);
        return ResponseEntity.ok(true);
    }
}
