package io.conduktor.connect.otel;

import io.conduktor.connect.otel.OtlpReceiver.OtlpMessage;
import io.conduktor.connect.otel.OtlpReceiver.OtlpSignalType;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTaskContext;
import org.apache.kafka.connect.storage.OffsetStorageReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.management.JMException;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class OpenTelemetrySourceTaskTest {

    private OpenTelemetrySourceTask task;
    private Map<String, String> props;

    @Mock
    private SourceTaskContext context;

    @Mock
    private OffsetStorageReader offsetStorageReader;

    @BeforeEach
    void setUp() {
        task = new OpenTelemetrySourceTask();
        props = new HashMap<>();
        props.put("name", "test-connector");
        props.put("otlp.grpc.enabled", "false");
        props.put("otlp.http.enabled", "false"); // Disable servers to avoid port binding
        props.put("kafka.topic.traces", "test-traces");
        props.put("kafka.topic.metrics", "test-metrics");
        props.put("kafka.topic.logs", "test-logs");
        props.put("otlp.message.queue.size", "1000");

        // Setup mock context with lenient stubbing to avoid unnecessary stubbing errors
        lenient().when(context.offsetStorageReader()).thenReturn(offsetStorageReader);
        lenient().when(offsetStorageReader.offset(any())).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        if (task != null) {
            try {
                task.stop();
            } catch (Exception e) {
                // Ignore cleanup exceptions
            }
        }
    }

    @Test
    void testVersion() {
        assertNotNull(task.version());
        assertFalse(task.version().isEmpty());
    }

    @Test
    void testStartWithValidConfig() {
        task.initialize(context);
        assertDoesNotThrow(() -> task.start(props));
    }

    @Test
    void testStartInitializesTopicNames() {
        task.initialize(context);
        task.start(props);

        // Verify task started without error - topic names are set internally
        assertDoesNotThrow(() -> task.poll());
    }

    @Test
    void testPollReturnsNullWhenQueuesEmpty() throws InterruptedException {
        task.initialize(context);
        task.start(props);

        List<SourceRecord> records = task.poll();
        assertNull(records, "Should return null when queues are empty");
    }

    @Test
    void testPollReturnsNullWhenStopping() throws InterruptedException {
        task.initialize(context);
        task.start(props);
        task.stop();

        List<SourceRecord> records = task.poll();
        assertNull(records, "Should return null when task is stopping");
    }

    @Test
    void testPollProcessesTracesFromQueue() throws Exception {
        // Create a custom task with accessible receiver
        TestableSourceTask testTask = new TestableSourceTask();
        testTask.initialize(context);
        testTask.start(props);

        // Add a trace message to the queue
        String tracePayload = "{\"trace\":\"data\"}";
        OtlpMessage traceMessage = new OtlpMessage(OtlpSignalType.TRACES, tracePayload);
        testTask.getReceiver().getTracesQueue().offer(traceMessage);

        // Poll and verify
        List<SourceRecord> records = testTask.poll();
        assertNotNull(records);
        assertEquals(1, records.size());

        SourceRecord record = records.get(0);
        assertEquals("test-traces", record.topic());
        assertEquals(tracePayload, record.value());
        assertNotNull(record.sourcePartition());
        assertNotNull(record.sourceOffset());

        testTask.stop();
    }

    @Test
    void testPollProcessesMetricsFromQueue() throws Exception {
        TestableSourceTask testTask = new TestableSourceTask();
        testTask.initialize(context);
        testTask.start(props);

        // Add a metrics message to the queue
        String metricsPayload = "{\"metrics\":\"data\"}";
        OtlpMessage metricsMessage = new OtlpMessage(OtlpSignalType.METRICS, metricsPayload);
        testTask.getReceiver().getMetricsQueue().offer(metricsMessage);

        // Poll and verify
        List<SourceRecord> records = testTask.poll();
        assertNotNull(records);
        assertEquals(1, records.size());

        SourceRecord record = records.get(0);
        assertEquals("test-metrics", record.topic());
        assertEquals(metricsPayload, record.value());

        testTask.stop();
    }

    @Test
    void testPollProcessesLogsFromQueue() throws Exception {
        TestableSourceTask testTask = new TestableSourceTask();
        testTask.initialize(context);
        testTask.start(props);

        // Add a logs message to the queue
        String logsPayload = "{\"logs\":\"data\"}";
        OtlpMessage logsMessage = new OtlpMessage(OtlpSignalType.LOGS, logsPayload);
        testTask.getReceiver().getLogsQueue().offer(logsMessage);

        // Poll and verify
        List<SourceRecord> records = testTask.poll();
        assertNotNull(records);
        assertEquals(1, records.size());

        SourceRecord record = records.get(0);
        assertEquals("test-logs", record.topic());
        assertEquals(logsPayload, record.value());

        testTask.stop();
    }

    @Test
    void testPollProcessesMultipleMessagesFromAllQueues() throws Exception {
        TestableSourceTask testTask = new TestableSourceTask();
        testTask.initialize(context);
        testTask.start(props);

        // Add messages to all three queues
        testTask.getReceiver().getTracesQueue().offer(
                new OtlpMessage(OtlpSignalType.TRACES, "{\"trace\":\"1\"}"));
        testTask.getReceiver().getTracesQueue().offer(
                new OtlpMessage(OtlpSignalType.TRACES, "{\"trace\":\"2\"}"));
        testTask.getReceiver().getMetricsQueue().offer(
                new OtlpMessage(OtlpSignalType.METRICS, "{\"metric\":\"1\"}"));
        testTask.getReceiver().getLogsQueue().offer(
                new OtlpMessage(OtlpSignalType.LOGS, "{\"log\":\"1\"}"));

        // Poll and verify
        List<SourceRecord> records = testTask.poll();
        assertNotNull(records);
        assertEquals(4, records.size());

        // Count records by topic
        long traceCount = records.stream().filter(r -> r.topic().equals("test-traces")).count();
        long metricCount = records.stream().filter(r -> r.topic().equals("test-metrics")).count();
        long logCount = records.stream().filter(r -> r.topic().equals("test-logs")).count();

        assertEquals(2, traceCount);
        assertEquals(1, metricCount);
        assertEquals(1, logCount);

        testTask.stop();
    }

    @Test
    void testSourceRecordContainsCorrectOffsetStructure() throws Exception {
        TestableSourceTask testTask = new TestableSourceTask();
        testTask.initialize(context);
        testTask.start(props);

        // Add a trace message
        testTask.getReceiver().getTracesQueue().offer(
                new OtlpMessage(OtlpSignalType.TRACES, "{\"trace\":\"test\"}"));

        List<SourceRecord> records = testTask.poll();
        assertNotNull(records);
        assertEquals(1, records.size());

        SourceRecord record = records.get(0);

        // Verify source partition structure
        Map<String, ?> partition = record.sourcePartition();
        assertNotNull(partition);
        assertTrue(partition.containsKey("connector_name"));
        assertTrue(partition.containsKey("signal_type"));
        assertEquals("TRACES", partition.get("signal_type"));

        // Verify source offset structure
        Map<String, ?> offset = record.sourceOffset();
        assertNotNull(offset);
        assertTrue(offset.containsKey("session_id"));
        assertTrue(offset.containsKey("signal_type"));
        assertTrue(offset.containsKey("sequence"));
        assertEquals("TRACES", offset.get("signal_type"));
        assertEquals(1L, ((Number) offset.get("sequence")).longValue());

        testTask.stop();
    }

    @Test
    void testSequenceIncrementsSeparatelyPerSignalType() throws Exception {
        TestableSourceTask testTask = new TestableSourceTask();
        testTask.initialize(context);
        testTask.start(props);

        // Add multiple messages for each signal type
        for (int i = 0; i < 3; i++) {
            testTask.getReceiver().getTracesQueue().offer(
                    new OtlpMessage(OtlpSignalType.TRACES, "{\"trace\":\"" + i + "\"}"));
            testTask.getReceiver().getMetricsQueue().offer(
                    new OtlpMessage(OtlpSignalType.METRICS, "{\"metric\":\"" + i + "\"}"));
            testTask.getReceiver().getLogsQueue().offer(
                    new OtlpMessage(OtlpSignalType.LOGS, "{\"log\":\"" + i + "\"}"));
        }

        List<SourceRecord> records = testTask.poll();
        assertNotNull(records);
        assertEquals(9, records.size());

        // Verify each signal type has its own sequence starting from 1
        List<SourceRecord> traceRecords = records.stream()
                .filter(r -> r.topic().equals("test-traces"))
                .sorted(Comparator.comparing(r -> ((Number) r.sourceOffset().get("sequence")).longValue()))
                .toList();
        assertEquals(3, traceRecords.size());
        assertEquals(1L, ((Number) traceRecords.get(0).sourceOffset().get("sequence")).longValue());
        assertEquals(2L, ((Number) traceRecords.get(1).sourceOffset().get("sequence")).longValue());
        assertEquals(3L, ((Number) traceRecords.get(2).sourceOffset().get("sequence")).longValue());

        testTask.stop();
    }

    @Test
    void testCommitRecordTracksCommittedSequences() throws Exception {
        TestableSourceTask testTask = new TestableSourceTask();
        testTask.initialize(context);
        testTask.start(props);

        // Add a message and poll it
        testTask.getReceiver().getTracesQueue().offer(
                new OtlpMessage(OtlpSignalType.TRACES, "{\"trace\":\"test\"}"));

        List<SourceRecord> records = testTask.poll();
        assertNotNull(records);
        assertEquals(1, records.size());

        SourceRecord record = records.get(0);

        // Simulate Kafka commit callback
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("test-traces", 0),
                0L,  // baseOffset
                0,   // batchIndex
                0L,  // timestamp
                0,   // serializedKeySize
                0    // serializedValueSize
        );

        // Should not throw
        assertDoesNotThrow(() -> testTask.commitRecord(record, metadata));

        testTask.stop();
    }

    @Test
    void testCommitRecordHandlesNullOffset() throws Exception {
        TestableSourceTask testTask = new TestableSourceTask();
        testTask.initialize(context);
        testTask.start(props);

        // Create a record with null offset
        SourceRecord record = new SourceRecord(
                Collections.singletonMap("test", "value"),
                null, // null offset
                "test-topic",
                null,
                null,
                null,
                null,
                "test-value"
        );

        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("test-topic", 0),
                0L, 0, 0L, 0, 0
        );

        // Should not throw
        assertDoesNotThrow(() -> testTask.commitRecord(record, metadata));

        testTask.stop();
    }

    @Test
    void testCommitRecordHandlesUnknownSignalType() throws Exception {
        TestableSourceTask testTask = new TestableSourceTask();
        testTask.initialize(context);
        testTask.start(props);

        // Create a record with unknown signal type
        Map<String, Object> offset = new HashMap<>();
        offset.put("session_id", "test-session");
        offset.put("signal_type", "UNKNOWN");
        offset.put("sequence", 1L);

        SourceRecord record = new SourceRecord(
                Collections.singletonMap("test", "value"),
                offset,
                "test-topic",
                null,
                null,
                null,
                null,
                "test-value"
        );

        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("test-topic", 0),
                0L, 0, 0L, 0, 0
        );

        // Should not throw
        assertDoesNotThrow(() -> testTask.commitRecord(record, metadata));

        testTask.stop();
    }

    @Test
    void testStopDrainsMessages() throws Exception {
        TestableSourceTask testTask = new TestableSourceTask();
        testTask.initialize(context);
        testTask.start(props);

        // Add messages to queue
        for (int i = 0; i < 5; i++) {
            testTask.getReceiver().getTracesQueue().offer(
                    new OtlpMessage(OtlpSignalType.TRACES, "{\"trace\":\"" + i + "\"}"));
        }

        // Stop should drain messages
        testTask.stop();

        // Queue should be empty after stop
        assertTrue(testTask.getReceiver().getTracesQueue().isEmpty());
    }

    @Test
    void testStopWithEmptyQueues() throws Exception {
        TestableSourceTask testTask = new TestableSourceTask();
        testTask.initialize(context);
        testTask.start(props);

        // Stop with empty queues should not throw
        assertDoesNotThrow(() -> testTask.stop());
    }

    @Test
    void testRestoreOffsetFromStorage() throws Exception {
        // Setup mock to return previous offset
        Map<String, Object> previousOffset = new HashMap<>();
        previousOffset.put("session_id", "old-session-id");
        previousOffset.put("signal_type", "TRACES");
        previousOffset.put("sequence", 42L);

        when(offsetStorageReader.offset(argThat(partition ->
                "TRACES".equals(((Map<?, ?>) partition).get("signal_type")))))
                .thenReturn(previousOffset);

        TestableSourceTask testTask = new TestableSourceTask();
        testTask.initialize(context);
        testTask.start(props);

        // Add a message and verify sequence continues from 42
        testTask.getReceiver().getTracesQueue().offer(
                new OtlpMessage(OtlpSignalType.TRACES, "{\"trace\":\"test\"}"));

        List<SourceRecord> records = testTask.poll();
        assertNotNull(records);
        assertEquals(1, records.size());

        // Next sequence should be 43 (42 + 1)
        Long sequence = (Long) records.get(0).sourceOffset().get("sequence");
        assertEquals(43L, sequence);

        testTask.stop();
    }

    @Test
    void testRestoreOffsetHandlesDifferentSessionId() throws Exception {
        Map<String, Object> previousOffset = new HashMap<>();
        previousOffset.put("session_id", "different-session");
        previousOffset.put("signal_type", "METRICS");
        previousOffset.put("sequence", 10L);

        when(offsetStorageReader.offset(argThat(partition ->
                "METRICS".equals(((Map<?, ?>) partition).get("signal_type")))))
                .thenReturn(previousOffset);

        TestableSourceTask testTask = new TestableSourceTask();
        testTask.initialize(context);
        testTask.start(props);

        // Should still restore the sequence even with different session
        testTask.getReceiver().getMetricsQueue().offer(
                new OtlpMessage(OtlpSignalType.METRICS, "{\"metric\":\"test\"}"));

        List<SourceRecord> records = testTask.poll();
        assertNotNull(records);
        assertEquals(1, records.size());

        Long sequence = (Long) records.get(0).sourceOffset().get("sequence");
        assertEquals(11L, sequence);

        testTask.stop();
    }

    @Test
    void testMessageTimestampIsSet() throws Exception {
        TestableSourceTask testTask = new TestableSourceTask();
        testTask.initialize(context);
        testTask.start(props);

        long beforeAdd = System.currentTimeMillis();
        testTask.getReceiver().getTracesQueue().offer(
                new OtlpMessage(OtlpSignalType.TRACES, "{\"trace\":\"test\"}"));
        long afterAdd = System.currentTimeMillis();

        List<SourceRecord> records = testTask.poll();
        assertNotNull(records);
        assertEquals(1, records.size());

        SourceRecord record = records.get(0);
        Long timestamp = record.timestamp();
        assertNotNull(timestamp);
        assertTrue(timestamp >= beforeAdd && timestamp <= afterAdd,
                "Timestamp should be within the time range when message was created");

        testTask.stop();
    }

    @Test
    void testTaskLifecycleStartPollStop() throws Exception {
        TestableSourceTask testTask = new TestableSourceTask();

        // 1. Initialize
        testTask.initialize(context);

        // 2. Start
        testTask.start(props);

        // 3. Poll (should return null on empty queue)
        List<SourceRecord> records = testTask.poll();
        assertNull(records);

        // 4. Add message and poll again
        testTask.getReceiver().getTracesQueue().offer(
                new OtlpMessage(OtlpSignalType.TRACES, "{\"trace\":\"test\"}"));
        records = testTask.poll();
        assertNotNull(records);
        assertEquals(1, records.size());

        // 5. Stop
        testTask.stop();

        // 6. Poll after stop should return null
        records = testTask.poll();
        assertNull(records);
    }

    /**
     * Testable version of OpenTelemetrySourceTask that exposes the receiver
     * for testing purposes without binding to real network ports.
     */
    static class TestableSourceTask extends OpenTelemetrySourceTask {
        @Override
        public void start(Map<String, String> props) {
            // Call parent start which sets up config and creates receiver
            super.start(props);
        }

        public OtlpReceiver getReceiver() {
            try {
                java.lang.reflect.Field receiverField = OpenTelemetrySourceTask.class.getDeclaredField("receiver");
                receiverField.setAccessible(true);
                return (OtlpReceiver) receiverField.get(this);
            } catch (Exception e) {
                throw new RuntimeException("Failed to access receiver field", e);
            }
        }
    }
}
