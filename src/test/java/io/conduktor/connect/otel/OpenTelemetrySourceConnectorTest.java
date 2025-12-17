package io.conduktor.connect.otel;

import org.apache.kafka.connect.connector.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenTelemetrySourceConnectorTest {

    private OpenTelemetrySourceConnector connector;
    private Map<String, String> props;

    @BeforeEach
    void setUp() {
        connector = new OpenTelemetrySourceConnector();
        props = new HashMap<>();
        props.put("name", "test-connector");
    }

    @Test
    void testVersion() {
        assertNotNull(connector.version());
        assertFalse(connector.version().isEmpty());
    }

    @Test
    void testTaskClass() {
        assertEquals(OpenTelemetrySourceTask.class, connector.taskClass());
    }

    @Test
    void testStartWithValidConfig() {
        assertDoesNotThrow(() -> connector.start(props));
    }

    @Test
    void testStartWithBothReceiversDisabled() {
        props.put("otlp.grpc.enabled", "false");
        props.put("otlp.http.enabled", "false");

        assertThrows(IllegalArgumentException.class, () -> connector.start(props));
    }

    @Test
    void testStartWithTlsButNoCertPath() {
        props.put("otlp.tls.enabled", "true");
        props.put("otlp.tls.cert.path", null);

        assertThrows(IllegalArgumentException.class, () -> connector.start(props));
    }

    @Test
    void testStartWithTlsButNoKeyPath() {
        props.put("otlp.tls.enabled", "true");
        props.put("otlp.tls.key.path", null);

        assertThrows(IllegalArgumentException.class, () -> connector.start(props));
    }

    @Test
    void testTaskConfigsReturnsOneTask() {
        connector.start(props);
        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);

        assertEquals(1, taskConfigs.size());
        assertEquals(props, taskConfigs.get(0));
    }

    @Test
    void testTaskConfigsWithMultipleTasksWarning() {
        connector.start(props);
        List<Map<String, String>> taskConfigs = connector.taskConfigs(5);

        // Should still return only one task
        assertEquals(1, taskConfigs.size());
    }

    @Test
    void testConfig() {
        assertNotNull(connector.config());
        assertEquals(OpenTelemetrySourceConnectorConfig.CONFIG_DEF, connector.config());
    }

    @Test
    void testStop() {
        connector.start(props);
        assertDoesNotThrow(() -> connector.stop());
    }

    @Test
    void testStartWithGrpcOnly() {
        props.put("otlp.grpc.enabled", "true");
        props.put("otlp.http.enabled", "false");

        assertDoesNotThrow(() -> connector.start(props));
    }

    @Test
    void testStartWithHttpOnly() {
        props.put("otlp.grpc.enabled", "false");
        props.put("otlp.http.enabled", "true");

        assertDoesNotThrow(() -> connector.start(props));
    }

    @Test
    void testStartWithCustomTopics() {
        props.put("kafka.topic.traces", "my-traces");
        props.put("kafka.topic.metrics", "my-metrics");
        props.put("kafka.topic.logs", "my-logs");

        assertDoesNotThrow(() -> connector.start(props));
    }
}
