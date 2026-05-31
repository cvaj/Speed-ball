package com.speedball.app.measurement

import com.speedball.app.capture.DirectFrameProof
import com.speedball.app.capture.DirectPixelProofSignature
import com.speedball.app.capture.DirectProofRunId
import com.speedball.app.capture.DirectSequenceContentIdentity
import com.speedball.app.capture.DirectTimingSourceProofOutcome
import com.speedball.app.capture.MIN_DIRECT_PROOF_TOKEN_FRAMES
import com.speedball.app.capture.buildDirectPixelProofSignature
import com.speedball.app.capture.buildDirectSequenceContentIdentity
import kotlin.math.abs

/**
 * Production timing token minted only from current-run direct-source proof.
 *
 * The token is intentionally not enough to authorize arbitrary frame sequences.
 * Direct-source measurement must enter through [DirectSourceMeasurementInput],
 * which is emitted as a bound `(sequence, token)` unit by the vetted factory.
 */
class DirectSourceMeasurementTimingProof private constructor(
    internal val runId: DirectProofRunId,
    internal val sequenceIdentity: DirectSequenceContentIdentity,
    override val evidenceLabel: String,
) : MeasurementTimingProof {
    companion object Factory {
        internal fun fromVerifiedDirectProof(
            runId: DirectProofRunId,
            sequenceIdentity: DirectSequenceContentIdentity,
            evidenceLabel: String,
        ): DirectSourceMeasurementTimingProof =
            DirectSourceMeasurementTimingProof(
                runId = runId,
                sequenceIdentity = sequenceIdentity,
                evidenceLabel = evidenceLabel,
            )
    }
}

/**
 * Inseparable direct-source measurement input.
 *
 * Main code cannot attach a direct timing token to an arbitrary
 * [TimedFrameSequence]. The pipeline accepts direct-source timing only through
 * this bound unit; the generic proven-timing path rejects direct tokens.
 */
class DirectSourceMeasurementInput private constructor(
    val sequence: TimedFrameSequence,
    internal val timingProof: DirectSourceMeasurementTimingProof,
) {
    companion object {
        internal fun fromProof(
            proof: DirectTimingSourceProofOutcome.Success,
            sequence: TimedFrameSequence,
        ): DirectSourceMeasurementInput? {
            if (proof.frames.size < MIN_DIRECT_PROOF_TOKEN_FRAMES) return null

            val recomputedIdentity = buildDirectSequenceContentIdentity(proof.frames)
            val eligibility = proof.tokenEligibility
            if (eligibility.sequenceIdentity != recomputedIdentity) return null
            if (proof.diagnostics.sequenceIdentity != recomputedIdentity) return null
            if (proof.diagnostics.tokenEligibility != eligibility) return null
            if (!sequence.matchesProofFrames(proof.frames)) return null

            return DirectSourceMeasurementInput(
                sequence = sequence,
                timingProof = DirectSourceMeasurementTimingProof.fromVerifiedDirectProof(
                    runId = eligibility.runId,
                    sequenceIdentity = recomputedIdentity,
                    evidenceLabel = eligibility.evidenceLabel,
                ),
            )
        }
    }
}

private fun TimedFrameSequence.matchesProofFrames(
    proofFrames: List<DirectFrameProof>,
): Boolean {
    if (frames.size != proofFrames.size) return false
    if (proofFrames.isEmpty()) return false
    val firstTimestampNanos = proofFrames.first().timestampNanos
    return frames.zip(proofFrames).all { (frame, proofFrame) ->
        if (frame.width != proofFrame.width || frame.height != proofFrame.height) return@all false
        if (frame.argbPixels.size != frame.width * frame.height) return@all false
        val expectedRelativeSeconds = (proofFrame.timestampNanos - firstTimestampNanos).toDouble() / NANOS_PER_SECOND
        if (abs(frame.timestampSeconds - expectedRelativeSeconds) > TIMESTAMP_SECONDS_EPSILON) return@all false
        val signature = buildDirectPixelProofSignature(
            timestampNanos = proofFrame.timestampNanos,
            frameWidth = frame.width,
            frameHeight = frame.height,
            tileLeft = proofFrame.pixelSignature.tileLeft,
            tileTop = proofFrame.pixelSignature.tileTop,
            tileWidth = proofFrame.pixelSignature.tileWidth,
            tileHeight = proofFrame.pixelSignature.tileHeight,
            argbPixels = frame.extractTilePixels(proofFrame.pixelSignature),
        )
        signature == proofFrame.pixelSignature
    }
}

private fun RgbFrame.extractTilePixels(
    signature: DirectPixelProofSignature,
): IntArray {
    if (argbPixels.size != width * height) return IntArray(0)
    return IntArray(signature.tileWidth * signature.tileHeight) { index ->
        val x = signature.tileLeft + index % signature.tileWidth
        val y = signature.tileTop + index / signature.tileWidth
        argbPixels[y * width + x]
    }
}

private const val NANOS_PER_SECOND: Double = 1_000_000_000.0
private const val TIMESTAMP_SECONDS_EPSILON: Double = 1.0e-9
