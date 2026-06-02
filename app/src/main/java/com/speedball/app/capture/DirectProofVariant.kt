package com.speedball.app.capture

/** Stable id for the current companion-encoder plus GL readback baseline. */
const val BASELINE_DIRECT_PROOF_VARIANT_ID: String = "companion-gl"

/** Pure description of one source-route candidate for direct timing proof. */
data class DirectProofVariant(
    val id: String,
    val sessionShape: DirectProofSessionShape,
    val consumerModel: DirectProofConsumerModel,
    val constrainedHighSpeed: Boolean,
    val surfaceOrder: List<DirectProofSurfaceRole>,
    val requestTemplate: DirectProofRequestTemplate,
    val requiresCompanionScratch: Boolean,
    val imageReaderMaxImages: Int? = null,
) {
    init {
        require(id.isNotBlank()) { "Variant id must not be blank." }
        require(surfaceOrder.isNotEmpty()) { "Variant surface order must not be empty." }
        require(surfaceOrder.distinct().size == surfaceOrder.size) { "Variant surface order must not contain duplicates." }
        require(!requiresCompanionScratch || DirectProofSurfaceRole.COMPANION_ENCODER in surfaceOrder) {
            "Companion scratch variants must include the companion encoder surface."
        }
        require(
            when (consumerModel) {
                DirectProofConsumerModel.SINGLE_SURFACE_TEXTURE,
                DirectProofConsumerModel.PBO_GL_READBACK,
                -> DirectProofSurfaceRole.DIRECT_GL_READBACK in surfaceOrder

                DirectProofConsumerModel.CONSTRAINED_IMAGE_READER,
                DirectProofConsumerModel.CONSTRAINED_PRIVATE_IMAGE_READER,
                DirectProofConsumerModel.STANDARD_IMAGE_READER,
                -> DirectProofSurfaceRole.IMAGE_READER in surfaceOrder
            },
        ) {
            "Consumer model must match the variant surface roles."
        }
        require(
            imageReaderMaxImages == null ||
                (imageReaderMaxImages > 1 && DirectProofSurfaceRole.IMAGE_READER in surfaceOrder),
        ) {
            "ImageReader maxImages must be greater than one and tied to an ImageReader surface."
        }
    }
}

/** Phase 12 direct-source variants in the reviewed probe order. */
fun plannedDirectProofVariants(): List<DirectProofVariant> =
    listOf(
        companionGlBaselineVariant(),
        companionGlDirectFirstVariant(),
        companionPboGlReadbackVariant(),
        directOnlyGlVariant(),
        constrainedImageReaderVariant(),
        constrainedPrivateImageReaderVariant(),
        standardImageReaderVariant(),
    )

/** Current production baseline: companion encoder drives constrained high speed, GL reads direct frames. */
fun companionGlBaselineVariant(): DirectProofVariant =
    DirectProofVariant(
        id = BASELINE_DIRECT_PROOF_VARIANT_ID,
        sessionShape = DirectProofSessionShape.COMPANION_ENCODER,
        consumerModel = DirectProofConsumerModel.SINGLE_SURFACE_TEXTURE,
        constrainedHighSpeed = true,
        surfaceOrder = listOf(
            DirectProofSurfaceRole.COMPANION_ENCODER,
            DirectProofSurfaceRole.DIRECT_GL_READBACK,
        ),
        requestTemplate = DirectProofRequestTemplate.RECORD,
        requiresCompanionScratch = true,
    )

/** Surface-order control for constrained high-speed sessions. */
fun companionGlDirectFirstVariant(): DirectProofVariant =
    companionGlBaselineVariant().copy(
        id = "direct-gl-first",
        sessionShape = DirectProofSessionShape.COMPANION_ENCODER_REVERSED,
        surfaceOrder = listOf(
            DirectProofSurfaceRole.DIRECT_GL_READBACK,
            DirectProofSurfaceRole.COMPANION_ENCODER,
        ),
    )

/** Real PBO-backed GL readback probe; it does not alias the inline GL consumer model. */
fun companionPboGlReadbackVariant(): DirectProofVariant =
    companionGlDirectFirstVariant().copy(
        id = "pbo-gl-readback",
        consumerModel = DirectProofConsumerModel.PBO_GL_READBACK,
    )

