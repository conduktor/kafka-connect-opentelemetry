# Configuration Reference

Complete reference for all configuration options of the Kafka Connect OpenTelemetry Source Connector.

## Quick Reference

### Minimal Configuration

Only the connector class is required - all other settings have sensible defaults:

```json
{
  "name": "otlp-connector",
  "config": {
    "connector.class": "io.conduktor.connect.otel.OpenTelemetrySourceConnector",
    "tasks.max": "1"
  }
}
```

This uses:
- gRPC on port 4317
- HTTP on port 4318
- Topics: `otlp-traces`, `otlp-metrics`, `otlp-logs`
- JSON output format
- 10,000 message queue size per signal type

## Configuration Parameters

### Required Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `connector.class` | string | Must be `io.conduktor.connect.otel.OpenTelemetrySourceConnector` |
| `tasks.max` | int | Must be `1` (connector only supports single task) |

### OTLP Receiver Configuration

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `otlp.grpc.enabled` | boolean | `true` | Enable OTLP gRPC receiver |
| `otlp.grpc.port` | int | `4317` | Port for OTLP gRPC receiver (standard OTLP port) |
| `otlp.http.enabled` | boolean | `true` | Enable OTLP HTTP receiver |
| `otlp.http.port` | int | `4318` | Port for OTLP HTTP receiver (standard OTLP port) |
| `otlp.bind.address` | string | `0.0.0.0` | Bind address for OTLP receivers (use `127.0.0.1` for localhost only) |

!!! warning "At Least One Protocol Required"
    You must enable at least one protocol (gRPC or HTTP). Setting both to `false` will cause the connector to fail.

### Kafka Topic Configuration

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `kafka.topic.traces` | string | `otlp-traces` | Kafka topic for trace data (OTLP `TracesData`) |
| `kafka.topic.metrics` | string | `otlp-metrics` | Kafka topic for metric data (OTLP `MetricsData`) |
| `kafka.topic.logs` | string | `otlp-logs` | Kafka topic for log data (OTLP `LogsData`) |

!!! tip "Topic Naming Convention"
    Use descriptive topic names that include:
    - Signal type (traces/metrics/logs)
    - Environment (prod/staging/dev)
    - Application or team name

    Examples: `prod-checkout-traces`, `staging-payment-metrics`, `dev-analytics-logs`

### Message Format Configuration

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `otlp.message.format` | string | `json` | Output format: `json` or `protobuf` |
| `otlp.message.queue.size` | int | `10000` | Maximum size of message buffer queue per signal type |

#### Format Comparison

| Aspect | JSON | Protobuf |
|--------|------|----------|
| **Size** | Larger (3-5x) | Smaller (binary) |
| **Readability** | Human-readable | Base64-encoded binary |
| **Debugging** | Easy to inspect | Requires decoding |
| **Performance** | Slower serialization | Faster serialization |
| **Downstream** | Easy JSON processing | Requires protobuf decoder |
| **Best for** | Development, debugging | Production, high volume |

### TLS Configuration (Planned)

!!! info "Coming Soon"
    TLS support is planned for a future release.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `otlp.tls.enabled` | boolean | `false` | Enable TLS for OTLP receivers |
| `otlp.tls.cert.path` | string | - | Path to TLS certificate file (PEM format) |
| `otlp.tls.key.path` | string | - | Path to TLS private key file (PEM format) |

## Configuration Examples

### Production Configuration

High-throughput production setup with protobuf format:

```json
{
  "name": "otlp-production",
  "config": {
    "connector.class": "io.conduktor.connect.otel.OpenTelemetrySourceConnector",
    "tasks.max": "1",

    "otlp.grpc.enabled": "true",
    "otlp.grpc.port": "4317",
    "otlp.http.enabled": "true",
    "otlp.http.port": "4318",
    "otlp.bind.address": "0.0.0.0",

    "kafka.topic.traces": "prod-otlp-traces",
    "kafka.topic.metrics": "prod-otlp-metrics",
    "kafka.topic.logs": "prod-otlp-logs",

    "otlp.message.format": "protobuf",
    "otlp.message.queue.size": "50000"
  }
}
```

### Development Configuration

Development setup with JSON format for easy debugging:

```json
{
  "name": "otlp-dev",
  "config": {
    "connector.class": "io.conduktor.connect.otel.OpenTelemetrySourceConnector",
    "tasks.max": "1",

    "otlp.grpc.enabled": "true",
    "otlp.grpc.port": "4317",
    "otlp.http.enabled": "true",
    "otlp.http.port": "4318",
    "otlp.bind.address": "127.0.0.1",

    "kafka.topic.traces": "dev-traces",
    "kafka.topic.metrics": "dev-metrics",
    "kafka.topic.logs": "dev-logs",

    "otlp.message.format": "json",
    "otlp.message.queue.size": "5000"
  }
}
```

### gRPC Only Configuration

Accept only gRPC connections (disable HTTP):

