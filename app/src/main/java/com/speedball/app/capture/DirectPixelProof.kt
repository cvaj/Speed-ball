package com.speedball.app.capture

/**
 * Bounded pixel-proof validation controls.
 *
 * The proof window is intentionally small. It proves same-frame pixel access,
 * not detection quality or measurement readiness.
 */
data class DirectPixelProofConfig(
    val maxTotalSamples: Int,
    val requireInterFrameVariation: Boolean = true,
) {
    init {
        require(maxTotalSamples > 0) { "Max total samples must be positive." }
    }
}

/** Diagnostic summary for bounded direct pixel proof. */
data class DirectPixelProofDiagnostics(
    val expectedTimestampCount: Int,
    val signatureCount: Int,
    val totalSampleCount: Int,
    val uniqueSignatureCount: Int,
)

/** Terminal result from bounded direct pixel proof validation. */
sealed interface DirectPixelProofOutcome {
    data class Success(
        val signatures: List<DirectPixelProofSignature>,
        val diagnostics: DirectPixelProofDiagnostics,
    ) : DirectPixelProofOutcome

    data class Failure(
        val reason: DirectTimingSourceFailure,
        val message: String,
        val diagnostics: DirectPixelProofDiagnostics,
    ) : DirectPixelProofOutcome
}

/**
 * Builds a non-reconstructable aggregate signature for one same-`updateTexImage`
 * tile readback.
 *
 * Callers must pass pixels read after the frame's `SurfaceTexture.timestamp`
 * was captured and before any later `updateTexImage()` call. Raw pixels are
 * consumed only to produce aggregate hash/checksum/variation metrics and are not
 * retained in the returned proof.
 */
fun buildDirectPixelProofSignature(
    timestampNanos: Long,
    frameWidth: Int,
    frameHeight: Int,
    tileLeft: Int,
    tileTop: Int,
    tileWidth: Int,
    tileHeight: Int,
    argbPixels: IntArray,
): DirectPixelProofSignature {
    require(argbPixels.isNotEmpty()) { "Pixel sample must not be empty." }
    require(argbPixels.size == tileWidth * tileHeight) { "Pixel sample count must match tile dimensions." }

    var hash = FNV_OFFSET_BASIS
    var checksum = 0L
    var minLuma = Int.MAX_VALUE
    var maxLuma = Int.MIN_VALUE
    argbPixels.forEach { pixel ->
        val unsigned = pixel.toLong() and 0xffff_ffffL
        checksum = (checksum + unsigned) and 0x7fff_ffff_ffff_ffffL
        hash = (hash xor unsigned) * FNV_PRIME
        val luma = pixel.lumaByte()
        minLuma = minOf(minLuma, luma)
        maxLuma = maxOf(maxLuma, luma)
    }
    return DirectPixelProofSignature(
        timestampNanos = timestampNanos,
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        tileLeft = tileLeft,
        tileTop = tileTop,
        tileWidth = tileWidth,
        tileHeight = tileHeight,
        sampleCount = argbPixels.size,
        aggregateHash = hash,
        aggregateChecksum = checksum,
        variationScore = (maxLuma - minLuma).toLong(),
    )
}

fun validateDirectPixelProofSignatures(
    expectedTimestampNanos: List<Long>,
    signatures: List<DirectPixelProofSignature>,
    config: DirectPixelProofConfig,
): DirectPixelProofOutcome {
    val diagnostics = pixelDiagnostics(expectedTimestampNanos, signatures)
    if (expectedTimestampNanos.isEmpty()) {
        return pixelFailure(DirectTimingSourceFailure.MISSING_DIRECT_TIMESTAMPS, "Pixel proof requires at least one accepted timestamp.", diagnostics)
    }
    if (signatures.size != expectedTimestampNanos.size) {
        return pixelFailure(DirectTimingSourceFailure.PIXEL_READBACK_FAILED, "Pixel proof count did not match accepted timestamp count.", diagnostics)
    }
    if (signatures.map { it.timestampNanos } != expectedTimestampNanos) {
        return pixelFailure(DirectTimingSourceFailure.PIXEL_READBACK_FAILED, "Pixel proof timestamps did not match the accepted direct timestamps in order.", diagnostics)
    }
    if (diagnostics.totalSampleCount > config.maxTotalSamples) {
        return pixelFailure(DirectTimingSourceFailure.RESOURCE_LIMIT_EXCEEDED, "Pixel proof exceeded the configured sample limit.", diagnostics)
    }
    if (signatures.any { it.variationScore <= 0L || (it.aggregateHash == 0L && it.aggregateChecksum == 0L) }) {
        return pixelFailure(DirectTimingSourceFailure.BLANK_OR_STALE_PIXEL_PROOF, "Pixel proof looked blank or had no within-tile variation.", diagnostics)
    }
    if (config.requireInterFrameVariation && signatures.size > 1 && diagnostics.uniqueSignatureCount == 1) {
        return pixelFailure(DirectTimingSourceFailure.BLANK_OR_STALE_PIXEL_PROOF, "Pixel proof signatures repeated identically across the proof window.", diagnostics)
    }
    return DirectPixelProofOutcome.Success(signatures, diagnostics)
}

private fun pixelDiagnostics(
    expectedTimestampNanos: List<Long>,
    signatures: List<DirectPixelProofSignature>,
): DirectPixelProofDiagnostics =
    DirectPixelProofDiagnostics(
        expectedTimestampCount = expectedTimestampNanos.size,
        signatureCount = signatures.size,
        totalSampleCount = signatures.sumOf { it.sampleCount },
        uniqueSignatureCount = signatures.map { it.signatureFingerprint() }.distinct().size,
    )

private fun pixelFailure(
    reason: DirectTimingSourceFailure,
    message: String,
    diagnostics: DirectPixelProofDiagnostics,
): DirectPixelProofOutcome.Failure =
    DirectPixelProofOutcome.Failure(reason, message, diagnostics)

private fun DirectPixelProofSignature.signatureFingerprint(): List<Long> =
    listOf(
        frameWidth.toLong(),
        frameHeight.toLong(),
        tileLeft.toLong(),
        tileTop.toLong(),
        tileWidth.toLong(),
        tileHeight.toLong(),
        sampleCount.toLong(),
        aggregateHash,
        aggregateChecksum,
        variationScore,
    )

private fun Int.lumaByte(): Int {
    val red = (this ushr 16) and 0xff
    val green = (this ushr 8) and 0xff
    val blue = this and 0xff
    return (red * 299 + green * 587 + blue * 114) / 1000
}

private const val FNV_OFFSET_BASIS: Long = -3750763034362895579L
private const val FNV_PRIME: Long = 1099511628211L