/** Direct-only constrained high-speed GL target when Camera2/HAL accepts it. */
fun directOnlyGlVariant(): DirectProofVariant =
    DirectProofVariant(
        id = "direct-gl-only",
        sessionShape = DirectProofSessionShape.DIRECT_ONLY,
        consumerModel = DirectProofConsumerModel.SINGLE_SURFACE_TEXTURE,
        constrainedHighSpeed = true,
        surfaceOrder = listOf(DirectProofSurfaceRole.DIRECT_GL_READBACK),
        requestTemplate = DirectProofRequestTemplate.RECORD,
        requiresCompanionScratch = false,
    )

/** Constrained high-speed ImageReader acceptance and buffering probe. */
fun constrainedImageReaderVariant(maxImages: Int = 3): DirectProofVariant =
    DirectProofVariant(
        id = "constrained-image-reader",
        sessionShape = DirectProofSessionShape.CONSTRAINED_IMAGE_READER,
        consumerModel = DirectProofConsumerModel.CONSTRAINED_IMAGE_READER,
        constrainedHighSpeed = true,
        surfaceOrder = listOf(DirectProofSurfaceRole.IMAGE_READER),
        requestTemplate = DirectProofRequestTemplate.RECORD,
        requiresCompanionScratch = false,
        imageReaderMaxImages = maxImages,
    )

/** Constrained high-speed PRIVATE ImageReader probe with video-encode usage flags. */
fun constrainedPrivateImageReaderVariant(maxImages: Int = 3): DirectProofVariant =
    DirectProofVariant(
        id = "constrained-private-image-reader",
        sessionShape = DirectProofSessionShape.CONSTRAINED_PRIVATE_IMAGE_READER,
        consumerModel = DirectProofConsumerModel.CONSTRAINED_PRIVATE_IMAGE_READER,
        constrainedHighSpeed = true,
        surfaceOrder = listOf(DirectProofSurfaceRole.IMAGE_READER),
        requestTemplate = DirectProofRequestTemplate.RECORD,
        requiresCompanionScratch = false,
        imageReaderMaxImages = maxImages,
    )

/** Standard Camera2 ImageReader consumer-model control when constrained ImageReader is rejected. */
fun standardImageReaderVariant(maxImages: Int = 3): DirectProofVariant =
    DirectProofVariant(
        id = "standard-image-reader",
        sessionShape = DirectProofSessionShape.STANDARD_IMAGE_READER,
        consumerModel = DirectProofConsumerModel.STANDARD_IMAGE_READER,
        constrainedHighSpeed = false,
        surfaceOrder = listOf(DirectProofSurfaceRole.IMAGE_READER),
        requestTemplate = DirectProofRequestTemplate.RECORD,
        requiresCompanionScratch = false,
        imageReaderMaxImages = maxImages,
    )

/** Producer-cadence gate for interpreting standard-session consumer-model ratios. */
data class DirectProducerCadenceGate(
    val requestedFps: Int,
    val medianGapMillis: Double?,
    val maximumGapMillis: Double?,
    val inRequestedFpsBand: Boolean,
) {
    init {
        require(requestedFps > 0) { "Requested fps must be positive." }
        require(medianGapMillis == null || medianGapMillis >= 0.0) { "Median gap must be non-negative." }
        require(maximumGapMillis == null || maximumGapMillis >= 0.0) { "Maximum gap must be non-negative." }
    }
}

/** Evaluates whether producer callbacks are close enough to requested fps to interpret consumer ratios. */
fun evaluateProducerCadenceGate(
    requestedFps: Int,
    medianGapMillis: Double?,
    maximumGapMillis: Double?,
): DirectProducerCadenceGate {
    require(requestedFps > 0) { "Requested fps must be positive." }
    val expectedGapMillis = 1_000.0 / requestedFps
    val lower = expectedGapMillis * 0.75
    val upper = expectedGapMillis * 1.25
    val inBand = medianGapMillis != null && medianGapMillis in lower..upper
    return DirectProducerCadenceGate(
        requestedFps = requestedFps,
        medianGapMillis = medianGapMillis,
        maximumGapMillis = maximumGapMillis,
        inRequestedFpsBand = inBand,
    )
}

/** Applies the Phase 12 rule that standard-session ratios need in-band producer cadence first. */
fun canInterpretConsumerRatio(
    variant: DirectProofVariant,
    producerGate: DirectProducerCadenceGate,
): Boolean =
    variant.consumerModel != DirectProofConsumerModel.STANDARD_IMAGE_READER ||
        producerGate.inRequestedFpsBand
