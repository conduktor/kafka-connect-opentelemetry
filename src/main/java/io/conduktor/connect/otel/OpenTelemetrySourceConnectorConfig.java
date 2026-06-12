package io.conduktor.connect.otel;

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.config.types.Password;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configuration for the OpenTelemetry OTLP Source Connector.
 */
public class OpenTelemetrySourceConnectorConfig extends AbstractConfig {

    // OTLP gRPC configuration
    public static final String OTLP_GRPC_ENABLED_CONFIG = "otlp.grpc.enabled";
    private static final String OTLP_GRPC_ENABLED_DOC = "Enable OTLP gRPC receiver";

    public static final String OTLP_GRPC_PORT_CONFIG = "otlp.grpc.port";
    private static final String OTLP_GRPC_PORT_DOC = "Port for OTLP gRPC receiver";

    // OTLP HTTP configuration
    public static final String OTLP_HTTP_ENABLED_CONFIG = "otlp.http.enabled";
    private static final String OTLP_HTTP_ENABLED_DOC = "Enable OTLP HTTP receiver";

    public static final String OTLP_HTTP_PORT_CONFIG = "otlp.http.port";
    private static final String OTLP_HTTP_PORT_DOC = "Port for OTLP HTTP receiver";

    // TLS configuration
    public static final String OTLP_TLS_ENABLED_CONFIG = "otlp.tls.enabled";
    private static final String OTLP_TLS_ENABLED_DOC = "Enable TLS for OTLP receivers";

    public static final String OTLP_TLS_CERT_PATH_CONFIG = "otlp.tls.cert.path";
    private static final String OTLP_TLS_CERT_PATH_DOC = "Path to TLS certificate file (PEM format)";

    public static final String OTLP_TLS_KEY_PATH_CONFIG = "otlp.tls.key.path";
    private static final String OTLP_TLS_KEY_PATH_DOC = "Path to TLS private key file (PEM format)";

    // Kafka topic configuration
    public static final String KAFKA_TOPIC_TRACES_CONFIG = "kafka.topic.traces";
    private static final String KAFKA_TOPIC_TRACES_DOC = "Kafka topic for OTLP trace data";

    public static final String KAFKA_TOPIC_METRICS_CONFIG = "kafka.topic.metrics";
    private static final String KAFKA_TOPIC_METRICS_DOC = "Kafka topic for OTLP metric data";

    public static final String KAFKA_TOPIC_LOGS_CONFIG = "kafka.topic.logs";
    private static final String KAFKA_TOPIC_LOGS_DOC = "Kafka topic for OTLP log data";

    // Message format configuration
    public static final String OTLP_MESSAGE_FORMAT_CONFIG = "otlp.message.format";
    private static final String OTLP_MESSAGE_FORMAT_DOC = "Output format for OTLP data: 'json' or 'protobuf'";

    // Queue configuration
    public static final String OTLP_MESSAGE_QUEUE_SIZE_CONFIG = "otlp.message.queue.size";
    private static final String OTLP_MESSAGE_QUEUE_SIZE_DOC = "Maximum size of the message buffer queue for each signal type";

    // Bind address configuration
    public static final String OTLP_BIND_ADDRESS_CONFIG = "otlp.bind.address";
    private static final String OTLP_BIND_ADDRESS_DOC = "Bind address for OTLP receivers (default: 0.0.0.0)";

    // Authentication configuration
    public static final String OTLP_AUTH_ENABLED_CONFIG = "otlp.auth.enabled";
    private static final String OTLP_AUTH_ENABLED_DOC =
            "Enable authentication on the OTLP gRPC and HTTP receivers. When disabled, the ports accept "
                    + "any incoming telemetry (default: false).";

    public static final String OTLP_AUTH_METHODS_CONFIG = "otlp.auth.methods";
    private static final String OTLP_AUTH_METHODS_DOC =
            "Comma-separated list of enabled authentication methods. Supported values: 'api-key', 'oidc'. "
                    + "A request is accepted if it satisfies any one of the configured methods.";

