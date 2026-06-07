package com.speedball.app.importing

import com.speedball.app.capture.ContainerTimeWindow

/** Proof that decoded frames came from the requested container presentation-time window. */
data class RecordedHfrDecodedWindowProof(
    val requestedWindowStartUs: Long,
    val requestedWindowEndUs: Long,
    val emittedFrameCount: Int,
    val emittedFirstPtsUs: Long?,
    val emittedLastPtsUs: Long?,
    val syncPrefixFrameCount: Int,
    val decodeWallClockMillis: Long,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
) {
    val emittedPtsRangeUs: LongRange? =
        if (emittedFirstPtsUs != null && emittedLastPtsUs != null) emittedFirstPtsUs..emittedLastPtsUs else null
}

/**
 * Window source wrapper for recorded-HFR decoder foundations.
 *
 * Android codec code supplies an [ImportFrameSource] already seeking from the
 * nearest sync sample. This wrapper emits only frames whose container PTS falls
 * inside [window] and closes the upstream source on every terminal path.
 */
class RecordedHfrWindowFrameSource private constructor(
    private val upstream: ImportFrameSource,
    private val window: ContainerTimeWindow,
) : ImportFrameSource {
    private var emitted = 0
    private var previousPtsNanos: Long? = null
    private var closed = false

    override fun nextFrame(): ImportVideoFrame? {
        if (emitted >= window.maxFrames) return null
        while (true) {
            val frame = upstream.nextFrame() ?: return null
            val ptsNanos = frame.presentationTimestampNanos
                ?: throw IllegalStateException("Recorded-HFR window frames must expose container PTS.")
            val ptsUs = ptsNanos / 1_000L
            if (ptsUs < window.windowStartUs) continue
            if (ptsUs > window.windowEndUs) return null
            previousPtsNanos?.let { previous ->
                if (ptsNanos <= previous) throw IllegalStateException("Recorded-HFR window PTS must be strictly increasing.")
            }
            previousPtsNanos = ptsNanos
            emitted += 1
            return frame
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        upstream.close()
    }

    companion object {
        fun create(
            upstream: ImportFrameSource,
            window: ContainerTimeWindow,
        ): ImportValidationResult<RecordedHfrWindowFrameSource> {
            if (window.windowStartUs < 0L || window.windowEndUs <= window.windowStartUs || window.maxFrames <= 0) {
                return ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.NO_TRUSTWORTHY_TIMING,
                    message = "Recorded-HFR window bounds must be finite and positive.",
                )
            }
            return ImportValidationResult.Success(RecordedHfrWindowFrameSource(upstream, window))
        }
    }
}

/** Validates emitted container PTS and wall-clock budget for a decoded HFR window. */
object RecordedHfrDecodedWindowValidator {
    fun validate(
        proof: RecordedHfrDecodedWindowProof,
        requestedMaxFrames: Int,
        minUsableFrames: Int,
        maxDecodeWallClockMillis: Long,
    ): ImportValidationResult<RecordedHfrDecodedWindowProof> {
        if (proof.cancelled) return noRead("Recorded-HFR window decode was cancelled.")
        if (proof.timedOut || proof.decodeWallClockMillis > maxDecodeWallClockMillis) {
            return noRead("Recorded-HFR window decode exceeded the wall-clock budget.")
        }
        if (requestedMaxFrames <= 0 || minUsableFrames <= 0 || minUsableFrames > requestedMaxFrames || maxDecodeWallClockMillis <= 0L) {
            return noRead("Recorded-HFR window decode limits must be valid.")
        }
        if (proof.requestedWindowEndUs <= proof.requestedWindowStartUs || proof.requestedWindowStartUs < 0L) {
            return noRead("Recorded-HFR requested window must be a positive container-time interval.")
        }
        if (proof.emittedFrameCount < minUsableFrames || proof.emittedFrameCount > requestedMaxFrames) {
            return noRead("Recorded-HFR decoded window returned an invalid frame count.")
        }
        val first = proof.emittedFirstPtsUs ?: return noRead("Recorded-HFR decoded window had no first PTS.")
        val last = proof.emittedLastPtsUs ?: return noRead("Recorded-HFR decoded window had no last PTS.")
        if (first < proof.requestedWindowStartUs || last > proof.requestedWindowEndUs || last < first) {
            return noRead("Recorded-HFR decoded frames were outside the requested container-time window.")
        }
        return ImportValidationResult.Success(proof)
    }

    private fun noRead(message: String): ImportValidationResult.NoRead =
        ImportValidationResult.NoRead(ImportNoReadReason.NO_TRUSTWORTHY_TIMING, message)
}
