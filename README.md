# Kafka Connect OpenTelemetry Source Connector

A Kafka Connect source connector that receives OpenTelemetry Protocol (OTLP) telemetry data and streams it into Apache Kafka topics.

**[Documentation](https://conduktor.github.io/kafka-connect-opentelemetry)**

## Architecture

```
┌─────────────────────┐     ┌───────────────────────────────┐     ┌─────────────────┐
│   OpenTelemetry     │     │        Kafka Connect          │     │   Apache Kafka  │
│   Instrumented Apps │────▶│  OpenTelemetry Source Connector│────▶│     Topics      │
└─────────────────────┘     └───────────────────────────────┘     └─────────────────┘
         │                              │                              │
   OTLP gRPC (4317)             Routes by signal type          otlp-traces
   OTLP HTTP (4318)             Converts to JSON/Protobuf      otlp-metrics
                                                               otlp-logs
```

## Features

- Dual protocol support: OTLP gRPC (4317) and HTTP (4318)
- Automatic routing of traces, metrics, and logs to separate topics
- JSON or Protobuf output format
- Configurable message buffering per signal type
- JMX metrics for monitoring
- TLS support (optional)

## Quick Start

### Installation

```bash
# Download the pre-built JAR
wget https://github.com/conduktor/kafka-connect-opentelemetry/releases/download/v1.0.0/kafka-connect-opentelemetry-1.0.0-jar-with-dependencies.jar

# Copy to Kafka Connect plugins directory
mkdir -p $KAFKA_HOME/plugins/kafka-connect-opentelemetry
cp kafka-connect-opentelemetry-1.0.0-jar-with-dependencies.jar $KAFKA_HOME/plugins/kafka-connect-opentelemetry/

# Restart Kafka Connect
systemctl restart kafka-connect
```

### Deploy a Connector

```bash
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d '{
    "name": "otlp-receiver",
    "config": {
      "connector.class": "io.conduktor.connect.otel.OpenTelemetrySourceConnector",
      "tasks.max": "1",
      "kafka.topic.traces": "otlp-traces",
      "kafka.topic.metrics": "otlp-metrics",
      "kafka.topic.logs": "otlp-logs"
    }
  }'
```

### Configure Your Apps

Point your OpenTelemetry-instrumented applications to the connector:

```bash
# gRPC
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_EXPORTER_OTLP_PROTOCOL=grpc

# Or HTTP
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
```

### Verify

```bash
# Check connector status
curl http://localhost:8083/connectors/otlp-receiver/status

# Consume traces
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic otlp-traces --from-beginning
```

## Configuration

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `otlp.grpc.enabled` | No | true | Enable OTLP gRPC receiver |
| `otlp.grpc.port` | No | 4317 | gRPC receiver port |
| `otlp.http.enabled` | No | true | Enable OTLP HTTP receiver |
| `otlp.http.port` | No | 4318 | HTTP receiver port |
| `kafka.topic.traces` | No | otlp-traces | Topic for trace data |
| `kafka.topic.metrics` | No | otlp-metrics | Topic for metric data |
| `kafka.topic.logs` | No | otlp-logs | Topic for log data |
| `otlp.message.format` | No | json | Output format: `json` or `protobuf` |
| `otlp.message.queue.size` | No | 10000 | Buffer size per signal type |
| `otlp.bind.address` | No | 0.0.0.0 | Bind address for receivers |

## Limitations

- **Single task per connector**: OTLP receiver runs as a single server instance
- **At-most-once delivery**: Messages can be lost during shutdowns, crashes, or queue overflow
- **No replay capability**: OTLP protocol is push-based without replay support

> **Note**: Best suited for observability pipelines where telemetry data is continuously generated.

## Documentation

Full documentation is available at: **[conduktor.github.io/kafka-connect-opentelemetry](https://conduktor.github.io/kafka-connect-opentelemetry)**

- [Getting Started Guide](https://conduktor.github.io/kafka-connect-opentelemetry/getting-started/)
- [Configuration Reference](https://conduktor.github.io/kafka-connect-opentelemetry/getting-started/configuration/)
- [Monitoring & Operations](https://conduktor.github.io/kafka-connect-opentelemetry/operations/RUNBOOK/)
- [FAQ](https://conduktor.github.io/kafka-connect-opentelemetry/faq/)

## Development

### Building from Source

```bash
git clone https://github.com/conduktor/kafka-connect-opentelemetry.git
cd kafka-connect-opentelemetry
mvn clean package
```

Output: `target/kafka-connect-opentelemetry-1.0.0-jar-with-dependencies.jar`

### Running Tests

```bash
# Run unit tests
mvn test

# Run integration tests (requires Docker)
mvn verify

# Run specific test class
mvn test -Dtest=OpenTelemetrySourceConnectorConfigTest
```

### Test Coverage

The test suite includes:

- **Unit Tests**: Configuration validation, connector lifecycle, task management
- **Integration Tests**: OTLP receiver behavior, gRPC/HTTP protocol handling, TLS configuration
- **System Integration Tests** (`OtelConnectorSystemIT`): Full end-to-end testing with Testcontainers
  - Spins up Kafka and Kafka Connect containers
  - Deploys connector via REST API
  - Sends OTLP traces, metrics, and logs via both gRPC and HTTP
  - Verifies messages arrive in Kafka topics

> **Note**: System integration tests require Docker to be running

## License

Apache License 2.0 - see [LICENSE](LICENSE) for details.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidelines.
