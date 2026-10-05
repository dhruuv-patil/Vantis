package com.flowops.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds the {@code flowops.reliability} tree from {@code application.yml}: the
 * thresholds and guardrails of the reliability layer (telemetry, baselines, anomaly
 * detection, severity), mirroring {@link ExecutionProperties}.
 *
 * <p>Every threshold here is deliberately conservative — the whole point is to avoid
 * false positives. Baselines are unusable below {@code minSampleCount}, warm-up
 * suppresses detection until {@code warmupPeriod} of history exists, and the
 * {@code sensitivity} multiplier scales how many MADs away from the median counts as
 * anomalous (higher = fewer alerts). {@code cooldown} is the dedup window during which
 * a repeat of the same anomaly key aggregates instead of alerting afresh.
 *
 * @param baselineWindow    history window the baseline engine summarizes
 * @param schedulerDelay    how often the volume/baseline sweep recomputes
 * @param minSampleCount    min observations before a baseline is trusted
 * @param warmupPeriod      min history age before detection runs at all
 * @param sensitivity       MAD multiplier for latency/size outlier thresholds
 * @param volumeZThreshold  robust-z threshold for a volume anomaly
 * @param volumeRatioFloor  min ratio of current/baseline count before a volume alert
 * @param cooldown          dedup window for a repeated anomaly key
 * @param nullRateSpike     absolute null-rate jump that flags an output field
 * @param minConfidence     findings below this confidence are suppressed (FP control)
 * @param volumeDropWindow  wider window used to make a volume-collapse detectable
 */
@ConfigurationProperties(prefix = "flowops.reliability")
public record ReliabilityProperties(
        @DefaultValue("24h") Duration baselineWindow,
        @DefaultValue("5m") Duration schedulerDelay,
        @DefaultValue("30") int minSampleCount,
        @DefaultValue("1h") Duration warmupPeriod,
        @DefaultValue("2.5") double sensitivity,
        @DefaultValue("3.0") double volumeZThreshold,
        @DefaultValue("1.5") double volumeRatioFloor,
        @DefaultValue("15m") Duration cooldown,
        @DefaultValue("0.15") double nullRateSpike,
        @DefaultValue("0.2") double minConfidence,
        @DefaultValue("30m") Duration volumeDropWindow) {

    public ReliabilityProperties {
        if (minSampleCount < 1) {
            minSampleCount = 1;
        }
        if (sensitivity < 0.5) {
            sensitivity = 0.5;
        }
    }

    public static ReliabilityProperties defaults() {
        return new ReliabilityProperties(
                Duration.ofHours(24),
                Duration.ofMinutes(5),
                30,
                Duration.ofHours(1),
                2.5,
                3.0,
                1.5,
                Duration.ofMinutes(15),
                0.15,
                0.2,
                Duration.ofMinutes(30));
    }
}
