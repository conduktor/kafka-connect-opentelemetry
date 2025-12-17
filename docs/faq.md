# Frequently Asked Questions

Common questions and answers about the Kafka Connect OpenTelemetry connector.

## General Questions

### What is this connector used for?

The Kafka Connect OpenTelemetry connector receives telemetry data (traces, metrics, logs) from OpenTelemetry-instrumented applications via OTLP protocol and streams it into Kafka topics. It's ideal for:

- Building observability pipelines with Kafka as the central data platform
- Buffering telemetry data before sending to observability backends
- Long-term storage and replay of traces, metrics, and logs
- Fan-out telemetry to multiple downstream consumers
- Decoupling applications from specific observability backends
- Aggregating telemetry from microservices architectures

### How does it differ from the OpenTelemetry Collector?

| Feature | Kafka Connect OTLP | OpenTelemetry Collector |
|---------|-------------------|------------------------|
| **Primary Purpose** | Kafka ingestion | Multi-backend export |
| **Kafka Support** | Native, built-in | Via Kafka exporter |
| **Signal Routing** | Automatic to topics | Manual configuration |
| **Deployment** | Kafka Connect framework | Standalone service |
| **Buffering** | Kafka topics (durable) | Memory (volatile) |
| **Transformations** | Kafka Streams/ksqlDB | Built-in processors |
| **Best For** | Kafka-centric pipelines | Direct backend export |

**Use both together**: Many deployments use this connector to ingest into Kafka, then use OTEL Collector to consume from Kafka and export to multiple backends.

### What delivery guarantees does this connector provide?

The connector provides **at-least-once** delivery semantics with sequence-based offset management:

- Messages are tracked with sequence numbers
- Offsets are committed to Kafka Connect offset storage
- On restart, the connector resumes from the last committed offset
- Messages may be duplicated during failures (at-least-once)
- Messages are never lost if Kafka is properly configured

!!! warning "Queue Overflow Scenarios"
    Messages can be dropped if all three signal queues fill up (when `TracesDropped`, `MetricsDropped`, or `LogsDropped` > 0). Monitor queue utilization and increase `otlp.message.queue.size` if needed.

## Installation & Setup

### Do I need to build from source?

**For now, yes.** Pre-built JARs will be available from GitHub Releases in future versions:

```bash
# Build from source
mvn clean package

# Copy JAR to plugin directory
cp target/kafka-connect-opentelemetry-1.0.0-jar-with-dependencies.jar \
   $KAFKA_HOME/plugins/kafka-connect-opentelemetry/
```

### Which Kafka version do I need?

**Minimum:** Kafka 3.9.0
**Recommended:** Latest stable Kafka version

The connector uses Kafka Connect API features available in 3.9.0+.

### Can I use this with Confluent Platform?

Yes, the connector works with:

- Apache Kafka (open source)
- Confluent Platform
- Amazon MSK (Managed Streaming for Kafka)
- Azure Event Hubs for Kafka
- Any Kafka-compatible platform supporting Connect API

### Where should I install the connector JAR?

Install in the Kafka Connect plugin directory:

```bash
# Default locations
/usr/local/share/kafka/plugins/kafka-connect-opentelemetry/
# or
$KAFKA_HOME/plugins/kafka-connect-opentelemetry/
```

Ensure `plugin.path` in `connect-distributed.properties` includes this location.

### Do I need separate dependency JARs?

**No.** The release JAR (`kafka-connect-opentelemetry-X.X.X-jar-with-dependencies.jar`) includes all dependencies (gRPC, Protobuf, etc.). Just download and deploy the single JAR file.

## Configuration

### What's the minimum configuration?

Only the connector class is required:

```json
{
  "connector.class": "io.conduktor.connect.otel.OpenTelemetrySourceConnector",
  "tasks.max": "1"
}
```

This uses defaults: gRPC on 4317, HTTP on 4318, topics `otlp-traces/metrics/logs`, JSON format.

### How do I receive only traces (or only metrics/logs)?

You can't disable specific signal types - the connector receives all three. However, you can:

1. **Route to the same topic** (if you don't need separation):
   ```json
   {
     "kafka.topic.traces": "all-telemetry",
     "kafka.topic.metrics": "all-telemetry",
     "kafka.topic.logs": "all-telemetry"
   }
   ```

2. **Filter downstream** using Kafka Streams or consumers

3. **Configure SDK to send only specific signals** (recommended approach)

### Should I use JSON or Protobuf format?

| Scenario | Recommended Format | Reason |
|----------|-------------------|--------|
| Development/Testing | JSON | Human-readable, easy debugging |
| Low-volume production | JSON | Simpler downstream processing |
| High-volume production | Protobuf | 3-5x smaller, faster serialization |
| Downstream JSON processors | JSON | No decoding needed |
| Downstream analytics (Spark, Flink) | Protobuf | More efficient processing |

**Tip:** Start with JSON, switch to Protobuf when scaling.

### Can I use custom topic names?

Yes! Use environment or application-specific names:

```json
{
  "kafka.topic.traces": "prod-checkout-traces",
  "kafka.topic.metrics": "prod-checkout-metrics",
  "kafka.topic.logs": "prod-checkout-logs"
}
```

### How many tasks should I configure?

**Always 1 task** (`tasks.max: "1"`).

The connector runs a single OTLP receiver server instance per connector. Each connector binds to specific ports (4317, 4318), so you cannot run multiple tasks.

To scale: Deploy multiple connector instances on different ports, not multiple tasks.

## OpenTelemetry SDK Integration

### Which OpenTelemetry SDKs are supported?

**All official OpenTelemetry SDKs** that support OTLP export:

- Java (opentelemetry-java)
- Python (opentelemetry-python)
- Go (opentelemetry-go)
- Node.js (opentelemetry-js)
- .NET (opentelemetry-dotnet)
- Ruby (opentelemetry-ruby)
- PHP (opentelemetry-php)
- Rust (opentelemetry-rust)

The connector implements standard OTLP gRPC and HTTP protocols.

### How do I configure my application to send to the connector?

**Environment variables (easiest):**

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://kafka-connect-host:4318
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
export OTEL_SERVICE_NAME=my-application
```

**Programmatic configuration:**

See examples in the [main documentation](index.md#opentelemetry-sdk-configuration-examples).

### Can I send traces and metrics to different endpoints?

Yes, use signal-specific environment variables:

```bash
# Traces to connector
export OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://kafka-connect:4318/v1/traces

# Metrics to different endpoint (e.g., Prometheus)
export OTEL_EXPORTER_OTLP_METRICS_ENDPOINT=http://prometheus:4318/v1/metrics

# Logs to connector
export OTEL_EXPORTER_OTLP_LOGS_ENDPOINT=http://kafka-connect:4318/v1/logs
```

### Does the connector support OTLP compression?

**Yes**, both gRPC and HTTP support compression:

- **gRPC**: Automatic gzip compression support
- **HTTP**: Supports `Content-Encoding: gzip` header

Configure compression in your SDK:

```bash
export OTEL_EXPORTER_OTLP_COMPRESSION=gzip
```

## Data & Reliability

### What happens to messages during connector restart?

**Graceful shutdown:**
- In-flight messages in queues are produced to Kafka before shutdown
- Offset is committed after successful production
- On restart, connector resumes from last committed offset
- No message loss during graceful shutdown

**Ungraceful shutdown (crash):**
- Messages in queues may be lost
- Last committed offset is used on restart
- Messages received after last commit but before crash may be re-delivered (at-least-once)

### Can I replay historical telemetry data?

**From Kafka: Yes** (if using Kafka topics as long-term storage)

```bash
# Consume from beginning
kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic otlp-traces \
  --from-beginning
```

**From OTLP: No** (OTLP is a streaming protocol with no built-in replay)

### What data format does the connector produce?

Messages are produced as **strings**:

- **JSON format**: UTF-8 encoded JSON string
- **Protobuf format**: Base64-encoded binary string

Message structure:
```json
{
  "topic": "otlp-traces",
  "partition": 2,
  "offset": 12345,
  "key": null,
  "value": "{\"resourceSpans\":[...]}"  // JSON
  // or
  "value": "CiYKFgoKc2VydmljZS5uYW1l..."  // Protobuf (base64)
}
```

### How do I process Protobuf-formatted messages downstream?

**Kafka Streams example:**

```java
import io.opentelemetry.proto.trace.v1.TracesData;
import java.util.Base64;

// Decode base64 + parse protobuf
byte[] decoded = Base64.getDecoder().decode(value);
TracesData traces = TracesData.parseFrom(decoded);
```

**Python example:**

```python
import base64
from opentelemetry.proto.trace.v1.trace_pb2 import TracesData

# Decode base64 + parse protobuf
decoded = base64.b64decode(value)
traces = TracesData.FromString(decoded)
```

## Operations

### How do I monitor the connector?

Three approaches:

1. **JMX Metrics** (recommended):
   ```bash
   jconsole <kafka-connect-pid>
   # Navigate to: io.conduktor.connect.otel → OpenTelemetryConnector
   ```

2. **Connector Logs**:
   ```bash
   tail -f $KAFKA_HOME/logs/connect.log | grep "event="
   ```

3. **Kafka Connect REST API**:
   ```bash
   curl http://localhost:8083/connectors/otlp-source/status
   ```

### What metrics should I alert on?

**Critical alerts:**

- **TracesDropped/MetricsDropped/LogsDropped > 0** - Queue overflow, increase queue size
- **Connector state != RUNNING** - Connector failure, check logs
- **DropRate > 1%** - Significant message loss

**Warning alerts:**

- **MaxQueueUtilizationPercent > 80%** - Approaching queue capacity
- **TotalLagCount > 10000** - Processing backlog

See [Operational Runbook](operations/RUNBOOK.md) for detailed monitoring setup.

### How do I troubleshoot high queue utilization?

**Check JMX metrics:**
```bash
# TracesQueueSize, MetricsQueueSize, LogsQueueSize
# MaxQueueUtilizationPercent
```

**Solutions:**

1. **Increase queue size**:
   ```json
   "otlp.message.queue.size": "50000"
   ```

2. **Optimize Kafka producer** (in `connect-distributed.properties`):
   ```properties
   producer.linger.ms=10
   producer.batch.size=32768
   producer.compression.type=lz4
   ```

3. **Scale Kafka brokers** (increase throughput)

4. **Switch to Protobuf** (smaller messages, faster serialization)

### How do I update connector configuration?

**For running connectors:**

```bash
curl -X PUT http://localhost:8083/connectors/otlp-source/config \
  -H "Content-Type: application/json" \
  -d @updated-config.json
```

The connector will restart automatically with the new configuration.

**Configuration changes requiring restart:**
- Port changes (`otlp.grpc.port`, `otlp.http.port`)
- Protocol enable/disable (`otlp.grpc.enabled`, `otlp.http.enabled`)
- Bind address (`otlp.bind.address`)

### Can I pause and resume the connector?

Yes, but with caveats:

**Pause:**
```bash
curl -X PUT http://localhost:8083/connectors/otlp-source/pause
```

**Resume:**
```bash
curl -X PUT http://localhost:8083/connectors/otlp-source/resume
```

!!! warning "Telemetry Loss During Pause"
    Pausing stops OTLP receivers - applications will fail to send telemetry during pause. Configure SDKs with retry logic or buffer telemetry.

## Performance

### What throughput can I expect?

Typical performance on standard hardware (4 CPU, 8 GB RAM):

- **Traces**: 5,000-10,000 spans/second
- **Metrics**: 10,000-20,000 data points/second
- **Logs**: 10,000-20,000 log records/second

Actual throughput depends on:
- Message size
- Queue configuration
- Kafka broker performance
- Output format (Protobuf is faster)

### How many connectors can I run?

**One connector per port pair** (gRPC + HTTP).

Examples:

```json
// Connector 1: Default ports
{"otlp.grpc.port": "4317", "otlp.http.port": "4318"}

// Connector 2: Custom ports
{"otlp.grpc.port": "5317", "otlp.http.port": "5318"}

// Connector 3: Different environment
{"otlp.grpc.port": "6317", "otlp.http.port": "6318"}
```

### What's the memory footprint?

**Formula:**
```
Memory ≈ (queue_size × avg_message_size × 3 queues) + JVM overhead
```

**Examples:**

- Queue: 10,000, Avg message: 2 KB → ~60 MB + overhead = ~100 MB
- Queue: 50,000, Avg message: 2 KB → ~300 MB + overhead = ~400 MB

**Recommendation:**
- Development: 1 GB heap
- Production: 4 GB heap

### How do I optimize throughput?

1. **Use Protobuf format**:
   ```json
   "otlp.message.format": "protobuf"
   ```

2. **Increase queue size**:
   ```json
   "otlp.message.queue.size": "50000"
   ```

3. **Optimize Kafka producer** (`connect-distributed.properties`):
   ```properties
   producer.linger.ms=10
   producer.batch.size=32768
   producer.compression.type=lz4
   producer.acks=1
   ```

4. **Increase topic partitions**:
   ```bash
   kafka-topics.sh --alter --topic otlp-traces --partitions 12
   ```

## Troubleshooting

### Why isn't my connector appearing in the plugin list?

**Check:**

1. JAR is in the correct plugin directory
2. `plugin.path` is configured in `connect-distributed.properties`
3. Kafka Connect was restarted after installation
4. JAR includes all dependencies

**Verify:**
```bash
ls -lh $KAFKA_HOME/plugins/kafka-connect-opentelemetry/
curl http://localhost:8083/connector-plugins | jq '.[] | select(.class | contains("OpenTelemetry"))'
```

### Why do I get "Address already in use" error?

**Cause:** Ports 4317 or 4318 are already bound.

**Solution:**

1. **Find the conflicting process**:
   ```bash
   lsof -i :4317
   lsof -i :4318
   ```

2. **Use different ports**:
   ```json
   {
     "otlp.grpc.port": "14317",
     "otlp.http.port": "14318"
   }
   ```

### Why are messages being dropped?

**Check JMX metrics:**
```bash
# TracesDropped, MetricsDropped, LogsDropped
```

**Causes:**
1. Queue overflow (queue size too small)
2. Kafka throughput bottleneck
3. High incoming telemetry rate

**Solutions:**
1. Increase `otlp.message.queue.size`
2. Optimize Kafka producer settings
3. Switch to Protobuf format
4. Scale Kafka brokers

See [Operational Runbook](operations/RUNBOOK.md#monitoring) for detailed troubleshooting.

### How do I test the OTLP endpoints?

**gRPC endpoint:**
```bash
grpcurl -plaintext localhost:4317 list

# Should show:
# opentelemetry.proto.collector.trace.v1.TraceService
# opentelemetry.proto.collector.metrics.v1.MetricsService
# opentelemetry.proto.collector.logs.v1.LogsService
```

**HTTP endpoint:**
```bash
curl -v http://localhost:4318/v1/traces \
  -H "Content-Type: application/x-protobuf"

# Should return 200 or 400
```

## Compatibility

### Does this work with Kafka 2.x?

No, minimum Kafka version is 3.9.0. The connector uses APIs introduced in Kafka 3.x.

### Does this work with Java 8?

No, minimum Java version is 11. The connector and its dependencies require Java 11+.

### Does this work with Kubernetes?

Yes, deploy Kafka Connect in Kubernetes and include this connector in the plugin directory:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: kafka-connect-plugins
data:
  kafka-connect-opentelemetry-1.0.0.jar: |
    # Base64-encoded JAR content
```

Or build a custom Docker image:

```dockerfile
FROM confluentinc/cp-kafka-connect:7.5.0

COPY kafka-connect-opentelemetry-1.0.0-jar-with-dependencies.jar \
  /usr/share/confluent-hub-components/kafka-connect-opentelemetry/
```

### Does this work with Docker?

Yes. Example `docker-compose.yml`:

```yaml
version: '3.8'
services:
  kafka-connect:
    image: confluentinc/cp-kafka-connect:7.5.0
    volumes:
      - ./kafka-connect-opentelemetry-1.0.0-jar-with-dependencies.jar:/usr/share/confluent-hub-components/kafka-connect-opentelemetry/kafka-connect-opentelemetry-1.0.0.jar
    ports:
      - "8083:8083"  # Connect REST API
      - "4317:4317"  # OTLP gRPC
      - "4318:4318"  # OTLP HTTP
```

## Comparison with Alternatives

### When should I use this connector vs. OTEL Collector?

**Use this connector when:**
- You need Kafka as central telemetry platform
- You want durable buffering (Kafka topics)
- You're building custom telemetry pipelines
- You need fan-out to multiple consumers
- You want to leverage Kafka Streams/ksqlDB

**Use OTEL Collector when:**
- You need direct export to 50+ backends
- You require advanced transformations
- You don't need Kafka in your architecture
- You need sophisticated sampling/filtering

**Use both:**
Apps → Connector → Kafka → OTEL Collector → Backends

### How does this compare to Jaeger Kafka?

| Feature | This Connector | Jaeger Kafka |
|---------|---------------|--------------|
| **Protocol** | OTLP (all signals) | Jaeger (traces only) |
| **Signal Types** | Traces + Metrics + Logs | Traces only |
| **Format** | JSON or Protobuf | Jaeger Protobuf |
| **Ecosystem** | OpenTelemetry | Jaeger |
| **Flexibility** | High (all OTEL SDKs) | Medium (Jaeger clients) |

## Still Have Questions?

- **GitHub Issues**: [Open an issue](https://github.com/conduktor/kafka-connect-opentelemetry/issues)
- **Slack Community**: [Join Conduktor Slack](https://conduktor.io/slack)
- **Documentation**: [Browse documentation](https://conduktor.github.io/kafka-connect-opentelemetry/)
