package com.speedball.app.capture

import com.speedball.app.decode.buildOrderedTimestampDiagnostics
import com.speedball.app.decode.buildTimestampDiagnostics

/**
 * Result from one direct-source Camera2 session shape.
 *
 * A successful companion probe contains only consumed direct frame proofs and
 * `SENSOR_TIMESTAMP` callback values. The companion encoder file is not exposed
 * here and cannot become a source for timing or pixels.
 */
sealed interface DirectSessionProbeOutcome {
    data class Success(
        val shape: DirectProofSessionShape,
        val requestListSize: Int,
        val sensorTimestampsNanos: List<Long>,
        val frames: List<DirectFrameProof>,
        val scratchCleanupStatus: CompanionScratchCleanupStatus = CompanionScratchCleanupStatus.ALREADY_ABSENT,
        val captureDiagnostics: DirectCaptureDiagnostics = DirectCaptureDiagnostics(),
    ) : DirectSessionProbeOutcome {
        init {
            require(requestListSize > 0) { "Request list size must be positive." }
            require(frames.isNotEmpty()) { "Successful direct session requires at least one consumed frame." }
        }
    }

    data class Failure(
        val shape: DirectProofSessionShape,
        val reason: DirectTimingSourceFailure,
        val message: String,
        val requestListSize: Int = 0,
        val directTimestampCount: Int = 0,
        val sensorTimestampCount: Int = 0,
        val pixelProofCount: Int = 0,
        val scratchCleanupStatus: CompanionScratchCleanupStatus = CompanionScratchCleanupStatus.ALREADY_ABSENT,
        val captureDiagnostics: DirectCaptureDiagnostics = DirectCaptureDiagnostics(),
    ) : DirectSessionProbeOutcome {
        init {
            require(message.isNotBlank()) { "Failure message must not be blank." }
            require(requestListSize >= 0) { "Request list size must be non-negative." }
            require(directTimestampCount >= 0) { "Direct timestamp count must be non-negative." }
            require(sensorTimestampCount >= 0) { "Sensor timestamp count must be non-negative." }
            require(pixelProofCount >= 0) { "Pixel proof count must be non-negative." }
        }
    }
}

/** Summary of the preview-only control. It is diagnostic and cannot mint a token. */
data class DirectPreviewControlReport(
    val attempted: Boolean,
    val outcome: PreviewFrameOutcome?,
)

/** Terminal result from the companion-first direct proof runner. */
data class DirectTimingSourceProofRunResult(
    val companionOutcome: DirectSessionProbeOutcome,
    val previewControl: DirectPreviewControlReport,
    val proofOutcome: DirectTimingSourceProofOutcome,
)

/**
 * Runs Phase 9 proof orchestration in the reviewed order.
 *
 * The companion-encoder shape is always attempted first. The preview-only
 * control is run only after the companion session reports terminal teardown and
 * scratch cleanup, and its outcome is never used as proof-token eligibility.
 */
class DirectTimingSourceProofRunner(
    private val companionProbe: () -> DirectSessionProbeOutcome,
    private val previewControl: () -> PreviewFrameOutcome,
    private val primaryShape: DirectProofSessionShape = DirectProofSessionShape.COMPANION_ENCODER,
    private val logger: (String) -> Unit = {},
) {
    fun run(
        runId: DirectProofRunId,
        mode: HighSpeedMode,
        pixelConfig: DirectPixelProofConfig,
    ): DirectTimingSourceProofRunResult {
        logger(directProofLogLine("DIRECT_PROOF_START", mode, primaryShape))
        val companionOutcome = companionProbe()
        logger(directProofLogLine("DIRECT_PROOF_COMPANION", mode, companionOutcome))
        val proofOutcome = buildProofOutcomeFromCompanion(runId, mode, pixelConfig, companionOutcome)

        val previewReport = if (shouldRunPreviewControlAfterCompanion(companionOutcome)) {
            logger(directProofLogLine("DIRECT_PROOF_PREVIEW_START", mode, DirectProofSessionShape.PREVIEW_ONLY_CONTROL))
            val outcome = previewControl()
            logger(directPreviewLogLine("DIRECT_PROOF_PREVIEW", mode, outcome))
            DirectPreviewControlReport(attempted = true, outcome = outcome)
        } else {
            DirectPreviewControlReport(attempted = false, outcome = null)
        }

        return DirectTimingSourceProofRunResult(
            companionOutcome = companionOutcome,
            previewControl = previewReport,
            proofOutcome = proofOutcome,
        ).also {
            logger(directProofOutcomeLogLine("DIRECT_PROOF_RESULT", mode, it.proofOutcome))
        }
    }
}

