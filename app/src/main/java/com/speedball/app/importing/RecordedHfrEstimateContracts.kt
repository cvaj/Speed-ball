package com.speedball.app.importing

import com.speedball.app.capture.BurstDiagnostics
import com.speedball.app.measurement.FrameDimensions

/**
 * Capture-side proof that a recorded-HFR estimate may use the requested frame
 * interval as estimate-only timing. This is not a strict measurement proof.
 */
data class RecordedHfrCaptureGateProof(
    val metadataSampleCount: Int,
    val scannedFrameCount: Int,
    val retainedCandidateFrameCount: Int,
    val uniqueSensorTimestampCount: Int,
    val sensorCadencePasses: Boolean,
    val captureProofPasses: Boolean,
    val metadataDurationSeconds: Double,
    val secondaryDurationSanityPasses: Boolean,
) {
    val dropVerdict: String =
        if (metadataSampleCount == scannedFrameCount && scannedFrameCount == uniqueSensorTimestampCount) {
            "PASS"
        } else {
            "NO_READ_CAPTURE_DECODE_COUNT_MISMATCH"
        }

    val cadenceVerdict: String =
        if (sensorCadencePasses && captureProofPasses) "PASS" else "NO_READ_SENSOR_CADENCE"
}

/**
 * Fails loud unless decoded frames match capture-side sensor delivery and the
 * sensor cadence supports a constant requested-frame-interval estimate.
 */
object RecordedHfrCaptureGate {
    fun validate(
        metadataSampleCount: Int?,
        scannedFrameCount: Int?,
        retainedCandidateFrameCount: Int?,
        diagnostics: BurstDiagnostics,
        metadata: ImportVideoMetadata,
    ): ImportValidationResult<RecordedHfrCaptureGateProof> {
        val metadataCount = metadataSampleCount ?: return noRead("Recorded capture did not expose decoded sample count.")
        val scannedCount = scannedFrameCount ?: return noRead("Recorded capture did not expose scanned frame count.")
        val candidateCount = retainedCandidateFrameCount ?: return noRead("Recorded capture did not expose retained candidate count.")
        if (metadataCount <= 0) return noRead("Recorded capture produced no decoded video frames.")
        if (scannedCount <= 0) return noRead("Recorded capture produced no scanned video frames.")
        if (candidateCount < 0) return noRead("Recorded retained candidate count cannot be negative.")
        if (scannedCount != metadataCount) {
            return noRead(
                "Recorded scanned frame count ($scannedCount) did not match decoded sample count ($metadataCount).",
            )
        }
        if (diagnostics.uniqueTimestampCount <= 0) return noRead("Recorded capture has no unique SENSOR_TIMESTAMP values.")
        if (metadataCount != diagnostics.uniqueTimestampCount) {
            return noRead(
                "Recorded decoded frame count ($metadataCount) did not match capture-side unique SENSOR_TIMESTAMP count (${diagnostics.uniqueTimestampCount}).",
            )
        }
        if (scannedCount != diagnostics.uniqueTimestampCount) {
            return noRead(
                "Recorded scanned frame count ($scannedCount) did not match capture-side unique SENSOR_TIMESTAMP count (${diagnostics.uniqueTimestampCount}).",
            )
        }
        if (!diagnostics.medianGapPassesRateBand || !diagnostics.captureProofPasses) {
            return noRead("Recorded capture sensor cadence did not stay in the requested high-speed band.")
        }
        if (!metadata.durationSeconds.isFinite() || metadata.durationSeconds <= 0.0) {
            return noRead("Recorded video metadata duration is not finite and positive.")
        }
        return ImportValidationResult.Success(
            RecordedHfrCaptureGateProof(
                metadataSampleCount = metadataCount,
                scannedFrameCount = scannedCount,
                retainedCandidateFrameCount = candidateCount,
                uniqueSensorTimestampCount = diagnostics.uniqueTimestampCount,
                sensorCadencePasses = diagnostics.medianGapPassesRateBand,
                captureProofPasses = diagnostics.captureProofPasses,
                metadataDurationSeconds = metadata.durationSeconds,
                secondaryDurationSanityPasses = true,
            ),
        )
    }

    private fun noRead(message: String): ImportValidationResult.NoRead =
        ImportValidationResult.NoRead(
            reason = ImportNoReadReason.NO_TRUSTWORTHY_TIMING,
            message = message,
        )
}

/** Proof that a sound-triggered recorded-HFR window is a bounded usable subset. */
data class RecordedHfrWindowGateProof(
    val metadataSampleCount: Int,
    val decodedWindowFrameCount: Int,
    val requestedWindowFrameCount: Int,
    val uniqueSensorTimestampCount: Int,
    val sensorCadencePasses: Boolean,
    val captureProofPasses: Boolean,
    val metadataDurationSeconds: Double,
    val windowStartUs: Long,
    val windowEndUs: Long,
    val emittedFirstPtsUs: Long,
    val emittedLastPtsUs: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val proofFrameCount: Int,
    val sourceValidityVerdict: String,
) {
    val windowVerdict: String =
        if (decodedWindowFrameCount == requestedWindowFrameCount) "PASS" else "NO_READ_WINDOW_FRAME_COUNT"

    val cadenceVerdict: String =
        if (sensorCadencePasses && captureProofPasses) "PASS" else "NO_READ_SENSOR_CADENCE"
}

