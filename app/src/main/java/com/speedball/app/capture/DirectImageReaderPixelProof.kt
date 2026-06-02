package com.speedball.app.capture

/**
 * Minimal same-image snapshot interface for ImageReader pixel proof.
 *
 * Implementations must bind `timestampNanos` and `readArgbTile()` to the same
 * acquired Image object. The caller closes the snapshot after the aggregate
 * signature is built and must not retain raw image planes or full-frame pixels.
 */
internal interface DirectImageReaderSnapshot {
    val timestampNanos: Long
    val width: Int
    val height: Int

    fun readArgbTile(
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): IntArray

    fun close()
}

/** Terminal result from one ImageReader same-image pixel-signature attempt. */
internal sealed interface DirectImageReaderPixelProofOutcome {
    data class Success(val signature: DirectPixelProofSignature) : DirectImageReaderPixelProofOutcome

    data class Failure(
        val reason: DirectTimingSourceFailure,
        val message: String,
    ) : DirectImageReaderPixelProofOutcome
}

/**
 * Builds a direct pixel-proof signature from one acquired ImageReader snapshot.
 *
 * This is the ImageReader counterpart to the GL same-`updateTexImage()` window:
 * the acquired image is the atomic snapshot that binds timestamp and planes.
 */
internal fun buildDirectImageReaderPixelProofSignature(
    snapshot: DirectImageReaderSnapshot,
    tileLeft: Int,
    tileTop: Int,
    tileWidth: Int,
    tileHeight: Int,
): DirectImageReaderPixelProofOutcome =
    try {
        if (snapshot.timestampNanos <= 0L) {
            DirectImageReaderPixelProofOutcome.Failure(
                reason = DirectTimingSourceFailure.MISSING_DIRECT_TIMESTAMPS,
                message = "ImageReader proof requires a positive acquired-image timestamp.",
            )
        } else {
            val argb = snapshot.readArgbTile(tileLeft, tileTop, tileWidth, tileHeight)
            DirectImageReaderPixelProofOutcome.Success(
                buildDirectPixelProofSignature(
                    timestampNanos = snapshot.timestampNanos,
                    frameWidth = snapshot.width,
                    frameHeight = snapshot.height,
                    tileLeft = tileLeft,
                    tileTop = tileTop,
                    tileWidth = tileWidth,
                    tileHeight = tileHeight,
                    argbPixels = argb,
                ),
            )
        }
    } catch (exception: IllegalArgumentException) {
        DirectImageReaderPixelProofOutcome.Failure(
            reason = DirectTimingSourceFailure.PIXEL_READBACK_FAILED,
            message = "ImageReader same-image tile conversion failed: ${exception.message ?: exception.javaClass.simpleName}.",
        )
    } catch (exception: IllegalStateException) {
        DirectImageReaderPixelProofOutcome.Failure(
            reason = DirectTimingSourceFailure.PIXEL_READBACK_FAILED,
            message = "ImageReader same-image tile conversion failed: ${exception.message ?: exception.javaClass.simpleName}.",
        )
    } finally {
        snapshot.close()
    }
