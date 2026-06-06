package com.speedball.app.importing

import com.speedball.app.capture.BurstDiagnostics
import com.speedball.app.measurement.FrameDimensions

/**
 * Capture-side proof that a recorded-HFR estimate may use the requested frame
 * interval as estimate-only timing. This is not a strict measurement proof.
 */
data class RecordedHfrCaptureGateProof(
    val decodedFrameCount: Int,
    val extractedFrameCount: Int,
    val uniqueSensorTimestampCount: Int,
    val sensorCadencePasses: Boolean,
    val captureProofPasses: Boolean,
    val metadataDurationSeconds: Double,
    val secondaryDurationSanityPasses: Boolean,
) {
    val dropVerdict: String =
        if (decodedFrameCount == uniqueSensorTimestampCount && extractedFrameCount == uniqueSensorTimestampCount) {
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
        decodedFrameCount: Int?,
        extractedFrameCount: Int?,
        diagnostics: BurstDiagnostics,
        metadata: ImportVideoMetadata,
    ): ImportValidationResult<RecordedHfrCaptureGateProof> {
        val count = decodedFrameCount ?: return noRead("Recorded capture did not expose decoded sample count.")
        val extractedCount = extractedFrameCount ?: return noRead("Recorded capture did not expose extracted frame count.")
        if (count <= 0) return noRead("Recorded capture produced no decoded video frames.")
        if (extractedCount <= 0) return noRead("Recorded capture produced no extracted video frames.")
        if (extractedCount != count) {
            return noRead(
                "Recorded extracted frame count ($extractedCount) did not match decoded sample count ($count).",
            )
        }
        if (diagnostics.uniqueTimestampCount <= 0) return noRead("Recorded capture has no unique SENSOR_TIMESTAMP values.")
        if (count != diagnostics.uniqueTimestampCount) {
            return noRead(
                "Recorded decoded frame count ($count) did not match capture-side unique SENSOR_TIMESTAMP count (${diagnostics.uniqueTimestampCount}).",
            )
        }
        if (extractedCount != diagnostics.uniqueTimestampCount) {
            return noRead(
                "Recorded extracted frame count ($extractedCount) did not match capture-side unique SENSOR_TIMESTAMP count (${diagnostics.uniqueTimestampCount}).",
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
                decodedFrameCount = count,
                extractedFrameCount = extractedCount,
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
