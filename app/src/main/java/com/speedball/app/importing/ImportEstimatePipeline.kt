package com.speedball.app.importing

import com.speedball.app.measurement.EstimateTimingBasis
import com.speedball.app.measurement.MeasurementCalibrationState
import com.speedball.app.measurement.RgbFrame
import com.speedball.app.measurement.TimedFrameSequence
import com.speedball.app.measurement.VisualEstimateDiagnostics
import com.speedball.app.measurement.VisualEstimateDetectorTrace
import com.speedball.app.measurement.VisualEstimateFramePipeline
import com.speedball.app.measurement.VisualEstimateFramePipelineConfig
import com.speedball.app.measurement.VisualEstimateFrameTiming
import com.speedball.app.measurement.VisualEstimateNoReadReason
import com.speedball.app.measurement.VisualEstimateOutcome

/** Runs imported frames through the existing visual-estimate pipeline. */
object ImportEstimatePipeline {
    fun estimate(
        frames: ImportVideoFrameSequence,
        timing: ImportTimingReconciliation,
        calibration: MeasurementCalibrationState,
        config: VisualEstimateFramePipelineConfig,
    ): ImportEstimateOutcome =
        estimateWithTrace(frames, timing, calibration, config).outcome

    fun estimateWithTrace(
        frames: ImportVideoFrameSequence,
        timing: ImportTimingReconciliation,
        calibration: MeasurementCalibrationState,
        config: VisualEstimateFramePipelineConfig,
    ): ImportEstimatePipelineResult {
        if (frames.frames.size != timing.timestampsSeconds.size) {
            val noRead = importNoRead(
                frameCount = frames.frames.size,
                timing = timing,
                message = "Imported frame count does not match reconciled timing.",
            )
            return ImportEstimatePipelineResult(
                outcome = noRead,
                processedFrames = emptyList(),
                detectorTrace = VisualEstimateDetectorTrace(config.trackConfig.detectorConfig, emptyList()).withOutcome(noRead),
            )
        }
        val processedFrames = frames.frames.zip(timing.timestampsSeconds).map { (frame, timestampSeconds) ->
            RgbFrame(
                width = frame.width,
                height = frame.height,
                argbPixels = frame.argbPixels,
                timestampSeconds = timestampSeconds,
            )
        }
        val result = VisualEstimateFramePipeline.estimateFromFramesWithTrace(
            sequence = TimedFrameSequence(processedFrames),
            calibration = calibration,
            config = config.copy(timing = VisualEstimateFrameTiming.RequireRealTimestamps),
        )
        return ImportEstimatePipelineResult(
            outcome = result.outcome.withImportTimingProvenance(timing),
            processedFrames = processedFrames,
            detectorTrace = result.detectorTrace.withOutcome(result.outcome.withImportTimingProvenance(timing)),
        )
    }

    private fun importNoRead(
        frameCount: Int,
        timing: ImportTimingReconciliation,
        message: String,
    ): VisualEstimateOutcome.NoRead =
        VisualEstimateOutcome.NoRead(
            reason = VisualEstimateNoReadReason.BAD_TIMESTAMPS,
            message = message,
            diagnostics = VisualEstimateDiagnostics(
                frameCount = frameCount,
                detectionCount = 0,
                timingBasis = timing.basis.toEstimateTimingBasis(),
                timestampGapSummary = timing.timestampGapSummary,
                fitResidualPx = null,
                confidence = timing.confidence,
                assumptions = timing.assumptions,
            ),
        )

    private fun VisualEstimateOutcome.withImportTimingProvenance(
        timing: ImportTimingReconciliation,
    ): VisualEstimateOutcome =
        when (this) {
            is VisualEstimateOutcome.Success -> VisualEstimateOutcome.Success(
                milesPerHour = milesPerHour,
                launchAngleDegrees = launchAngleDegrees,
                diagnostics = diagnostics.withImportTimingProvenance(timing),
            )
            is VisualEstimateOutcome.NoRead -> copy(
                diagnostics = diagnostics?.withImportTimingProvenance(timing)
                    ?: VisualEstimateDiagnostics(
                        frameCount = timing.timestampsSeconds.size,
                        detectionCount = 0,
                        timingBasis = timing.basis.toEstimateTimingBasis(),
                        timestampGapSummary = timing.timestampGapSummary,
                        fitResidualPx = null,
                        confidence = timing.confidence,
                        assumptions = timing.assumptions,
                    ),
            )
        }

    private fun VisualEstimateDiagnostics.withImportTimingProvenance(
        timing: ImportTimingReconciliation,
    ): VisualEstimateDiagnostics =
        copy(
            timingBasis = timing.basis.toEstimateTimingBasis(),
            timestampGapSummary = timing.timestampGapSummary,
            confidence = timing.confidence,
            assumptions = (assumptions + timing.assumptions).distinct(),
        )

    private fun ImportTimingBasis.toEstimateTimingBasis(): EstimateTimingBasis =
        when (this) {
            ImportTimingBasis.CONTAINER_PRESENTATION_TIMESTAMPS ->
                EstimateTimingBasis.IMPORT_CONTAINER_PRESENTATION_TIMESTAMPS
            ImportTimingBasis.PARTIAL_TIMESTAMP_VISUAL_GAP_RECONCILIATION ->
                EstimateTimingBasis.IMPORT_PARTIAL_TIMESTAMP_VISUAL_GAP_RECONCILIATION
            ImportTimingBasis.VISUAL_FRAME_DELTA_INFERENCE ->
                EstimateTimingBasis.IMPORT_VISUAL_FRAME_DELTA_INFERENCE
            ImportTimingBasis.RECORDED_CAPTURE_FRAME_INTERVAL ->
                EstimateTimingBasis.RECORDED_CAPTURE_FRAME_INTERVAL
            ImportTimingBasis.RECORDED_CONTAINER_PRESENTATION_TIMESTAMPS ->
                EstimateTimingBasis.RECORDED_CONTAINER_PRESENTATION_TIMESTAMPS
            ImportTimingBasis.NO_TRUSTWORTHY_TIMING ->
                EstimateTimingBasis.IMPORT_VISUAL_FRAME_DELTA_INFERENCE
        }
}

/** Estimate output plus processed frames and estimator trace for proof rendering. */
data class ImportEstimatePipelineResult(
    val outcome: ImportEstimateOutcome,
    val processedFrames: List<RgbFrame>,
    val detectorTrace: VisualEstimateDetectorTrace,
)
