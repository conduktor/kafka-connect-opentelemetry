# Quick Start Guide

This guide will help you get the OpenTelemetry OTLP Source Connector up and running quickly.

## Prerequisites

- Java 11 or higher
- Apache Kafka 3.x or Confluent Platform
- Maven (for building from source)

## 1. Build the Connector

```bash
mvn clean package
```

This creates `target/kafka-connect-opentelemetry-1.0.0-jar-with-dependencies.jar`

## 2. Install the Connector

Copy the JAR to your Kafka Connect plugins directory:

```bash
# For standalone mode
mkdir -p /path/to/kafka-connect/plugins/opentelemetry
cp target/kafka-connect-opentelemetry-1.0.0-jar-with-dependencies.jar \
   /path/to/kafka-connect/plugins/opentelemetry/

# For distributed mode
mkdir -p /usr/local/share/kafka/plugins/opentelemetry
cp target/kafka-connect-opentelemetry-1.0.0-jar-with-dependencies.jar \
   /usr/local/share/kafka/plugins/opentelemetry/
```

## 3. Configure Kafka Connect

Edit your Kafka Connect worker configuration to include the plugin path:

```properties
# connect-standalone.properties or connect-distributed.properties
plugin.path=/path/to/kafka-connect/plugins
```

## 4. Start Kafka Connect

### Standalone Mode

```bash
connect-standalone.sh config/connect-standalone.properties \
                      config/opentelemetry-source.json
```

### Distributed Mode

First start the Connect worker:

```bash
connect-distributed.sh config/connect-distributed.properties
```

Then deploy the connector via REST API:

```bash
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @config/opentelemetry-source.json
```

## 5. Verify the Connector

Check connector status:

```bash
curl http://localhost:8083/connectors/otlp-source-connector/status
```

You should see:

```json
{
  "name": "otlp-source-connector",
  "connector": {
    "state": "RUNNING",
    "worker_id": "..."
  },
  "tasks": [
    {
      "id": 0,
      "state": "RUNNING",
      "worker_id": "..."
    }
  ]
}
```

## 6. Test with OpenTelemetry

### Using Environment Variables

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
export OTEL_SERVICE_NAME=my-test-service

# Run your OpenTelemetry instrumented application
./my-app
```

### Using Python

```python
from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor

# Configure tracer
trace.set_tracer_provider(TracerProvider())
tracer_provider = trace.get_tracer_provider()

# Configure OTLP exporter pointing to the connector
otlp_exporter = OTLPSpanExporter(
    endpoint="http://localhost:4318/v1/traces"
)

# Add span processor
tracer_provider.add_span_processor(
    BatchSpanProcessor(otlp_exporter)
)

# Create a tracer
tracer = trace.get_tracer(__name__)

# Create a test span
with tracer.start_as_current_span("test-span"):
    print("Sending test trace to Kafka Connect OTLP connector")
```

### Using Java

```java
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

OtlpHttpSpanExporter spanExporter = OtlpHttpSpanExporter.builder()
    .setEndpoint("http://localhost:4318/v1/traces")
    .build();

SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
    .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
    .build();

OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
    .setTracerProvider(tracerProvider)
    .buildAndRegisterGlobal();

// Use the tracer
Tracer tracer = openTelemetry.getTracer("my-instrumentation");
Span span = tracer.spanBuilder("test-span").startSpan();
try {
    // Your code here
} finally {
    span.end();
}
```

## 7. Consume the Data from Kafka

```bash
# Consume traces
kafka-console-consumer --bootstrap-server localhost:9092 \
                       --topic otlp-traces \
                       --from-beginning

# Consume metrics
kafka-console-consumer --bootstrap-server localhost:9092 \
                       --topic otlp-metrics \
                       --from-beginning

# Consume logs
kafka-console-consumer --bootstrap-server localhost:9092 \
                       --topic otlp-logs \
                       --from-beginning
```

## 8. Monitor the Connector

### View JMX Metrics

```bash
# Using jconsole
jconsole <kafka-connect-pid>

# Navigate to:
# MBeans -> io.conduktor.connect.otel -> OpenTelemetryConnector -> name=otlp-source-connector
```

### Check Logs

```bash
tail -f /path/to/kafka/logs/connect.log | grep "event="
```

Look for events like:
- `event=task_starting` - Connector starting
- `event=otlp_receiver_started` - OTLP receivers ready
- `event=task_metrics` - Periodic health metrics

## Troubleshooting

### Connector Not Starting

1. Check if the JAR is in the plugin path:
   ```bash
   ls -l /path/to/plugins/opentelemetry/
   ```

2. Check Kafka Connect logs for errors:
   ```bash
   tail -f logs/connect.log
   ```

### No Data in Kafka Topics

1. Verify OTLP endpoints are accessible:
   ```bash
   netstat -an | grep 4317  # gRPC
   netstat -an | grep 4318  # HTTP
   ```

2. Test with curl:
   ```bash
   curl http://localhost:4318/v1/traces
   # Should return: {"error":"Only POST method is supported"}
   ```

3. Check connector metrics for dropped messages:
   ```bash
   curl http://localhost:8083/connectors/otlp-source-connector/status
   ```

### Messages Being Dropped

Check JMX metrics `TracesDropped`, `MetricsDropped`, `LogsDropped`.

**Solution**: Increase `otlp.message.queue.size` in connector config:

```json
{
  "config": {
    "otlp.message.queue.size": "50000"
  }
}
```

## Next Steps

- Review the full [README.md](README.md) for all configuration options
- Set up monitoring dashboards using JMX metrics
- Configure TLS for production deployments
- Integrate with your observability stack (Grafana, Jaeger, etc.)
