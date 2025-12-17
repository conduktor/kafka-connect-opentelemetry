package io.conduktor.connect.otel;

import io.conduktor.connect.otel.OtlpReceiver.OtlpMessage;
import io.conduktor.connect.otel.OtlpReceiver.OtlpSignalType;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import javax.management.JMException;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenTelemetry OTLP Source Task that receives OTLP telemetry data and produces it to Kafka.
 *
 * This implementation provides:
 * - Sequence-based offset management for reliable message tracking
 * - Separate queues for traces, metrics, and logs
 * - JMX metrics for monitoring
 * - Graceful shutdown with message draining
 */
public class OpenTelemetrySourceTask extends SourceTask {
    private static final Logger log = LoggerFactory.getLogger(OpenTelemetrySourceTask.class);

    // Shutdown and lifecycle management
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private static final long SHUTDOWN_DRAIN_TIMEOUT_MS = 5000L;
    private static final long POLL_TIMEOUT_MS = 1000L;

    // Offset management - sequence-based tracking per signal type
    private final AtomicLong tracesSequence = new AtomicLong(0);
    private final AtomicLong metricsSequence = new AtomicLong(0);
    private final AtomicLong logsSequence = new AtomicLong(0);
    private final AtomicLong lastCommittedTracesSequence = new AtomicLong(-1);
    private final AtomicLong lastCommittedMetricsSequence = new AtomicLong(-1);
    private final AtomicLong lastCommittedLogsSequence = new AtomicLong(-1);
    private volatile String sessionId;

    // OTLP receiver and configuration
    private OtlpReceiver receiver;
    private OpenTelemetrySourceConnectorConfig config;
    private String tracesTopicName;
    private String metricsTopicName;
    private String logsTopicName;

    // Metrics
    private final AtomicLong recordsProduced = new AtomicLong(0);
    private long lastLogTime = System.currentTimeMillis();
    private OpenTelemetryMetrics metrics;
    private String connectorName;

    @Override
    public String version() {
        return VersionUtil.getVersion();
    }

    @Override
    public void start(Map<String, String> props) {
        config = new OpenTelemetrySourceConnectorConfig(props);
        tracesTopicName = config.getKafkaTopicTraces();
        metricsTopicName = config.getKafkaTopicMetrics();
        logsTopicName = config.getKafkaTopicLogs();

        // Extract connector name from properties or generate one
        connectorName = props.getOrDefault("name", "otlp-connector-" + UUID.randomUUID().toString().substring(0, 8));

        // Set up MDC context for all logging in this task
        MDC.put("connector_name", connectorName);
        MDC.put("grpc_enabled", String.valueOf(config.isGrpcEnabled()));
        MDC.put("http_enabled", String.valueOf(config.isHttpEnabled()));

        log.info("event=task_starting connector_name={} grpc_port={} http_port={} topics={},{},{}",
                connectorName, config.getGrpcPort(), config.getHttpPort(),
                tracesTopicName, metricsTopicName, logsTopicName);

        // Initialize session ID (unique identifier for this connection lifecycle)
        sessionId = UUID.randomUUID().toString();
        MDC.put("session_id", sessionId);
        log.info("event=session_initialized session_id={}", sessionId);

        // Initialize JMX metrics
        try {
            metrics = new OpenTelemetryMetrics(connectorName);
            log.info("event=jmx_metrics_initialized connector_name={}", connectorName);
        } catch (JMException e) {
            log.error("event=jmx_metrics_init_failed connector_name={} error={}", connectorName, e.getMessage(), e);
            // Continue without metrics - not critical for operation
        }

        // Restore offset from Kafka Connect framework if available
        restoreOffsetState();

        // Create and start OTLP receiver
        receiver = new OtlpReceiver(config);
        if (metrics != null) {
            receiver.setMetrics(metrics);
        }

        try {
            receiver.start();
            log.info("event=otlp_receiver_started");
        } catch (Exception e) {
            log.error("event=otlp_receiver_start_failed error={}", e.getMessage(), e);
            throw new RuntimeException("Failed to start OTLP receiver", e);
        }

        log.info("event=task_started session_id={} traces_seq={} metrics_seq={} logs_seq={}",
                sessionId, tracesSequence.get(), metricsSequence.get(), logsSequence.get());
    }

