package io.conduktor.connect.otel;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.MetricsServiceGrpc;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * System integration test that verifies the complete flow:
 * OTLP Client -> Kafka Connect (OTLP Receiver) -> Kafka Topic
 *
 * Uses Testcontainers to spin up:
 * - Kafka (with KRaft)
 * - Kafka Connect with the OpenTelemetry connector
 *
 * The connector runs an OTLP receiver that accepts telemetry data.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OtelConnectorSystemIT {

    private static final String TRACES_TOPIC = "otlp-traces-test";
    private static final String METRICS_TOPIC = "otlp-metrics-test";
    private static final String LOGS_TOPIC = "otlp-logs-test";
    private static final String CONNECTOR_NAME = "otel-system-test-connector";

    private static Network network;

    @Container
    private static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
            .withNetwork(Network.newNetwork())
            .withNetworkAliases("kafka");

    private static GenericContainer<?> kafkaConnect;
    private static HttpClient httpClient;
    private static int otlpGrpcPort;
    private static int otlpHttpPort;

    @BeforeAll
    static void setUpAll() throws Exception {
        network = kafka.getNetwork();
        httpClient = HttpClient.newHttpClient();

        // Find the built JAR
        String jarPath = findConnectorJar();

        // Start Kafka Connect container with our connector
        // The OTLP receiver will listen on exposed ports
        kafkaConnect = new GenericContainer<>(DockerImageName.parse("confluentinc/cp-kafka-connect:7.5.0"))
                .withNetwork(network)
                .withNetworkAliases("kafka-connect")
                .withExposedPorts(8083, 4317, 4318) // REST, gRPC OTLP, HTTP OTLP
                .withEnv("CONNECT_BOOTSTRAP_SERVERS", "kafka:9092")
                .withEnv("CONNECT_REST_PORT", "8083")
                .withEnv("CONNECT_GROUP_ID", "otel-test-group")
                .withEnv("CONNECT_CONFIG_STORAGE_TOPIC", "connect-configs")
                .withEnv("CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR", "1")
                .withEnv("CONNECT_OFFSET_STORAGE_TOPIC", "connect-offsets")
                .withEnv("CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR", "1")
                .withEnv("CONNECT_STATUS_STORAGE_TOPIC", "connect-status")
                .withEnv("CONNECT_STATUS_STORAGE_REPLICATION_FACTOR", "1")
                .withEnv("CONNECT_KEY_CONVERTER", "org.apache.kafka.connect.storage.StringConverter")
                .withEnv("CONNECT_VALUE_CONVERTER", "org.apache.kafka.connect.storage.StringConverter")
                .withEnv("CONNECT_REST_ADVERTISED_HOST_NAME", "kafka-connect")
                .withEnv("CONNECT_PLUGIN_PATH", "/usr/share/java,/usr/share/confluent-hub-components,/connect-plugins")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(jarPath),
                        "/connect-plugins/kafka-connect-opentelemetry/kafka-connect-opentelemetry.jar")
                .waitingFor(Wait.forHttp("/connectors").forPort(8083).withStartupTimeout(Duration.ofMinutes(2)))
                .dependsOn(kafka);

        kafkaConnect.start();

        otlpGrpcPort = kafkaConnect.getMappedPort(4317);
        otlpHttpPort = kafkaConnect.getMappedPort(4318);

        // Wait for Connect to be fully ready
        waitForConnectReady();
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (kafkaConnect != null) {
            try {
                deleteConnector();
            } catch (Exception ignored) {
            }
            kafkaConnect.stop();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Kafka Connect should load the OpenTelemetry connector plugin")
    void testConnectorPluginLoaded() throws Exception {
        String response = httpGet("/connector-plugins");

        assertTrue(response.contains("OpenTelemetrySourceConnector"),
                "OpenTelemetry connector plugin should be loaded. Available plugins: " + response);
    }

    @Test
    @Order(2)
    @DisplayName("Should deploy OpenTelemetry connector successfully")
    void testDeployConnector() throws Exception {
        String config = String.format(
                "{" +
                "\"name\": \"%s\"," +
                "\"config\": {" +
                    "\"connector.class\": \"io.conduktor.connect.otel.OpenTelemetrySourceConnector\"," +
                    "\"tasks.max\": \"1\"," +
                    "\"otlp.grpc.enabled\": \"true\"," +
                    "\"otlp.grpc.port\": \"4317\"," +
                    "\"otlp.http.enabled\": \"true\"," +
                    "\"otlp.http.port\": \"4318\"," +
                    "\"otlp.bind.address\": \"0.0.0.0\"," +
                    "\"kafka.topic.traces\": \"%s\"," +
                    "\"kafka.topic.metrics\": \"%s\"," +
                    "\"kafka.topic.logs\": \"%s\"," +
                    "\"otlp.message.format\": \"json\"," +
                    "\"otlp.message.queue.size\": \"1000\"" +
                "}" +
                "}", CONNECTOR_NAME, TRACES_TOPIC, METRICS_TOPIC, LOGS_TOPIC);

        String response = httpPost("/connectors", config);

        assertTrue(response.contains(CONNECTOR_NAME),
                "Connector should be created. Response: " + response);

        // Wait for connector to start
        waitForConnectorRunning();

        // Give the OTLP receivers time to start
        Thread.sleep(3000);
    }

    @Test
    @Order(3)
    @DisplayName("Should receive traces via OTLP gRPC in Kafka topic")
    void testTracesViaGrpc() throws Exception {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(kafkaConnect.getHost(), otlpGrpcPort)
                .usePlaintext()
                .build();

        try {
            TraceServiceGrpc.TraceServiceBlockingStub stub = TraceServiceGrpc.newBlockingStub(channel);

            // Send test traces
            String testId = UUID.randomUUID().toString().substring(0, 8);
            for (int i = 0; i < 3; i++) {
                ExportTraceServiceRequest request = createTraceRequest("grpc-trace-" + testId + "-" + i);
                stub.export(request);
            }

            // Consume messages from Kafka and verify
            List<String> receivedMessages = consumeMessagesWithFilter(TRACES_TOPIC, testId, 3, Duration.ofSeconds(30));

            assertEquals(3, receivedMessages.size(),
                    "Should receive all trace messages. Received: " + receivedMessages.size());

            // Verify content is valid JSON with trace data
            for (String msg : receivedMessages) {
                assertTrue(msg.contains("resourceSpans") || msg.contains("resource_spans"),
                        "Message should contain trace data: " + msg);
            }
        } finally {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Order(4)
    @DisplayName("Should receive metrics via OTLP gRPC in Kafka topic")
    void testMetricsViaGrpc() throws Exception {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(kafkaConnect.getHost(), otlpGrpcPort)
                .usePlaintext()
                .build();

        try {
            MetricsServiceGrpc.MetricsServiceBlockingStub stub = MetricsServiceGrpc.newBlockingStub(channel);

            String testId = UUID.randomUUID().toString().substring(0, 8);
            for (int i = 0; i < 3; i++) {
                ExportMetricsServiceRequest request = createMetricsRequest("grpc-metric-" + testId + "-" + i);
                stub.export(request);
            }

            List<String> receivedMessages = consumeMessagesWithFilter(METRICS_TOPIC, testId, 3, Duration.ofSeconds(30));

            assertEquals(3, receivedMessages.size(),
                    "Should receive all metric messages. Received: " + receivedMessages.size());
        } finally {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Order(5)
    @DisplayName("Should receive logs via OTLP gRPC in Kafka topic")
    void testLogsViaGrpc() throws Exception {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(kafkaConnect.getHost(), otlpGrpcPort)
                .usePlaintext()
                .build();

        try {
            LogsServiceGrpc.LogsServiceBlockingStub stub = LogsServiceGrpc.newBlockingStub(channel);

            String testId = UUID.randomUUID().toString().substring(0, 8);
            for (int i = 0; i < 3; i++) {
                ExportLogsServiceRequest request = createLogsRequest("grpc-log-" + testId + "-" + i);
                stub.export(request);
            }

            List<String> receivedMessages = consumeMessagesWithFilter(LOGS_TOPIC, testId, 3, Duration.ofSeconds(30));

            assertEquals(3, receivedMessages.size(),
                    "Should receive all log messages. Received: " + receivedMessages.size());
        } finally {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Order(6)
    @DisplayName("Should receive traces via OTLP HTTP in Kafka topic")
    void testTracesViaHttp() throws Exception {
        String testId = UUID.randomUUID().toString().substring(0, 8);

        for (int i = 0; i < 3; i++) {
            ExportTraceServiceRequest request = createTraceRequest("http-trace-" + testId + "-" + i);

            URL url = new URL("http://" + kafkaConnect.getHost() + ":" + otlpHttpPort + "/v1/traces");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-protobuf");

            try (OutputStream os = connection.getOutputStream()) {
                os.write(request.toByteArray());
            }

            assertEquals(200, connection.getResponseCode(), "HTTP request should succeed");
            connection.disconnect();
        }

        List<String> receivedMessages = consumeMessagesWithFilter(TRACES_TOPIC, testId, 3, Duration.ofSeconds(30));

        assertEquals(3, receivedMessages.size(),
                "Should receive all HTTP trace messages. Received: " + receivedMessages.size());
    }

    @Test
    @Order(7)
    @DisplayName("Should handle high throughput via OTLP gRPC")
    void testHighThroughput() throws Exception {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(kafkaConnect.getHost(), otlpGrpcPort)
                .usePlaintext()
                .build();

        try {
            TraceServiceGrpc.TraceServiceBlockingStub stub = TraceServiceGrpc.newBlockingStub(channel);

            int messageCount = 50;
            String testId = UUID.randomUUID().toString().substring(0, 8);

            for (int i = 0; i < messageCount; i++) {
                ExportTraceServiceRequest request = createTraceRequest("throughput-" + testId + "-" + i);
                stub.export(request);
            }

            List<String> receivedMessages = consumeMessagesWithFilter(TRACES_TOPIC, testId, messageCount, Duration.ofSeconds(60));

            assertEquals(messageCount, receivedMessages.size(),
                    "Should receive all " + messageCount + " messages. Received: " + receivedMessages.size());
        } finally {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Order(8)
    @DisplayName("Connector status should show RUNNING")
    void testConnectorStatus() throws Exception {
        String response = httpGet("/connectors/" + CONNECTOR_NAME + "/status");

        assertTrue(response.contains("\"state\":\"RUNNING\""),
                "Connector should be in RUNNING state. Status: " + response);
    }

    // --- Helper methods ---

    private static String findConnectorJar() {
        java.io.File targetDir = new java.io.File("target");
        java.io.File[] jars = targetDir.listFiles((dir, name) ->
                name.startsWith("kafka-connect-opentelemetry") && name.endsWith("-jar-with-dependencies.jar"));

        if (jars == null || jars.length == 0) {
            throw new IllegalStateException(
                    "Connector JAR not found in target/. Run 'mvn package' first.");
        }
        return jars[0].getAbsolutePath();
    }

    private static void waitForConnectReady() throws Exception {
        int maxAttempts = 60;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                String response = httpGet("/");
                if (response.contains("version")) {
                    return;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Kafka Connect did not become ready in time");
    }

    private static void waitForConnectorRunning() throws Exception {
        int maxAttempts = 30;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                String response = httpGet("/connectors/" + CONNECTOR_NAME + "/status");
                if (response.contains("\"state\":\"RUNNING\"")) {
                    return;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(1000);
        }

        String finalStatus = httpGet("/connectors/" + CONNECTOR_NAME + "/status");
        throw new IllegalStateException("Connector did not reach RUNNING state. Status: " + finalStatus);
    }

    private static void deleteConnector() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getConnectUrl() + "/connectors/" + CONNECTOR_NAME))
                .DELETE()
                .build();
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String httpGet(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getConnectUrl() + path))
                .GET()
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private static String httpPost(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getConnectUrl() + path))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private static String getConnectUrl() {
        return "http://" + kafkaConnect.getHost() + ":" + kafkaConnect.getMappedPort(8083);
    }

    private List<String> consumeMessagesWithFilter(String topic, String filter, int expectedCount, Duration timeout) {
        List<String> messages = new CopyOnWriteArrayList<>();

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "system-test-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));

            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (messages.size() < expectedCount && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    String value = record.value();
                    if (filter == null || value.contains(filter)) {
                        messages.add(value);
                    }
                }
            }
        }

        return messages;
    }

    private ExportTraceServiceRequest createTraceRequest(String serviceName) {
        return ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(KeyValue.newBuilder()
                                        .setKey("service.name")
                                        .setValue(AnyValue.newBuilder()
                                                .setStringValue(serviceName)
                                                .build())
                                        .build())
                                .build())
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(Span.newBuilder()
                                        .setTraceId(ByteString.copyFrom(UUID.randomUUID().toString().getBytes(), 0, 16))
                                        .setSpanId(ByteString.copyFrom(UUID.randomUUID().toString().getBytes(), 0, 8))
                                        .setName("test-span-" + serviceName)
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private ExportMetricsServiceRequest createMetricsRequest(String serviceName) {
        return ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(KeyValue.newBuilder()
                                        .setKey("service.name")
                                        .setValue(AnyValue.newBuilder()
                                                .setStringValue(serviceName)
                                                .build())
                                        .build())
                                .build())
                        .addScopeMetrics(ScopeMetrics.newBuilder()
                                .addMetrics(Metric.newBuilder()
                                        .setName("test-metric-" + serviceName)
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private ExportLogsServiceRequest createLogsRequest(String serviceName) {
        return ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(ResourceLogs.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(KeyValue.newBuilder()
                                        .setKey("service.name")
                                        .setValue(AnyValue.newBuilder()
                                                .setStringValue(serviceName)
                                                .build())
                                        .build())
                                .build())
                        .addScopeLogs(ScopeLogs.newBuilder()
                                .addLogRecords(LogRecord.newBuilder()
                                        .setBody(AnyValue.newBuilder()
                                                .setStringValue("Test log message for " + serviceName)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();
    }
}
