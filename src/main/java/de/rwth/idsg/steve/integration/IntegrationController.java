package de.rwth.idsg.steve.integration;

import de.rwth.idsg.steve.SteveException;
import de.rwth.idsg.steve.integration.dto.ChargingLimitRequest;
import de.rwth.idsg.steve.integration.dto.ChargingLimitResponse;
import de.rwth.idsg.steve.integration.dto.ChargingProfileResponse;
import de.rwth.idsg.steve.integration.dto.EnergyMeterData;
import de.rwth.idsg.steve.ocpp.CommunicationTask;
import de.rwth.idsg.steve.ocpp.OcppTransport;
import de.rwth.idsg.steve.ocpp.RequestResult;
import de.rwth.idsg.steve.ocpp.task.GetConfigurationTask;
import de.rwth.idsg.steve.ocpp.task.RemoteStartTransactionTask;
import de.rwth.idsg.steve.repository.ChargePointRepository;
import de.rwth.idsg.steve.repository.ChargingProfileRepository;
import de.rwth.idsg.steve.repository.TaskStore;
import de.rwth.idsg.steve.repository.TransactionRepository;
import de.rwth.idsg.steve.repository.dto.*;
import de.rwth.idsg.steve.service.ChargePointHelperService;
import de.rwth.idsg.steve.service.ChargePointService16_Client;
import de.rwth.idsg.steve.utils.mapper.ChargingProfileDetailsMapper;
import de.rwth.idsg.steve.web.dto.ChargePointQueryForm;
import de.rwth.idsg.steve.web.dto.ChargingProfileAssignmentQueryForm;
import de.rwth.idsg.steve.web.dto.ChargingProfileForm;
import de.rwth.idsg.steve.web.dto.ocpp.*;
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

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Controller
@ResponseBody
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class IntegrationController {

    private final ChargePointRepository chargePointRepository;
    private final ChargingProfileRepository chargingProfileRepository;
    private final ChargePointHelperService chargePointHelperService;
    private final TransactionRepository transactionRepository;
    private final ChargePointService16_Client client16;
    private final MqttService mqttService;
    private final TaskStore taskStore;

    public IntegrationController(ChargePointRepository chargePointRepository, ChargingProfileRepository chargingProfileRepository, ChargePointHelperService chargePointHelperService, TransactionRepository transactionRepository, @Qualifier("ChargePointService16_Client") ChargePointService16_Client client16, MqttService mqttService, TaskStore taskStore) {
        this.chargePointRepository = chargePointRepository;
        this.chargingProfileRepository = chargingProfileRepository;
        this.chargePointHelperService = chargePointHelperService;
        this.transactionRepository = transactionRepository;
        this.client16 = client16;
        this.mqttService = mqttService;
        this.taskStore = taskStore;
    }

    @RequestMapping(value = "/chargepoints/{chargePointId}", method = RequestMethod.POST)
    public ResponseEntity<String> addChargePoint(@PathVariable String chargePointId) {
        Optional<String> registrationStatus = chargePointRepository.getRegistrationStatus(chargePointId);
        if (registrationStatus.isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        chargePointRepository.addChargePointList(Collections.singletonList(chargePointId));
        chargePointHelperService.removeUnknown(chargePointId);
        return ResponseEntity.ok(chargePointId);
    }

    @RequestMapping(value = "/chargepoints/mqtt-test", method = RequestMethod.POST)
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

    @RequestMapping(value = "/chargepoints/{chargeBoxId}/{connectorId}/charginglimit", method = RequestMethod.POST)
    public ResponseEntity<ChargingLimitResponse> setChargingLimit(@PathVariable String chargeBoxId, @PathVariable int connectorId, @RequestBody ChargingLimitRequest request) {
        boolean connected = chargePointHelperService.isConnected(chargeBoxId);
        if (!connected) {
            log.warn("[chargeBoxId={}] Charge box is not connected", chargeBoxId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ChargingLimitResponse(false, "Charge box " + chargeBoxId + " is not connected"));
        }

        List<Integer> activeTransactionIds = transactionRepository.getActiveTransactionIds(chargeBoxId);
        if (activeTransactionIds.isEmpty()) {
            log.warn("[chargeBoxId={}, connectorId={}] No active transaction for chargeBoxId on this connector",chargeBoxId, connectorId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ChargingLimitResponse(false, "No active transaction for chargeBoxId " + chargeBoxId + " on connector " + connectorId));
        }

        int chargingProfileId = addChargingProfile(request);

        int taskId = sendChargingProfile(chargeBoxId, connectorId, chargingProfileId);

        return ResponseEntity.ok(new ChargingLimitResponse(true));
    }

    @RequestMapping(value= "/chargingprofile", method = RequestMethod.POST, consumes = "application/json")
    public ResponseEntity<ChargingProfileForm> createChargingProfile(@RequestBody ChargingProfileForm request) {
        //Null checker on mandatory values
        if(request.getChargingProfileKind() == null ||
            request.getStackLevel() == null ||
            request.getChargingProfilePurpose() == null ||
            request.getChargingRateUnit() == null ||
            request.getSchedulePeriodMap().isEmpty() ||
            !request.isFromToValid() ||
            !request.isStartScheduleValid() ||
            !request.isFromToAndProfileSettingCorrect()) {
                return ResponseEntity.badRequest().body(null);
        }

        int chargingProfileId = chargingProfileRepository.add(request);

        return ResponseEntity.ok(ChargingProfileDetailsMapper.mapToForm(chargingProfileRepository.getDetails(chargingProfileId)));
    }

    @RequestMapping(value = "/chargingprofile/{chargingProfilePk}", method = RequestMethod.GET)
    public ResponseEntity<ChargingProfileForm> getChargingProfile(@PathVariable int chargingProfilePk) {
        ChargingProfile.Details details = chargingProfileRepository.getDetails(chargingProfilePk);
        if(details == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }

        ChargingProfileForm form = ChargingProfileDetailsMapper.mapToForm(details);
        return ResponseEntity.ok(form);
    }

    @RequestMapping(value="/chargingprofile/{chargingProfilePk}", method = RequestMethod.POST, consumes ="application/json")
    public ResponseEntity<ChargingProfileForm> updateChargingProfile(@PathVariable int chargingProfilePk, @RequestBody ChargingProfileForm request) {
        List<String> chargingBoxes = chargingProfileRepository.isChargingProfileUsed(chargingProfilePk);
        List<ChargingProfileAssignment> profileUsage = new ArrayList<>();
        for (String chargingBoxId : chargingBoxes) {
            ChargingProfileAssignmentQueryForm useProfile = new ChargingProfileAssignmentQueryForm();
            useProfile.setChargingProfilePk(chargingProfilePk);
            useProfile.setChargeBoxId(chargingBoxId);
            profileUsage.addAll(chargingProfileRepository.getAssignments(useProfile));
        }
        profileUsage.forEach(entry -> chargingProfileRepository.clearProfile(chargingProfilePk, entry.getChargeBoxId()));

        chargingProfileRepository.update(request);
        for (ChargingProfileAssignment useProfile: profileUsage) {
            chargingProfileRepository.setProfile(useProfile.getChargingProfilePk(), useProfile.getChargeBoxId(), useProfile.getConnectorId());
        }
        ChargingProfile.Details details = chargingProfileRepository.getDetails(chargingProfilePk);
        ChargingProfileForm form = ChargingProfileDetailsMapper.mapToForm(details);
        return ResponseEntity.ok(form);
    }

    @RequestMapping(value="/chargingprofile/{chargeBoxId}/{connectorId}/{chargingProfileId}", method=RequestMethod.DELETE)
    public ResponseEntity<ChargingProfileResponse> clearChargingProfile(@PathVariable String chargeBoxId, @PathVariable int connectorId, @PathVariable int chargingProfileId) {
        boolean connected = chargePointHelperService.isConnected(chargeBoxId);
        if (!connected) {
            log.warn("[chargeBoxId={}] Charge box is not connected", chargeBoxId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ChargingProfileResponse(false, "Charge box " +chargeBoxId + " is not connected"));
        }
        ChargingProfile.Details details = chargingProfileRepository.getDetails(chargingProfileId);

        chargingProfileRepository.clearProfile(chargeBoxId, connectorId,
                ChargingProfilePurposeType.fromValue(details.getProfile().getChargingProfilePurpose()),
                details.getProfile().getStackLevel());
        return ResponseEntity.ok(new ChargingProfileResponse(true, chargingProfileId));
    }

    @RequestMapping(value = "/chargingprofile/{chargeBoxId}/{connectorId}/{chargingProfileId}", method = RequestMethod.GET)
    public ResponseEntity<ChargingProfileResponse> setChargingProfile(@PathVariable String chargeBoxId, @PathVariable int connectorId, @PathVariable int chargingProfileId) {
        boolean connected = chargePointHelperService.isConnected(chargeBoxId);
        if (!connected) {
            log.warn("[chargeBoxId={}] Charge box is not connected", chargeBoxId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ChargingProfileResponse(false, "Charge box " + chargeBoxId + " is not connected"));
        }
        //Check if charging profile is in use for this chargebox ID
        ChargingProfileAssignmentQueryForm useProfile = new ChargingProfileAssignmentQueryForm();
        useProfile.setChargingProfilePk(chargingProfileId);
        useProfile.setChargeBoxId(chargeBoxId);

        //Return OK in both cases, if the charging profile is in use it's still the wanted result.
        if (chargingProfileRepository.getAssignments(useProfile).isEmpty()) {
            int taskId = sendChargingProfile(chargeBoxId, connectorId, chargingProfileId);
            return ResponseEntity.ok(new ChargingProfileResponse(true, chargingProfileId));
        } else {
            return ResponseEntity.ok(new ChargingProfileResponse(false, "Charging profile in use"));
        }

    }

    private int sendChargingProfile(String chargeBoxId, int connectorId, int chargingProfileId) {
        ChargePointSelect chargePointSelect = new ChargePointSelect(OcppTransport.JSON, chargeBoxId);

        List<ChargePointSelect> chargePointSelectList = new ArrayList<>();
        chargePointSelectList.add(chargePointSelect);

        SetChargingProfileParams params = new SetChargingProfileParams();
        params.setChargePointSelectList(chargePointSelectList);
        params.setConnectorId(connectorId);
        params.setChargingProfilePk(chargingProfileId);

        return client16.setChargingProfile(params);

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

    @RequestMapping(value = "/chargepoints/{chargeBoxId}/{connectorId}/transaction", method = RequestMethod.DELETE)
    public ResponseEntity<Integer> stopActiveTransaction(@PathVariable String chargeBoxId, @PathVariable int connectorId) {
        if (chargeBoxId == null || chargeBoxId.isEmpty()) {
            log.warn("[chargeBoxId={}, connectorId={}] chargeBoxId cannot be empty", chargeBoxId, connectorId);
            return ResponseEntity.badRequest().build();
        }

        // What is a sensible upper limit here?
        if (connectorId < 0 || connectorId > 5) {
            log.warn("[chargeBoxId={}, connectorId={}] Invalid connector id", chargeBoxId, connectorId);
            return ResponseEntity.badRequest().build();
        }

        boolean connected = chargePointHelperService.isConnected(chargeBoxId);

        if (!connected) {
            log.warn("[chargeBoxId={}] Charge box not connected", chargeBoxId);
            return ResponseEntity.badRequest().build();
        }

        List<Integer> activeTransactionIds = transactionRepository.getActiveTransactionIds(chargeBoxId);

        if (activeTransactionIds.isEmpty()) {
            log.warn("[chargeBoxId={}] No active transactions for charge box", chargeBoxId);
            return ResponseEntity.badRequest().build();
        }

        List<Transaction> transactions = new ArrayList<>();
        for (Integer activeTransactionId : activeTransactionIds) {
            TransactionDetails details = transactionRepository.getDetailsWithoutMeterValues(activeTransactionId);
            transactions.add(details.getTransaction());
        }

        Optional<Transaction> optionalTransaction = transactions
                .stream()
                .filter(transaction -> (transaction.getConnectorId() == connectorId || connectorId == 0))
                .findFirst();

        if (optionalTransaction.isEmpty()) {
            log.warn("[chargeBoxId={}, connectorId={}] No transaction found", chargeBoxId, connectorId);
            return ResponseEntity.badRequest().build();
        }
        Transaction transaction = optionalTransaction.get();

        List<ChargePointSelect> chargePointSelectList = new ArrayList<>();
        ChargePointSelect chargePointSelect = new ChargePointSelect(OcppTransport.JSON, chargeBoxId);
        chargePointSelectList.add(chargePointSelect);

        RemoteStopTransactionParams params = new RemoteStopTransactionParams();
        params.setTransactionId(transaction.getId());
        params.setChargePointSelectList(chargePointSelectList);

        int taskId = client16.remoteStopTransaction(params);

        log.info("[chargeBoxId={}, connectorId={}, transactionId={}, taskId={}] Remote stop transaction", chargeBoxId, connectorId, transaction.getId(), taskId);
        return ResponseEntity.ok(transaction.getId());
    }

    @RequestMapping(value = "/chargepoints/{chargeBoxId}/transactions/{transactionId}", method = RequestMethod.PUT)
    public ResponseEntity<Boolean> resumeTransaction(@PathVariable String chargeBoxId, @PathVariable int transactionId) {
        if (chargeBoxId == null || chargeBoxId.isEmpty()) {
            log.warn("[chargeBoxId={}] chargeBoxId cannot be empty", chargeBoxId);
            return ResponseEntity.badRequest().build();
        }

        if (transactionId < 0) {
            log.warn("[chargeBoxId={}, transactionId={}] Invalid transaction id", chargeBoxId, transactionId);
            return ResponseEntity.badRequest().build();
        }

        boolean connected = chargePointHelperService.isConnected(chargeBoxId);

        if (!connected) {
            log.warn("[chargeBoxId={}] Charge box not connected", chargeBoxId);
            return ResponseEntity.badRequest().build();
        }

        Transaction transaction;
        try {
            TransactionDetails transactionDetails = transactionRepository.getDetailsWithoutMeterValues(transactionId);
            transaction = transactionDetails.getTransaction();
        } catch (SteveException e) {
            log.warn("[chargeBoxId={}, transactionId={}] Could not find transaction", chargeBoxId, transactionId);
            return ResponseEntity.badRequest().build();
        }

        if (transaction.getStopTimestamp() == null) {
            log.warn("[chargeBoxId={}, transactionId={}] Transaction already active", chargeBoxId, transactionId);
            return ResponseEntity.badRequest().build();
        }

        int connectorId = transaction.getConnectorId();
        String ocppIdTag = transaction.getOcppIdTag();

        List<ChargePointSelect> chargePointSelectList = new ArrayList<>();
        ChargePointSelect chargePointSelect = new ChargePointSelect(OcppTransport.JSON, chargeBoxId);
        chargePointSelectList.add(chargePointSelect);

        RemoteStartTransactionParams params = new RemoteStartTransactionParams();
        params.setChargePointSelectList(chargePointSelectList);
        params.setConnectorId(connectorId);
        params.setIdTag(ocppIdTag);

        int taskId = client16.remoteStartTransaction(params);

        log.info("[chargeBoxId={}, connectorId={}, taskId={}] Remote start transaction", chargeBoxId, connectorId, taskId);
        return ResponseEntity.ok(true);
    }

    @RequestMapping(value = "/chargepoints/{chargeBoxId}/{connectorId}/{tag}/transaction", method = RequestMethod.GET)
    public ResponseEntity<Boolean> startTransaction(@PathVariable String chargeBoxId, @PathVariable int connectorId, @PathVariable String tag) throws InterruptedException {
        List<ChargePointSelect> chargePointSelectList = new ArrayList<>();
        ChargePointSelect chargePointSelect = new ChargePointSelect(OcppTransport.JSON, chargeBoxId);
        boolean connected = chargePointHelperService.isConnected(chargeBoxId);

        if (!connected) {
            log.warn("[chargeBoxId={}] Charge box not connected", chargeBoxId);
            return ResponseEntity.badRequest().body(false);
        }

        List<Integer> activeTransactions = transactionRepository.getActiveTransactionIds(chargeBoxId);

        chargePointSelectList.add(chargePointSelect);
        if (!activeTransactions.isEmpty()) {
            for (Integer transaction : activeTransactions) {
                if (transactionRepository.getDetailsWithoutMeterValues(transaction).getTransaction().getConnectorId() == connectorId) {

                    Transaction stopTransaction = transactionRepository.getDetailsWithoutMeterValues(transaction).getTransaction();
                    RemoteStopTransactionParams params = new RemoteStopTransactionParams();
                    params.setTransactionId(stopTransaction.getId());
                    params.setChargePointSelectList(chargePointSelectList);

                    int taskId = client16.remoteStopTransaction(params);

                    log.warn("[chargeBoxId={}, connectorId={}, transactionId={}] Transaction already active, trying to stop", chargeBoxId, connectorId, transaction);
                    //return ResponseEntity.badRequest().body(false);
                }
            }
        }

        RemoteStartTransactionParams params = new RemoteStartTransactionParams();
        params.setChargePointSelectList(chargePointSelectList);
        params.setConnectorId(connectorId);
        params.setIdTag(tag);

        int taskId = client16.remoteStartTransaction(params);
        Thread.sleep(5000);
        RemoteStartTransactionTask task = (RemoteStartTransactionTask) taskStore.get(taskId);

        Map<String, RequestResult> resultMap = task.getResultMap();
        RequestResult requestResult = resultMap.get(chargeBoxId);

        String response = requestResult.getResponse();
        String errorMessage = requestResult.getErrorMessage();

        log.info("[chargeBoxId={}, connectorId={}] RemoteStartTransaction response was {}", chargeBoxId, connectorId, response);

        if (response.equals("Accepted")) {
            log.info("[chargeBoxId={}, connectorId={}] Transaction started", chargeBoxId, connectorId);
            return ResponseEntity.ok(null);
        } else if (errorMessage != null) {
            log.warn("[chargeBoxId={}, connectorId={}] Failed to start transaction with error {}", chargeBoxId, connectorId, errorMessage);
            return ResponseEntity.badRequest().build();
        } else {
            log.warn("[chargeBoxId={}, connectorId={}] Charge box rejected remote start transaction request", chargeBoxId, connectorId);
            return ResponseEntity.badRequest().build();
        }
    }

    @RequestMapping(value = "/chargepoints/{chargeBoxId}", method = RequestMethod.GET)
    public ResponseEntity<ChargeBoxDetails.Overview> getChargeBoxOverview(@PathVariable String chargeBoxId) {
        ChargePointQueryForm form = new ChargePointQueryForm();
        form.setChargeBoxId(chargeBoxId);
        List<ChargeBoxDetails.Overview> chargeBoxOverview = chargePointRepository.getChargeBoxDetails(form);

        if (chargeBoxOverview.isEmpty()) {
            log.debug("[chargeBoxId={}] Charge box id not found in overview information", chargeBoxId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }
        return ResponseEntity.ok().body(chargeBoxOverview.get(0));
    }

   @RequestMapping(value="/chargepoints/{chargeBoxId}/configuration", method = RequestMethod.GET)
    public ResponseEntity<List<GetConfigurationTask.KeyValue>> getChargeBoxConfiguration(@PathVariable String chargeBoxId) throws InterruptedException {
        boolean connected = chargePointHelperService.isConnected(chargeBoxId);
        if (!connected) {
            log.warn("[chargeBoxId={}] Chargebox is not connected", chargeBoxId);
            return ResponseEntity.badRequest().body(null);
        }
        GetConfigurationParams params = new GetConfigurationParams();
        List<ChargePointSelect> chargePointList = new ArrayList<>();

        chargePointList.add(new ChargePointSelect(OcppTransport.JSON, chargeBoxId));
        params.setChargePointSelectList(chargePointList);
        int taskId = client16.getConfiguration(params);
        Thread.sleep(5000);
        CommunicationTask task = taskStore.get(taskId);
        return ResponseEntity.ok(((GetConfigurationTask.ResponseWrapper)((RequestResult)task.getResultMap().get(chargeBoxId)).getDetails()).getConfigurationKeys());
    }

    @RequestMapping(value="/chargingprofile/{chargingProfilePk}", method = RequestMethod.DELETE)
    public ResponseEntity<Boolean> deleteChargingProfile(@PathVariable int chargingProfilePk) {
        List<String> chargingPoints = chargingProfileRepository.isChargingProfileUsed(chargingProfilePk);
        if (!chargingPoints.isEmpty()) {
            for (String chargingBoxId : chargingPoints) {
                chargingProfileRepository.clearProfile(chargingProfilePk,chargingBoxId);
            }
        }
        chargingProfileRepository.delete(chargingProfilePk);
        return ResponseEntity.ok(true);
    }

    @RequestMapping(value = "/chargepoints/{chargeBoxId}/status", method = RequestMethod.GET)
    public ResponseEntity<Boolean> triggerStatusUpdate(@PathVariable String chargeBoxId) {
        boolean connected = chargePointHelperService.isConnected(chargeBoxId);
        if (!connected) {
            log.warn("[chargeBoxId={}] Chargebox is not connected", chargeBoxId);
            return ResponseEntity.badRequest().body(false);
        }
        List<ChargePointSelect> chargePointSelectList = new ArrayList<>();
        TriggerMessageParams message = new TriggerMessageParams();
        ChargePointSelect chargePointSelect = new ChargePointSelect(OcppTransport.JSON, chargeBoxId);
        chargePointSelectList.add(chargePointSelect);

        message.setTriggerMessage(TriggerMessageEnum.StatusNotification);
        message.setChargePointSelectList(chargePointSelectList);

        client16.triggerMessage(message);
        log.info("[chargeBoxId={}] trigger status message " + message.getTriggerMessage().value() + " to connector " + message.getConnectorId(), chargeBoxId);
        return ResponseEntity.ok(true);
    }

    @RequestMapping(value="/chargepoints/{chargeBoxId}/changeConfiguration", method = RequestMethod.POST, consumes ="application/json")
    public ResponseEntity<Boolean> changeChargeBoxConfiguration(@PathVariable String chargeBoxId, @RequestBody List<ChangeConfigurationParams> configurations) throws InterruptedException {
        boolean connected = chargePointHelperService.isConnected(chargeBoxId);
        if(!connected) {
            log.warn("[chargeBoxId={}] Chargebox is not connected", chargeBoxId);
            return  ResponseEntity.badRequest().body(false);
        }
        int taskId = 0;
        List<ChargePointSelect> chargePointSelectList = new ArrayList<>();
        chargePointSelectList.add(new ChargePointSelect(OcppTransport.JSON, chargeBoxId));
        for(ChangeConfigurationParams confParam : configurations) {
            confParam.setChargePointSelectList(chargePointSelectList);
            taskId = client16.changeConfiguration(confParam);
        }
        Thread.sleep(5000);
        return ResponseEntity.ok(((RequestResult)taskStore.get(taskId).getResultMap().get(chargeBoxId)).getResponse().equals("Accepted") ? true : false);
    }

}