    @Override
    public List<SourceRecord> poll() throws InterruptedException {
        // If we're stopping, don't accept new messages
        if (stopping.get()) {
            return null;
        }

        List<SourceRecord> records = new ArrayList<>();

        // Poll from all three queues with timeout
        pollQueue(receiver.getTracesQueue(), OtlpSignalType.TRACES, records);
        pollQueue(receiver.getMetricsQueue(), OtlpSignalType.METRICS, records);
        pollQueue(receiver.getLogsQueue(), OtlpSignalType.LOGS, records);

        if (records.isEmpty()) {
            // Return null to let Kafka Connect framework control the polling pace
            return null;
        }

        // Update metrics
        if (metrics != null && !records.isEmpty()) {
            metrics.incrementRecordsProduced(records.size());
        }

        // Log metrics periodically
        long now = System.currentTimeMillis();
        if (now - lastLogTime > 30000) { // Every 30 seconds
            logMetrics();
            lastLogTime = now;
        }

        return records;
    }

    @Override
    public void stop() {
        log.info("event=task_stopping session_id={}", sessionId);

        // Step 1: Set stopping flag to prevent accepting new messages in poll()
        stopping.set(true);

        // Step 2: Stop the OTLP receiver (stops accepting new incoming data)
        if (receiver != null) {
            receiver.stop();
        }

        // Step 3: Drain remaining messages from the queues with timeout
        long drainStartTime = System.currentTimeMillis();
        int drainedMessages = 0;

        if (receiver != null) {
            log.info("event=message_draining_started timeout_ms={}", SHUTDOWN_DRAIN_TIMEOUT_MS);

            while (System.currentTimeMillis() - drainStartTime < SHUTDOWN_DRAIN_TIMEOUT_MS) {
                int drained = 0;
                drained += receiver.getTracesQueue().drainTo(new ArrayList<>());
                drained += receiver.getMetricsQueue().drainTo(new ArrayList<>());
                drained += receiver.getLogsQueue().drainTo(new ArrayList<>());

                if (drained == 0) {
                    break; // No more messages to drain
                }

                drainedMessages += drained;
                log.debug("event=messages_drained count={}", drained);

                // Small sleep to allow framework to process these messages
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("event=drain_interrupted");
                    break;
                }
            }

            log.info("event=message_draining_completed drained_count={} duration_ms={}",
                    drainedMessages, (System.currentTimeMillis() - drainStartTime));
        }

        // Step 4: Log final metrics and close JMX
        logMetrics();

        if (metrics != null) {
            try {
                metrics.close();
                log.info("event=jmx_metrics_closed connector_name={}", connectorName);
            } catch (Exception e) {
                log.error("event=jmx_metrics_close_failed error={}", e.getMessage(), e);
            }
        }

        log.info("event=task_stopped session_id={} final_traces_seq={} final_metrics_seq={} final_logs_seq={}",
                sessionId, tracesSequence.get(), metricsSequence.get(), logsSequence.get());

