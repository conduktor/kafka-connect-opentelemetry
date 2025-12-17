package io.conduktor.connect.otel;

import org.apache.kafka.common.config.Config;
import org.apache.kafka.common.config.ConfigValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OpenTelemetrySourceConnector.validate() method.
 * Addresses SME review finding: "BLOCKER: No tests verify Connector.validate() behavior"
 *
 * Tests validate():
 * - Required configuration validation
 * - Custom validator behavior
 * - TLS configuration cross-validation
 * - Message format validation
 */
class OpenTelemetrySourceConnectorValidateTest {

    private OpenTelemetrySourceConnector connector;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        connector = new OpenTelemetrySourceConnector();
    }

    @Test
    void testValidateWithDefaultConfiguration() {
        // Given: Minimal configuration (defaults should be valid)
        Map<String, String> props = createMinimalConfig();

        // When: Validating configuration
        Config result = connector.validate(props);

        // Then: Should return no critical errors
        assertNotNull(result, "Validation result should not be null");
        assertFalse(hasErrors(result), "Default configuration should be valid");
    }

    @Test
    void testValidateWithBothReceiversDisabled() {
        // Given: Configuration with both gRPC and HTTP disabled
        Map<String, String> props = createMinimalConfig();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_GRPC_ENABLED_CONFIG, "false");
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_HTTP_ENABLED_CONFIG, "false");

        // When: Starting connector (validation happens in start())
        // Then: Should throw because at least one receiver must be enabled
        assertThrows(IllegalArgumentException.class, () -> connector.start(props),
                "Should throw when both receivers are disabled");
    }

    @Test
    void testValidateWithGrpcOnlyEnabled() {
        // Given: Only gRPC enabled
        Map<String, String> props = createMinimalConfig();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_GRPC_ENABLED_CONFIG, "true");
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_HTTP_ENABLED_CONFIG, "false");

        // When: Validating
        Config result = connector.validate(props);

        // Then: Should be valid
        assertFalse(hasErrors(result), "gRPC-only configuration should be valid");
    }

    @Test
    void testValidateWithHttpOnlyEnabled() {
        // Given: Only HTTP enabled
        Map<String, String> props = createMinimalConfig();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_GRPC_ENABLED_CONFIG, "false");
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_HTTP_ENABLED_CONFIG, "true");

        // When: Validating
        Config result = connector.validate(props);

        // Then: Should be valid
        assertFalse(hasErrors(result), "HTTP-only configuration should be valid");
    }

    @Test
    void testValidateWithInvalidGrpcPort() {
        // Given: Invalid gRPC port (out of range)
        Map<String, String> props = createMinimalConfig();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_GRPC_PORT_CONFIG, "99999");

        // When: Validating
        Config result = connector.validate(props);

        // Then: Should have errors for port
        ConfigValue portConfig = findConfigValue(result, OpenTelemetrySourceConnectorConfig.OTLP_GRPC_PORT_CONFIG);
        assertNotNull(portConfig, "Port config should be present");
        assertFalse(portConfig.errorMessages().isEmpty(),
                "Invalid port should produce validation error");
    }

    @Test
    void testValidateWithInvalidHttpPort() {
        // Given: Invalid HTTP port (out of range)
        Map<String, String> props = createMinimalConfig();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_HTTP_PORT_CONFIG, "0");

        // When: Validating
        Config result = connector.validate(props);

        // Then: Should have errors for port
        ConfigValue portConfig = findConfigValue(result, OpenTelemetrySourceConnectorConfig.OTLP_HTTP_PORT_CONFIG);
        assertNotNull(portConfig, "Port config should be present");
        assertFalse(portConfig.errorMessages().isEmpty(),
                "Invalid port should produce validation error");
    }

    @Test
    void testValidateWithNonNumericPort() {
        // Given: Non-numeric port
        Map<String, String> props = createMinimalConfig();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_GRPC_PORT_CONFIG, "not-a-number");

        // When: Validating
        Config result = connector.validate(props);

        // Then: Should have errors
        assertTrue(hasErrors(result), "Non-numeric port should produce validation error");
    }

    @Test
    void testValidateWithInvalidMessageFormat() {
        // Given: Invalid message format
        Map<String, String> props = createMinimalConfig();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_MESSAGE_FORMAT_CONFIG, "xml");

        // When: Validating
        Config result = connector.validate(props);

        // Then: Should have errors for format
        ConfigValue formatConfig = findConfigValue(result, OpenTelemetrySourceConnectorConfig.OTLP_MESSAGE_FORMAT_CONFIG);
        assertNotNull(formatConfig, "Format config should be present");
        assertFalse(formatConfig.errorMessages().isEmpty(),
                "Invalid message format should produce validation error");
    }

    @Test
    void testValidateWithJsonMessageFormat() {
        // Given: Valid JSON format
        Map<String, String> props = createMinimalConfig();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_MESSAGE_FORMAT_CONFIG, "json");

        // When: Validating
        Config result = connector.validate(props);

        // Then: Should be valid
        ConfigValue formatConfig = findConfigValue(result, OpenTelemetrySourceConnectorConfig.OTLP_MESSAGE_FORMAT_CONFIG);
        assertNotNull(formatConfig);
        assertTrue(formatConfig.errorMessages().isEmpty(),
                "JSON format should be valid");
    }

    @Test
    void testValidateWithProtobufMessageFormat() {
        // Given: Valid protobuf format
        Map<String, String> props = createMinimalConfig();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_MESSAGE_FORMAT_CONFIG, "protobuf");

        // When: Validating
        Config result = connector.validate(props);

        // Then: Should be valid
        ConfigValue formatConfig = findConfigValue(result, OpenTelemetrySourceConnectorConfig.OTLP_MESSAGE_FORMAT_CONFIG);
        assertNotNull(formatConfig);
        assertTrue(formatConfig.errorMessages().isEmpty(),
                "Protobuf format should be valid");
    }

    @Test
    void testValidateWithTlsEnabledMissingCert() {
        // Given: TLS enabled but no cert
        Map<String, String> props = createMinimalConfig();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_TLS_ENABLED_CONFIG, "true");

        // When: Starting connector (cross-validation happens in start())
        // Then: Should throw because cert path is required
        assertThrows(IllegalArgumentException.class, () -> connector.start(props),
                "Should throw when TLS enabled without cert path");
    }

    @Test
    void testValidateWithTlsEnabledMissingKey() throws IOException {
        // Given: TLS enabled with cert but no key
        Path certFile = tempDir.resolve("cert.pem");
        Files.writeString(certFile, "-----BEGIN CERTIFICATE-----\ntest\n-----END CERTIFICATE-----");

        Map<String, String> props = createMinimalConfig();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_TLS_ENABLED_CONFIG, "true");
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_TLS_CERT_PATH_CONFIG, certFile.toString());

        // When: Starting connector (cross-validation happens in start())
        // Then: Should throw because key path is required
        assertThrows(IllegalArgumentException.class, () -> connector.start(props),
                "Should throw when TLS enabled without key path");
    }

    @Test
    void testValidateWithTlsFullyConfigured() throws IOException {
        // Given: TLS with both cert and key
        Path certFile = tempDir.resolve("cert.pem");
        Path keyFile = tempDir.resolve("key.pem");
        Files.writeString(certFile, "-----BEGIN CERTIFICATE-----\ntest\n-----END CERTIFICATE-----");
        Files.writeString(keyFile, "-----BEGIN PRIVATE KEY-----\ntest\n-----END PRIVATE KEY-----");

        Map<String, String> props = createMinimalConfig();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_TLS_ENABLED_CONFIG, "true");
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_TLS_CERT_PATH_CONFIG, certFile.toString());
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_TLS_KEY_PATH_CONFIG, keyFile.toString());

        // When: Validating
        Config result = connector.validate(props);

        // Then: Should be valid (cert/key validation passes)
        // Note: connector.start() will do additional TLS validation
        assertFalse(hasErrors(result), "Full TLS config should pass basic validation");
    }

    @Test
    void testValidateWithInvalidCertPath() {
        // Given: Non-existent cert path
        Map<String, String> props = createMinimalConfig();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_TLS_ENABLED_CONFIG, "true");
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_TLS_CERT_PATH_CONFIG, "/nonexistent/path/cert.pem");

        // When: Validating
        Config result = connector.validate(props);

        // Then: Should have errors for cert path
        ConfigValue certConfig = findConfigValue(result, OpenTelemetrySourceConnectorConfig.OTLP_TLS_CERT_PATH_CONFIG);
        assertNotNull(certConfig, "Cert path config should be present");
        assertFalse(certConfig.errorMessages().isEmpty(),
                "Non-existent cert path should produce validation error");
    }

    @Test
    void testValidateWithInvalidKeyPath() throws IOException {
        // Given: Valid cert but non-existent key path
        Path certFile = tempDir.resolve("cert.pem");
        Files.writeString(certFile, "test-cert");

        Map<String, String> props = createMinimalConfig();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_TLS_ENABLED_CONFIG, "true");
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_TLS_CERT_PATH_CONFIG, certFile.toString());
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_TLS_KEY_PATH_CONFIG, "/nonexistent/path/key.pem");

        // When: Validating
        Config result = connector.validate(props);

        // Then: Should have errors for key path
        ConfigValue keyConfig = findConfigValue(result, OpenTelemetrySourceConnectorConfig.OTLP_TLS_KEY_PATH_CONFIG);
        assertNotNull(keyConfig, "Key path config should be present");
        assertFalse(keyConfig.errorMessages().isEmpty(),
                "Non-existent key path should produce validation error");
    }

    @Test
    void testValidateWithInvalidQueueSize() {
        // Given: Queue size below minimum
        Map<String, String> props = createMinimalConfig();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_MESSAGE_QUEUE_SIZE_CONFIG, "10");

        // When: Validating
        Config result = connector.validate(props);

        // Then: Should have errors for queue size
        ConfigValue queueConfig = findConfigValue(result, OpenTelemetrySourceConnectorConfig.OTLP_MESSAGE_QUEUE_SIZE_CONFIG);
        assertNotNull(queueConfig, "Queue size config should be present");
        assertFalse(queueConfig.errorMessages().isEmpty(),
                "Queue size below minimum should produce validation error");
    }

    @Test
    void testValidateWithValidQueueSize() {
        // Given: Valid queue size
        Map<String, String> props = createMinimalConfig();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_MESSAGE_QUEUE_SIZE_CONFIG, "500");

        // When: Validating
        Config result = connector.validate(props);

        // Then: Should be valid
        ConfigValue queueConfig = findConfigValue(result, OpenTelemetrySourceConnectorConfig.OTLP_MESSAGE_QUEUE_SIZE_CONFIG);
        assertNotNull(queueConfig);
        assertTrue(queueConfig.errorMessages().isEmpty(),
                "Valid queue size should not produce errors");
    }

    @Test
    void testValidateWithCustomKafkaTopics() {
        // Given: Custom Kafka topics
        Map<String, String> props = createMinimalConfig();
        props.put(OpenTelemetrySourceConnectorConfig.KAFKA_TOPIC_TRACES_CONFIG, "my-traces");
        props.put(OpenTelemetrySourceConnectorConfig.KAFKA_TOPIC_METRICS_CONFIG, "my-metrics");
        props.put(OpenTelemetrySourceConnectorConfig.KAFKA_TOPIC_LOGS_CONFIG, "my-logs");

        // When: Validating
        Config result = connector.validate(props);

        // Then: Should be valid
        assertFalse(hasErrors(result), "Custom Kafka topics should be valid");
    }

    @Test
    void testValidateWithCustomBindAddress() {
        // Given: Custom bind address
        Map<String, String> props = createMinimalConfig();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_BIND_ADDRESS_CONFIG, "127.0.0.1");

        // When: Validating
        Config result = connector.validate(props);

        // Then: Should be valid
        ConfigValue bindConfig = findConfigValue(result, OpenTelemetrySourceConnectorConfig.OTLP_BIND_ADDRESS_CONFIG);
        assertNotNull(bindConfig);
        assertTrue(bindConfig.errorMessages().isEmpty(),
                "Custom bind address should be valid");
    }

    @Test
    void testValidateWithAllConfigsSet() {
        // Given: All optional configs explicitly set
        Map<String, String> props = createMinimalConfig();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_GRPC_ENABLED_CONFIG, "true");
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_GRPC_PORT_CONFIG, "4317");
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_HTTP_ENABLED_CONFIG, "true");
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_HTTP_PORT_CONFIG, "4318");
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_MESSAGE_FORMAT_CONFIG, "json");
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_MESSAGE_QUEUE_SIZE_CONFIG, "5000");
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_BIND_ADDRESS_CONFIG, "0.0.0.0");
        props.put(OpenTelemetrySourceConnectorConfig.KAFKA_TOPIC_TRACES_CONFIG, "traces");
        props.put(OpenTelemetrySourceConnectorConfig.KAFKA_TOPIC_METRICS_CONFIG, "metrics");
        props.put(OpenTelemetrySourceConnectorConfig.KAFKA_TOPIC_LOGS_CONFIG, "logs");

        // When: Validating
        Config result = connector.validate(props);

        // Then: Should be valid
        assertFalse(hasErrors(result), "All valid configs should produce no errors");
    }

    @Test
    void testValidateReturnsAllConfigKeys() {
        // Given: Minimal configuration
        Map<String, String> props = createMinimalConfig();

        // When: Validating
        Config result = connector.validate(props);

        // Then: Should return all config keys
        List<ConfigValue> configValues = result.configValues();
        assertNotNull(configValues);
        assertFalse(configValues.isEmpty(), "Validation should return config values");

        // Verify key configs are present
        assertTrue(configValues.stream().anyMatch(cv ->
                        cv.name().equals(OpenTelemetrySourceConnectorConfig.OTLP_GRPC_ENABLED_CONFIG)),
                "Should contain gRPC enabled config");
        assertTrue(configValues.stream().anyMatch(cv ->
                        cv.name().equals(OpenTelemetrySourceConnectorConfig.OTLP_HTTP_ENABLED_CONFIG)),
                "Should contain HTTP enabled config");
        assertTrue(configValues.stream().anyMatch(cv ->
                        cv.name().equals(OpenTelemetrySourceConnectorConfig.KAFKA_TOPIC_TRACES_CONFIG)),
                "Should contain traces topic config");
    }

    @Test
    void testValidateWithInvalidBooleanValue() {
        // Given: Invalid boolean value
        Map<String, String> props = createMinimalConfig();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_GRPC_ENABLED_CONFIG, "not-a-boolean");

        // When: Validating
        Config result = connector.validate(props);

        // Then: Should have errors
        assertTrue(hasErrors(result), "Invalid boolean should produce validation error");
    }

    // ========== Helper Methods ==========

    private Map<String, String> createMinimalConfig() {
        Map<String, String> props = new HashMap<>();
        props.put("name", "test-connector");
        // Defaults are sufficient for a valid minimal config
        return props;
    }

    private boolean hasErrors(Config config) {
        return config.configValues().stream()
                .anyMatch(cv -> !cv.errorMessages().isEmpty());
    }

    private ConfigValue findConfigValue(Config config, String name) {
        return config.configValues().stream()
                .filter(cv -> cv.name().equals(name))
                .findFirst()
                .orElse(null);
    }
}
