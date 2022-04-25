package de.rwth.idsg.steve.integration;

import de.rwth.idsg.steve.integration.dto.EnergyMeterData;
import de.rwth.idsg.steve.integration.dto.ChargingLimitRequest;
import de.rwth.idsg.steve.integration.dto.ChargingLimitResponse;
import de.rwth.idsg.steve.ocpp.OcppTransport;
import de.rwth.idsg.steve.repository.ChargePointRepository;
import de.rwth.idsg.steve.repository.ChargingProfileRepository;
import de.rwth.idsg.steve.repository.TransactionRepository;
import de.rwth.idsg.steve.repository.dto.ChargePoint;
import de.rwth.idsg.steve.repository.dto.ChargePointSelect;
import de.rwth.idsg.steve.repository.dto.Transaction;
import de.rwth.idsg.steve.repository.dto.TransactionDetails;
import de.rwth.idsg.steve.service.ChargePointHelperService;
import de.rwth.idsg.steve.service.ChargePointService16_Client;
import de.rwth.idsg.steve.web.dto.ChargePointQueryForm;
import de.rwth.idsg.steve.web.dto.ChargingProfileForm;
import de.rwth.idsg.steve.web.dto.ocpp.RemoteStartTransactionParams;
import de.rwth.idsg.steve.web.dto.ocpp.RemoteStopTransactionParams;
import de.rwth.idsg.steve.web.dto.ocpp.SetChargingProfileParams;
import lombok.extern.slf4j.Slf4j;
import ocpp.cp._2015._10.ChargingProfileKindType;
import ocpp.cp._2015._10.ChargingProfilePurposeType;
import ocpp.cp._2015._10.ChargingRateUnitType;
import org.joda.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
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
    private final ChargePointService16_Client client16;
    private final MqttService mqttService;

    private enum Status {
        OK,
        NOK,
        STARTED,
        NOTSTARTED,
        ALREADYSTARTED,
        ERROR
    }
    public IntegrationController(ChargePointRepository chargePointRepository, ChargingProfileRepository chargingProfileRepository, ChargePointHelperService chargePointHelperService, TransactionRepository transactionRepository, @Qualifier("ChargePointService16_Client") ChargePointService16_Client client16, MqttService mqttService) {
        this.chargePointRepository = chargePointRepository;
        this.chargingProfileRepository = chargingProfileRepository;
        this.chargePointHelperService = chargePointHelperService;
        this.transactionRepository = transactionRepository;
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

    @RequestMapping(value = "/{chargeBoxId}/{connectorId}/charginglimit", method = RequestMethod.POST)
    public ResponseEntity<ChargingLimitResponse> setChargingLimit(@PathVariable String chargeBoxId, @PathVariable int connectorId, @RequestBody ChargingLimitRequest request) {
        boolean connected = chargePointHelperService.isConnected(chargeBoxId);
        if (!connected) {
            log.warn("Charge box " + chargeBoxId + " is not connected");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ChargingLimitResponse(false, "Charge box " + chargeBoxId + " is not connected"));
        }

        List<Integer> activeTransactionIds = transactionRepository.getActiveTransactionIds(chargeBoxId);
        if (activeTransactionIds.isEmpty()) {
            log.warn("No active transaction for chargeBoxId " + chargeBoxId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ChargingLimitResponse(false, "No active transaction for chargeBoxId " + chargeBoxId));
        }

        int chargingProfileId = addChargingProfile(request);

        ChargePointSelect chargePointSelect = new ChargePointSelect(OcppTransport.JSON, chargeBoxId);

        List<ChargePointSelect> chargePointSelectList = new ArrayList<>();
        chargePointSelectList.add(chargePointSelect);

        SetChargingProfileParams params = new SetChargingProfileParams();
        params.setChargePointSelectList(chargePointSelectList);
        params.setConnectorId(connectorId);
        params.setChargingProfilePk(chargingProfileId);

        client16.setChargingProfile(params);

        return ResponseEntity.ok(new ChargingLimitResponse(true));
    }

    private int addChargingProfile(ChargingLimitRequest request) {
        ChargingProfileForm form = new ChargingProfileForm();
        form.setStackLevel(1);
        form.setChargingProfilePurpose(ChargingProfilePurposeType.TX_PROFILE);
        form.setChargingProfileKind(ChargingProfileKindType.ABSOLUTE);
        form.setChargingRateUnit(request.getUnit().equals("W") ? ChargingRateUnitType.W : ChargingRateUnitType.A);
        form.setStartSchedule(LocalDateTime.now());

        ChargingProfileForm.SchedulePeriod schedulePeriod = new ChargingProfileForm.SchedulePeriod();
        schedulePeriod.setStartPeriodInSeconds(0);
        schedulePeriod.setPowerLimit(BigDecimal.valueOf(request.getLimit()));

        Map<String, ChargingProfileForm.SchedulePeriod> schedulePeriodMap = new HashMap<>();
        schedulePeriodMap.put("first", schedulePeriod);

        form.setSchedulePeriodMap(schedulePeriodMap);

        return chargingProfileRepository.add(form);
    }

    @RequestMapping(value = "/{chargeBoxId}/{connectorId}/transaction", method = RequestMethod.DELETE)
    public ResponseEntity<Boolean> stopActiveTransaction(@PathVariable String chargeBoxId, @PathVariable int connectorId, HttpServletRequest request) {
        List<Integer> activeTransactionIds = transactionRepository.getActiveTransactionIds(chargeBoxId);

        if (activeTransactionIds.isEmpty()) {
            log.info("No active transactions for charge box {} and connector {}", chargeBoxId, connectorId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        List<Transaction> transactions = new ArrayList<>();
        for (Integer activeTransactionId : activeTransactionIds) {
            TransactionDetails details = transactionRepository.getDetails(activeTransactionId);
            transactions.add(details.getTransaction());
        }

        Optional<Transaction> optionalTransaction = transactions
                .stream()
                .filter(transaction -> transaction.getConnectorId() == connectorId)
                .findFirst();

        if (optionalTransaction.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Transaction transaction = optionalTransaction.get();
        log.info("Transaction selected: {}", transaction.getId());

        List<ChargePointSelect> chargePointSelectList = new ArrayList<>();
        ChargePointSelect chargePointSelect = new ChargePointSelect(OcppTransport.JSON, chargeBoxId);
        chargePointSelectList.add(chargePointSelect);

        RemoteStopTransactionParams params = new RemoteStopTransactionParams();
        params.setTransactionId(transaction.getId());
        params.setChargePointSelectList(chargePointSelectList);

        int taskId = client16.remoteStopTransaction(params);

        log.info("[chargeBoxId={}, transactionId={}, taskId={}] Remote stop transaction", chargeBoxId, transaction.getId(), taskId);
        return ResponseEntity.ok(true);
    }

    @RequestMapping(value = "/{chargeBoxId}/{connectorId}/transaction", method = RequestMethod.GET)
    public ResponseEntity<Status> startTransaction(@PathVariable String chargeBoxId, @PathVariable int connectorId) {
        List<ChargePointSelect> chargePointSelectList = new ArrayList<>();
        ChargePointSelect chargePointSelect = new ChargePointSelect(OcppTransport.JSON, chargeBoxId);
        chargePointSelectList.add(chargePointSelect);

        RemoteStartTransactionParams params = new RemoteStartTransactionParams();
        params.setChargePointSelectList(chargePointSelectList);
        params.setConnectorId(connectorId);
        params.setIdTag("999999"); // default id tag on the charge amps box

        int taskId = client16.remoteStartTransaction(params);

        log.debug("[chargeBoxId={}, connectionId={}, idTag={}, taskId={}] Remote start transaction", chargeBoxId, 1, 999999, taskId);
        return ResponseEntity.ok(Status.OK);
    }

    @RequestMapping(value = "/{chargeBoxId}/getChargeBoxOverview", method = RequestMethod.GET)
    public ResponseEntity<ChargePoint.Overview> getChargeBoxOverview(@PathVariable String chargeBoxId) {
        ChargePointQueryForm form = new ChargePointQueryForm();
        form.setChargeBoxId(chargeBoxId);
        List<ChargePoint.Overview> chargeBoxOverview = chargePointRepository.getOverview(form);

        if(chargeBoxOverview.isEmpty()) {
            log.debug("[chargeBoxId={}] Charge box id not found in overview information", chargeBoxId);
            return ResponseEntity.badRequest().body(null);
        }

        return ResponseEntity.ok().body(chargeBoxOverview.get(0));
    }
}