```json
{
  "name": "otlp-grpc-only",
  "config": {
    "connector.class": "io.conduktor.connect.otel.OpenTelemetrySourceConnector",
    "tasks.max": "1",

    "otlp.grpc.enabled": "true",
    "otlp.grpc.port": "4317",
    "otlp.http.enabled": "false",

    "kafka.topic.traces": "otlp-traces",
    "kafka.topic.metrics": "otlp-metrics",
    "kafka.topic.logs": "otlp-logs",

    "otlp.message.format": "json"
  }
}
```

### HTTP Only Configuration

Accept only HTTP connections (disable gRPC):

```json
{
  "name": "otlp-http-only",
  "config": {
    "connector.class": "io.conduktor.connect.otel.OpenTelemetrySourceConnector",
    "tasks.max": "1",

    "otlp.grpc.enabled": "false",
    "otlp.http.enabled": "true",
    "otlp.http.port": "4318",

    "kafka.topic.traces": "otlp-traces",
    "kafka.topic.metrics": "otlp-metrics",
    "kafka.topic.logs": "otlp-logs",

    "otlp.message.format": "json"
  }
}
```

### Custom Ports Configuration

Use non-standard ports (e.g., when standard ports are in use):

```json
{
  "name": "otlp-custom-ports",
  "config": {
    "connector.class": "io.conduktor.connect.otel.OpenTelemetrySourceConnector",
    "tasks.max": "1",

    "otlp.grpc.enabled": "true",
    "otlp.grpc.port": "14317",
    "otlp.http.enabled": "true",
    "otlp.http.port": "14318",

    "kafka.topic.traces": "otlp-traces",
    "kafka.topic.metrics": "otlp-metrics",
    "kafka.topic.logs": "otlp-logs"
  }
}
```

### Multi-Environment Setup

Separate configurations for different environments:

=== "Production"

    ```json
    {
      "name": "otlp-prod",
      "config": {
        "connector.class": "io.conduktor.connect.otel.OpenTelemetrySourceConnector",
        "tasks.max": "1",

        "otlp.grpc.port": "4317",
        "otlp.http.port": "4318",

        "kafka.topic.traces": "prod-traces",
        "kafka.topic.metrics": "prod-metrics",
        "kafka.topic.logs": "prod-logs",

        "otlp.message.format": "protobuf",
        "otlp.message.queue.size": "50000"
      }
    }
    ```

=== "Staging"

    ```json
    {
      "name": "otlp-staging",
      "config": {
        "connector.class": "io.conduktor.connect.otel.OpenTelemetrySourceConnector",
        "tasks.max": "1",

        "otlp.grpc.port": "5317",
        "otlp.http.port": "5318",

        "kafka.topic.traces": "staging-traces",
        "kafka.topic.metrics": "staging-metrics",
        "kafka.topic.logs": "staging-logs",

        "otlp.message.format": "json",
        "otlp.message.queue.size": "20000"
      }
    }
    ```

=== "Development"

    ```json
    {
      "name": "otlp-dev",
      "config": {
        "connector.class": "io.conduktor.connect.otel.OpenTelemetrySourceConnector",
        "tasks.max": "1",

        "otlp.grpc.port": "6317",
        "otlp.http.port": "6318",

        "kafka.topic.traces": "dev-traces",
        "kafka.topic.metrics": "dev-metrics",
        "kafka.topic.logs": "dev-logs",

        "otlp.message.format": "json",
        "otlp.message.queue.size": "5000"
      }
    }
    ```

## Performance Tuning

### Queue Size Recommendations

Choose queue size based on your expected throughput and latency requirements:

| Throughput | Queue Size | Memory Impact | Use Case |
|------------|------------|---------------|----------|
| Low (< 100 msg/s) | 1,000 - 5,000 | ~10-50 MB | Development, testing |
| Medium (100-1,000 msg/s) | 10,000 | ~100 MB | Standard production |
| High (1,000-5,000 msg/s) | 20,000 - 50,000 | ~200-500 MB | High-volume production |
| Very High (> 5,000 msg/s) | 50,000 - 100,000 | ~500 MB - 1 GB | Extreme throughput |

!!! warning "Memory Impact"
    Memory usage = `queue_size × avg_message_size × 3 queues`

    Example: 50,000 queue × 2 KB avg × 3 = ~300 MB

### Format Selection Guide

| Scenario | Recommended Format | Reason |
|----------|-------------------|--------|
| Development | JSON | Easy debugging, human-readable |
| Testing | JSON | Simple validation, inspection |
| Production (low volume) | JSON | Flexibility, easier troubleshooting |
| Production (high volume) | Protobuf | Smaller size, better performance |
| Compliance/Audit | JSON | Long-term readability |
| Real-time analytics | Protobuf | Lower latency, higher throughput |

## Deployment Workflow

### 1. Create Configuration File

