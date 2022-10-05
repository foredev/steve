package de.rwth.idsg.steve.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.joda.JodaModule;
import de.rwth.idsg.steve.integration.dto.ConnectorStatus;
import de.rwth.idsg.steve.integration.dto.EnergyMeterData;
import de.rwth.idsg.steve.ocpp.ws.JsonObjectMapper;
import de.rwth.idsg.steve.repository.TransactionRepository;
import de.rwth.idsg.steve.repository.dto.Transaction;
import de.rwth.idsg.steve.repository.dto.TransactionDetails;
import de.rwth.idsg.steve.web.dto.TransactionQueryForm;
import ocpp.cs._2015._10.MeterValuesRequest;
import ocpp.cs._2015._10.StartTransactionRequest;
import ocpp.cs._2015._10.StopTransactionRequest;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

public class IntegrationServiceImplTest {

    private IntegrationServiceImpl integrationService;
    private TestMqttService mqttService;

    private TestTransactionRepository transactionRepository;

    @BeforeEach
    public void init() {
        mqttService = new TestMqttService();
        transactionRepository = new TestTransactionRepository();

        integrationService = new IntegrationServiceImpl(mqttService, transactionRepository);
    }

    @Test
    public void easee_meterValues_to_mqtt() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JodaModule());

        JsonObjectMapper objectMapper = JsonObjectMapper.INSTANCE;

        MeterValuesRequest mvr = objectMapper.getMapper().readValue(easeeJsonString, MeterValuesRequest.class);

        integrationService.meterValues("charge-box-1", mvr);

        List<TestMqttService.EnergyMeterCall> energyMeterCalls = mqttService.energyMeterCalls;
        Assertions.assertEquals(3, energyMeterCalls.size());

        TestMqttService.EnergyMeterCall call1 = energyMeterCalls.get(0);
        EnergyMeterData data1 = call1.data;

        Assertions.assertEquals("charge-box-1", call1.chargeBoxId);
        Assertions.assertEquals("1", call1.connector);
        Assertions.assertEquals(0, data1.getCurrent().get(0));
        Assertions.assertEquals(0.010999999940395355, data1.getCurrent().get(1));
        Assertions.assertEquals(0, data1.getCurrent().get(2));


        TestMqttService.EnergyMeterCall call2 = energyMeterCalls.get(1);
        EnergyMeterData data2 = call2.data;

        Assertions.assertEquals("charge-box-1", call2.chargeBoxId);
        Assertions.assertEquals("1", call2.connector);
        Assertions.assertEquals(0, data2.getCurrent().get(0));
        Assertions.assertEquals(0, data2.getCurrent().get(1));
        Assertions.assertEquals(0, data2.getCurrent().get(2));


        TestMqttService.EnergyMeterCall call3 = energyMeterCalls.get(2);
        EnergyMeterData data3 = call3.data;

        Assertions.assertEquals("charge-box-1", call3.chargeBoxId);
        Assertions.assertEquals("1", call3.connector);
        Assertions.assertEquals(14.20199966430664, data3.getCurrent().get(0));
        Assertions.assertEquals(0, data3.getCurrent().get(1));
        Assertions.assertEquals(0, data3.getCurrent().get(2));
    }

    @Test
    public void onStartTransaction_meterStartZero_expectEnergyZero() {
        StartTransactionRequest startTransactionRequest = new StartTransactionRequest();
        startTransactionRequest.setConnectorId(1);
        startTransactionRequest.setMeterStart(0);

        DateTime timestamp = new DateTime(2022, 10, 4, 14, 24, 30);
        startTransactionRequest.setTimestamp(timestamp);

        integrationService.onStartTransaction("charge-box-1", startTransactionRequest);

        List<TestMqttService.EnergyMeterCall> energyMeterCalls = mqttService.energyMeterCalls;

        Assertions.assertEquals(1, energyMeterCalls.size());

        TestMqttService.EnergyMeterCall energyMeterCall = energyMeterCalls.get(0);

        Assertions.assertEquals("charge-box-1", energyMeterCall.chargeBoxId);
        Assertions.assertEquals("1", energyMeterCall.connector);
        Assertions.assertEquals(0, energyMeterCall.data.getEnergy());
        Assertions.assertEquals(0, energyMeterCall.data.getPower());
        Assertions.assertEquals(timestamp.toDate(), energyMeterCall.data.getTimestamp());
    }

    @Test
    public void onStartTransaction_meterStartNonZero_expectEnergyNonZero() {
        StartTransactionRequest startTransactionRequest = new StartTransactionRequest();
        startTransactionRequest.setConnectorId(1);
        startTransactionRequest.setMeterStart(124904);

        DateTime timestamp = new DateTime(2022, 10, 4, 14, 25, 30);
        startTransactionRequest.setTimestamp(timestamp);

        integrationService.onStartTransaction("charge-box-2", startTransactionRequest);

        List<TestMqttService.EnergyMeterCall> energyMeterCalls = mqttService.energyMeterCalls;

        Assertions.assertEquals(1, energyMeterCalls.size());

        TestMqttService.EnergyMeterCall energyMeterCall = energyMeterCalls.get(0);

        Assertions.assertEquals("charge-box-2", energyMeterCall.chargeBoxId);
        Assertions.assertEquals("1", energyMeterCall.connector);
        Assertions.assertEquals(124904, energyMeterCall.data.getEnergy());
        Assertions.assertEquals(0, energyMeterCall.data.getPower());
        Assertions.assertEquals(timestamp.toDate(), energyMeterCall.data.getTimestamp());
    }

    @Test
    public void onStopTransaction() {
        Transaction transaction = Transaction.builder().id(111).connectorId(1).build();
        transactionRepository.transactionDetails = new TransactionDetails(transaction, new ArrayList<>(), null);

        StopTransactionRequest stopTransactionRequest = new StopTransactionRequest();
        stopTransactionRequest.setTransactionId(111);
        stopTransactionRequest.setMeterStop(128971);

        DateTime timestamp = new DateTime(2022, 10, 4, 14, 26, 30);
        stopTransactionRequest.setTimestamp(timestamp);

        integrationService.onStopTransaction("charge-box-3", stopTransactionRequest);

        List<TestMqttService.EnergyMeterCall> energyMeterCalls = mqttService.energyMeterCalls;

        Assertions.assertEquals(1, energyMeterCalls.size());

        TestMqttService.EnergyMeterCall energyMeterCall = energyMeterCalls.get(0);

        Assertions.assertEquals("charge-box-3", energyMeterCall.chargeBoxId);
        Assertions.assertEquals("1", energyMeterCall.connector);
        Assertions.assertEquals(128971, energyMeterCall.data.getEnergy());
        Assertions.assertEquals(0, energyMeterCall.data.getPower());
        Assertions.assertEquals(timestamp.toDate(), energyMeterCall.data.getTimestamp());
    }

    private final String easeeJsonString = "{\n" +
            "  \"connectorId\": 1,\n" +
            "  \"transactionId\": 5226,\n" +
            "  \"meterValue\": [\n" +
            "    {\n" +
            "      \"timestamp\": \"2022-09-29T09:26:51.0000000Z\",\n" +
            "      \"sampledValue\": [\n" +
            "        {\n" +
            "          \"value\": \"0.010999999940395355\",\n" +
            "          \"context\": \"Sample.Periodic\",\n" +
            "          \"format\": \"Raw\",\n" +
            "          \"measurand\": \"Current.Import\",\n" +
            "          \"phase\": \"L2\",\n" +
            "          \"location\": \"Inlet\",\n" +
            "          \"unit\": \"A\"\n" +
            "        }\n" +
            "      ]\n" +
            "    },\n" +
            "    {\n" +
            "      \"timestamp\": \"2022-09-29T09:27:27.0000000Z\",\n" +
            "      \"sampledValue\": [\n" +
            "        {\n" +
            "          \"value\": \"14.20199966430664\",\n" +
            "          \"context\": \"Sample.Periodic\",\n" +
            "          \"format\": \"Raw\",\n" +
            "          \"measurand\": \"Current.Import\",\n" +
            "          \"phase\": \"N\",\n" +
            "          \"location\": \"Inlet\",\n" +
            "          \"unit\": \"A\"\n" +
            "        }\n" +
            "      ]\n" +
            "    },\n" +
            "    {\n" +
            "      \"timestamp\": \"2022-09-29T09:27:27.0000000Z\",\n" +
            "      \"sampledValue\": [\n" +
            "        {\n" +
            "          \"value\": \"14.20199966430664\",\n" +
            "          \"context\": \"Sample.Periodic\",\n" +
            "          \"format\": \"Raw\",\n" +
            "          \"measurand\": \"Current.Import\",\n" +
            "          \"phase\": \"L1\",\n" +
            "          \"location\": \"Inlet\",\n" +
            "          \"unit\": \"A\"\n" +
            "        }\n" +
            "      ]\n" +
            "    }\n" +
            "  ]\n" +
            "}\n";

    private static class TestMqttService implements MqttService {

        public List<EnergyMeterCall> energyMeterCalls = new ArrayList<>();

        @Override
        public void publishEnergyMeterData(String chargeBoxId, String connector, EnergyMeterData data) {
            energyMeterCalls.add(new EnergyMeterCall(chargeBoxId, connector, data));
        }

        @Override
        public void publishChargeBoxStatus(String chargeBoxId, String connector, ConnectorStatus status) {

        }

        @Override
        public void sendToMqttBroker(String path, JsonNode payload) {

        }

        private static class EnergyMeterCall {
            public String chargeBoxId;
            public String connector;
            public EnergyMeterData data;

            public EnergyMeterCall(String chargeBoxId, String connector, EnergyMeterData data) {
                this.chargeBoxId = chargeBoxId;
                this.connector = connector;
                this.data = data;
            }
        }
    }

    private static class TestTransactionRepository implements TransactionRepository {

        public TransactionDetails transactionDetails = null;

        @Override
        public List<Transaction> getTransactions(TransactionQueryForm form) {
            return null;
        }

        @Override
        public void writeTransactionsCSV(TransactionQueryForm form, Writer writer) {

        }

        @Override
        public List<Integer> getActiveTransactionIds(String chargeBoxId) {
            return null;
        }

        @Override
        public TransactionDetails getDetails(int transactionPk, boolean firstArrivingMeterValueIfMultiple) {
            return transactionDetails;
        }
    }
}
