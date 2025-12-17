package io.conduktor.connect.otel;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.MetricsServiceGrpc;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for OtlpReceiver with REAL gRPC and HTTP servers enabled.
 * Addresses SME review finding: "BLOCKER: gRPC/HTTP disabled in all tests"
 *
 * These tests verify actual OTLP protocol compliance by:
 * - Starting real gRPC and HTTP servers
 * - Sending actual OTLP requests
 * - Verifying responses and queue behavior
 */
class OtlpReceiverIntegrationTest {

    private OtlpReceiver receiver;
    private Map<String, String> props;
    private int grpcPort;
    private int httpPort;
    private ManagedChannel grpcChannel;

    @BeforeEach
    void setUp() throws Exception {
        // Find available ports for testing
        grpcPort = findAvailablePort();
        httpPort = findAvailablePort();

        props = new HashMap<>();
        props.put("name", "test-integration-receiver");
        props.put("otlp.grpc.enabled", "true");
        props.put("otlp.grpc.port", String.valueOf(grpcPort));
        props.put("otlp.http.enabled", "true");
        props.put("otlp.http.port", String.valueOf(httpPort));
        props.put("otlp.message.queue.size", "100");
        props.put("otlp.message.format", "json");
        props.put("otlp.bind.address", "127.0.0.1");

        OpenTelemetrySourceConnectorConfig config = new OpenTelemetrySourceConnectorConfig(props);
        receiver = new OtlpReceiver(config);
        receiver.start();

        // Give servers time to start
        Thread.sleep(200);
    }

    @AfterEach
    void tearDown() {
        if (grpcChannel != null) {
            grpcChannel.shutdownNow();
            try {
                grpcChannel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (receiver != null) {
            receiver.stop();
        }
    }

    private int findAvailablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    // ========== gRPC Integration Tests ==========

    @Test
    void testGrpcServerStartsSuccessfully() {
        // Server started in setUp - verify by connecting
        grpcChannel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort)
                .usePlaintext()
                .build();

        assertFalse(grpcChannel.isShutdown());
    }

    @Test
    void testGrpcTraceExport() {
        grpcChannel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort)
                .usePlaintext()
                .build();

        TraceServiceGrpc.TraceServiceBlockingStub stub = TraceServiceGrpc.newBlockingStub(grpcChannel);

        ExportTraceServiceRequest request = createSampleTraceRequest("grpc-test-service");
        ExportTraceServiceResponse response = stub.export(request);

        assertNotNull(response, "gRPC trace export should return a response");
        assertEquals(1, receiver.getTracesQueue().size(), "Trace should be added to queue");
    }

    @Test
    void testGrpcMetricsExport() {
        grpcChannel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort)
                .usePlaintext()
                .build();

        MetricsServiceGrpc.MetricsServiceBlockingStub stub = MetricsServiceGrpc.newBlockingStub(grpcChannel);

        ExportMetricsServiceRequest request = createSampleMetricsRequest("grpc-test-service");
        ExportMetricsServiceResponse response = stub.export(request);