    public static final String OTLP_AUTH_API_KEY_CONFIG = "otlp.auth.api-key";
    private static final String OTLP_AUTH_API_KEY_DOC =
            "Comma-separated list of valid API keys. A request authenticates if its key header matches "
                    + "any of these values. Stored as a password (masked in logs and the REST API).";

    public static final String OTLP_AUTH_API_KEY_HEADER_CONFIG = "otlp.auth.api-key.header";
    private static final String OTLP_AUTH_API_KEY_HEADER_DOC =
            "Header (HTTP) / metadata key (gRPC) carrying the API key (default: x-api-key).";

    public static final String OTLP_AUTH_OIDC_ISSUER_CONFIG = "otlp.auth.oidc.issuer";
    private static final String OTLP_AUTH_OIDC_ISSUER_DOC =
            "OIDC issuer URL. Bearer tokens must carry a matching 'iss' claim. Used to discover the JWKS "
                    + "endpoint when otlp.auth.oidc.jwks-uri is not set.";

    public static final String OTLP_AUTH_OIDC_JWKS_URI_CONFIG = "otlp.auth.oidc.jwks-uri";
    private static final String OTLP_AUTH_OIDC_JWKS_URI_DOC =
            "Explicit JWKS URI for fetching token-signing public keys. When empty, it is derived from the "
                    + "issuer's /.well-known/openid-configuration document.";

    public static final String OTLP_AUTH_OIDC_AUDIENCE_CONFIG = "otlp.auth.oidc.audience";
    private static final String OTLP_AUTH_OIDC_AUDIENCE_DOC =
            "Expected 'aud' claim. When set, tokens whose audience does not include this value are rejected. "
                    + "When empty, the audience claim is not checked.";

    public static final ConfigDef CONFIG_DEF = createConfigDef();

