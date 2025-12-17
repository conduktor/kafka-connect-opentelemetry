package io.conduktor.connect.otel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenTelemetryMetricsTest {

    private OpenTelemetryMetrics metrics;
    private String connectorName;
    private MBeanServer mbs;

    @BeforeEach
    void setUp() throws JMException {
        connectorName = "test-connector-" + System.currentTimeMillis();
        mbs = ManagementFactory.getPlatformMBeanServer();
        metrics = new OpenTelemetryMetrics(connectorName);
    }

    @AfterEach
    void tearDown() {
        if (metrics != null) {
            try {
                metrics.close();
            } catch (Exception e) {
                // Ignore cleanup exceptions
            }
        }
    }

    @Test
    void testMetricsInitialization() throws JMException {
        assertNotNull(metrics);
        assertEquals(connectorName, metrics.getConnectorName());

        // Verify MBean is registered
        ObjectName objectName = new ObjectName(
                String.format("io.conduktor.connect.otel:type=OpenTelemetryConnector,name=%s",
                        ObjectName.quote(connectorName))
        );
        assertTrue(mbs.isRegistered(objectName));
    }

    @Test
    void testInitialMetricValues() {
        assertEquals(0L, metrics.getTracesReceived());
        assertEquals(0L, metrics.getMetricsReceived());
        assertEquals(0L, metrics.getLogsReceived());
        assertEquals(0L, metrics.getTracesDropped());
        assertEquals(0L, metrics.getMetricsDropped());
        assertEquals(0L, metrics.getLogsDropped());
        assertEquals(0L, metrics.getRecordsProduced());
        assertEquals(0, metrics.getTracesQueueSize());
        assertEquals(0, metrics.getMetricsQueueSize());
        assertEquals(0, metrics.getLogsQueueSize());
        assertEquals(0, metrics.getQueueCapacity());
    }

    @Test
    void testIncrementTracesReceived() {
        metrics.incrementTracesReceived();
        assertEquals(1L, metrics.getTracesReceived());

        metrics.incrementTracesReceived();
        assertEquals(2L, metrics.getTracesReceived());

        for (int i = 0; i < 10; i++) {
            metrics.incrementTracesReceived();
        }
        assertEquals(12L, metrics.getTracesReceived());
    }

    @Test
    void testIncrementMetricsReceived() {
        metrics.incrementMetricsReceived();
        assertEquals(1L, metrics.getMetricsReceived());

        metrics.incrementMetricsReceived();
        metrics.incrementMetricsReceived();
        assertEquals(3L, metrics.getMetricsReceived());
    }

    @Test
    void testIncrementLogsReceived() {
        metrics.incrementLogsReceived();
        assertEquals(1L, metrics.getLogsReceived());

        for (int i = 0; i < 5; i++) {
            metrics.incrementLogsReceived();
        }
        assertEquals(6L, metrics.getLogsReceived());
    }

    @Test
    void testIncrementTracesDropped() {
        metrics.incrementTracesDropped();
        assertEquals(1L, metrics.getTracesDropped());

        metrics.incrementTracesDropped();
        assertEquals(2L, metrics.getTracesDropped());
    }

    @Test
    void testIncrementMetricsDropped() {
        metrics.incrementMetricsDropped();
        assertEquals(1L, metrics.getMetricsDropped());

        metrics.incrementMetricsDropped();
        metrics.incrementMetricsDropped();
        assertEquals(3L, metrics.getMetricsDropped());
    }

    @Test
    void testIncrementLogsDropped() {
        metrics.incrementLogsDropped();
        assertEquals(1L, metrics.getLogsDropped());

        for (int i = 0; i < 4; i++) {
            metrics.incrementLogsDropped();
        }
        assertEquals(5L, metrics.getLogsDropped());
    }

    @Test
    void testIncrementRecordsProduced() {
        metrics.incrementRecordsProduced(1);
        assertEquals(1L, metrics.getRecordsProduced());

        metrics.incrementRecordsProduced(10);
        assertEquals(11L, metrics.getRecordsProduced());

        metrics.incrementRecordsProduced(100);
        assertEquals(111L, metrics.getRecordsProduced());
    }

    @Test
    void testIncrementRecordsProducedWithZero() {
        metrics.incrementRecordsProduced(0);
        assertEquals(0L, metrics.getRecordsProduced());
    }

    @Test
    void testUpdateTracesQueueSize() {
        metrics.updateTracesQueueSize(50);
        assertEquals(50, metrics.getTracesQueueSize());

        metrics.updateTracesQueueSize(100);
        assertEquals(100, metrics.getTracesQueueSize());

        metrics.updateTracesQueueSize(0);
        assertEquals(0, metrics.getTracesQueueSize());
    }

    @Test
    void testUpdateMetricsQueueSize() {
        metrics.updateMetricsQueueSize(25);
        assertEquals(25, metrics.getMetricsQueueSize());

        metrics.updateMetricsQueueSize(75);
        assertEquals(75, metrics.getMetricsQueueSize());
    }

    @Test
    void testUpdateLogsQueueSize() {
        metrics.updateLogsQueueSize(30);
        assertEquals(30, metrics.getLogsQueueSize());

        metrics.updateLogsQueueSize(90);
        assertEquals(90, metrics.getLogsQueueSize());
    }

    @Test
    void testSetQueueCapacity() {
        metrics.setQueueCapacity(1000);
        assertEquals(1000, metrics.getQueueCapacity());

        metrics.setQueueCapacity(5000);
        assertEquals(5000, metrics.getQueueCapacity());
    }

    @Test
    void testGetTotalMessagesReceived() {
        metrics.incrementTracesReceived();
        metrics.incrementTracesReceived();
        metrics.incrementMetricsReceived();
        metrics.incrementLogsReceived();
        metrics.incrementLogsReceived();
        metrics.incrementLogsReceived();

        assertEquals(6L, metrics.getTotalMessagesReceived());
    }

    @Test
    void testGetTotalMessagesDropped() {
        metrics.incrementTracesDropped();
        metrics.incrementMetricsDropped();
        metrics.incrementMetricsDropped();
        metrics.incrementLogsDropped();

        assertEquals(4L, metrics.getTotalMessagesDropped());
    }

    @Test
    void testGetMaxQueueUtilizationPercentWithZeroCapacity() {
        metrics.setQueueCapacity(0);
        metrics.updateTracesQueueSize(10);

        assertEquals(0.0, metrics.getMaxQueueUtilizationPercent(), 0.001);
    }

    @Test
    void testGetMaxQueueUtilizationPercentWithTracesHighest() {
        metrics.setQueueCapacity(100);
        metrics.updateTracesQueueSize(80);
        metrics.updateMetricsQueueSize(50);
        metrics.updateLogsQueueSize(30);

        assertEquals(80.0, metrics.getMaxQueueUtilizationPercent(), 0.001);
    }

    @Test
    void testGetMaxQueueUtilizationPercentWithMetricsHighest() {
        metrics.setQueueCapacity(1000);
        metrics.updateTracesQueueSize(400);
        metrics.updateMetricsQueueSize(850);
        metrics.updateLogsQueueSize(200);

        assertEquals(85.0, metrics.getMaxQueueUtilizationPercent(), 0.001);
    }

    @Test
    void testGetMaxQueueUtilizationPercentWithLogsHighest() {
        metrics.setQueueCapacity(200);
        metrics.updateTracesQueueSize(50);
        metrics.updateMetricsQueueSize(60);
        metrics.updateLogsQueueSize(190);

        assertEquals(95.0, metrics.getMaxQueueUtilizationPercent(), 0.001);
    }

    @Test
    void testGetMaxQueueUtilizationPercentFullQueue() {
        metrics.setQueueCapacity(100);
        metrics.updateTracesQueueSize(100);

        assertEquals(100.0, metrics.getMaxQueueUtilizationPercent(), 0.001);
    }

    @Test
    void testGetTotalLagCount() {
        metrics.incrementTracesReceived();
        metrics.incrementTracesReceived();
        metrics.incrementTracesReceived();
        metrics.incrementMetricsReceived();
        metrics.incrementLogsReceived();
        // Total received: 5

        metrics.incrementRecordsProduced(2);
        // Produced: 2

        assertEquals(3L, metrics.getTotalLagCount());
    }

    @Test
    void testGetTotalLagCountWhenProducedEqualsReceived() {
        metrics.incrementTracesReceived();
        metrics.incrementMetricsReceived();
        metrics.incrementRecordsProduced(2);

        assertEquals(0L, metrics.getTotalLagCount());
    }

    @Test
    void testGetTotalLagCountWhenProducedExceedsReceived() {
        metrics.incrementTracesReceived();
        metrics.incrementRecordsProduced(5);

        // Lag can be negative if produced exceeds received
        assertEquals(-4L, metrics.getTotalLagCount());
    }

    @Test
    void testGetDropRateWithNoMessagesReceived() {
        metrics.incrementTracesDropped();
        metrics.incrementMetricsDropped();

        assertEquals(0.0, metrics.getDropRate(), 0.001);
    }

    @Test
    void testGetDropRateWithNoDrops() {
        metrics.incrementTracesReceived();
        metrics.incrementMetricsReceived();
        metrics.incrementLogsReceived();

        assertEquals(0.0, metrics.getDropRate(), 0.001);
    }

    @Test
    void testGetDropRateCalculation() {
        // Receive 100 messages
        for (int i = 0; i < 80; i++) {
            metrics.incrementTracesReceived();
        }
        for (int i = 0; i < 20; i++) {
            metrics.incrementMetricsReceived();
        }

        // Drop 10 messages
        for (int i = 0; i < 7; i++) {
            metrics.incrementTracesDropped();
        }
        for (int i = 0; i < 3; i++) {
            metrics.incrementMetricsDropped();
        }

        // Drop rate should be 10/100 = 10%
        assertEquals(10.0, metrics.getDropRate(), 0.001);
    }

    @Test
    void testGetDropRateHundredPercent() {
        // All messages dropped
        for (int i = 0; i < 50; i++) {
            metrics.incrementTracesReceived();
            metrics.incrementTracesDropped();
        }

        assertEquals(100.0, metrics.getDropRate(), 0.001);
    }

    @Test
    void testResetCounters() {
        // Set various metrics
        metrics.incrementTracesReceived();
        metrics.incrementMetricsReceived();
        metrics.incrementLogsReceived();
        metrics.incrementTracesDropped();
        metrics.incrementMetricsDropped();
        metrics.incrementLogsDropped();
        metrics.incrementRecordsProduced(5);

        // Reset
        metrics.resetCounters();

        // All counters should be zero
        assertEquals(0L, metrics.getTracesReceived());
        assertEquals(0L, metrics.getMetricsReceived());
        assertEquals(0L, metrics.getLogsReceived());
        assertEquals(0L, metrics.getTracesDropped());
        assertEquals(0L, metrics.getMetricsDropped());
        assertEquals(0L, metrics.getLogsDropped());
        assertEquals(0L, metrics.getRecordsProduced());
    }

    @Test
    void testResetCountersDoesNotAffectQueueMetrics() {
        metrics.updateTracesQueueSize(50);
        metrics.updateMetricsQueueSize(60);
        metrics.updateLogsQueueSize(70);
        metrics.setQueueCapacity(100);

        metrics.resetCounters();

        // Queue metrics should remain unchanged
        assertEquals(50, metrics.getTracesQueueSize());
        assertEquals(60, metrics.getMetricsQueueSize());
        assertEquals(70, metrics.getLogsQueueSize());
        assertEquals(100, metrics.getQueueCapacity());
    }

    @Test
    void testGetConnectorName() {
        assertEquals(connectorName, metrics.getConnectorName());
    }

    @Test
    void testMBeanRegistration() throws Exception {
        ObjectName objectName = new ObjectName(
                String.format("io.conduktor.connect.otel:type=OpenTelemetryConnector,name=%s",
                        ObjectName.quote(connectorName))
        );

        assertTrue(mbs.isRegistered(objectName));

        // Verify we can read attributes via JMX
        Long tracesReceived = (Long) mbs.getAttribute(objectName, "TracesReceived");
        assertEquals(0L, tracesReceived);

        String name = (String) mbs.getAttribute(objectName, "ConnectorName");
        assertEquals(connectorName, name);
    }

    @Test
    void testMBeanAttributesAccessibleViaJMX() throws Exception {
        ObjectName objectName = new ObjectName(
                String.format("io.conduktor.connect.otel:type=OpenTelemetryConnector,name=%s",
                        ObjectName.quote(connectorName))
        );

        // Update some metrics
        metrics.incrementTracesReceived();
        metrics.incrementMetricsReceived();
        metrics.setQueueCapacity(1000);
        metrics.updateTracesQueueSize(250);

        // Read via JMX
        assertEquals(1L, mbs.getAttribute(objectName, "TracesReceived"));
        assertEquals(1L, mbs.getAttribute(objectName, "MetricsReceived"));
        assertEquals(0L, mbs.getAttribute(objectName, "LogsReceived"));
        assertEquals(1000, mbs.getAttribute(objectName, "QueueCapacity"));
        assertEquals(250, mbs.getAttribute(objectName, "TracesQueueSize"));
        assertEquals(25.0, (Double) mbs.getAttribute(objectName, "MaxQueueUtilizationPercent"), 0.001);
    }

    @Test
    void testMBeanOperationsAccessibleViaJMX() throws Exception {
        ObjectName objectName = new ObjectName(
                String.format("io.conduktor.connect.otel:type=OpenTelemetryConnector,name=%s",
                        ObjectName.quote(connectorName))
        );

        // Set some values
        metrics.incrementTracesReceived();
        metrics.incrementMetricsReceived();

        // Invoke reset operation via JMX
        mbs.invoke(objectName, "resetCounters", new Object[]{}, new String[]{});

        // Verify counters were reset
        assertEquals(0L, metrics.getTracesReceived());
        assertEquals(0L, metrics.getMetricsReceived());
    }

    @Test
    void testCloseUnregistersMBean() throws Exception {
        ObjectName objectName = new ObjectName(
                String.format("io.conduktor.connect.otel:type=OpenTelemetryConnector,name=%s",
                        ObjectName.quote(connectorName))
        );

        assertTrue(mbs.isRegistered(objectName));

        metrics.close();

        assertFalse(mbs.isRegistered(objectName));
    }

    @Test
    void testMultipleCloseCallsDoNotThrow() {
        metrics.close();
        assertDoesNotThrow(() -> metrics.close());
    }

    @Test
    void testDuplicateConnectorNameHandling() throws Exception {
        // Create first metrics instance (already created in setUp)
        String duplicateName = "duplicate-connector";
        OpenTelemetryMetrics metrics1 = new OpenTelemetryMetrics(duplicateName);

        // Create second with same name - should unregister old one
        OpenTelemetryMetrics metrics2 = new OpenTelemetryMetrics(duplicateName);

        ObjectName objectName = new ObjectName(
                String.format("io.conduktor.connect.otel:type=OpenTelemetryConnector,name=%s",
                        ObjectName.quote(duplicateName))
        );

        // Should still be registered
        assertTrue(mbs.isRegistered(objectName));

        // Update metrics2 and verify it's the active one
        metrics2.incrementTracesReceived();
        assertEquals(1L, (Long) mbs.getAttribute(objectName, "TracesReceived"));

        // Cleanup
        metrics1.close();
        metrics2.close();
    }

    @Test
    void testConcurrentMetricUpdates() throws InterruptedException {
        final int numThreads = 10;
        final int incrementsPerThread = 100;
        List<Thread> threads = new ArrayList<>();

        // Create threads that increment metrics concurrently
        for (int i = 0; i < numThreads; i++) {
            Thread thread = new Thread(() -> {
                for (int j = 0; j < incrementsPerThread; j++) {
                    metrics.incrementTracesReceived();
                    metrics.incrementMetricsReceived();
                    metrics.incrementLogsReceived();
                }
            });
            threads.add(thread);
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join(5000);
        }

        // Verify all increments were counted
        long expected = (long) numThreads * incrementsPerThread;
        assertEquals(expected, metrics.getTracesReceived());
        assertEquals(expected, metrics.getMetricsReceived());
        assertEquals(expected, metrics.getLogsReceived());
        assertEquals(expected * 3, metrics.getTotalMessagesReceived());
    }

    @Test
    void testMetricsAccuracyUnderLoad() {
        // Simulate realistic load
        for (int i = 0; i < 10000; i++) {
            metrics.incrementTracesReceived();
        }
        for (int i = 0; i < 5000; i++) {
            metrics.incrementMetricsReceived();
        }
        for (int i = 0; i < 3000; i++) {
            metrics.incrementLogsReceived();
        }

        // Some drops
        for (int i = 0; i < 100; i++) {
            metrics.incrementTracesDropped();
        }
        for (int i = 0; i < 50; i++) {
            metrics.incrementMetricsDropped();
        }

        // Produced records
        metrics.incrementRecordsProduced(17500);

        assertEquals(10000L, metrics.getTracesReceived());
        assertEquals(5000L, metrics.getMetricsReceived());
        assertEquals(3000L, metrics.getLogsReceived());
        assertEquals(18000L, metrics.getTotalMessagesReceived());
        assertEquals(100L, metrics.getTracesDropped());
        assertEquals(50L, metrics.getMetricsDropped());
        assertEquals(0L, metrics.getLogsDropped());
        assertEquals(150L, metrics.getTotalMessagesDropped());
        assertEquals(17500L, metrics.getRecordsProduced());
        assertEquals(500L, metrics.getTotalLagCount());
        assertEquals(0.833, metrics.getDropRate(), 0.01); // ~0.833%
    }

    @Test
    void testQueueMetricsUpdatesDoNotAffectCounters() {
        metrics.updateTracesQueueSize(100);
        metrics.updateMetricsQueueSize(200);
        metrics.updateLogsQueueSize(300);
        metrics.setQueueCapacity(1000);

        // Counters should still be zero
        assertEquals(0L, metrics.getTracesReceived());
        assertEquals(0L, metrics.getMetricsReceived());
        assertEquals(0L, metrics.getLogsReceived());
        assertEquals(0L, metrics.getTotalMessagesReceived());
    }
}
