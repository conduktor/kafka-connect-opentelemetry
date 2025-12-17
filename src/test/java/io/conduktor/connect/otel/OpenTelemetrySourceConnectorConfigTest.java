package io.conduktor.connect.otel;

import org.apache.kafka.common.config.ConfigException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenTelemetrySourceConnectorConfigTest {

    @Test
    void testDefaultConfiguration() {
        Map<String, String> props = new HashMap<>();
        OpenTelemetrySourceConnectorConfig config = new OpenTelemetrySourceConnectorConfig(props);

        assertTrue(config.isGrpcEnabled());
        assertEquals(4317, config.getGrpcPort());
        assertTrue(config.isHttpEnabled());
        assertEquals(4318, config.getHttpPort());
        assertFalse(config.isTlsEnabled());
        assertEquals("otlp-traces", config.getKafkaTopicTraces());
        assertEquals("otlp-metrics", config.getKafkaTopicMetrics());
        assertEquals("otlp-logs", config.getKafkaTopicLogs());
        assertEquals("json", config.getMessageFormat());
        assertEquals(10000, config.getMessageQueueSize());
        assertEquals("0.0.0.0", config.getBindAddress());
    }

    @Test
    void testCustomConfiguration() {
        Map<String, String> props = new HashMap<>();
        props.put("otlp.grpc.enabled", "false");
        props.put("otlp.grpc.port", "14317");
        props.put("otlp.http.enabled", "true");
        props.put("otlp.http.port", "14318");
        props.put("otlp.tls.enabled", "false");
        props.put("kafka.topic.traces", "custom-traces");
        props.put("kafka.topic.metrics", "custom-metrics");
        props.put("kafka.topic.logs", "custom-logs");
        props.put("otlp.message.format", "protobuf");
        props.put("otlp.message.queue.size", "50000");
        props.put("otlp.bind.address", "127.0.0.1");

        OpenTelemetrySourceConnectorConfig config = new OpenTelemetrySourceConnectorConfig(props);

        assertFalse(config.isGrpcEnabled());
        assertEquals(14317, config.getGrpcPort());
        assertTrue(config.isHttpEnabled());
        assertEquals(14318, config.getHttpPort());
        assertFalse(config.isTlsEnabled());
        assertEquals("custom-traces", config.getKafkaTopicTraces());
        assertEquals("custom-metrics", config.getKafkaTopicMetrics());
        assertEquals("custom-logs", config.getKafkaTopicLogs());
        assertEquals("protobuf", config.getMessageFormat());
        assertEquals(50000, config.getMessageQueueSize());
        assertEquals("127.0.0.1", config.getBindAddress());
    }

    @Test
    void testInvalidMessageFormat() {
        Map<String, String> props = new HashMap<>();
        props.put("otlp.message.format", "xml");

        assertThrows(ConfigException.class, () -> {
            new OpenTelemetrySourceConnectorConfig(props);
        });
    }

    @Test
    void testInvalidPortNumber() {
        Map<String, String> props = new HashMap<>();
        props.put("otlp.grpc.port", "99999");

        assertThrows(ConfigException.class, () -> {
            new OpenTelemetrySourceConnectorConfig(props);
        });
    }

    @Test
    void testInvalidQueueSize() {
        Map<String, String> props = new HashMap<>();
        props.put("otlp.message.queue.size", "50");

        assertThrows(ConfigException.class, () -> {
            new OpenTelemetrySourceConnectorConfig(props);
        });
    }

    @Test
    void testValidJsonFormat() {
        Map<String, String> props = new HashMap<>();
        props.put("otlp.message.format", "json");

        OpenTelemetrySourceConnectorConfig config = new OpenTelemetrySourceConnectorConfig(props);
        assertEquals("json", config.getMessageFormat());
    }

    @Test
    void testValidProtobufFormat() {
        Map<String, String> props = new HashMap<>();
        props.put("otlp.message.format", "protobuf");

        OpenTelemetrySourceConnectorConfig config = new OpenTelemetrySourceConnectorConfig(props);
        assertEquals("protobuf", config.getMessageFormat());
    }

    @Test
    void testCaseInsensitiveFormat() {
        Map<String, String> props = new HashMap<>();
        props.put("otlp.message.format", "JSON");

        OpenTelemetrySourceConnectorConfig config = new OpenTelemetrySourceConnectorConfig(props);
        assertEquals("JSON", config.getMessageFormat());
    }
}
