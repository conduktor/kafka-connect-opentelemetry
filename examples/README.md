# OpenTelemetry Connector Docker Example

This example demonstrates the OpenTelemetry Source Connector receiving OTLP telemetry data.

## Prerequisites

- Docker and Docker Compose
- Built connector JAR (run `mvn clean package` in parent directory)

## Quick Start

```bash
# Build the connector first
cd ..
mvn clean package -DskipTests

# Start the stack
cd examples
docker compose up -d

# Wait for services to be healthy (about 30-60 seconds)
docker compose ps

# Deploy the OpenTelemetry connector
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d '{
    "name": "otlp-source",
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
      "otlp.message.format": "json"
    }
  }'

# Check connector status
curl http://localhost:8083/connectors/otlp-source/status | jq

# View traces in Kafka (telemetrygen sends traces automatically)
docker exec kafka-otel /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic otlp-traces \
  --from-beginning \
  --max-messages 5
```

## What's Running

| Service | Port | Description |
|---------|------|-------------|
| Kafka | 9092 | Apache Kafka broker |
| Kafka Connect | 8083 | REST API for connector management |
| OTLP gRPC | 4317 | OpenTelemetry gRPC receiver |
| OTLP HTTP | 4318 | OpenTelemetry HTTP receiver |
| Conduktor Console | 8080 | Web UI for Kafka management |
| telemetrygen | - | Generates test traces |

## Testing with Different OTLP Clients

### Using telemetrygen (included in docker-compose)
The `telemetrygen` container automatically sends traces at 1/second.

### Using otel-cli
```bash
# Install otel-cli
brew install equinix-labs/otel-cli/otel-cli

# Send a trace
otel-cli exec \
  --endpoint localhost:4317 \
  --protocol grpc \
  --service my-service \
  --name "test-operation" \
  -- echo "Hello from OTEL!"
```

### Using curl (HTTP)
```bash
# Send a simple trace via HTTP
curl -X POST http://localhost:4318/v1/traces \
  -H "Content-Type: application/json" \
  -d '{
    "resourceSpans": [{
      "resource": {
        "attributes": [{
          "key": "service.name",
          "value": {"stringValue": "curl-test"}
        }]
      },
      "scopeSpans": [{
        "spans": [{
          "traceId": "5B8EFFF798038103D269B633813FC60C",
          "spanId": "EEE19B7EC3C1B174",
          "name": "test-span",
          "kind": 1,
          "startTimeUnixNano": "1609459200000000000",
          "endTimeUnixNano": "1609459200500000000"
        }]
      }]
    }]
  }'
```

## Viewing Data

### Via Console
Open http://localhost:8080 to access Conduktor Console and view the topics.

### Via CLI
```bash
# View traces
docker exec kafka-otel /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic otlp-traces \
  --from-beginning

# View metrics
docker exec kafka-otel /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic otlp-metrics \
  --from-beginning

# View logs
docker exec kafka-otel /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic otlp-logs \
  --from-beginning
```

## Cleanup

```bash
docker compose down -v
```
