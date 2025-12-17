# Kafka Connect OpenTelemetry Source Connector

A Kafka Connect source connector that receives [OpenTelemetry Protocol (OTLP)](https://opentelemetry.io/docs/specs/otlp/) telemetry data and streams it into Kafka topics.

This connector acts as an OTLP collector endpoint, receiving traces, metrics, and logs from OpenTelemetry-instrumented applications via gRPC or HTTP, and routing them to separate Kafka topics.

## Features

- **Dual Protocol Support**: Accepts OTLP data via both gRPC (port 4317) and HTTP (port 4318)
- **Signal Type Routing**: Automatically routes traces, metrics, and logs to separate Kafka topics
- **Flexible Output Format**: Supports both JSON and Protobuf (base64-encoded) output formats
- **High Throughput**: Configurable message queues with backpressure handling
- **Reliable Delivery**: Sequence-based offset management for message tracking and delivery guarantees
- **Observability**: Built-in JMX metrics for monitoring connector health and performance
- **TLS Support**: Optional TLS encryption for secure telemetry transmission (planned)

## Architecture

```
OpenTelemetry Apps
       |
       | OTLP/gRPC (4317)
       | OTLP/HTTP (4318)
       v
  OTLP Receiver
       |
  +----+----+----+
  |    |    |    |
Traces Metrics Logs
  |    |    |    |
  v    v    v    v
Kafka Topics
```

## Quick Start

### 1. Build the Connector

```bash
mvn clean package
```

This creates `target/kafka-connect-opentelemetry-1.0.0-jar-with-dependencies.jar`

### 2. Install in Kafka Connect

Copy the JAR to your Kafka Connect plugins directory:

```bash
cp target/kafka-connect-opentelemetry-1.0.0-jar-with-dependencies.jar \
   /path/to/kafka-connect/plugins/
```

Restart Kafka Connect to load the plugin.

### 3. Create Connector Configuration

Create a file `opentelemetry-source.json`:

```json
{
  "name": "otlp-source-connector",
  "config": {
    "connector.class": "io.conduktor.connect.otel.OpenTelemetrySourceConnector",
    "tasks.max": "1",

    "otlp.grpc.enabled": "true",
    "otlp.grpc.port": "4317",
    "otlp.http.enabled": "true",
    "otlp.http.port": "4318",

    "kafka.topic.traces": "otlp-traces",
    "kafka.topic.metrics": "otlp-metrics",
    "kafka.topic.logs": "otlp-logs",

    "otlp.message.format": "json",
    "otlp.message.queue.size": "10000"
  }
}
```

### 4. Deploy the Connector

Using Kafka Connect REST API:

```bash
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @opentelemetry-source.json
```

### 5. Send OTLP Data

Point your OpenTelemetry applications to the connector endpoint:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf

# Run your instrumented application
./my-app
```

## Configuration Options

### OTLP Receiver Configuration

| Configuration | Type | Default | Description |
|--------------|------|---------|-------------|
| `otlp.grpc.enabled` | boolean | `true` | Enable OTLP gRPC receiver |
| `otlp.grpc.port` | int | `4317` | Port for OTLP gRPC receiver |
| `otlp.http.enabled` | boolean | `true` | Enable OTLP HTTP receiver |
| `otlp.http.port` | int | `4318` | Port for OTLP HTTP receiver |
| `otlp.bind.address` | string | `0.0.0.0` | Bind address for OTLP receivers |

### Kafka Topic Configuration

| Configuration | Type | Default | Description |
|--------------|------|---------|-------------|
| `kafka.topic.traces` | string | `otlp-traces` | Kafka topic for trace data |
| `kafka.topic.metrics` | string | `otlp-metrics` | Kafka topic for metric data |
| `kafka.topic.logs` | string | `otlp-logs` | Kafka topic for log data |

### Message Format Configuration

| Configuration | Type | Default | Description |
|--------------|------|---------|-------------|
| `otlp.message.format` | string | `json` | Output format: `json` or `protobuf` |
| `otlp.message.queue.size` | int | `10000` | Maximum size of message buffer queue per signal type |

### TLS Configuration (Planned)

| Configuration | Type | Default | Description |
|--------------|------|---------|-------------|
| `otlp.tls.enabled` | boolean | `false` | Enable TLS for OTLP receivers |
| `otlp.tls.cert.path` | string | - | Path to TLS certificate file (PEM format) |
| `otlp.tls.key.path` | string | - | Path to TLS private key file (PEM format) |

## Configuration Examples

### Minimal Configuration (Defaults)

```json
{
  "name": "otlp-connector",
  "config": {
    "connector.class": "io.conduktor.connect.otel.OpenTelemetrySourceConnector",
    "tasks.max": "1"
  }
}
```

This uses all default values: gRPC on 4317, HTTP on 4318, JSON format, topics `otlp-traces`, `otlp-metrics`, `otlp-logs`.

### gRPC Only

```json
{
  "name": "otlp-grpc-only",
  "config": {
    "connector.class": "io.conduktor.connect.otel.OpenTelemetrySourceConnector",
    "tasks.max": "1",
    "otlp.grpc.enabled": "true",
    "otlp.grpc.port": "4317",
    "otlp.http.enabled": "false"
  }
}
```

### HTTP Only with Custom Topics

```json
{
  "name": "otlp-http-custom",
  "config": {
    "connector.class": "io.conduktor.connect.otel.OpenTelemetrySourceConnector",
    "tasks.max": "1",
    "otlp.grpc.enabled": "false",
    "otlp.http.enabled": "true",
    "otlp.http.port": "4318",
    "kafka.topic.traces": "my-app-traces",
    "kafka.topic.metrics": "my-app-metrics",
    "kafka.topic.logs": "my-app-logs"
  }
}
```

### Protobuf Format with Large Queue

```json
{
  "name": "otlp-protobuf",
  "config": {
    "connector.class": "io.conduktor.connect.otel.OpenTelemetrySourceConnector",
    "tasks.max": "1",
    "otlp.message.format": "protobuf",
    "otlp.message.queue.size": "50000"
  }
}
```

### Custom Ports

```json
{
  "name": "otlp-custom-ports",
  "config": {
    "connector.class": "io.conduktor.connect.otel.OpenTelemetrySourceConnector",
    "tasks.max": "1",
    "otlp.grpc.port": "14317",
    "otlp.http.port": "14318",
    "otlp.bind.address": "127.0.0.1"
  }
}
```

## Monitoring

### JMX Metrics

The connector exposes JMX metrics under the name:

```
io.conduktor.connect.otel:type=OpenTelemetryConnector,name=<connector-name>
```

Available metrics:

- **Counters**:
  - `TracesReceived` - Total traces received
  - `MetricsReceived` - Total metrics received
  - `LogsReceived` - Total logs received
  - `TracesDropped` - Traces dropped due to queue full
  - `MetricsDropped` - Metrics dropped due to queue full
  - `LogsDropped` - Logs dropped due to queue full
  - `RecordsProduced` - Total records produced to Kafka

- **Queue Metrics**:
  - `TracesQueueSize` - Current traces queue size
  - `MetricsQueueSize` - Current metrics queue size
  - `LogsQueueSize` - Current logs queue size
  - `QueueCapacity` - Maximum queue capacity
  - `MaxQueueUtilizationPercent` - Highest queue utilization percentage

- **Derived Metrics**:
  - `TotalMessagesReceived` - Sum of all messages received
  - `TotalMessagesDropped` - Sum of all messages dropped
  - `TotalLagCount` - Messages received but not yet produced to Kafka
  - `DropRate` - Percentage of messages dropped

### Viewing JMX Metrics

Using `jconsole` or `jmxterm`:

```bash
# Connect to Kafka Connect JVM
jconsole <kafka-connect-pid>

# Navigate to MBeans tab
# -> io.conduktor.connect.otel
#    -> OpenTelemetryConnector
#       -> name=<your-connector-name>
```

### Log Events

The connector uses structured logging with `event=` prefix for easy parsing:

- `event=task_starting` - Task initialization
- `event=otlp_receiver_started` - OTLP receivers started
- `event=trace_dropped` - Trace dropped due to queue full
- `event=task_metrics` - Periodic metrics log
- `event=task_stopping` - Graceful shutdown initiated

## OpenTelemetry SDK Configuration

### Java Example

```java
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
    .setEndpoint("http://localhost:4317")
    .build();

SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
    .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
    .build();

OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
    .setTracerProvider(tracerProvider)
    .build();
```

### Environment Variables

```bash
# gRPC endpoint
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_EXPORTER_OTLP_PROTOCOL=grpc

# HTTP endpoint
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
```

### Python Example

```python
from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor

trace.set_tracer_provider(TracerProvider())
tracer_provider = trace.get_tracer_provider()

otlp_exporter = OTLPSpanExporter(endpoint="http://localhost:4317")
tracer_provider.add_span_processor(BatchSpanProcessor(otlp_exporter))

tracer = trace.get_tracer(__name__)
```

## Message Format

### JSON Format (Default)

Traces, metrics, and logs are converted to JSON using the protobuf JSON mapping:

```json
{
  "resourceSpans": [
    {
      "resource": {
        "attributes": [
          {
            "key": "service.name",
            "value": {
              "stringValue": "my-service"
            }
          }
        ]
      },
      "scopeSpans": [
        {
          "spans": [
            {
              "traceId": "5B8EFFF798038103D269B633813FC60C",
              "spanId": "EEE19B7EC3C1B174",
              "name": "my-operation",
              "startTimeUnixNano": "1609459200000000000",
              "endTimeUnixNano": "1609459200500000000"
            }
          ]
        }
      ]
    }
  ]
}
```

### Protobuf Format

The protobuf binary is base64-encoded for safe transmission:

```
CiYKFgoKc2VydmljZS5uYW1lEggSBm15LXNlcnZpY2USDAoKbXktb3BlcmF0aW9u...
```

## Performance Tuning

### Queue Size

Increase `otlp.message.queue.size` for high-throughput scenarios:

```json
"otlp.message.queue.size": "50000"
```

Monitor the `MaxQueueUtilizationPercent` JMX metric to tune this value.

### Message Format

- **JSON**: Human-readable, easier to debug, larger message size
- **Protobuf**: Binary format, smaller message size, requires decoding

For high-volume production deployments, consider `protobuf` format.

### Tasks

The connector only supports `tasks.max=1` because it runs a single OTLP receiver server instance.

## Troubleshooting

### Messages Being Dropped

Check the JMX metrics for `TracesDropped`, `MetricsDropped`, or `LogsDropped`.

**Solution**: Increase `otlp.message.queue.size` or increase Kafka throughput.

### Connection Refused

Ensure the OTLP receiver ports are accessible:

```bash
netstat -an | grep 4317
netstat -an | grep 4318
```

**Solution**: Check firewall rules and `otlp.bind.address` configuration.

### High Queue Utilization

Monitor the `event=task_metrics` logs for `status=HIGH_QUEUE_UTILIZATION`.

**Solution**: Increase queue size or reduce incoming OTLP traffic.

## Development

### Building

```bash
mvn clean package
```

### Running Tests

```bash
mvn test
```

### Integration Tests

```bash
mvn verify
```

## License

Copyright (C) 2024 Conduktor

## Contributing

Contributions are welcome! Please open an issue or pull request.

## Related Projects

- [OpenTelemetry Collector](https://github.com/open-telemetry/opentelemetry-collector) - Official OTLP collector
- [Kafka Connect WebSocket](../kafka-connect-websocket) - WebSocket source connector
