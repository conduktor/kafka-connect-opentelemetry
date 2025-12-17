package io.conduktor.connect.otel;

import com.google.protobuf.ByteString;
import io.conduktor.connect.otel.OtlpReceiver.OtlpMessage;
import io.conduktor.connect.otel.OtlpReceiver.OtlpSignalType;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
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

import javax.management.JMException;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OtlpReceiverTest {

    private OtlpReceiver receiver;
    private Map<String, String> props;
    private OpenTelemetrySourceConnectorConfig config;

    @BeforeEach
    void setUp() {
        props = new HashMap<>();
        props.put("name", "test-receiver");
        props.put("otlp.grpc.enabled", "false"); // Disable to avoid port binding
        props.put("otlp.http.enabled", "false");  // Disable to avoid port binding
        props.put("otlp.message.queue.size", "100");
        props.put("otlp.message.format", "json");

        config = new OpenTelemetrySourceConnectorConfig(props);
        receiver = new OtlpReceiver(config);
    }

    @AfterEach
    void tearDown() {
        if (receiver != null) {
            try {
                receiver.stop();
            } catch (Exception e) {
                // Ignore cleanup exceptions
            }
        }
    }

    @Test
    void testReceiverInitialization() {
        assertNotNull(receiver);
        assertNotNull(receiver.getTracesQueue());
        assertNotNull(receiver.getMetricsQueue());
        assertNotNull(receiver.getLogsQueue());
    }

    @Test
    void testQueueSeparationForSignalTypes() {
        BlockingQueue<OtlpMessage> tracesQueue = receiver.getTracesQueue();
        BlockingQueue<OtlpMessage> metricsQueue = receiver.getMetricsQueue();
        BlockingQueue<OtlpMessage> logsQueue = receiver.getLogsQueue();

        // Verify queues are separate instances
        assertNotSame(tracesQueue, metricsQueue);
        assertNotSame(tracesQueue, logsQueue);
        assertNotSame(metricsQueue, logsQueue);
    }

    @Test
    void testQueueCapacityRespected() {
        // Queues should have the configured capacity
        assertEquals(100, receiver.getTracesQueue().remainingCapacity() + receiver.getTracesQueue().size());
        assertEquals(100, receiver.getMetricsQueue().remainingCapacity() + receiver.getMetricsQueue().size());
        assertEquals(100, receiver.getLogsQueue().remainingCapacity() + receiver.getLogsQueue().size());
    }

    @Test
    void testEmptyQueuesInitially() {
        assertTrue(receiver.getTracesQueue().isEmpty());
        assertTrue(receiver.getMetricsQueue().isEmpty());
        assertTrue(receiver.getLogsQueue().isEmpty());
    }

    @Test
    void testOtlpMessageCreation() {
        String payload = "{\"test\":\"data\"}";
        long beforeCreate = System.currentTimeMillis();
        OtlpMessage message = new OtlpMessage(OtlpSignalType.TRACES, payload);
        long afterCreate = System.currentTimeMillis();

        assertNotNull(message);
        assertEquals(OtlpSignalType.TRACES, message.getSignalType());
        assertEquals(payload, message.getPayload());
        assertTrue(message.getTimestamp() >= beforeCreate && message.getTimestamp() <= afterCreate);
    }

    @Test
    void testOtlpMessageForAllSignalTypes() {
        String payload = "test-payload";

        OtlpMessage traceMsg = new OtlpMessage(OtlpSignalType.TRACES, payload);
        assertEquals(OtlpSignalType.TRACES, traceMsg.getSignalType());

        OtlpMessage metricMsg = new OtlpMessage(OtlpSignalType.METRICS, payload);
        assertEquals(OtlpSignalType.METRICS, metricMsg.getSignalType());

        OtlpMessage logMsg = new OtlpMessage(OtlpSignalType.LOGS, payload);
        assertEquals(OtlpSignalType.LOGS, logMsg.getSignalType());
    }

    @Test
    void testQueueOperationsOffer() {
        OtlpMessage message = new OtlpMessage(OtlpSignalType.TRACES, "test");

        boolean added = receiver.getTracesQueue().offer(message);
        assertTrue(added);
        assertEquals(1, receiver.getTracesQueue().size());
    }

    @Test
    void testQueueOperationsPoll() throws InterruptedException {
        OtlpMessage message = new OtlpMessage(OtlpSignalType.METRICS, "test");
        receiver.getMetricsQueue().offer(message);

        OtlpMessage polled = receiver.getMetricsQueue().poll(100, TimeUnit.MILLISECONDS);
        assertNotNull(polled);
        assertEquals("test", polled.getPayload());
        assertEquals(OtlpSignalType.METRICS, polled.getSignalType());
        assertTrue(receiver.getMetricsQueue().isEmpty());
    }

    @Test
    void testQueueOperationsDrainTo() {
        // Add multiple messages
        for (int i = 0; i < 5; i++) {
            receiver.getLogsQueue().offer(new OtlpMessage(OtlpSignalType.LOGS, "log-" + i));
        }

        List<OtlpMessage> drained = new ArrayList<>();
        int drainedCount = receiver.getLogsQueue().drainTo(drained);

        assertEquals(5, drainedCount);
        assertEquals(5, drained.size());
        assertTrue(receiver.getLogsQueue().isEmpty());
    }

    @Test
    void testQueueOverflowHandling() {
        // Fill the queue to capacity (100)
        for (int i = 0; i < 100; i++) {
            boolean added = receiver.getTracesQueue().offer(
                    new OtlpMessage(OtlpSignalType.TRACES, "trace-" + i));
            assertTrue(added, "Should be able to add message " + i);
        }

        // Try to add one more - should fail
        boolean overflow = receiver.getTracesQueue().offer(
                new OtlpMessage(OtlpSignalType.TRACES, "overflow"));
        assertFalse(overflow, "Queue should reject message when full");
        assertEquals(100, receiver.getTracesQueue().size());
    }

    @Test
    void testMultipleMessagesInDifferentQueues() {
        receiver.getTracesQueue().offer(new OtlpMessage(OtlpSignalType.TRACES, "trace1"));
        receiver.getTracesQueue().offer(new OtlpMessage(OtlpSignalType.TRACES, "trace2"));
        receiver.getMetricsQueue().offer(new OtlpMessage(OtlpSignalType.METRICS, "metric1"));
        receiver.getLogsQueue().offer(new OtlpMessage(OtlpSignalType.LOGS, "log1"));
        receiver.getLogsQueue().offer(new OtlpMessage(OtlpSignalType.LOGS, "log2"));
        receiver.getLogsQueue().offer(new OtlpMessage(OtlpSignalType.LOGS, "log3"));

        assertEquals(2, receiver.getTracesQueue().size());
        assertEquals(1, receiver.getMetricsQueue().size());
        assertEquals(3, receiver.getLogsQueue().size());
    }

    @Test
    void testJsonMessageFormat() throws Exception {
        // Test with JSON format configuration
        props.put("otlp.message.format", "json");
        config = new OpenTelemetrySourceConnectorConfig(props);
        receiver = new OtlpReceiver(config);

        // Access the convertToFormat method via reflection
        ExportTraceServiceRequest request = createSampleTraceRequest();
        String result = invokeConvertToFormat(receiver, request);

        assertNotNull(result);
        assertTrue(result.contains("{"), "JSON format should contain braces");
        assertTrue(result.contains("resourceSpans") || result.contains("resource_spans"),
                "JSON should contain trace data");
    }

    @Test
    void testProtobufMessageFormat() throws Exception {
        // Test with protobuf format configuration
        props.put("otlp.message.format", "protobuf");
        config = new OpenTelemetrySourceConnectorConfig(props);
        receiver = new OtlpReceiver(config);

        ExportTraceServiceRequest request = createSampleTraceRequest();
        String result = invokeConvertToFormat(receiver, request);

        assertNotNull(result);
        // Protobuf format should be base64 encoded
        assertFalse(result.contains("{"), "Protobuf format should not contain JSON braces");
        assertTrue(result.matches("^[A-Za-z0-9+/]*={0,2}$"), "Should be valid base64");
    }

    @Test
    void testMetricsIntegration() throws JMException {
        OpenTelemetryMetrics metrics = new OpenTelemetryMetrics("test-receiver");
        receiver.setMetrics(metrics);

        // Verify metrics are set (we'll test actual metric updates in integration scenarios)
        assertDoesNotThrow(() -> receiver.setMetrics(metrics));

        metrics.close();
    }

    @Test
    void testStopWithEmptyQueues() {
        assertDoesNotThrow(() -> receiver.stop());
    }

    @Test
    void testStopWithMessagesInQueues() {
        // Add some messages
        receiver.getTracesQueue().offer(new OtlpMessage(OtlpSignalType.TRACES, "trace"));
        receiver.getMetricsQueue().offer(new OtlpMessage(OtlpSignalType.METRICS, "metric"));
        receiver.getLogsQueue().offer(new OtlpMessage(OtlpSignalType.LOGS, "log"));

        // Stop should not throw even with messages present
        assertDoesNotThrow(() -> receiver.stop());
    }

    @Test
    void testStartStopLifecycle() throws Exception {
        // Note: We don't actually start the servers (grpc/http disabled)
        // but we can test the lifecycle methods don't throw
        assertDoesNotThrow(() -> receiver.start());
        assertDoesNotThrow(() -> receiver.stop());
    }

    @Test
    void testMultipleStopCalls() throws Exception {
        receiver.start();
        receiver.stop();

        // Second stop should not throw
        assertDoesNotThrow(() -> receiver.stop());
    }

    @Test
    void testQueueSizeConfiguration() {
        props.put("otlp.message.queue.size", "500");
        config = new OpenTelemetrySourceConnectorConfig(props);
        OtlpReceiver largeQueueReceiver = new OtlpReceiver(config);

        // Should be able to add 500 messages
        for (int i = 0; i < 500; i++) {
            assertTrue(largeQueueReceiver.getTracesQueue().offer(
                    new OtlpMessage(OtlpSignalType.TRACES, "trace-" + i)));
        }

        assertEquals(500, largeQueueReceiver.getTracesQueue().size());

        // 501st should fail
        assertFalse(largeQueueReceiver.getTracesQueue().offer(
                new OtlpMessage(OtlpSignalType.TRACES, "overflow")));

        largeQueueReceiver.stop();
    }

    @Test
    void testMessageTimestampAccuracy() throws InterruptedException {
        long before = System.currentTimeMillis();
        Thread.sleep(10); // Small delay to ensure timestamp difference
        OtlpMessage msg1 = new OtlpMessage(OtlpSignalType.TRACES, "msg1");
        Thread.sleep(10);
        OtlpMessage msg2 = new OtlpMessage(OtlpSignalType.TRACES, "msg2");
        Thread.sleep(10);
        long after = System.currentTimeMillis();

        assertTrue(msg1.getTimestamp() >= before);
        assertTrue(msg2.getTimestamp() <= after);
        assertTrue(msg2.getTimestamp() >= msg1.getTimestamp(),
                "Later message should have later or equal timestamp");
    }

    @Test
    void testConcurrentQueueAccess() throws InterruptedException {
        final int numThreads = 5;
        final int messagesPerThread = 10;
        List<Thread> threads = new ArrayList<>();

        // Create threads that add messages concurrently
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            Thread thread = new Thread(() -> {
                for (int j = 0; j < messagesPerThread; j++) {
                    receiver.getTracesQueue().offer(
                            new OtlpMessage(OtlpSignalType.TRACES,
                                    "thread-" + threadId + "-msg-" + j));
                }
            });
            threads.add(thread);
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join(5000);
        }

        // Verify all messages were added
        assertEquals(numThreads * messagesPerThread, receiver.getTracesQueue().size());
    }

    @Test
    void testQueueRemainingCapacity() {
        assertEquals(100, receiver.getTracesQueue().remainingCapacity());

        // Add 30 messages
        for (int i = 0; i < 30; i++) {
            receiver.getTracesQueue().offer(new OtlpMessage(OtlpSignalType.TRACES, "msg-" + i));
        }

        assertEquals(70, receiver.getTracesQueue().remainingCapacity());
        assertEquals(30, receiver.getTracesQueue().size());
    }

    @Test
    void testJsonFormatContainsExpectedFields() throws Exception {
        props.put("otlp.message.format", "json");
        config = new OpenTelemetrySourceConnectorConfig(props);
        receiver = new OtlpReceiver(config);

        ExportTraceServiceRequest request = createSampleTraceRequest();
        String json = invokeConvertToFormat(receiver, request);

        assertNotNull(json);
        // JSON should contain key OpenTelemetry fields
        assertTrue(json.contains("resourceSpans") || json.contains("resource_spans"));
    }

    @Test
    void testProtobufFormatCanBeDecoded() throws Exception {
        props.put("otlp.message.format", "protobuf");
        config = new OpenTelemetrySourceConnectorConfig(props);
        receiver = new OtlpReceiver(config);

        ExportTraceServiceRequest request = createSampleTraceRequest();
        String base64Result = invokeConvertToFormat(receiver, request);

        // Decode base64 and verify it's valid protobuf
        byte[] decoded = Base64.getDecoder().decode(base64Result);
        ExportTraceServiceRequest reconstructed = ExportTraceServiceRequest.parseFrom(decoded);

        assertNotNull(reconstructed);
        assertEquals(request.getResourceSpansCount(), reconstructed.getResourceSpansCount());
    }

    @Test
    void testMetricsRequestConversionToJson() throws Exception {
        props.put("otlp.message.format", "json");
        config = new OpenTelemetrySourceConnectorConfig(props);
        receiver = new OtlpReceiver(config);

        ExportMetricsServiceRequest request = createSampleMetricsRequest();
        String json = invokeConvertToFormat(receiver, request);

        assertNotNull(json);
        assertTrue(json.contains("resourceMetrics") || json.contains("resource_metrics"));
    }

    @Test
    void testLogsRequestConversionToJson() throws Exception {
        props.put("otlp.message.format", "json");
        config = new OpenTelemetrySourceConnectorConfig(props);
        receiver = new OtlpReceiver(config);

        ExportLogsServiceRequest request = createSampleLogsRequest();
        String json = invokeConvertToFormat(receiver, request);

        assertNotNull(json);
        assertTrue(json.contains("resourceLogs") || json.contains("resource_logs"));
    }

    // Helper methods

    private ExportTraceServiceRequest createSampleTraceRequest() {
        return ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(KeyValue.newBuilder()
                                        .setKey("service.name")
                                        .setValue(AnyValue.newBuilder()
                                                .setStringValue("test-service")
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

    private ExportMetricsServiceRequest createSampleMetricsRequest() {
        return ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(KeyValue.newBuilder()
                                        .setKey("service.name")
                                        .setValue(AnyValue.newBuilder()
                                                .setStringValue("test-service")
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

    private ExportLogsServiceRequest createSampleLogsRequest() {
        return ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(ResourceLogs.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(KeyValue.newBuilder()
                                        .setKey("service.name")
                                        .setValue(AnyValue.newBuilder()
                                                .setStringValue("test-service")
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

    private String invokeConvertToFormat(OtlpReceiver receiver, com.google.protobuf.Message message)
            throws Exception {
        java.lang.reflect.Method method = OtlpReceiver.class.getDeclaredMethod(
                "convertToFormat", com.google.protobuf.Message.class);
        method.setAccessible(true);
        return (String) method.invoke(receiver, message);
    }
}