internal fun buildProofOutcomeFromCompanion(
    runId: DirectProofRunId,
    mode: HighSpeedMode,
    pixelConfig: DirectPixelProofConfig,
    companionOutcome: DirectSessionProbeOutcome,
): DirectTimingSourceProofOutcome {
    return when (companionOutcome) {
        is DirectSessionProbeOutcome.Failure -> DirectTimingSourceProofOutcome.Failure(
            reason = companionOutcome.reason,
            message = companionOutcome.message,
            diagnostics = DirectTimingSourceDiagnostics(
                runId = runId,
                sessionShape = companionOutcome.shape,
                requestedFps = mode.fps,
                directTimestampCount = companionOutcome.directTimestampCount,
                sensorTimestampCount = companionOutcome.sensorTimestampCount,
                pixelProofCount = companionOutcome.pixelProofCount,
                sequenceIdentity = null,
                tokenEligibility = null,
                finalFailure = companionOutcome.reason,
                captureDiagnostics = companionOutcome.captureDiagnostics,
            ),
        )

        is DirectSessionProbeOutcome.Success -> {
            if (companionOutcome.scratchCleanupStatus == CompanionScratchCleanupStatus.FAILED) {
                return DirectTimingSourceProofOutcome.Failure(
                    reason = DirectTimingSourceFailure.SCRATCH_FILE_CLEANUP_FAILED,
                    message = "Companion scratch file cleanup failed after direct proof capture.",
                    diagnostics = companionOutcome.diagnostics(runId, mode, sequenceIdentity = null, tokenEligibility = null, finalFailure = DirectTimingSourceFailure.SCRATCH_FILE_CLEANUP_FAILED),
                )
            }
            if (companionOutcome.frames.size < MIN_DIRECT_PROOF_TOKEN_FRAMES) {
                return DirectTimingSourceProofOutcome.Failure(
                    reason = DirectTimingSourceFailure.INSUFFICIENT_DIRECT_FRAMES,
                    message = "Direct proof requires at least $MIN_DIRECT_PROOF_TOKEN_FRAMES consumed same-update frames before cadence can authorize measurement.",
                    diagnostics = companionOutcome.diagnostics(runId, mode, sequenceIdentity = null, tokenEligibility = null, finalFailure = DirectTimingSourceFailure.INSUFFICIENT_DIRECT_FRAMES),
                )
            }
            val timestamps = companionOutcome.frames.map { it.timestampNanos }
            when (
                val timestampProof = proveDirectFrameTimestamps(
                    rawDirectTimestampsNanos = timestamps,
                    rawSensorTimestampsNanos = companionOutcome.sensorTimestampsNanos,
                    fps = mode.fps,
                )
            ) {
                is DirectFrameTimestampProofOutcome.Failure -> DirectTimingSourceProofOutcome.Failure(
                    reason = timestampProof.reason,
                    message = timestampProof.message,
                    diagnostics = companionOutcome.diagnostics(runId, mode, sequenceIdentity = null, tokenEligibility = null, finalFailure = timestampProof.reason),
                )

                is DirectFrameTimestampProofOutcome.Success -> {
                    when (
                        val pixelProof = validateDirectPixelProofSignatures(
                            expectedTimestampNanos = timestampProof.matches.map { it.directTimestampNanos },
                            signatures = companionOutcome.frames.map { it.pixelSignature },
                            config = pixelConfig,
                        )
                    ) {
                        is DirectPixelProofOutcome.Failure -> DirectTimingSourceProofOutcome.Failure(
                            reason = pixelProof.reason,
                            message = pixelProof.message,
                            diagnostics = companionOutcome.diagnostics(runId, mode, sequenceIdentity = null, tokenEligibility = null, finalFailure = pixelProof.reason),
                        )

                        is DirectPixelProofOutcome.Success -> {
                            val identity = buildDirectSequenceContentIdentity(companionOutcome.frames)
                            val eligibility = DirectProofTokenEligibility.fromProvenSequence(
                                runId = runId,
                                sequenceIdentity = identity,
                                evidenceLabel = "direct-${mode.width}x${mode.height}-${mode.fps}fps",
                            )
                            DirectTimingSourceProofOutcome.Success(
                                frames = companionOutcome.frames,
                                diagnostics = companionOutcome.diagnostics(runId, mode, identity, eligibility),
                                tokenEligibility = eligibility,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun shouldRunPreviewControlAfterCompanion(outcome: DirectSessionProbeOutcome): Boolean =
    when (outcome) {
        is DirectSessionProbeOutcome.Success ->
            outcome.scratchCleanupStatus != CompanionScratchCleanupStatus.FAILED

        is DirectSessionProbeOutcome.Failure ->
            outcome.reason != DirectTimingSourceFailure.CAPTURE_BUSY &&
                outcome.reason != DirectTimingSourceFailure.CAMERA_OPEN_FAILED &&
                outcome.reason != DirectTimingSourceFailure.SCRATCH_FILE_CLEANUP_FAILED &&
                outcome.scratchCleanupStatus != CompanionScratchCleanupStatus.FAILED
    }

private fun DirectSessionProbeOutcome.Success.diagnostics(
    runId: DirectProofRunId,
    mode: HighSpeedMode,
    sequenceIdentity: DirectSequenceContentIdentity?,
    tokenEligibility: DirectProofTokenEligibility?,
    finalFailure: DirectTimingSourceFailure? = null,
): DirectTimingSourceDiagnostics =
    run {
        val directDiagnostics = buildOrderedTimestampDiagnostics(frames.map { it.timestampNanos }, mode.fps)
        val sensorDiagnostics = buildTimestampDiagnostics(sensorTimestampsNanos, mode.fps)
        DirectTimingSourceDiagnostics(
            runId = runId,
            sessionShape = shape,
            requestedFps = mode.fps,
            directTimestampCount = frames.size,
            sensorTimestampCount = sensorTimestampsNanos.size,
            pixelProofCount = frames.size,
            sequenceIdentity = sequenceIdentity,
            tokenEligibility = tokenEligibility,
            directMedianGapMillis = directDiagnostics.medianGapMillis,
            directMaximumGapMillis = directDiagnostics.maximumGapMillis,
            sensorMedianGapMillis = sensorDiagnostics.medianGapMillis,
            sensorMaximumGapMillis = sensorDiagnostics.maximumGapMillis,
            finalFailure = finalFailure,
            captureDiagnostics = captureDiagnostics,
        )
    }

private fun directProofLogLine(
    event: String,
    mode: HighSpeedMode,
    shape: DirectProofSessionShape,
): String =
    "$event mode=${mode.label.sanitizedDirectLogToken()} shape=$shape"

private fun directProofLogLine(
    event: String,
    mode: HighSpeedMode,
    outcome: DirectSessionProbeOutcome,
): String =
    when (outcome) {
        is DirectSessionProbeOutcome.Success -> "$event mode=${mode.label.sanitizedDirectLogToken()} shape=${outcome.shape} requestListSize=${outcome.requestListSize} directCount=${outcome.frames.size} sensorCount=${outcome.sensorTimestampsNanos.size} pixelCount=${outcome.frames.size} ${outcome.captureDiagnostics.logFields()} verdict=CAPTURED"
        is DirectSessionProbeOutcome.Failure -> "$event mode=${mode.label.sanitizedDirectLogToken()} shape=${outcome.shape} requestListSize=${outcome.requestListSize} directCount=${outcome.directTimestampCount} sensorCount=${outcome.sensorTimestampCount} pixelCount=${outcome.pixelProofCount} ${outcome.captureDiagnostics.logFields()} verdict=${outcome.reason}"
    }

private fun directPreviewLogLine(
    event: String,
    mode: HighSpeedMode,
    outcome: PreviewFrameOutcome,
): String =
    when (outcome) {
        is PreviewFrameOutcome.Success -> "$event mode=${mode.label.sanitizedDirectLogToken()} shape=${DirectProofSessionShape.PREVIEW_ONLY_CONTROL} previewCount=${outcome.pairs.size} verdict=CAPTURED_DIAGNOSTIC_ONLY"
        is PreviewFrameOutcome.Failure -> "$event mode=${mode.label.sanitizedDirectLogToken()} shape=${DirectProofSessionShape.PREVIEW_ONLY_CONTROL} verdict=${outcome.reason}"
        PreviewFrameOutcome.Cancelled -> "$event mode=${mode.label.sanitizedDirectLogToken()} shape=${DirectProofSessionShape.PREVIEW_ONLY_CONTROL} verdict=CANCELLED"
    }

private fun directProofOutcomeLogLine(
    event: String,
    mode: HighSpeedMode,
    outcome: DirectTimingSourceProofOutcome,
): String =
    when (outcome) {
        is DirectTimingSourceProofOutcome.Success -> "$event mode=${mode.label.sanitizedDirectLogToken()} verdict=TOKEN_ELIGIBLE frames=${outcome.frames.size} ${outcome.diagnostics.logFields()}"
        is DirectTimingSourceProofOutcome.Failure -> "$event mode=${mode.label.sanitizedDirectLogToken()} verdict=${outcome.reason} ${outcome.diagnostics?.logFields().orEmpty()}"
        DirectTimingSourceProofOutcome.Cancelled -> "$event mode=${mode.label.sanitizedDirectLogToken()} verdict=CANCELLED"
    }

private fun DirectCaptureDiagnostics.logFields(): String =
    "variant=$variantId consumer=$consumerModel surfaces=${surfaceOrder.joinToString(separator = "+")} requestTemplate=$requestTemplate directBuffer=${directBufferWidth ?: 0}x${directBufferHeight ?: 0} requestList=${requestListSize ?: 0} frameCallbacks=$frameAvailableCallbackCount captureCallbacks=$captureResultCallbackCount appended=$appendedDirectFrameCount readbacks=$readbackCount imageAcquireNulls=$imageAcquireNullCount readbackMedianMs=${medianReadbackMillis.formatOrNa()} readbackMaxMs=${maximumReadbackMillis.formatOrNa()} producerMedianMs=${producerMedianGapMillis.formatOrNa()} producerMaxMs=${producerMaximumGapMillis.formatOrNa()} producerInBand=${producerInRequestedFpsBand.formatOrNa()} consumerRatioUsable=${consumerRatioInterpretable.formatOrNa()} releaseSteps=${releaseStepTimings.size}"

private fun DirectTimingSourceDiagnostics.logFields(): String =
    "directMedianMs=${directMedianGapMillis.formatOrNa()} directMaxMs=${directMaximumGapMillis.formatOrNa()} sensorMedianMs=${sensorMedianGapMillis.formatOrNa()} sensorMaxMs=${sensorMaximumGapMillis.formatOrNa()} finalGate=${finalFailure ?: "none"} ${captureDiagnostics.logFields()}"

private fun Double?.formatOrNa(): String =
    this?.let { "%.3f".format(it) } ?: "na"

private fun Boolean?.formatOrNa(): String =
    this?.toString() ?: "na"

private fun String.sanitizedDirectLogToken(): String =
    replace(Regex("\\s+"), "_")
