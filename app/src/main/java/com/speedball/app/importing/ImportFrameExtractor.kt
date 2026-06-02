package com.speedball.app.importing

/** Cancellation token used by bounded import extraction. */
fun interface ImportCancellationSignal {
    fun isCancelled(): Boolean
}

/** Source boundary for decoded import frames. Android codec code implements this later. */
interface ImportFrameSource : AutoCloseable {
    fun nextFrame(): ImportVideoFrame?
}

/** Bounded import frame extraction limits. */
data class ImportFrameExtractionConfig(
    val maxFrames: Int,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxTotalPixels: Long,
) {
    fun validate(): ImportValidationResult<ImportFrameExtractionConfig> {
        if (maxFrames <= 0 || maxWidth <= 0 || maxHeight <= 0 || maxTotalPixels <= 0L) {
            return ImportValidationResult.NoRead(
                reason = ImportNoReadReason.RESOURCE_LIMIT_EXCEEDED,
                message = "Import extraction limits must be positive.",
            )
        }
        return ImportValidationResult.Success(this)
    }
}

/** Extracts imported frames with resource, timestamp, and cancellation gates. */
object ImportFrameExtractor {
    fun extract(
        source: ImportFrameSource,
        config: ImportFrameExtractionConfig,
        cancellationSignal: ImportCancellationSignal = ImportCancellationSignal { false },
    ): ImportValidationResult<ImportVideoFrameSequence> {
        config.validate().let { validation ->
            if (validation is ImportValidationResult.NoRead) return validation
        }
        val frames = mutableListOf<ImportVideoFrame>()
        var previousPts: Long? = null
        return try {
            while (true) {
                if (cancellationSignal.isCancelled()) {
                    return ImportValidationResult.NoRead(
                        reason = ImportNoReadReason.RESOURCE_LIMIT_EXCEEDED,
                        message = "Import frame extraction was cancelled.",
                    )
                }
                val frame = source.nextFrame() ?: break
                validateFrame(frame, config, previousPts)?.let { return it }
                previousPts = frame.presentationTimestampNanos ?: previousPts
                frames += frame
                if (frames.size > config.maxFrames) {
                    return ImportValidationResult.NoRead(
                        reason = ImportNoReadReason.RESOURCE_LIMIT_EXCEEDED,
                        message = "Imported clip exceeds the frame extraction limit.",
                    )
                }
            }
            ImportValidationResult.Success(ImportVideoFrameSequence(frames.toList()))
        } catch (_: RuntimeException) {
            ImportValidationResult.NoRead(
                reason = ImportNoReadReason.INVALID_METADATA,
                message = "Imported clip could not be decoded safely.",
            )
        } finally {
            source.close()
        }
    }

    private fun validateFrame(
        frame: ImportVideoFrame,
        config: ImportFrameExtractionConfig,
        previousPts: Long?,
    ): ImportValidationResult.NoRead? {
        if (frame.frameIndex < 0 || frame.width <= 0 || frame.height <= 0) {
            return ImportValidationResult.NoRead(
                reason = ImportNoReadReason.INVALID_METADATA,
                message = "Imported frame metadata is invalid.",
            )
        }
        if (frame.width > config.maxWidth || frame.height > config.maxHeight) {
            return ImportValidationResult.NoRead(
                reason = ImportNoReadReason.RESOURCE_LIMIT_EXCEEDED,
                message = "Imported frame dimensions exceed configured limits.",
            )
        }
        val pixelCount = frame.width.toLong() * frame.height.toLong()
        if (pixelCount > config.maxTotalPixels || frame.argbPixels.size != pixelCount.toInt()) {
            return ImportValidationResult.NoRead(
                reason = ImportNoReadReason.RESOURCE_LIMIT_EXCEEDED,
                message = "Imported frame pixels exceed configured limits.",
            )
        }
        val pts = frame.presentationTimestampNanos
        if (pts != null && pts <= (previousPts ?: Long.MIN_VALUE)) {
            return ImportValidationResult.NoRead(
                reason = ImportNoReadReason.INVALID_METADATA,
                message = "Imported frame timestamps must be strictly increasing when present.",
            )
        }
        return null
    }
}
