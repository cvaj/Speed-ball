package com.speedball.app.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DirectProofVariantTest {
    @Test
    fun plannedVariantsIncludeBaselineControlsAndImageReaderRoutes() {
        val variants = plannedDirectProofVariants()

        assertEquals(
            listOf(
                "companion-gl",
                "direct-gl-first",
                "pbo-gl-readback",
                "direct-gl-only",
                "constrained-image-reader",
                "constrained-private-image-reader",
                "standard-image-reader",
            ),
            variants.map { it.id },
        )
        assertEquals(
            listOf(
                DirectProofSurfaceRole.COMPANION_ENCODER,
                DirectProofSurfaceRole.DIRECT_GL_READBACK,
            ),
            variants.first().surfaceOrder,
        )
        assertEquals(
            listOf(
                DirectProofSurfaceRole.DIRECT_GL_READBACK,
                DirectProofSurfaceRole.COMPANION_ENCODER,
            ),
            variants[1].surfaceOrder,
        )
        assertEquals(
            DirectProofConsumerModel.PBO_GL_READBACK,
            variants.single { it.id == "pbo-gl-readback" }.consumerModel,
        )
        assertTrue(variants.single { it.id == "constrained-image-reader" }.constrainedHighSpeed)
        assertTrue(variants.single { it.id == "constrained-private-image-reader" }.constrainedHighSpeed)
        assertFalse(variants.single { it.id == "standard-image-reader" }.constrainedHighSpeed)
        assertEquals(3, variants.single { it.id == "standard-image-reader" }.imageReaderMaxImages)
        assertFalse(
            DirectProofConsumerModel.entries.any { it.name == "DECOUPLED_GL_READBACK" },
            "Do not expose a decoupled GL consumer label until the behavior exists.",
        )
    }

    @Test
    fun variantsRejectContradictoryConsumerAndSurfaceDefinitions() {
        assertThrows(IllegalArgumentException::class.java) {
            DirectProofVariant(
                id = "bad-image-reader",
                sessionShape = DirectProofSessionShape.CONSTRAINED_IMAGE_READER,
                consumerModel = DirectProofConsumerModel.CONSTRAINED_IMAGE_READER,
                constrainedHighSpeed = true,
                surfaceOrder = listOf(DirectProofSurfaceRole.DIRECT_GL_READBACK),
                requestTemplate = DirectProofRequestTemplate.RECORD,
                requiresCompanionScratch = false,
                imageReaderMaxImages = 3,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DirectProofVariant(
                id = "bad-pbo",
                sessionShape = DirectProofSessionShape.COMPANION_ENCODER_REVERSED,
                consumerModel = DirectProofConsumerModel.PBO_GL_READBACK,
                constrainedHighSpeed = true,
                surfaceOrder = listOf(DirectProofSurfaceRole.IMAGE_READER),
                requestTemplate = DirectProofRequestTemplate.RECORD,
                requiresCompanionScratch = false,
                imageReaderMaxImages = 3,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DirectProofVariant(
                id = "bad-companion",
                sessionShape = DirectProofSessionShape.COMPANION_ENCODER,
                consumerModel = DirectProofConsumerModel.SINGLE_SURFACE_TEXTURE,
                constrainedHighSpeed = true,
                surfaceOrder = listOf(DirectProofSurfaceRole.DIRECT_GL_READBACK),
                requestTemplate = DirectProofRequestTemplate.RECORD,
                requiresCompanionScratch = true,
            )
        }
    }

    @Test
    fun glReadbackModeDistinguishesInlineAndPboConsumers() {
        assertEquals(DirectGlReadbackMode.INLINE_READ_PIXELS, directGlReadbackModeFor(companionGlBaselineVariant()))
        assertEquals(DirectGlReadbackMode.PBO_ASYNC_READBACK, directGlReadbackModeFor(companionPboGlReadbackVariant()))
        assertThrows(IllegalStateException::class.java) {
            directGlReadbackModeFor(standardImageReaderVariant())
        }
    }

    @Test
    fun standardConsumerRatiosRequireInBandProducerCadence() {
        val standard = standardImageReaderVariant()
        val constrained = constrainedImageReaderVariant()
        val privateConstrained = constrainedPrivateImageReaderVariant()
        val inBand = evaluateProducerCadenceGate(
            requestedFps = 120,
            medianGapMillis = 8.33,
            maximumGapMillis = 10.0,
        )
        val belowBand = evaluateProducerCadenceGate(
            requestedFps = 120,
            medianGapMillis = 33.33,
            maximumGapMillis = 34.0,
        )

        assertTrue(inBand.inRequestedFpsBand)
        assertFalse(belowBand.inRequestedFpsBand)
        assertTrue(canInterpretConsumerRatio(standard, inBand))
        assertFalse(canInterpretConsumerRatio(standard, belowBand))
        assertTrue(canInterpretConsumerRatio(constrained, belowBand))
        assertTrue(canInterpretConsumerRatio(privateConstrained, belowBand))
    }

    @Test
    fun captureDiagnosticsCarryVariantAndProducerCadenceFields() {
        val diagnostics = DirectCaptureDiagnostics(
            variantId = "standard-image-reader",
            consumerModel = DirectProofConsumerModel.STANDARD_IMAGE_READER,
            surfaceOrder = listOf(DirectProofSurfaceRole.IMAGE_READER),
            requestTemplate = DirectProofRequestTemplate.RECORD,
            directBufferWidth = 1280,
            directBufferHeight = 720,
            requestListSize = 1,
            producerMedianGapMillis = 33.33,
            producerMaximumGapMillis = 35.0,
            producerInRequestedFpsBand = false,
            consumerRatioInterpretable = false,
            imageAcquireNullCount = 2,
        )

        assertEquals("standard-image-reader", diagnostics.variantId)
        assertEquals(DirectProofConsumerModel.STANDARD_IMAGE_READER, diagnostics.consumerModel)
        assertEquals(false, diagnostics.producerInRequestedFpsBand)
        assertEquals(false, diagnostics.consumerRatioInterpretable)
        assertEquals(2, diagnostics.imageAcquireNullCount)
    }
}
