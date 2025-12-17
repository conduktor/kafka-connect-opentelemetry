# Changelog

All notable changes to the Kafka Connect OpenTelemetry Source Connector will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Planned Features

- [ ] TLS support for OTLP receivers (gRPC and HTTP)
- [ ] Custom authentication mechanisms (API keys, mTLS)
- [ ] Configurable message transformations (SMTs)
- [ ] Metrics dashboard templates (Grafana)
- [ ] Helm chart for Kubernetes deployments
- [ ] Resource attribute filtering
- [ ] Sampling configuration (head-based, tail-based)

## [1.0.0] - 2025-12-17

### Added

#### Core Features
- Initial release of Kafka Connect OpenTelemetry Source Connector
- OTLP receiver for traces, metrics, and logs
- Dual protocol support: gRPC (port 4317) and HTTP (port 4318)
- Automatic signal type routing to separate Kafka topics
- Flexible output formats: JSON and Protobuf (base64-encoded)
- Configurable message queues with backpressure handling (per signal type)
- Sequence-based offset management for reliable delivery
- Graceful shutdown with in-flight message completion

#### OTLP Protocol Support
- **gRPC receiver** on port 4317 (configurable)
  - `opentelemetry.proto.collector.trace.v1.TraceService`
  - `opentelemetry.proto.collector.metrics.v1.MetricsService`
  - `opentelemetry.proto.collector.logs.v1.LogsService`
- **HTTP receiver** on port 4318 (configurable)
  - `/v1/traces` endpoint
  - `/v1/metrics` endpoint
  - `/v1/logs` endpoint
- Support for all OpenTelemetry SDKs (Java, Python, Go, Node.js, .NET, Ruby, etc.)
- Automatic gzip compression support (gRPC and HTTP)

#### Configuration
- Required parameters:
  - `connector.class` - Connector class name
  - `tasks.max` - Number of tasks (must be 1)
- OTLP receiver configuration:
  - `otlp.grpc.enabled` - Enable/disable gRPC receiver (default: true)
  - `otlp.grpc.port` - gRPC port (default: 4317)
  - `otlp.http.enabled` - Enable/disable HTTP receiver (default: true)
  - `otlp.http.port` - HTTP port (default: 4318)
  - `otlp.bind.address` - Bind address (default: 0.0.0.0)
- Kafka topic configuration:
  - `kafka.topic.traces` - Traces topic (default: otlp-traces)
  - `kafka.topic.metrics` - Metrics topic (default: otlp-metrics)
  - `kafka.topic.logs` - Logs topic (default: otlp-logs)
- Message format configuration:
  - `otlp.message.format` - Output format: json or protobuf (default: json)
  - `otlp.message.queue.size` - Queue size per signal (default: 10000)

#### Monitoring & Observability
- JMX metrics exposure via Kafka Connect framework
- Built-in metrics:
  - **Counters**: TracesReceived, MetricsReceived, LogsReceived, TracesDropped, MetricsDropped, LogsDropped, RecordsProduced
  - **Queue Metrics**: TracesQueueSize, MetricsQueueSize, LogsQueueSize, QueueCapacity, MaxQueueUtilizationPercent
  - **Derived Metrics**: TotalMessagesReceived, TotalMessagesDropped, TotalLagCount, DropRate
- Structured logging with `event=` prefix:
  - `event=task_starting` - Task initialization
  - `event=otlp_receiver_started` - OTLP receivers started
  - `event=trace_received`, `event=metric_received`, `event=log_received` - Signal reception
  - `event=trace_dropped`, `event=metric_dropped`, `event=log_dropped` - Queue overflow
  - `event=task_metrics` - Periodic metrics log (every 30 seconds)
  - `event=task_stopping` - Graceful shutdown
- Integration with Prometheus via JMX Exporter
- Sample Grafana dashboard configuration

#### Documentation
- Comprehensive README with installation, configuration, and usage examples
- Quick start guide with OpenTelemetry SDK examples (Java, Python, Go)
- Detailed configuration reference with all parameters
- Troubleshooting guide with common issues and solutions
- OpenTelemetry SDK integration examples for multiple languages
- Architecture documentation with data flow diagrams
- Production deployment recommendations
- Operational runbook for monitoring and troubleshooting
- FAQ covering common questions and use cases

#### Testing
- Unit tests for connector, configuration, and offset management
- Integration tests with gRPC and HTTP receivers
- Configuration validation tests
- Offset commit and recovery tests
- Queue overflow and backpressure tests

#### Dependencies
- Apache Kafka Connect API 3.9.0
- gRPC 1.59.0
- Protobuf 3.25.0
- OpenTelemetry Protocol definitions 1.0.0
- SLF4J logging API 1.7.36
- JUnit 5.9.2 (testing)
- Mockito 5.2.0 (testing)

### Technical Details

#### Architecture
- **OpenTelemetrySourceConnector**: Main connector class managing configuration and task lifecycle
- **OpenTelemetrySourceTask**: Task implementation running OTLP receivers and message polling
- **OtlpGrpcReceiver**: gRPC server implementing OTLP trace/metrics/logs services
- **OtlpHttpReceiver**: HTTP server handling /v1/traces, /v1/metrics, /v1/logs endpoints
- **SignalRouter**: Routes incoming signals to appropriate queues (traces/metrics/logs)
- **OffsetManager**: Manages sequence-based offsets for each signal type
- **OpenTelemetrySourceConnectorConfig**: Configuration definition with validation