    private static ConfigDef createConfigDef() {
        return new ConfigDef()
                // gRPC configuration
                .define(
                        OTLP_GRPC_ENABLED_CONFIG,
                        Type.BOOLEAN,
                        true,
                        Importance.HIGH,
                        OTLP_GRPC_ENABLED_DOC
                )
                .define(
                        OTLP_GRPC_PORT_CONFIG,
                        Type.INT,
                        4317,
                        ConfigDef.Range.between(1, 65535),
                        Importance.HIGH,
                        OTLP_GRPC_PORT_DOC
                )
                // HTTP configuration
                .define(
                        OTLP_HTTP_ENABLED_CONFIG,
                        Type.BOOLEAN,
                        true,
                        Importance.HIGH,
                        OTLP_HTTP_ENABLED_DOC
                )
                .define(
                        OTLP_HTTP_PORT_CONFIG,
                        Type.INT,
                        4318,
                        ConfigDef.Range.between(1, 65535),
                        Importance.HIGH,
                        OTLP_HTTP_PORT_DOC
                )
                // TLS configuration
                .define(
                        OTLP_TLS_ENABLED_CONFIG,
                        Type.BOOLEAN,
                        false,
                        Importance.MEDIUM,
                        OTLP_TLS_ENABLED_DOC
                )
                .define(
                        OTLP_TLS_CERT_PATH_CONFIG,
                        Type.STRING,
                        null,
                        new FilePathValidator(),
                        Importance.MEDIUM,
                        OTLP_TLS_CERT_PATH_DOC
                )
                .define(
                        OTLP_TLS_KEY_PATH_CONFIG,
                        Type.STRING,
                        null,
                        new FilePathValidator(),
                        Importance.MEDIUM,
                        OTLP_TLS_KEY_PATH_DOC
                )
                // Kafka topics
                .define(
                        KAFKA_TOPIC_TRACES_CONFIG,
                        Type.STRING,
                        "otlp-traces",
                        Importance.HIGH,
                        KAFKA_TOPIC_TRACES_DOC
                )
                .define(
                        KAFKA_TOPIC_METRICS_CONFIG,
                        Type.STRING,
                        "otlp-metrics",
                        Importance.HIGH,
                        KAFKA_TOPIC_METRICS_DOC
                )
                .define(
                        KAFKA_TOPIC_LOGS_CONFIG,
                        Type.STRING,
                        "otlp-logs",
                        Importance.HIGH,
                        KAFKA_TOPIC_LOGS_DOC
                )
                // Message format
                .define(
                        OTLP_MESSAGE_FORMAT_CONFIG,
                        Type.STRING,
                        "json",
                        new MessageFormatValidator(),
                        Importance.MEDIUM,
                        OTLP_MESSAGE_FORMAT_DOC
                )
                // Queue size
                .define(
                        OTLP_MESSAGE_QUEUE_SIZE_CONFIG,
                        Type.INT,
                        10000,
                        ConfigDef.Range.between(100, 1000000),
                        Importance.MEDIUM,
                        OTLP_MESSAGE_QUEUE_SIZE_DOC
                )
                // Bind address
                .define(
                        OTLP_BIND_ADDRESS_CONFIG,
                        Type.STRING,
                        "0.0.0.0",
                        Importance.LOW,
                        OTLP_BIND_ADDRESS_DOC
                )
                // Authentication
                .define(
                        OTLP_AUTH_ENABLED_CONFIG,
                        Type.BOOLEAN,
                        false,
                        Importance.HIGH,
                        OTLP_AUTH_ENABLED_DOC
                )
                .define(
                        OTLP_AUTH_METHODS_CONFIG,
                        Type.LIST,
                        Collections.emptyList(),
                        new AuthMethodsValidator(),
                        Importance.HIGH,
                        OTLP_AUTH_METHODS_DOC
                )
                .define(
                        OTLP_AUTH_API_KEY_CONFIG,
                        Type.PASSWORD,
                        null,
                        Importance.HIGH,
                        OTLP_AUTH_API_KEY_DOC
                )
                .define(
                        OTLP_AUTH_API_KEY_HEADER_CONFIG,
                        Type.STRING,
                        "x-api-key",
                        Importance.MEDIUM,
                        OTLP_AUTH_API_KEY_HEADER_DOC
                )
                .define(
                        OTLP_AUTH_OIDC_ISSUER_CONFIG,
                        Type.STRING,
                        "",
                        Importance.HIGH,
                        OTLP_AUTH_OIDC_ISSUER_DOC
                )
                .define(
                        OTLP_AUTH_OIDC_JWKS_URI_CONFIG,
                        Type.STRING,
                        "",
                        Importance.MEDIUM,
                        OTLP_AUTH_OIDC_JWKS_URI_DOC
                )
                .define(
                        OTLP_AUTH_OIDC_AUDIENCE_CONFIG,
                        Type.STRING,
                        "",
                        Importance.MEDIUM,
                        OTLP_AUTH_OIDC_AUDIENCE_DOC
                );
    }

    /** Supported authentication method identifiers. */
    public static final String AUTH_METHOD_API_KEY = "api-key";
    public static final String AUTH_METHOD_OIDC = "oidc";

    public OpenTelemetrySourceConnectorConfig(Map<?, ?> originals) {
        super(CONFIG_DEF, originals);
    }

    // Getters

    public boolean isGrpcEnabled() {
        return getBoolean(OTLP_GRPC_ENABLED_CONFIG);
    }

    public int getGrpcPort() {
        return getInt(OTLP_GRPC_PORT_CONFIG);
    }

    public boolean isHttpEnabled() {
        return getBoolean(OTLP_HTTP_ENABLED_CONFIG);
    }

    public int getHttpPort() {
        return getInt(OTLP_HTTP_PORT_CONFIG);
    }

    public boolean isTlsEnabled() {
        return getBoolean(OTLP_TLS_ENABLED_CONFIG);
    }

    public String getTlsCertPath() {
        return getString(OTLP_TLS_CERT_PATH_CONFIG);
    }

    public String getTlsKeyPath() {
        return getString(OTLP_TLS_KEY_PATH_CONFIG);
    }

    public String getKafkaTopicTraces() {
        return getString(KAFKA_TOPIC_TRACES_CONFIG);
    }

    public String getKafkaTopicMetrics() {
        return getString(KAFKA_TOPIC_METRICS_CONFIG);
    }

    public String getKafkaTopicLogs() {
        return getString(KAFKA_TOPIC_LOGS_CONFIG);
    }

    public String getMessageFormat() {
        return getString(OTLP_MESSAGE_FORMAT_CONFIG);
    }

