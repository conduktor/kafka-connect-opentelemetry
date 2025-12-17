package io.conduktor.connect.otel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMX Metrics for OpenTelemetry OTLP Source Connector.
 * Exposes operational metrics via JMX MBeans for monitoring.
 */
public class OpenTelemetryMetrics implements OpenTelemetryMetricsMBean, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(OpenTelemetryMetrics.class);

    private final String connectorName;
    private final ObjectName objectName;

    // Counter metrics per signal type
    private final AtomicLong tracesReceived = new AtomicLong(0);
    private final AtomicLong metricsReceived = new AtomicLong(0);
    private final AtomicLong logsReceived = new AtomicLong(0);
    private final AtomicLong tracesDropped = new AtomicLong(0);
    private final AtomicLong metricsDropped = new AtomicLong(0);
    private final AtomicLong logsDropped = new AtomicLong(0);

    // Records produced to Kafka
    private final AtomicLong recordsProduced = new AtomicLong(0);

    // Queue metrics (volatile for visibility)
    private volatile int tracesQueueSize = 0;
    private volatile int metricsQueueSize = 0;
    private volatile int logsQueueSize = 0;
    private volatile int queueCapacity = 0;

    public OpenTelemetryMetrics(String connectorName) throws JMException {
        this.connectorName = connectorName;

        // Create JMX ObjectName
        this.objectName = new ObjectName(
                String.format("io.conduktor.connect.otel:type=OpenTelemetryConnector,name=%s",
                        ObjectName.quote(connectorName))
        );

        // Register MBean
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            if (mbs.isRegistered(objectName)) {
                log.warn("MBean already registered, unregistering old instance: {}", objectName);
                mbs.unregisterMBean(objectName);
            }
            mbs.registerMBean(this, objectName);
            log.info("Registered JMX MBean: {}", objectName);
        } catch (Exception e) {
            log.error("Failed to register JMX MBean: {}", objectName, e);
            throw new JMException("Failed to register MBean: " + e.getMessage());
        }
    }

    // Metric update methods

    public void incrementTracesReceived() {
        tracesReceived.incrementAndGet();
    }

    public void incrementMetricsReceived() {
        metricsReceived.incrementAndGet();
    }

    public void incrementLogsReceived() {
        logsReceived.incrementAndGet();
    }

    public void incrementTracesDropped() {
        tracesDropped.incrementAndGet();
    }

    public void incrementMetricsDropped() {
        metricsDropped.incrementAndGet();
    }

    public void incrementLogsDropped() {
        logsDropped.incrementAndGet();
    }

    public void incrementRecordsProduced(long count) {
        recordsProduced.addAndGet(count);
    }

    public void updateTracesQueueSize(int size) {
        this.tracesQueueSize = size;
    }

    public void updateMetricsQueueSize(int size) {
        this.metricsQueueSize = size;
    }

    public void updateLogsQueueSize(int size) {
        this.logsQueueSize = size;
    }

    public void setQueueCapacity(int capacity) {
        this.queueCapacity = capacity;
    }

    // JMX MBean interface implementation

    @Override
    public long getTracesReceived() {
        return tracesReceived.get();
    }

    @Override
    public long getMetricsReceived() {
        return metricsReceived.get();
    }

    @Override
    public long getLogsReceived() {
        return logsReceived.get();
    }

    @Override
    public long getTracesDropped() {
        return tracesDropped.get();
    }

    @Override
    public long getMetricsDropped() {
        return metricsDropped.get();
    }

    @Override
    public long getLogsDropped() {
        return logsDropped.get();
    }

    @Override
    public long getTotalMessagesReceived() {
        return tracesReceived.get() + metricsReceived.get() + logsReceived.get();
    }

    @Override
    public long getTotalMessagesDropped() {
        return tracesDropped.get() + metricsDropped.get() + logsDropped.get();
    }

    @Override
    public long getRecordsProduced() {
        return recordsProduced.get();
    }

    @Override
    public int getTracesQueueSize() {
        return tracesQueueSize;
    }

    @Override
    public int getMetricsQueueSize() {
        return metricsQueueSize;
    }

    @Override
    public int getLogsQueueSize() {
        return logsQueueSize;
    }

    @Override
    public int getQueueCapacity() {
        return queueCapacity;
    }

    @Override
    public double getMaxQueueUtilizationPercent() {
        if (queueCapacity == 0) return 0.0;
        double tracesUtil = (tracesQueueSize * 100.0) / queueCapacity;
        double metricsUtil = (metricsQueueSize * 100.0) / queueCapacity;
        double logsUtil = (logsQueueSize * 100.0) / queueCapacity;
        return Math.max(Math.max(tracesUtil, metricsUtil), logsUtil);
    }

    @Override
    public long getTotalLagCount() {
        return getTotalMessagesReceived() - recordsProduced.get();
    }

    @Override
    public double getDropRate() {
        long received = getTotalMessagesReceived();
        if (received == 0) return 0.0;
        return (getTotalMessagesDropped() * 100.0) / received;
    }

    @Override
    public void resetCounters() {
        tracesReceived.set(0);
        metricsReceived.set(0);
        logsReceived.set(0);
        tracesDropped.set(0);
        metricsDropped.set(0);
        logsDropped.set(0);
        recordsProduced.set(0);
        log.info("Reset metrics counters for connector: {}", connectorName);
    }

    @Override
    public String getConnectorName() {
        return connectorName;
    }

    @Override
    public void close() {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            if (mbs.isRegistered(objectName)) {
                mbs.unregisterMBean(objectName);
                log.info("Unregistered JMX MBean: {}", objectName);
            }
        } catch (Exception e) {
            log.error("Failed to unregister JMX MBean: {}", objectName, e);
        }
    }
}