```bash
cat > otlp-connector.json <<EOF
{
  "name": "otlp-source",
  "config": {
    "connector.class": "io.conduktor.connect.otel.OpenTelemetrySourceConnector",
    "tasks.max": "1",
    "otlp.grpc.port": "4317",
    "otlp.http.port": "4318",
    "kafka.topic.traces": "otlp-traces",
    "kafka.topic.metrics": "otlp-metrics",
    "kafka.topic.logs": "otlp-logs",
    "otlp.message.format": "json",
    "otlp.message.queue.size": "10000"
  }
}
EOF
```

### 2. Deploy Connector

```bash
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @otlp-connector.json
```

### 3. Verify Deployment

```bash
# Check connector status
curl http://localhost:8083/connectors/otlp-source/status | jq .

# Expected output:
# {
#   "name": "otlp-source",
#   "connector": {
#     "state": "RUNNING",
#     "worker_id": "..."
#   },
#   "tasks": [
#     {
#       "id": 0,
#       "state": "RUNNING",
#       "worker_id": "..."
#     }
#   ]
# }
```

### 4. Test OTLP Endpoints

```bash
# Test gRPC endpoint
grpcurl -plaintext localhost:4317 list

# Test HTTP endpoint
curl -v http://localhost:4318/v1/traces
```

## Configuration Updates

### Update Running Connector

To update configuration without recreating:

```bash
# 1. Update configuration file
cat > updated-config.json <<EOF
{
  "connector.class": "io.conduktor.connect.otel.OpenTelemetrySourceConnector",
  "tasks.max": "1",
  "otlp.message.queue.size": "20000",
  "otlp.message.format": "protobuf"
}
EOF

# 2. Apply update
curl -X PUT http://localhost:8083/connectors/otlp-source/config \
  -H "Content-Type: application/json" \
  -d @updated-config.json

# 3. Verify update
curl http://localhost:8083/connectors/otlp-source/status
```

### Configuration Changes Requiring Restart

The following configuration changes require a connector restart:

- `otlp.grpc.enabled`
- `otlp.grpc.port`
- `otlp.http.enabled`
- `otlp.http.port`
- `otlp.bind.address`

The connector will automatically restart when you update these via PUT.

### Configuration Changes Without Restart

These can be changed without restart (applied to new messages only):

- `kafka.topic.traces`
- `kafka.topic.metrics`
- `kafka.topic.logs`
- `otlp.message.format`
- `otlp.message.queue.size`

## Validation

The connector validates configuration on startup:

### Common Validation Errors

| Error Message | Cause | Solution |
|--------------|-------|----------|
| "Invalid value for otlp.grpc.port" | Port out of range | Use port 1024-65535 |
| "Invalid value for otlp.message.format" | Unknown format | Use `json` or `protobuf` |
| "At least one protocol must be enabled" | Both protocols disabled | Enable gRPC or HTTP |
| "Invalid value for otlp.message.queue.size" | Queue size < 1 | Use positive integer |

### Validate Before Deployment

Use Kafka Connect's validation endpoint:

```bash
curl -X PUT http://localhost:8083/connector-plugins/io.conduktor.connect.otel.OpenTelemetrySourceConnector/config/validate \
  -H "Content-Type: application/json" \
  -d @otlp-connector.json
```

## Best Practices

### Production Checklist

- [x] Use `protobuf` format for high volume
- [x] Set `otlp.message.queue.size` based on throughput
- [x] Use descriptive topic names with environment prefix
- [x] Pre-create Kafka topics with appropriate partitions
- [x] Configure JMX monitoring
- [x] Set up alerts for queue utilization
- [x] Document connector configuration in version control
- [x] Test with realistic load before production

### Security Considerations

- Use `otlp.bind.address: "127.0.0.1"` if receiving telemetry from localhost only
- Plan for TLS when feature is released
- Secure Kafka Connect REST API with authentication
- Restrict network access to OTLP ports (4317, 4318)
- Use Kafka ACLs to control topic access

## Troubleshooting Configuration

### Port Already in Use

```bash
# Find what's using the port
lsof -i :4317

# Use different port
"otlp.grpc.port": "14317"
```

### Queue Overflow

```bash
# Check JMX metrics for drops
jconsole # Navigate to TracesDropped/MetricsDropped/LogsDropped

# Increase queue size
"otlp.message.queue.size": "50000"
```

### Wrong Message Format

```bash
# Check what format downstream consumers expect
# JSON: Easier to process with Kafka Streams, ksqlDB
# Protobuf: Requires protobuf decoder

# Change format
"otlp.message.format": "json"  # or "protobuf"
```

## Next Steps

- [Operational Runbook](../operations/RUNBOOK.md) - Monitoring and troubleshooting
- [FAQ](../faq.md) - Common questions
- [Main README](https://github.com/conduktor/kafka-connect-opentelemetry/blob/main/README.md) - Examples and usage

---

**Need help?** [Open an issue](https://github.com/conduktor/kafka-connect-opentelemetry/issues) or [join our Slack](https://conduktor.io/slack).