    public int getMessageQueueSize() {
        return getInt(OTLP_MESSAGE_QUEUE_SIZE_CONFIG);
    }

    public String getBindAddress() {
        return getString(OTLP_BIND_ADDRESS_CONFIG);
    }

    public boolean isAuthEnabled() {
        return getBoolean(OTLP_AUTH_ENABLED_CONFIG);
    }

    /**
     * Enabled authentication methods, normalized to lowercase.
     */
    public List<String> getAuthMethods() {
        List<String> raw = getList(OTLP_AUTH_METHODS_CONFIG);
        List<String> normalized = new ArrayList<>(raw.size());
        for (String method : raw) {
            normalized.add(method.trim().toLowerCase());
        }
        return Collections.unmodifiableList(normalized);
    }

    public boolean isApiKeyAuthEnabled() {
        return getAuthMethods().contains(AUTH_METHOD_API_KEY);
    }

    public boolean isOidcAuthEnabled() {
        return getAuthMethods().contains(AUTH_METHOD_OIDC);
    }

    /**
     * Configured API key(s). Returns null when unset.
     */
    public Password getApiKey() {
        return getPassword(OTLP_AUTH_API_KEY_CONFIG);
    }

    public String getApiKeyHeader() {
        return getString(OTLP_AUTH_API_KEY_HEADER_CONFIG);
    }

    public String getOidcIssuer() {
        return getString(OTLP_AUTH_OIDC_ISSUER_CONFIG);
    }

    public String getOidcJwksUri() {
        return getString(OTLP_AUTH_OIDC_JWKS_URI_CONFIG);
    }

    public String getOidcAudience() {
        return getString(OTLP_AUTH_OIDC_AUDIENCE_CONFIG);
    }

    /**
     * Validator for message format configuration.
     */
    private static class MessageFormatValidator implements ConfigDef.Validator {
        @Override
        public void ensureValid(String name, Object value) {
            if (value == null) {
                throw new ConfigException(name, value, "Message format cannot be null");
            }

            String format = (String) value;
            if (!format.equalsIgnoreCase("json") && !format.equalsIgnoreCase("protobuf")) {
                throw new ConfigException(name, value,
                        "Message format must be 'json' or 'protobuf', got: " + format);
            }
        }

        @Override
        public String toString() {
            return "Valid message format: 'json' or 'protobuf'";
        }
    }

    /**
     * Validator for the authentication methods list. Each entry must be a supported method.
     */
    private static class AuthMethodsValidator implements ConfigDef.Validator {
        private static final Set<String> SUPPORTED = Set.of(AUTH_METHOD_API_KEY, AUTH_METHOD_OIDC);

        @Override
        @SuppressWarnings("unchecked")
        public void ensureValid(String name, Object value) {
            if (value == null) {
                return;
            }
            List<String> methods = (List<String>) value;
            for (String method : methods) {
                String normalized = method.trim().toLowerCase();
                if (!SUPPORTED.contains(normalized)) {
                    throw new ConfigException(name, value,
                            "Unsupported authentication method '" + method + "'. Supported: " + SUPPORTED);
                }
            }
        }

        @Override
        public String toString() {
            return "Any subset of " + SUPPORTED;
        }
    }

    /**
     * Validator for file path configuration.
     * Only validates if TLS is enabled.
     */
    private static class FilePathValidator implements ConfigDef.Validator {
        @Override
        public void ensureValid(String name, Object value) {
            // Allow null - validation happens in connector when TLS is enabled
            if (value == null) {
                return;
            }

            String path = (String) value;
            if (path.trim().isEmpty()) {
                throw new ConfigException(name, value, "File path cannot be empty");
            }

            File file = new File(path);
            if (!file.exists()) {
                throw new ConfigException(name, value, "File does not exist: " + path);
            }

            if (!file.isFile()) {
                throw new ConfigException(name, value, "Path is not a file: " + path);
            }

            if (!file.canRead()) {
                throw new ConfigException(name, value, "File is not readable: " + path);
            }
        }

        @Override
        public String toString() {
            return "Valid readable file path";
        }
    }
}