        // Clear MDC context
        MDC.clear();
    }

    /**
     * Kafka Connect callback when a record is committed to Kafka.
     * This provides delivery guarantees and allows us to track which messages
     * have been successfully written to Kafka.
     */
    @Override
    public void commitRecord(SourceRecord record, org.apache.kafka.clients.producer.RecordMetadata metadata) {
        try {
            // Extract the sequence number and signal type from the source offset
            Map<String, ?> sourceOffset = record.sourceOffset();
            if (sourceOffset != null && sourceOffset.containsKey("sequence") && sourceOffset.containsKey("signal_type")) {
                long committedSeq = ((Number) sourceOffset.get("sequence")).longValue();
                String signalType = (String) sourceOffset.get("signal_type");

                // Update the appropriate last committed sequence based on signal type
                AtomicLong lastCommitted;
                switch (signalType) {
                    case "TRACES":
                        lastCommitted = lastCommittedTracesSequence;
                        break;
                    case "METRICS":
                        lastCommitted = lastCommittedMetricsSequence;
                        break;
                    case "LOGS":
                        lastCommitted = lastCommittedLogsSequence;
                        break;
                    default:
                        log.warn("Unknown signal type in committed record: {}", signalType);
                        return;
                }

                long previousCommitted = lastCommitted.get();
                lastCommitted.set(committedSeq);

                // Detect sequence gaps (potential message loss)
                if (previousCommitted >= 0 && committedSeq != previousCommitted + 1) {
                    long gap = committedSeq - previousCommitted - 1;
                    log.warn("Sequence gap detected for {}! Previous committed: {}, Current: {}, Gap size: {}",
                            signalType, previousCommitted, committedSeq, gap);
                }

                if (log.isDebugEnabled()) {
                    log.debug("Record committed - Signal: {}, Sequence: {}, Kafka offset: {}, partition: {}",
                            signalType, committedSeq, metadata.offset(), metadata.partition());
                }
            }
        } catch (Exception e) {
            log.error("Error in commitRecord callback", e);
        }
    }

    /**
     * Poll messages from a queue and convert them to SourceRecords.
     */
    private void pollQueue(BlockingQueue<OtlpMessage> queue, OtlpSignalType signalType,
                          List<SourceRecord> records) throws InterruptedException {
        // Poll multiple messages if available (batch processing)
        List<OtlpMessage> messages = new ArrayList<>();
        OtlpMessage message = queue.poll(100, TimeUnit.MILLISECONDS);
        if (message != null) {
            messages.add(message);
            // Drain additional messages without blocking
            queue.drainTo(messages, 99); // Batch up to 100 messages
        }

        for (OtlpMessage msg : messages) {
            SourceRecord record = createSourceRecord(msg);
            if (record != null) {
                records.add(record);
                recordsProduced.incrementAndGet();
            }
        }
    }

    /**
     * Create a SourceRecord with sequence-based offset management.
     *
     * Offset structure:
     * - session_id: Unique ID for this connection lifecycle (detects restarts)
     * - signal_type: Type of OTLP signal (TRACES, METRICS, LOGS)
     * - sequence: Monotonically increasing number for message ordering per signal type
     */
    private SourceRecord createSourceRecord(OtlpMessage message) {
        try {
            OtlpSignalType signalType = message.getSignalType();
            String topic;
            AtomicLong sequence;

            // Select appropriate topic and sequence counter based on signal type
            switch (signalType) {
                case TRACES:
                    topic = tracesTopicName;
                    sequence = tracesSequence;
                    break;
                case METRICS:
                    topic = metricsTopicName;
                    sequence = metricsSequence;
                    break;
                case LOGS:
                    topic = logsTopicName;
                    sequence = logsSequence;
                    break;
                default:
                    log.error("Unknown signal type: {}", signalType);
                    return null;
            }

            // Increment sequence number atomically for this message
            long seq = sequence.incrementAndGet();

            // Source partition identifies the data stream source
            Map<String, Object> sourcePartition = new HashMap<>();
            sourcePartition.put("connector_name", connectorName);
            sourcePartition.put("signal_type", signalType.name());

            // Source offset for tracking
            Map<String, Object> sourceOffset = new HashMap<>();
            sourceOffset.put("session_id", sessionId);
            sourceOffset.put("signal_type", signalType.name());
            sourceOffset.put("sequence", seq);

            return new SourceRecord(
                    sourcePartition,
                    sourceOffset,
                    topic,
                    null, // partition - let Kafka decide
                    null, // no key schema
                    null, // no key
                    Schema.STRING_SCHEMA,
                    message.getPayload(),
                    message.getTimestamp()
            );
        } catch (Exception e) {
            log.error("Error creating SourceRecord from OTLP message: {}", message.getSignalType(), e);
            return null;
        }
    }

    /**
     * Restore offset state from Kafka Connect framework.
     * This is called during task startup to resume from where we left off.
     */
    private void restoreOffsetState() {
        try {
            // Restore traces offset
            restoreSignalOffset("TRACES", tracesSequence, lastCommittedTracesSequence);
            // Restore metrics offset
            restoreSignalOffset("METRICS", metricsSequence, lastCommittedMetricsSequence);
            // Restore logs offset
            restoreSignalOffset("LOGS", logsSequence, lastCommittedLogsSequence);
        } catch (Exception e) {
            log.error("Error restoring offset state, starting from 0", e);
        }
    }

    /**
     * Restore offset for a specific signal type.
     */
    private void restoreSignalOffset(String signalType, AtomicLong sequence, AtomicLong lastCommitted) {
        try {
            Map<String, Object> partition = new HashMap<>();
            partition.put("connector_name", connectorName);
            partition.put("signal_type", signalType);

            Map<String, ?> offsetRaw = context.offsetStorageReader().offset(partition);
            @SuppressWarnings("unchecked")
            Map<String, Object> offset = offsetRaw != null ? (Map<String, Object>) offsetRaw : null;

            if (offset != null && !offset.isEmpty() && offset.containsKey("sequence")) {
                long restoredSequence = ((Number) offset.get("sequence")).longValue();
                sequence.set(restoredSequence);
                lastCommitted.set(restoredSequence);

                String restoredSessionId = offset.getOrDefault("session_id", "unknown").toString();
                log.info("Restored offset state for {} - Session: {}, Sequence: {}",
                        signalType, restoredSessionId, restoredSequence);

                // Note: Different session ID indicates a restart
                if (!sessionId.equals(restoredSessionId)) {
                    log.warn("Session ID changed for {} - Previous: {}, Current: {}. This indicates a connector restart.",
                            signalType, restoredSessionId, sessionId);
                }
            } else {
                log.info("No previous offset found for {}, starting fresh from sequence 0", signalType);
            }
        } catch (Exception e) {
            log.error("Error restoring offset for {}, starting from 0", signalType, e);
        }
    }

    /**
     * Log metrics about the task's performance with enhanced observability.
     */
    private void logMetrics() {
        if (receiver == null) {
            return;
        }

        int tracesQueueSize = receiver.getTracesQueue().size();
        int metricsQueueSize = receiver.getMetricsQueue().size();
        int logsQueueSize = receiver.getLogsQueue().size();
        int queueCapacity = config.getMessageQueueSize();

        long recordsProducedCount = recordsProduced.get();

        // Structured logging with key=value format
        String metricsLog = String.format(
                "event=task_metrics records_produced=%d " +
                "traces_queue_size=%d metrics_queue_size=%d logs_queue_size=%d queue_capacity=%d " +
                "traces_seq=%d metrics_seq=%d logs_seq=%d session_id=%s",
                recordsProducedCount,
                tracesQueueSize, metricsQueueSize, logsQueueSize, queueCapacity,
                tracesSequence.get(), metricsSequence.get(), logsSequence.get(), sessionId
        );

        // Log at appropriate level based on queue status
        double maxUtilization = Math.max(
                Math.max(
                        (tracesQueueSize * 100.0) / queueCapacity,
                        (metricsQueueSize * 100.0) / queueCapacity
                ),
                (logsQueueSize * 100.0) / queueCapacity
        );

        if (maxUtilization > 80.0) {
            log.warn(metricsLog + " status=HIGH_QUEUE_UTILIZATION");
        } else {
            log.info(metricsLog + " status=HEALTHY");
        }
    }
}