/**
 * Validates a bounded sound-triggered recorded-HFR decode window.
 *
 * This gate intentionally does not require decoded window count, container
 * sample count, and sensor timestamp count to match. MediaRecorder may drop
 * encoded frames, and this path only proves that the requested container-time
 * subset is usable for an estimate.
 */
object RecordedHfrWindowCaptureGate {
    fun validate(
        metadataSampleCount: Int?,
        decodedWindowFrameCount: Int?,
        requestedWindowFrameCount: Int,
        minUsableFrameCount: Int,
        diagnostics: BurstDiagnostics,
        metadata: ImportVideoMetadata,
        windowStartUs: Long,
        windowEndUs: Long,
        emittedFirstPtsUs: Long?,
        emittedLastPtsUs: Long?,
        sourceWidth: Int,
        sourceHeight: Int,
        proofFrameCount: Int,
        sourceValidityPasses: Boolean,
    ): ImportValidationResult<RecordedHfrWindowGateProof> {
        val metadataCount = metadataSampleCount ?: return noRead("Recorded window metadata did not expose decoded sample count.")
        val decodedCount = decodedWindowFrameCount ?: return noRead("Recorded window did not expose decoded frame count.")
        if (metadataCount <= 0) return noRead("Recorded window source metadata had no decoded samples.")
        if (requestedWindowFrameCount <= 0 || minUsableFrameCount <= 0 || minUsableFrameCount > requestedWindowFrameCount) {
            return noRead("Recorded window frame limits must be positive.")
        }
        if (decodedCount < minUsableFrameCount || decodedCount > requestedWindowFrameCount) {
            return noRead("Recorded window decoded frame count was outside the reviewed bounds.")
        }
        if (diagnostics.uniqueTimestampCount <= 0) return noRead("Recorded capture has no unique SENSOR_TIMESTAMP values.")
        if (!diagnostics.medianGapPassesRateBand || !diagnostics.captureProofPasses) {
            return noRead("Recorded capture sensor cadence did not stay in the requested high-speed band.")
        }
        if (!metadata.durationSeconds.isFinite() || metadata.durationSeconds <= 0.0) {
            return noRead("Recorded video metadata duration is not finite and positive.")
        }
        if (windowStartUs < 0L || windowEndUs <= windowStartUs) {
            return noRead("Recorded window bounds must be a positive container-time interval.")
        }
        val firstPts = emittedFirstPtsUs ?: return noRead("Recorded window emitted no first PTS.")
        val lastPts = emittedLastPtsUs ?: return noRead("Recorded window emitted no last PTS.")
        if (firstPts < windowStartUs || lastPts > windowEndUs || lastPts < firstPts) {
            return noRead("Recorded window emitted PTS outside the requested container-time interval.")
        }
        if (sourceWidth <= 0 || sourceHeight <= 0) return noRead("Recorded window source dimensions must be positive.")
        if (proofFrameCount <= 0) return noRead("Recorded window proof imagery is missing.")
        if (!sourceValidityPasses) return noRead("Recorded window source frames were black or invalid.")
        return ImportValidationResult.Success(
            RecordedHfrWindowGateProof(
                metadataSampleCount = metadataCount,
                decodedWindowFrameCount = decodedCount,
                requestedWindowFrameCount = requestedWindowFrameCount,
                uniqueSensorTimestampCount = diagnostics.uniqueTimestampCount,
                sensorCadencePasses = diagnostics.medianGapPassesRateBand,
                captureProofPasses = diagnostics.captureProofPasses,
                metadataDurationSeconds = metadata.durationSeconds,
                windowStartUs = windowStartUs,
                windowEndUs = windowEndUs,
                emittedFirstPtsUs = firstPts,
                emittedLastPtsUs = lastPts,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                proofFrameCount = proofFrameCount,
                sourceValidityVerdict = "PASS",
            ),
        )
    }

    private fun noRead(message: String): ImportValidationResult.NoRead =
        ImportValidationResult.NoRead(
            reason = ImportNoReadReason.NO_TRUSTWORTHY_TIMING,
            message = message,
        )
}

/** Source and bounded detector dimensions for recorded-HFR frame processing. */
data class RecordedHfrWorkingResolution(
    val source: FrameDimensions,
    val working: FrameDimensions,
) {
    val downscaledForDetection: Boolean get() = source != working
}

/** Chooses a CPU-bounded 16:9 detector working size for recorded-HFR frames. */
object RecordedHfrWorkingResolutionSelector {
    private const val MAX_WORKING_WIDTH = 1280
    private const val MAX_WORKING_HEIGHT = 720

    fun select(sourceWidth: Int, sourceHeight: Int): ImportValidationResult<RecordedHfrWorkingResolution> {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return ImportValidationResult.NoRead(
                reason = ImportNoReadReason.INVALID_METADATA,
                message = "Recorded source dimensions must be positive.",
            )
        }
        val source = FrameDimensions(sourceWidth, sourceHeight)
        val working = if (sourceWidth <= MAX_WORKING_WIDTH && sourceHeight <= MAX_WORKING_HEIGHT) {
            source
        } else {
            FrameDimensions(MAX_WORKING_WIDTH, MAX_WORKING_HEIGHT)
        }
        return ImportValidationResult.Success(RecordedHfrWorkingResolution(source, working))
    }
}
