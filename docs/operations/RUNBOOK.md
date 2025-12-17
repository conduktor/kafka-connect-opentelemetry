# OpenTelemetry Source Connector - Operational Runbook

This runbook provides operational guidance for monitoring, troubleshooting, and maintaining the OpenTelemetry Source Connector in production.

## Table of Contents

- [Incident Response Decision Tree](#incident-response-decision-tree)
- [Monitoring](#monitoring)
- [Common Issues](#common-issues)
- [Performance Tuning](#performance-tuning)
- [Troubleshooting](#troubleshooting)
- [Recovery Procedures](#recovery-procedures)

## Incident Response Decision Tree

Use this decision tree when an alert fires or an issue is reported:

```
START: Alert or Issue Reported
│
├─ Is connector in RUNNING state?
│  │  curl http://localhost:8083/connectors/<name>/status | jq '.connector.state'
│  │
│  └─ NO → Go to [Connector Not Running](#issue-connector-not-running)
│  └─ YES → Continue ↓
│
├─ Are OTLP receivers listening?
│  │  netstat -an | grep 4317 (gRPC)
│  │  netstat -an | grep 4318 (HTTP)
│  │
│  └─ NO → Go to [Receivers Not Started](#issue-1-otlp-receivers-not-listening)
│  └─ YES → Continue ↓
│
├─ Are messages being received? (TotalMessagesReceived increasing)
│  │  Check JMX: io.conduktor.connect.otel:*/TotalMessagesReceived
│  │
│  └─ NO → Go to [No Messages Received](#issue-2-no-messages-received)
│  └─ YES → Continue ↓
│
├─ Are queues near capacity? (MaxQueueUtilizationPercent > 80%)
│  │  Check JMX: io.conduktor.connect.otel:*/MaxQueueUtilizationPercent
│  │
│  └─ YES → Go to [High Message Drop Rate](#issue-3-high-message-drop-rate)
│  └─ NO → Continue ↓
│
├─ Is Kafka write lagging? (RecordsProduced << TotalMessagesReceived)
│  │  Check lag: TotalMessagesReceived - RecordsProduced > 10000
│  │
│  └─ YES → Go to [Processing Lag](#issue-4-processing-lag-building-up)
│  └─ NO → Continue ↓
│
└─ Check logs for errors
   │  grep "ERROR\|WARN" $KAFKA_HOME/logs/connect.log | tail -50
   │
   └─ Errors found → Match error to [Common Issues](#common-issues)
   └─ No errors → Monitor, escalate if issue persists
```

### Quick Commands Reference

```bash
# Check connector status
curl -s http://localhost:8083/connectors/<name>/status | jq .

# Restart connector
curl -X POST http://localhost:8083/connectors/<name>/restart

# Restart specific task
curl -X POST http://localhost:8083/connectors/<name>/tasks/0/restart

# Check recent logs
tail -100 $KAFKA_HOME/logs/connect.log | grep -E "OpenTelemetry|ERROR|WARN"

# Check OTLP endpoints
netstat -an | grep -E "4317|4318"

# Test gRPC endpoint
grpcurl -plaintext localhost:4317 list

# Test HTTP endpoint
curl -v http://localhost:4318/v1/traces
```

### Issue: Connector Not Running

**Symptoms**: Connector state is FAILED or task state is FAILED

**Quick Fix**:
```bash
# Check the error message
curl -s http://localhost:8083/connectors/<name>/status | jq '.tasks[0].trace'

# Restart connector
curl -X POST http://localhost:8083/connectors/<name>/restart

# If still failing, check config and redeploy
curl -X DELETE http://localhost:8083/connectors/<name>
curl -X POST http://localhost:8083/connectors -H "Content-Type: application/json" -d @connector.json
```

**Common Causes**:
- Invalid configuration (bad port, invalid format)
- Port already in use (4317 or 4318 bound to another process)
- Missing dependencies (ClassNotFoundException)
- Insufficient permissions to bind to ports

## Monitoring

### Key JMX Metrics

The connector exposes JMX metrics under `io.conduktor.connect.otel:type=OpenTelemetryConnector,name=<connector-name>`:

#### Counter Metrics

- **TracesReceived**: Total traces received from OTLP
  - **Alert**: Rate drops to 0 for > 5 minutes → Connection issue or no traffic
  - **Action**: Check OTLP endpoint accessibility, verify applications are sending

- **MetricsReceived**: Total metrics received from OTLP
  - **Alert**: Rate drops to 0 for > 5 minutes → Connection issue or no traffic
  - **Action**: Check OTLP endpoint accessibility, verify applications are sending

- **LogsReceived**: Total logs received from OTLP
  - **Alert**: Rate drops to 0 for > 5 minutes → Connection issue or no traffic
  - **Action**: Check OTLP endpoint accessibility, verify applications are sending

- **TracesDropped**: Traces dropped due to queue full
  - **Alert**: Drop rate > 1% → Queue capacity insufficient
  - **Action**: Increase `otlp.message.queue.size` or optimize Kafka throughput

- **MetricsDropped**: Metrics dropped due to queue full
  - **Alert**: Drop rate > 1% → Queue capacity insufficient
  - **Action**: Increase `otlp.message.queue.size` or optimize Kafka throughput

- **LogsDropped**: Logs dropped due to queue full
  - **Alert**: Drop rate > 1% → Queue capacity insufficient
  - **Action**: Increase `otlp.message.queue.size` or optimize Kafka throughput

- **RecordsProduced**: Total records written to Kafka
  - **Alert**: Lag (TotalMessagesReceived - RecordsProduced) > 10000 → Processing backlog
  - **Action**: Check Kafka broker health, review consumer lag

#### Queue Metrics

- **TracesQueueSize**: Current number of traces in queue
- **MetricsQueueSize**: Current number of metrics in queue
- **LogsQueueSize**: Current number of logs in queue
- **QueueCapacity**: Maximum queue capacity (per signal)
- **MaxQueueUtilizationPercent**: Highest utilization across all three queues
  - **Alert**: Utilization > 80% → Approaching capacity
  - **Action**: Monitor for drops, consider increasing queue size

#### Derived Metrics

- **TotalMessagesReceived**: TracesReceived + MetricsReceived + LogsReceived
- **TotalMessagesDropped**: TracesDropped + MetricsDropped + LogsDropped
- **TotalLagCount**: TotalMessagesReceived - RecordsProduced
  - **Alert**: > 10000 → Processing backlog
  - **Action**: Review Kafka producer performance

- **DropRate**: (TotalMessagesDropped / TotalMessagesReceived) * 100
  - **Alert**: > 1% → Significant message loss
  - **Action**: Increase queue size or optimize throughput

### Recommended Alerts

```yaml
# Example Prometheus alerting rules
groups:
  - name: otlp_connector
    rules:
      - alert: OTLPNoMessagesReceived
        expr: rate(otlp_TotalMessagesReceived[5m]) == 0
        for: 5m
        annotations:
          summary: "OTLP connector not receiving messages"
          description: "No messages received for 5 minutes on {{ $labels.connector_name }}"

      - alert: HighMessageDropRate
        expr: otlp_DropRate > 1
        for: 5m
        annotations:
          summary: "High message drop rate for {{ $labels.connector_name }}"
          description: "Dropping {{ $value }}% of messages - queue capacity insufficient"

      - alert: HighProcessingLag
        expr: otlp_TotalLagCount > 10000
        for: 5m
        annotations:
          summary: "High processing lag for {{ $labels.connector_name }}"
          description: "Lag count: {{ $value }} - Kafka throughput issue"

      - alert: HighQueueUtilization
        expr: otlp_MaxQueueUtilizationPercent > 80
        for: 3m
        annotations:
          summary: "High queue utilization for {{ $labels.connector_name }}"
          description: "Queue at {{ $value }}% capacity - risk of drops"

      - alert: TracesQueueDropping
        expr: increase(otlp_TracesDropped[5m]) > 0
        annotations:
          summary: "Traces being dropped on {{ $labels.connector_name }}"
          description: "{{ $value }} traces dropped in last 5 minutes"

      - alert: MetricsQueueDropping
        expr: increase(otlp_MetricsDropped[5m]) > 0
        annotations:
          summary: "Metrics being dropped on {{ $labels.connector_name }}"
          description: "{{ $value }} metrics dropped in last 5 minutes"

      - alert: LogsQueueDropping
        expr: increase(otlp_LogsDropped[5m]) > 0
        annotations:
          summary: "Logs being dropped on {{ $labels.connector_name }}"
          description: "{{ $value }} logs dropped in last 5 minutes"
```

## Common Issues

### Issue 1: OTLP Receivers Not Listening

**Symptoms:**
- `netstat -an | grep 4317` shows nothing
- `netstat -an | grep 4318` shows nothing
- Applications cannot connect to OTLP endpoints
- Logs showing "Failed to start OTLP receiver"

**Root Causes:**
1. Ports already in use by another process
2. Insufficient permissions to bind to ports
3. Firewall blocking ports
4. Connector failed to start

**Resolution:**
```bash
# Check what's using the ports
lsof -i :4317
lsof -i :4318

# If ports are in use, either:
# 1. Stop the conflicting process
# 2. Use different ports in connector config:
{
  "otlp.grpc.port": "14317",
  "otlp.http.port": "14318"
}

# Check firewall rules
sudo ufw status
sudo firewall-cmd --list-ports

# Allow ports if needed
sudo ufw allow 4317/tcp
sudo ufw allow 4318/tcp

# Restart connector
curl -X POST http://localhost:8083/connectors/otlp-source/restart
```

### Issue 2: No Messages Received

**Symptoms:**
- `TotalMessagesReceived` metric not incrementing
- OTLP endpoints are listening
- No errors in logs

**Root Causes:**
1. Applications not configured to send to connector
2. Applications using wrong endpoint
3. Network connectivity issues
4. Authentication/firewall blocking

**Resolution:**
```bash
# Verify endpoints are accessible
grpcurl -plaintext localhost:4317 list
curl -v http://localhost:4318/v1/traces

# Check application configuration
# Should point to connector endpoints:
export OTEL_EXPORTER_OTLP_ENDPOINT=http://<connector-host>:4318
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf

# Test sending data manually
# Using otel-cli:
otel-cli exec --endpoint http://localhost:4318 \
  --service my-test-service -- echo "test"

# Check connector logs for incoming requests
tail -f $KAFKA_HOME/logs/connect.log | grep "event=trace_received\|event=metric_received\|event=log_received"

# Actions:
1. Verify application OTEL_EXPORTER_OTLP_ENDPOINT points to connector
2. Check network connectivity from app to connector
3. Verify firewall allows inbound on 4317, 4318
4. Test with manual data send
```

### Issue 3: High Message Drop Rate

**Symptoms:**
- `DropRate` metric > 1%
- `TracesDropped`, `MetricsDropped`, or `LogsDropped` incrementing
- `MaxQueueUtilizationPercent` at or near 100%
- Logs showing "Queue full, dropping message" or "event=trace_dropped"

**Root Causes:**
1. Queue capacity too small for traffic volume
2. Kafka broker throughput bottleneck
3. Inefficient message format (JSON vs Protobuf)
4. Consumer lag on target topics

**Resolution:**
```bash
# Check queue metrics
jconsole # Connect to JMX and view queue metrics

# Actions:
1. Increase queue size:
   "otlp.message.queue.size": "50000"  # default: 10000

2. Switch to Protobuf format (smaller, faster):
   "otlp.message.format": "protobuf"

3. Check Kafka broker health:
   kafka-broker-api-versions --bootstrap-server localhost:9092

4. Monitor consumer lag on topics:
   kafka-consumer-groups --bootstrap-server localhost:9092 \
     --group <group> --describe

5. Optimize Kafka producer (in connect-distributed.properties):
   producer.linger.ms=10
   producer.batch.size=32768
   producer.compression.type=lz4
   producer.acks=1

6. Scale Kafka brokers if needed
```

### Issue 4: Processing Lag Building Up

**Symptoms:**
- `TotalLagCount` increasing over time
- Queue sizes growing
- `RecordsProduced` not keeping pace with `TotalMessagesReceived`

**Root Causes:**
1. Kafka broker slowness or overload
2. Network issues to Kafka
3. Kafka topic partitions insufficient
4. Serialization bottleneck

**Resolution:**
```bash
# Check Kafka broker metrics
kafka-run-class kafka.tools.JmxTool \
  --object-name kafka.server:type=BrokerTopicMetrics,name=MessagesInPerSec \
  --jmx-url service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi

# Check topic configuration
kafka-topics --bootstrap-server localhost:9092 \
  --describe --topic otlp-traces

# Actions:
1. Increase topic partitions for parallelism:
   kafka-topics --alter --topic otlp-traces --partitions 12
   kafka-topics --alter --topic otlp-metrics --partitions 12
   kafka-topics --alter --topic otlp-logs --partitions 12

2. Check Kafka broker disk I/O and network throughput:
   iostat -xz 1
   iftop

3. Monitor Kafka Connect producer metrics:
   curl http://localhost:8083/connectors/otlp-source/status

4. Review connector logs for serialization errors:
   grep "ERROR" $KAFKA_HOME/logs/connect.log

5. Switch to Protobuf format if using JSON:
   "otlp.message.format": "protobuf"
```

## Performance Tuning

### Queue Sizing

**Default:** 10000 messages per signal type (30000 total)

**Recommendations:**
- Low-volume (< 100 msg/s): 1000-5000
- Medium-volume (100-1000 msg/s): 10000-20000 (default)
- High-volume (1000-5000 msg/s): 20000-50000
- Very high-volume (> 5000 msg/s): 50000-100000

**Trade-offs:**
- Larger queue = more memory usage, better burst handling
- Smaller queue = less memory, more drops under traffic spikes

**Memory formula:**
```
Memory ≈ (queue_size × avg_message_size × 3 queues)
```

Example: 50,000 queue × 2 KB avg × 3 = ~300 MB

### Message Format Selection

**Production Recommendations:**

| Traffic Volume | Recommended Format | Reason |
|---------------|-------------------|--------|
| Low (< 100 msg/s) | JSON | Easier debugging |
| Medium (100-1000 msg/s) | JSON or Protobuf | Either works |
| High (> 1000 msg/s) | Protobuf | 3-5x smaller, faster |

**JSON Benefits:**
- Human-readable for debugging
- Easy downstream processing (ksqlDB, Kafka Streams)
- No decoding required

**Protobuf Benefits:**
- 3-5x smaller message size
- Faster serialization/deserialization
- Lower bandwidth usage

### Port Configuration

**Default Ports:**
```properties
otlp.grpc.port=4317
otlp.http.port=4318
```

**Custom Ports (avoid conflicts):**
```properties
# Example for staging environment
otlp.grpc.port=5317
otlp.http.port=5318

# Example for development
otlp.grpc.port=6317
otlp.http.port=6318
```

## Troubleshooting

### Debug Logging

Enable debug logging for detailed troubleshooting:

```properties
# Connector logging
log4j.logger.io.conduktor.connect.otel=DEBUG

# gRPC logging
log4j.logger.io.grpc=DEBUG
```

### Common Log Messages

| Log Message | Severity | Meaning | Action |
|-------------|----------|---------|--------|
| "event=task_starting" | INFO | Task initialization | Normal operation |
| "event=otlp_receiver_started" | INFO | OTLP receivers started | Normal operation |
| "event=trace_received" | DEBUG | Trace received | Normal operation |
| "event=trace_dropped" | WARN | Trace dropped (queue full) | Increase queue size |
| "event=task_metrics" | INFO | Periodic metrics log | Review metrics |
| "event=task_stopping" | INFO | Graceful shutdown | Normal shutdown |
| "Failed to start gRPC server" | ERROR | gRPC startup failed | Check port availability |
| "Failed to start HTTP server" | ERROR | HTTP startup failed | Check port availability |

### Health Check

Create a health check script:

```bash
#!/bin/bash
# health-check.sh

CONNECTOR_NAME="otlp-source"
CONNECT_URL="http://localhost:8083"

# Check connector status
STATUS=$(curl -s $CONNECT_URL/connectors/$CONNECTOR_NAME/status | jq -r '.connector.state')

if [ "$STATUS" != "RUNNING" ]; then
  echo "CRITICAL: Connector not running (state: $STATUS)"
  exit 2
fi

# Check task status
TASK_STATUS=$(curl -s $CONNECT_URL/connectors/$CONNECTOR_NAME/status | jq -r '.tasks[0].state')

if [ "$TASK_STATUS" != "RUNNING" ]; then
  echo "CRITICAL: Task not running (state: $TASK_STATUS)"
  exit 2
fi

# Check OTLP endpoints
if ! netstat -an | grep -q 4317; then
  echo "WARNING: gRPC port 4317 not listening"
  exit 1
fi

if ! netstat -an | grep -q 4318; then
  echo "WARNING: HTTP port 4318 not listening"
  exit 1
fi

echo "OK: Connector healthy"
exit 0
```

### OTLP Endpoint Testing

**Test gRPC endpoint:**
```bash
# List available services
grpcurl -plaintext localhost:4317 list

# Expected output:
# opentelemetry.proto.collector.trace.v1.TraceService
# opentelemetry.proto.collector.metrics.v1.MetricsService
# opentelemetry.proto.collector.logs.v1.LogsService

# Describe trace service
grpcurl -plaintext localhost:4317 describe opentelemetry.proto.collector.trace.v1.TraceService
```

**Test HTTP endpoint:**
```bash
# Test traces endpoint
curl -v http://localhost:4318/v1/traces \
  -H "Content-Type: application/x-protobuf"

# Test metrics endpoint
curl -v http://localhost:4318/v1/metrics \
  -H "Content-Type: application/x-protobuf"

# Test logs endpoint
curl -v http://localhost:4318/v1/logs \
  -H "Content-Type: application/x-protobuf"

# Should return 200 or 400
```

## Recovery Procedures

### Restart Connector

```bash
# Pause connector (stops receiving new messages)
curl -X PUT http://localhost:8083/connectors/otlp-source/pause

# Wait for in-flight messages to flush
sleep 10

# Resume connector
curl -X PUT http://localhost:8083/connectors/otlp-source/resume

# Verify status
curl -s http://localhost:8083/connectors/otlp-source/status | jq .
```

### Update Configuration

```bash
# Update connector configuration
cat > updated-config.json <<EOF
{
  "connector.class": "io.conduktor.connect.otel.OpenTelemetrySourceConnector",
  "tasks.max": "1",
  "otlp.message.queue.size": "50000",
  "otlp.message.format": "protobuf"
}
EOF

curl -X PUT http://localhost:8083/connectors/otlp-source/config \
  -H "Content-Type: application/json" \
  -d @updated-config.json
```

### Emergency Shutdown

If connector is misbehaving and needs immediate shutdown:

```bash
# Option 1: Pause connector (graceful)
curl -X PUT http://localhost:8083/connectors/otlp-source/pause

# Option 2: Delete connector (removes from cluster)
curl -X DELETE http://localhost:8083/connectors/otlp-source

# Option 3: Restart Connect worker (nuclear option)
kubectl delete pod -l app=kafka-connect
# or
systemctl restart kafka-connect
```

## Capacity Planning

### Estimating Queue Size

**Formula:**
```
queue_size = (peak_throughput_per_second × burst_duration_seconds) / 3
```

Divide by 3 because there are 3 queues (traces, metrics, logs).

**Example:**
- Peak: 5000 msg/s
- Burst: 10 seconds
- Queue size: (5000 × 10) / 3 = 16,667 per queue

**Recommendation:** Add 50% buffer → 25,000 per queue

### Estimating Memory

**Formula:**
```
Memory = (queue_size × avg_message_size × 3) + JVM_overhead
```

**Example:**
- Queue: 25,000
- Avg message: 2 KB
- Memory: (25,000 × 2 KB × 3) = 150 MB + overhead ≈ 200 MB

**Recommendation:** Set heap to 4× calculated = 800 MB minimum

### Scaling Considerations

**Single connector limitations:**
- One task per connector (cannot scale horizontally via tasks.max)
- One pair of ports (gRPC + HTTP)

**To scale:**
1. Run multiple connector instances on different ports
2. Use load balancer to distribute apps across connectors
3. Scale Kafka brokers to handle increased throughput

## Contact and Escalation

For issues not covered in this runbook:

1. Review connector logs at DEBUG level
2. Check Kafka Connect worker logs
3. Verify Kafka broker health
4. Check OTLP endpoint accessibility
5. Consult project documentation: https://github.com/conduktor/kafka-connect-opentelemetry
6. Open issue with:
   - Connector configuration
   - JMX metrics snapshot
   - Relevant logs (last 1000 lines)
   - Kafka Connect worker version
   - Kafka broker version
   - OpenTelemetry SDK version (if applicable)
