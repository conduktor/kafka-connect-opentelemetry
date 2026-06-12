package io.conduktor.connect.otel;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.source.SourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenTelemetry OTLP Source Connector for Kafka Connect.
 * Receives OpenTelemetry Protocol (OTLP) telemetry data via gRPC and HTTP
 * and streams it into Kafka topics.
 */
public class OpenTelemetrySourceConnector extends SourceConnector {
    private static final Logger log = LoggerFactory.getLogger(OpenTelemetrySourceConnector.class);

    private Map<String, String> configProperties;

    @Override
    public String version() {
        return VersionUtil.getVersion();
    }

    @Override
    public void start(Map<String, String> props) {
        log.info("Starting OpenTelemetry OTLP Source Connector");
        this.configProperties = props;

        // Validate configuration
        OpenTelemetrySourceConnectorConfig config = new OpenTelemetrySourceConnectorConfig(props);

        log.info("Connector configured - gRPC: {}:{}, HTTP: {}:{}, TLS: {}",
                config.isGrpcEnabled() ? "enabled" : "disabled", config.getGrpcPort(),
                config.isHttpEnabled() ? "enabled" : "disabled", config.getHttpPort(),
                config.isTlsEnabled() ? "enabled" : "disabled");
        log.info("Kafka topics - Traces: {}, Metrics: {}, Logs: {}",
                config.getKafkaTopicTraces(),
                config.getKafkaTopicMetrics(),
                config.getKafkaTopicLogs());
        log.info("Message format: {}, Queue size: {}",
                config.getMessageFormat(),
                config.getMessageQueueSize());

        // Validate that at least one receiver is enabled
        if (!config.isGrpcEnabled() && !config.isHttpEnabled()) {
            throw new IllegalArgumentException(
                    "At least one OTLP receiver must be enabled (gRPC or HTTP)");
        }

        // Validate TLS configuration if enabled
        if (config.isTlsEnabled()) {
            if (config.getTlsCertPath() == null || config.getTlsCertPath().isEmpty()) {
                throw new IllegalArgumentException(
                        "TLS certificate path must be provided when TLS is enabled");
            }
            if (config.getTlsKeyPath() == null || config.getTlsKeyPath().isEmpty()) {
                throw new IllegalArgumentException(
                        "TLS key path must be provided when TLS is enabled");
            }
        }

        // Validate authentication configuration if enabled
        if (config.isAuthEnabled()) {
            if (config.getAuthMethods().isEmpty()) {
                throw new IllegalArgumentException(
                        "At least one authentication method must be set in '"
                                + OpenTelemetrySourceConnectorConfig.OTLP_AUTH_METHODS_CONFIG
                                + "' when authentication is enabled (api-key, oidc)");
            }
            if (config.isApiKeyAuthEnabled()
                    && (config.getApiKey() == null || config.getApiKey().value().trim().isEmpty())) {
                throw new IllegalArgumentException(
                        "'" + OpenTelemetrySourceConnectorConfig.OTLP_AUTH_API_KEY_CONFIG
                                + "' must be provided when the 'api-key' authentication method is enabled");
            }
            if (config.isOidcAuthEnabled() && config.getOidcIssuer().trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "'" + OpenTelemetrySourceConnectorConfig.OTLP_AUTH_OIDC_ISSUER_CONFIG
                                + "' must be provided when the 'oidc' authentication method is enabled");
            }
            log.info("Authentication enabled - methods: {}", config.getAuthMethods());
        }
    }

    @Override
    public Class<? extends Task> taskClass() {
        return OpenTelemetrySourceTask.class;
    }

    @Override
    public List<Map<String, String>> taskConfigs(int maxTasks) {
        log.info("Creating task configurations for {} tasks", maxTasks);

        // OTLP receiver is single-threaded (one server instance), so we only create one task
        List<Map<String, String>> taskConfigs = new ArrayList<>(1);
        taskConfigs.add(configProperties);

        if (maxTasks > 1) {
            log.warn("Multiple tasks requested ({}), but OTLP receiver only supports single task. " +
                    "Only one task will be created.", maxTasks);
        }

        return taskConfigs;
    }

    @Override
    public void stop() {
        log.info("Stopping OpenTelemetry OTLP Source Connector");
    }

    @Override
    public ConfigDef config() {
        return OpenTelemetrySourceConnectorConfig.CONFIG_DEF;
    }
}