#### Data Flow
```
OpenTelemetry Apps
    |
    | OTLP/gRPC (4317)
    | OTLP/HTTP (4318)
    v
OTLP Receivers (gRPC + HTTP)
    |
    v
Signal Router
    |
    +---+---+---+
    |   |   |   |
    v   v   v   v
Traces Metrics Logs Queues
    |   |   |   |
    v   v   v   v
SourceTask.poll()
    |
    v
Kafka Topics (otlp-traces, otlp-metrics, otlp-logs)
```

#### Message Format

**JSON Format (Default):**
- Human-readable JSON using protobuf JSON mapping
- Larger size (~3-5x protobuf)
- Easier debugging and downstream processing

**Protobuf Format:**
- Base64-encoded binary protobuf
- Smaller size, faster serialization
- Requires protobuf decoder downstream

#### Delivery Semantics
- **At-least-once** delivery with offset management
- Sequence numbers for each signal type
- Offsets committed to Kafka Connect offset storage
- Messages may be duplicated on failure/restart
- Messages can be dropped on queue overflow (monitor JMX metrics)

### Known Limitations

- Single task per connector (OTLP receiver runs on specific ports)
- No built-in TLS support (planned for future release)
- No custom authentication (planned for future release)
- Queue overflow drops messages (monitor queue utilization)
- gRPC and HTTP ports must be available

### Known Issues

- None reported in initial release

### Breaking Changes

N/A - Initial release

---

## Version History Format

### [X.Y.Z] - YYYY-MM-DD

#### Added
Features or capabilities that were added in this release.

#### Changed
Changes in existing functionality or behavior.

#### Deprecated
Features that will be removed in future releases.

#### Removed
Features that were removed in this release.

#### Fixed
Bug fixes and error corrections.

#### Security
Security vulnerability fixes and improvements.

---

## Upgrade Guide

### From Pre-Release to 1.0.0

This is the initial stable release. No migration required.

### Future Upgrades

Upgrade instructions will be provided here for future releases.

---

## Support Policy

### Version Support

- **Latest stable version**: Full support with bug fixes and security updates
- **Previous minor version**: Security fixes only
- **Older versions**: Community support via GitHub Issues

### Compatibility Matrix

| Connector Version | Min Kafka Version | Max Kafka Version | Java Version | OTLP Version |
|-------------------|-------------------|-------------------|--------------|--------------|
| 1.0.0 | 3.9.0 | Latest | 11+ | 1.0.0 |

---

## Release Notes Archive

### Release Highlights

#### 1.0.0 - Initial Stable Release (2025-12-17)

**🎉 First Production-Ready Release**

The Kafka Connect OpenTelemetry Source Connector is now production-ready with comprehensive features for receiving OTLP telemetry and streaming it into Apache Kafka.

**Key Capabilities:**
- Receive traces, metrics, and logs from any OpenTelemetry-instrumented application
- Support for both gRPC (4317) and HTTP (4318) OTLP protocols
- Automatic routing to separate Kafka topics per signal type
- Flexible JSON or Protobuf output formats
- Configurable queues with backpressure handling

**Production Features:**
- Sequence-based offset management for reliable delivery
- Built-in JMX metrics for monitoring
- Comprehensive error handling and logging
- Prometheus/Grafana integration support
- Graceful shutdown with in-flight message completion

**Use Cases:**
- Build observability pipelines with Kafka
- Buffer telemetry before sending to backends
- Fan-out telemetry to multiple consumers
- Long-term trace/metrics/logs storage
- Decouple apps from specific observability backends

**Getting Started:**
```bash
mvn clean package
cp target/kafka-connect-opentelemetry-1.0.0-jar-with-dependencies.jar \
   $KAFKA_HOME/plugins/kafka-connect-opentelemetry/
```

**Example Configuration:**
```json
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
    "otlp.message.format": "json"
  }
}
```

**Important Notes:**
- At-least-once delivery semantics (sequence-based offsets)
- Single task per connector (port binding limitation)
- Monitor queue utilization to prevent message drops
- TLS support planned for future release

---

## Contributing to Changelog

When submitting a PR, please update the `[Unreleased]` section with your changes under the appropriate category (Added, Changed, Fixed, etc.).

**Format:**
```markdown
### Category
- Brief description of change ([#PR_NUMBER](link))
```

**Example:**
```markdown
### Added
- TLS support for OTLP receivers ([#42](https://github.com/conduktor/kafka-connect-opentelemetry/pull/42))

### Fixed
- Memory leak in queue overflow scenario ([#38](https://github.com/conduktor/kafka-connect-opentelemetry/pull/38))
```

---

## Links

- [GitHub Repository](https://github.com/conduktor/kafka-connect-opentelemetry)
- [Issue Tracker](https://github.com/conduktor/kafka-connect-opentelemetry/issues)
- [Documentation](https://conduktor.github.io/kafka-connect-opentelemetry/)
- [Conduktor Website](https://conduktor.io)

[Unreleased]: https://github.com/conduktor/kafka-connect-opentelemetry/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/conduktor/kafka-connect-opentelemetry/releases/tag/v1.0.0
