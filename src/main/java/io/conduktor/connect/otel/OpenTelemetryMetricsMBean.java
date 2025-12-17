package io.conduktor.connect.otel;

/**
 * MBean interface for OpenTelemetry OTLP metrics.
 * All getter methods are exposed as JMX attributes.
 */
public interface OpenTelemetryMetricsMBean {

    // Counter metrics per signal type
    long getTracesReceived();
    long getMetricsReceived();
    long getLogsReceived();
    long getTracesDropped();
    long getMetricsDropped();
    long getLogsDropped();

    // Total counters
    long getTotalMessagesReceived();
    long getTotalMessagesDropped();
    long getRecordsProduced();

    // Queue metrics
    int getTracesQueueSize();
    int getMetricsQueueSize();
    int getLogsQueueSize();
    int getQueueCapacity();
    double getMaxQueueUtilizationPercent();

    // Derived metrics
    long getTotalLagCount();
    double getDropRate();

    // Metadata
    String getConnectorName();

    // Operations
    void resetCounters();
}