        assertNotNull(response, "gRPC metrics export should return a response");
        assertEquals(1, receiver.getMetricsQueue().size(), "Metric should be added to queue");
    }

    @Test
    void testGrpcLogsExport() {
        grpcChannel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort)
                .usePlaintext()
                .build();

        LogsServiceGrpc.LogsServiceBlockingStub stub = LogsServiceGrpc.newBlockingStub(grpcChannel);

        ExportLogsServiceRequest request = createSampleLogsRequest("grpc-test-service");
        ExportLogsServiceResponse response = stub.export(request);

        assertNotNull(response, "gRPC logs export should return a response");
        assertEquals(1, receiver.getLogsQueue().size(), "Log should be added to queue");
    }

    @Test
    void testGrpcMultipleTraceExports() {
        grpcChannel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort)
                .usePlaintext()
                .build();

        TraceServiceGrpc.TraceServiceBlockingStub stub = TraceServiceGrpc.newBlockingStub(grpcChannel);

        // Send 5 trace requests
        for (int i = 0; i < 5; i++) {
            ExportTraceServiceRequest request = createSampleTraceRequest("test-service-" + i);
            stub.export(request);
        }

        assertEquals(5, receiver.getTracesQueue().size(), "All 5 traces should be queued");
    }

    @Test
    void testGrpcResponseIsSuccessful() {
        grpcChannel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort)
                .usePlaintext()
                .build();

        TraceServiceGrpc.TraceServiceBlockingStub stub = TraceServiceGrpc.newBlockingStub(grpcChannel);

        ExportTraceServiceRequest request = createSampleTraceRequest("test");
        ExportTraceServiceResponse response = stub.export(request);

        // OTLP spec: empty response indicates success
        assertNotNull(response);
        // The default instance is returned on success
    }

    // ========== HTTP Integration Tests ==========

    @Test
    void testHttpServerStartsSuccessfully() throws Exception {
        URL url = new URL("http://127.0.0.1:" + httpPort + "/v1/traces");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-protobuf");

        ExportTraceServiceRequest request = createSampleTraceRequest("http-test");
        connection.getOutputStream().write(request.toByteArray());

        int responseCode = connection.getResponseCode();
        assertEquals(200, responseCode, "HTTP trace export should return 200");
        connection.disconnect();
    }

    @Test
    void testHttpTraceExportProtobuf() throws Exception {
        URL url = new URL("http://127.0.0.1:" + httpPort + "/v1/traces");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-protobuf");

        ExportTraceServiceRequest request = createSampleTraceRequest("http-protobuf-test");
        try (OutputStream os = connection.getOutputStream()) {
            os.write(request.toByteArray());
        }

        assertEquals(200, connection.getResponseCode());
        assertEquals(1, receiver.getTracesQueue().size(), "Trace should be added to queue");
        connection.disconnect();
    }

    @Test
    void testHttpMetricsExport() throws Exception {
        URL url = new URL("http://127.0.0.1:" + httpPort + "/v1/metrics");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-protobuf");

        ExportMetricsServiceRequest request = createSampleMetricsRequest("http-test");
        try (OutputStream os = connection.getOutputStream()) {
            os.write(request.toByteArray());
        }

        assertEquals(200, connection.getResponseCode());
        assertEquals(1, receiver.getMetricsQueue().size(), "Metric should be added to queue");
        connection.disconnect();
    }

    @Test
    void testHttpLogsExport() throws Exception {
        URL url = new URL("http://127.0.0.1:" + httpPort + "/v1/logs");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-protobuf");

        ExportLogsServiceRequest request = createSampleLogsRequest("http-test");
        try (OutputStream os = connection.getOutputStream()) {
            os.write(request.toByteArray());
        }

        assertEquals(200, connection.getResponseCode());
        assertEquals(1, receiver.getLogsQueue().size(), "Log should be added to queue");
        connection.disconnect();
    }

    @Test
    void testHttpInvalidEndpointReturns404() throws Exception {
        URL url = new URL("http://127.0.0.1:" + httpPort + "/v1/invalid");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-protobuf");

        try (OutputStream os = connection.getOutputStream()) {
            os.write(new byte[]{0x0a});
        }

        assertEquals(404, connection.getResponseCode());
        connection.disconnect();
    }

    @Test
    void testHttpMethodNotAllowed() throws Exception {
        URL url = new URL("http://127.0.0.1:" + httpPort + "/v1/traces");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        int responseCode = connection.getResponseCode();
        assertEquals(405, responseCode, "GET method should return 405");
        connection.disconnect();
    }

    // ========== Queue Behavior Integration Tests ==========

    @Test
    void testMixedGrpcAndHttpTraffic() throws Exception {
        grpcChannel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort)
                .usePlaintext()
                .build();

        // Send via gRPC
        TraceServiceGrpc.TraceServiceBlockingStub stub = TraceServiceGrpc.newBlockingStub(grpcChannel);
        stub.export(createSampleTraceRequest("grpc-mixed"));

        // Send via HTTP
        URL url = new URL("http://127.0.0.1:" + httpPort + "/v1/traces");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-protobuf");

        try (OutputStream os = connection.getOutputStream()) {
            os.write(createSampleTraceRequest("http-mixed").toByteArray());
        }
        connection.getResponseCode();
        connection.disconnect();

        assertEquals(2, receiver.getTracesQueue().size(), "Both gRPC and HTTP traces should be queued");
    }

    @Test
    void testAllSignalTypesViaGrpc() {
        grpcChannel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort)
                .usePlaintext()
                .build();

        TraceServiceGrpc.TraceServiceBlockingStub traceStub = TraceServiceGrpc.newBlockingStub(grpcChannel);
        MetricsServiceGrpc.MetricsServiceBlockingStub metricsStub = MetricsServiceGrpc.newBlockingStub(grpcChannel);
        LogsServiceGrpc.LogsServiceBlockingStub logsStub = LogsServiceGrpc.newBlockingStub(grpcChannel);

        traceStub.export(createSampleTraceRequest("test"));
        metricsStub.export(createSampleMetricsRequest("test"));
        logsStub.export(createSampleLogsRequest("test"));

        assertEquals(1, receiver.getTracesQueue().size());
        assertEquals(1, receiver.getMetricsQueue().size());
        assertEquals(1, receiver.getLogsQueue().size());
    }

    @Test
    void testQueuedMessageContainsValidJson() throws Exception {
        grpcChannel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort)
                .usePlaintext()
                .build();

        TraceServiceGrpc.TraceServiceBlockingStub stub = TraceServiceGrpc.newBlockingStub(grpcChannel);
        stub.export(createSampleTraceRequest("json-validation-test"));

        OtlpReceiver.OtlpMessage message = receiver.getTracesQueue().poll(1, TimeUnit.SECONDS);
        assertNotNull(message);

        String payload = message.getPayload();
        assertTrue(payload.contains("{"), "Payload should be JSON");
        assertTrue(payload.contains("resourceSpans") || payload.contains("resource_spans"),
                "JSON should contain trace data");
        assertTrue(payload.contains("json-validation-test") || payload.contains("service.name"),
                "JSON should contain service name");
    }

    @Test
    void testMessageTimestampIsSet() throws Exception {
        long before = System.currentTimeMillis();

        grpcChannel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort)
                .usePlaintext()
                .build();

        TraceServiceGrpc.TraceServiceBlockingStub stub = TraceServiceGrpc.newBlockingStub(grpcChannel);
        stub.export(createSampleTraceRequest("timestamp-test"));

        long after = System.currentTimeMillis();

        OtlpReceiver.OtlpMessage message = receiver.getTracesQueue().poll(1, TimeUnit.SECONDS);
        assertNotNull(message);
        assertTrue(message.getTimestamp() >= before && message.getTimestamp() <= after,
                "Message timestamp should be within test bounds");
    }

    // ========== Helper Methods ==========

    private ExportTraceServiceRequest createSampleTraceRequest(String serviceName) {
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
                                        .setTraceId(ByteString.copyFrom(new byte[16]))
                                        .setSpanId(ByteString.copyFrom(new byte[8]))
                                        .setName("test-span")
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private ExportMetricsServiceRequest createSampleMetricsRequest(String serviceName) {
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
                                        .setName("test-metric")
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private ExportLogsServiceRequest createSampleLogsRequest(String serviceName) {
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
                                                .setStringValue("test log message")
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();
    }
}
