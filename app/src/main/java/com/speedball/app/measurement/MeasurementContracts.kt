package com.speedball.app.measurement

import com.speedball.core.measurement.VelocityMeasurement
import com.speedball.core.physics.TrajectoryResult

/** Immutable ARGB frame plus the timestamp attached by its source. */
data class RgbFrame(
    val width: Int,
    val height: Int,
    val argbPixels: IntArray,
    val timestampSeconds: Double,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is RgbFrame &&
            width == other.width &&
            height == other.height &&
            argbPixels.contentEquals(other.argbPixels) &&
            timestampSeconds == other.timestampSeconds

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + argbPixels.contentHashCode()
        result = 31 * result + timestampSeconds.hashCode()
        return result
    }
}

/** Ordered frame batch. Every consumer must validate timestamps before use. */
data class TimedFrameSequence(
    val frames: List<RgbFrame>,
)

/**
 * Evidence token for a frame source whose image/timestamp pairing has been proven.
 *
 * Phase 8 deliberately provides no implementation in main source. Integration
 * fixtures define a synthetic implementation in test source only; Phase 9 must
 * add a real production token only after source-specific timing proof exists.
 */
interface MeasurementTimingProof {
    val evidenceLabel: String
}

/** Fail-loud reason a measurement run cannot safely display a speed. */
enum class MeasurementRunFailure {
    UNPROVEN_TIMING,
    BAD_FRAME_SEQUENCE,
    DETECTION_FAILED,
    INSUFFICIENT_DETECTIONS,
    BAD_CALIBRATION,
    MEASUREMENT_REJECTED,
    RESOURCE_LIMIT_EXCEEDED,
}

/** End-to-end measurement outcome. Failures intentionally carry no partial mph. */
sealed interface MeasurementRunOutcome {
    data class Success(
        val measurement: VelocityMeasurement,
        val trajectory: TrajectoryResult,
        val detectionCount: Int,
        val timingProof: MeasurementTimingProof,
    ) : MeasurementRunOutcome

    data class NoRead(
        val reason: MeasurementRunFailure,
        val message: String,
    ) : MeasurementRunOutcome
}
