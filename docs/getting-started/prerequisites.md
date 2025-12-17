# Prerequisites

Before installing the Kafka Connect OpenTelemetry connector, ensure your environment meets the following requirements.

## Required Software

### Java Development Kit (JDK)

**Minimum Version:** Java 11
**Recommended:** Java 17 (LTS)

=== "Check Java Version"

    ```bash
    java -version
    ```

    Expected output:
    ```
    openjdk version "17.0.9" 2023-10-17 LTS
    OpenJDK Runtime Environment (build 17.0.9+9-LTS)
    ```

=== "Install Java (Ubuntu/Debian)"

    ```bash
    sudo apt update
    sudo apt install openjdk-17-jdk
    ```

=== "Install Java (macOS)"

    ```bash
    brew install openjdk@17
    ```

=== "Install Java (RHEL/CentOS)"

    ```bash
    sudo yum install java-17-openjdk-devel
    ```

### Apache Kafka

**Minimum Version:** 3.9.0
**Download:** [Apache Kafka Downloads](https://kafka.apache.org/downloads)

=== "Verify Kafka Installation"

    ```bash
    kafka-topics.sh --version
    ```

    Expected output:
    ```
    3.9.0 (Commit:...)
    ```

=== "Quick Kafka Setup (Local)"

    ```bash
    # Download Kafka
    wget https://downloads.apache.org/kafka/3.9.0/kafka_2.13-3.9.0.tgz
    tar -xzf kafka_2.13-3.9.0.tgz
    cd kafka_2.13-3.9.0

    # Start Kafka (KRaft mode)
    KAFKA_CLUSTER_ID="$(bin/kafka-storage.sh random-uuid)"
    bin/kafka-storage.sh format -t $KAFKA_CLUSTER_ID -c config/kraft/server.properties
    bin/kafka-server-start.sh config/kraft/server.properties
    ```

### Maven (for building from source)

**Minimum Version:** 3.6+
**Recommended:** 3.9+

```bash
mvn --version
```

Expected output:
```
Apache Maven 3.9.5
Maven home: /usr/share/maven
Java version: 17.0.9
```

## OpenTelemetry Applications

You'll need applications instrumented with OpenTelemetry SDKs to send telemetry data to the connector.

### Verify OTLP Support

Most OpenTelemetry SDKs support OTLP out of the box:

| Language | SDK | OTLP Support |
|----------|-----|--------------|
| Java | [opentelemetry-java](https://github.com/open-telemetry/opentelemetry-java) | ✅ Built-in |
| Python | [opentelemetry-python](https://github.com/open-telemetry/opentelemetry-python) | ✅ Built-in |
| Go | [opentelemetry-go](https://github.com/open-telemetry/opentelemetry-go) | ✅ Built-in |
| Node.js | [opentelemetry-js](https://github.com/open-telemetry/opentelemetry-js) | ✅ Built-in |
| .NET | [opentelemetry-dotnet](https://github.com/open-telemetry/opentelemetry-dotnet) | ✅ Built-in |
| Ruby | [opentelemetry-ruby](https://github.com/open-telemetry/opentelemetry-ruby) | ✅ Built-in |

### Test OTLP Connectivity

Install OpenTelemetry CLI tool for testing:

```bash
# Using Docker
docker run --rm -it \
  otel/opentelemetry-collector-contrib:latest \
  --config=/dev/null

# Or install locally
go install go.opentelemetry.io/collector/cmd/otelcol@latest
```

## Network Requirements

### Port Availability

The connector requires these ports to be available:

| Protocol | Port | Purpose | Configurable |
|----------|------|---------|--------------|
| OTLP gRPC | 4317 | Receive OTLP via gRPC | Yes (`otlp.grpc.port`) |
| OTLP HTTP | 4318 | Receive OTLP via HTTP | Yes (`otlp.http.port`) |

!!! tip "Check Port Availability"
    ```bash
    # Check if ports are available
    netstat -an | grep 4317
    netstat -an | grep 4318

    # Or use lsof
    lsof -i :4317
    lsof -i :4318
    ```

    If ports are in use, you can configure different ports in the connector configuration.

### Firewall Configuration

If running behind a firewall, ensure inbound access to OTLP ports:

```bash
# Allow OTLP gRPC (4317)
sudo ufw allow 4317/tcp

# Allow OTLP HTTP (4318)
sudo ufw allow 4318/tcp
```

### Test OTLP Endpoint

After starting the connector, test connectivity:

=== "gRPC (Port 4317)"

    ```bash
    # Using grpcurl
    grpcurl -plaintext localhost:4317 list

    # Should show OpenTelemetry services:
    # opentelemetry.proto.collector.trace.v1.TraceService
    # opentelemetry.proto.collector.metrics.v1.MetricsService
    # opentelemetry.proto.collector.logs.v1.LogsService
    ```

=== "HTTP (Port 4318)"

    ```bash
    # Test HTTP endpoint
    curl -v http://localhost:4318/v1/traces \
      -H "Content-Type: application/x-protobuf" \
      -d ""

    # Should return 200 or 400 (empty body)
    ```

## Kafka Connect Setup

### Distributed Mode (Recommended for Production)

Ensure Kafka Connect is running in distributed mode:

```bash
# Check if Connect is running
curl http://localhost:8083/
```

Expected response:
```json
{
  "version": "3.9.0",
  "commit": "...",
  "kafka_cluster_id": "..."
}
```

### Internal Topics Configuration

Kafka Connect in distributed mode requires three internal topics:

```properties
# In connect-distributed.properties
offset.storage.topic=connect-offsets
offset.storage.replication.factor=3
offset.storage.partitions=25

config.storage.topic=connect-configs
config.storage.replication.factor=3
config.storage.partitions=1

status.storage.topic=connect-status
status.storage.replication.factor=3
status.storage.partitions=5
```

### Producer Configuration

Configure producer settings for source connectors in `connect-distributed.properties`:

```properties
# Optimize for throughput
producer.linger.ms=10
producer.batch.size=32768
producer.compression.type=lz4
producer.acks=1
```

### Plugin Directory

Verify the plugin path is configured:

```bash
# Check connect-distributed.properties
grep plugin.path $KAFKA_HOME/config/connect-distributed.properties
```

Expected output:
```properties
plugin.path=/usr/local/share/kafka/plugins
```

!!! warning "Plugin Path Must Exist"
    The plugin directory must exist and have proper permissions:
    ```bash
    sudo mkdir -p /usr/local/share/kafka/plugins
    sudo chown -R $USER:$USER /usr/local/share/kafka/plugins
    ```

## Create Kafka Topics

Pre-create Kafka topics for telemetry data:

```bash
# Create traces topic
kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic otlp-traces \
  --partitions 6 \
  --replication-factor 3

# Create metrics topic
kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic otlp-metrics \
  --partitions 6 \
  --replication-factor 3

# Create logs topic
kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic otlp-logs \
  --partitions 6 \
  --replication-factor 3
```

!!! info "Topic Partitioning"
    - More partitions = higher parallelism for consumers
    - Recommended: 6-12 partitions per topic for production
    - Adjust based on expected throughput

## Resource Requirements

### Minimum Resources

For development/testing:

| Resource | Requirement |
|----------|-------------|
| CPU | 2 cores |
| Memory | 1 GB for connector |
| Disk | 100 MB for JAR files |
| Network | 10 Mbps |

### Production Resources

For production deployments:

| Resource | Requirement |
|----------|-------------|
| CPU | 4+ cores |
| Memory | 4 GB for Kafka Connect worker |
| Disk | 1 GB (for JARs + logs) |
| Network | 100+ Mbps |

### Memory Configuration

Configure heap size for Kafka Connect:

```bash
# In connect-distributed.sh or systemd service
export KAFKA_HEAP_OPTS="-Xms4G -Xmx4G"
```

!!! tip "Memory Sizing"
    Memory requirements scale with:
    - Queue size (`otlp.message.queue.size`)
    - Average message size
    - Throughput (messages per second)

    **Formula:** Memory ≈ (queue_size × avg_message_size × 3) × 3 queues

## Security Configuration

### Secure JMX Access

For production, enable JMX authentication:

```bash
# Create password file
echo "admin changeit" > /etc/kafka/jmx.password
chmod 600 /etc/kafka/jmx.password

# Create access file
echo "admin readwrite" > /etc/kafka/jmx.access
chmod 644 /etc/kafka/jmx.access

# Configure Kafka Connect
export KAFKA_JMX_OPTS="-Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.authenticate=true \
  -Dcom.sun.management.jmxremote.password.file=/etc/kafka/jmx.password \
  -Dcom.sun.management.jmxremote.access.file=/etc/kafka/jmx.access \
  -Dcom.sun.management.jmxremote.ssl=true"
```

!!! danger "Never Run JMX Without Authentication in Production"
    Default JMX configuration with `authenticate=false` exposes your connector to unauthorized access.

### TLS for OTLP (Planned Feature)

Future versions will support TLS for OTLP receivers:

```json
{
  "otlp.tls.enabled": "true",
  "otlp.tls.cert.path": "/etc/kafka/certs/server.crt",
  "otlp.tls.key.path": "/etc/kafka/certs/server.key"
}
```

## Optional Tools

### Monitoring Tools

For production deployments, consider installing:

- **JMX Monitoring**: JConsole, VisualVM, or JMX Exporter
- **Prometheus**: For metrics collection
- **Grafana**: For dashboards
- **Loki/ELK**: For log aggregation

### Development Tools

For connector development:

- **Git**: Version control
- **Docker**: Containerized Kafka setup
- **curl/jq**: API testing and JSON parsing
- **grpcurl**: gRPC endpoint testing

### OTLP Testing Tools

```bash
# Install grpcurl for gRPC testing
brew install grpcurl  # macOS
# or
go install github.com/fullstorydev/grpcurl/cmd/grpcurl@latest

# Install otel-cli for sending test data
go install github.com/equinix-labs/otel-cli@latest
```

## Verification Checklist

Before proceeding to installation, verify:

- [x] Java 11+ is installed and `java -version` works
- [x] Kafka 3.9.0+ is running
- [x] Kafka Connect REST API is accessible at `http://localhost:8083/`
- [x] Plugin directory exists and is writable
- [x] Maven 3.6+ is installed (for building from source)
- [x] Ports 4317 and 4318 are available
- [x] Kafka topics for traces, metrics, and logs are created
- [x] OpenTelemetry applications are ready to send data

## Troubleshooting Prerequisites

### Java Version Issues

**Problem:** Wrong Java version

```bash
# Check all Java installations
ls -la /usr/lib/jvm/

# Set JAVA_HOME
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
```

### Kafka Not Running

**Problem:** Kafka Connect not accessible

```bash
# Check Kafka Connect logs
tail -f $KAFKA_HOME/logs/connect.log

# Restart Kafka Connect
$KAFKA_HOME/bin/connect-distributed.sh config/connect-distributed.properties
```

### Port Conflicts

**Problem:** Ports 4317 or 4318 already in use

```bash
# Find process using the port
lsof -i :4317
lsof -i :4318

# Kill the process or use different ports
# Configure custom ports in connector config:
# "otlp.grpc.port": "14317"
# "otlp.http.port": "14318"
```

## Next Steps

Once all prerequisites are met:

1. [Installation](index.md#installation-steps) - Install the connector
2. [Configuration](configuration.md) - Configure connector options
3. [Quick Start](https://github.com/conduktor/kafka-connect-opentelemetry/blob/main/README.md#quick-start) - Deploy your first connector

---

**Need help?** Check our [FAQ](../faq.md) or [open an issue](https://github.com/conduktor/kafka-connect-opentelemetry/issues).
