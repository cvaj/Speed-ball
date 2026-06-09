package com.speedball.app.importing

import com.speedball.app.capture.ContainerTimeWindow
import com.speedball.app.measurement.BlobDetectionConfig
import com.speedball.app.measurement.CandidateReductionBudget
import com.speedball.app.measurement.EstimateTimingBasis
import com.speedball.app.measurement.FrameProcessingBounds
import com.speedball.app.measurement.HsvColor
import com.speedball.app.measurement.HsvThreshold
import com.speedball.app.measurement.HsvTolerance
import com.speedball.app.measurement.MeasurementCalibrationState
import com.speedball.app.measurement.MinimumSpeedGatePolicy
import com.speedball.app.measurement.RegionOfInterest
import com.speedball.app.measurement.RecordedHfrMotionDetectorConfig
import com.speedball.app.measurement.RecordedHfrPhysicalDetectorConfig
import com.speedball.app.measurement.TrackExtractionConfig
import com.speedball.app.measurement.VisualEstimateCandidateFrame
import com.speedball.app.measurement.VisualEstimateCaptureProofBuilder
import com.speedball.app.measurement.VisualEstimateConfidence
import com.speedball.app.measurement.VisualEstimateFramePipelineConfig
import com.speedball.app.measurement.VisualEstimateNoReadReason
import com.speedball.app.measurement.VisualEstimateOutcome
import com.speedball.app.measurement.VisualEstimatePipelineConfig
import com.speedball.core.model.ImagePoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class RecordedHfrStreamingEstimateTest {
    @Test
    fun streamingScansAllFramesButRetainsOnlyCandidateProofAndBlobRecords() {
        val frames = listOf(
            blankFrame(0),
            blankFrame(1),
            blankFrame(2),
            ballFrame(index = 40, x = 2),
            ballFrame(index = 43, x = 11),
            ballFrame(index = 46, x = 20),
            ballFrame(index = 49, x = 29),
        )

        val result = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(frames),
            config = streamingConfig(maxProofFrames = 3),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, result.outcome, result.outcome.toString())
        assertEquals(7, result.scannedFrameCount)
        assertEquals(4, result.retainedCandidateFrameCount)
        assertEquals(4, result.candidateBlobCount)
        assertEquals(4, result.selectedSampleCount)
        assertEquals("PASS", result.sourceValidity.verdict)
        assertTrue(result.sourceValidity.passes)
        assertEquals(3, result.proofThumbnails.size)
        assertTrue(result.proofThumbnails.all { it.thumbnailWidth <= 16 && it.thumbnailHeight <= 9 })
        assertEquals(listOf(3, 3, 3), result.timing?.frameStepMultipliers)
        assertEquals(EstimateTimingBasis.RECORDED_CAPTURE_FRAME_INTERVAL, success.diagnostics.timingBasis)
        assertEquals(VisualEstimateConfidence.LOW, success.diagnostics.confidence)
        assertFalse(
            VisualEstimateCandidateFrame::class.java.declaredFields.any { it.type == IntArray::class.java },
            "Retained candidate frame records must not expose full-frame ARGB pixels.",
        )
    }

    @Test
    fun retainedOriginalIndexesChangeSpeedInsteadOfCompactRenumbering() {
        val nonConsecutive = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(
                listOf(
                    ballFrame(index = 40, x = 2),
                    ballFrame(index = 43, x = 11),
                    ballFrame(index = 46, x = 20),
                    ballFrame(index = 49, x = 29),
                ),
            ),
            config = streamingConfig(),
        )
        val consecutive = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(
                listOf(
                    ballFrame(index = 40, x = 2),
                    ballFrame(index = 41, x = 11),
                    ballFrame(index = 42, x = 20),
                    ballFrame(index = 43, x = 29),
                ),
            ),
            config = streamingConfig(),
        )

        val nonConsecutiveSuccess = assertInstanceOf(VisualEstimateOutcome.Success::class.java, nonConsecutive.outcome)
        val consecutiveSuccess = assertInstanceOf(VisualEstimateOutcome.Success::class.java, consecutive.outcome)
        assertEquals(listOf(3, 3, 3), nonConsecutive.timing?.frameStepMultipliers)
        assertEquals(listOf(1, 1, 1), consecutive.timing?.frameStepMultipliers)
        assertEquals(27.2727, nonConsecutiveSuccess.milesPerHour, 0.01)
        assertEquals(81.8181, consecutiveSuccess.milesPerHour, 0.01)
    }

    @Test
    fun containerPtsDeltaModeUsesSparseWindowTimingInsteadOfConsecutiveIndexes() {
        val indexMode = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(
                listOf(
                    ballFrame(index = 0, x = 2, ptsNanos = 1_000_000_000L),
                    ballFrame(index = 1, x = 11, ptsNanos = 1_025_000_000L),
                    ballFrame(index = 2, x = 20, ptsNanos = 1_050_000_000L),
                    ballFrame(index = 3, x = 29, ptsNanos = 1_075_000_000L),
                ),
            ),
            config = streamingConfig(timingMode = RecordedHfrStreamingTimingMode.FRAME_INDEX_INTERVAL),
        )
        val ptsMode = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(
                listOf(
                    ballFrame(index = 0, x = 2, ptsNanos = 1_000_000_000L),
                    ballFrame(index = 1, x = 11, ptsNanos = 1_025_000_000L),
                    ballFrame(index = 2, x = 20, ptsNanos = 1_050_000_000L),
                    ballFrame(index = 3, x = 29, ptsNanos = 1_075_000_000L),
                ),
            ),
            config = streamingConfig(timingMode = RecordedHfrStreamingTimingMode.CONTAINER_PTS_DELTAS),
        )

        val indexSuccess = assertInstanceOf(VisualEstimateOutcome.Success::class.java, indexMode.outcome)
        val ptsSuccess = assertInstanceOf(VisualEstimateOutcome.Success::class.java, ptsMode.outcome)
        assertEquals(ImportTimingBasis.RECORDED_CONTAINER_PRESENTATION_TIMESTAMPS, ptsMode.timing?.basis)
        assertEquals(0.025, ptsMode.timing?.timestampGapSummary?.medianGapSeconds ?: 0.0, 1.0e-12)
        assertEquals(81.8181, indexSuccess.milesPerHour, 0.01)
        assertEquals(27.2727, ptsSuccess.milesPerHour, 0.01)
        assertEquals(EstimateTimingBasis.RECORDED_CONTAINER_PRESENTATION_TIMESTAMPS, ptsSuccess.diagnostics.timingBasis)
    }

    @Test
    fun recordedTimingProvenancePreservesDetectorConfidenceAfterValidTiming() {
        val result = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(
                List(8) { index ->
                    ballFrame(
                        index = index,
                        x = 2 + index * 3,
                        ptsNanos = 1_000_000_000L + index * 8_333_333L,
                    )
                },
            ),
            config = streamingConfig(timingMode = RecordedHfrStreamingTimingMode.CONTAINER_PTS_DELTAS),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, result.outcome, result.outcome.toString())
        assertEquals(8, result.selectedSampleCount)
        assertEquals(EstimateTimingBasis.RECORDED_CONTAINER_PRESENTATION_TIMESTAMPS, success.diagnostics.timingBasis)
        assertEquals(VisualEstimateConfidence.HIGH, success.diagnostics.confidence)
    }

    @Test
    fun containerPtsDeltaModeFailsLoudWhenWindowFrameLacksPts() {
        val result = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(
                listOf(
                    ballFrame(index = 0, x = 2, ptsNanos = 1_000_000_000L),
                    ballFrame(index = 1, x = 11, ptsNanos = null),
                    ballFrame(index = 2, x = 20, ptsNanos = 1_050_000_000L),
                    ballFrame(index = 3, x = 29, ptsNanos = 1_075_000_000L),
                ),
            ),
            config = streamingConfig(timingMode = RecordedHfrStreamingTimingMode.CONTAINER_PTS_DELTAS),
        )

        val noRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, result.outcome)
        assertEquals(VisualEstimateNoReadReason.BAD_TIMESTAMPS, noRead.reason)
        assertTrue(noRead.message.contains("container PTS"))
    }

    @Test
    fun recordedHfrScaleGoldenKeepsMphInvariantAndRejectsHalvingAt640x360() {
        val full = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(
                scaleGoldenFrames(
                    width = 1280,
                    height = 720,
                    y = 360,
                    positions = listOf(100, 130, 160, 190),
                    blobRadius = 3,
                ),
            ),
            config = scaleGoldenConfig(width = 1280, height = 720, pixelsPerFoot = 30.0, maxJumpPx = 90.0),
        )
        val working = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(
                scaleGoldenFrames(
                    width = 640,
                    height = 360,
                    y = 180,
                    positions = listOf(50, 65, 80, 95),
                    blobRadius = 2,
                ),
            ),
            config = scaleGoldenConfig(width = 640, height = 360, pixelsPerFoot = 15.0, maxJumpPx = 45.0),
        )

        val mph720 = assertInstanceOf(VisualEstimateOutcome.Success::class.java, full.outcome, full.outcome.toString()).milesPerHour
        val mph640 = assertInstanceOf(VisualEstimateOutcome.Success::class.java, working.outcome, working.outcome.toString()).milesPerHour
        val relativeError = abs(mph640 - mph720) / mph720
        val halvedRelativeError = abs(mph640 - mph720 * 0.5) / mph720

        assertTrue(relativeError <= 0.05, "640x360 mph=$mph640 720p mph=$mph720")
        assertFalse(halvedRelativeError <= 0.05, "640x360 mph must not match a half-speed scale bug")
    }


    @Test
    fun oneToThreeCandidateFramesNoReadWithCountsAndNoSpeed() {
        val result = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(
                listOf(
                    blankFrame(0),
                    ballFrame(index = 1, x = 2),
                    ballFrame(index = 2, x = 11),
                    ballFrame(index = 3, x = 20),
                ),
            ),
            config = streamingConfig(),
        )

        val noRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, result.outcome)
        assertEquals(VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS, noRead.reason)
        assertEquals(4, result.scannedFrameCount)
        assertEquals(3, result.retainedCandidateFrameCount)
        assertEquals(3, noRead.diagnostics?.candidateFrameCount)
        assertEquals(0, noRead.diagnostics?.selectedSampleCount)
        assertFalse(noRead.toString().contains("mph", ignoreCase = true))
    }

    @Test
    fun allBlackWindowFailsAsSourceInvalidWithProofBeforeInsufficientDetections() {
        val result = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(List(8) { index -> blankFrame(index, ptsNanos = index * 8_333_333L) }),
            config = streamingConfig(timingMode = RecordedHfrStreamingTimingMode.CONTAINER_PTS_DELTAS),
        )

        val noRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, result.outcome)
        assertEquals(VisualEstimateNoReadReason.DETECTION_FAILED, noRead.reason)
        assertTrue(noRead.message.contains("black", ignoreCase = true))
        assertEquals("NO_READ_BLACK_OR_INVALID_SOURCE", result.sourceValidity.verdict)
        assertFalse(result.sourceValidity.passes)
        assertTrue(result.retainedProofFrameCount > 0)
        assertTrue(result.proofThumbnails.isNotEmpty())
        assertEquals(0, result.retainedCandidateFrameCount)
        assertEquals(0, result.candidateBlobCount)
    }

    @Test
    fun scanBoundAndCancellationFailLoudAndCloseSource() {
        val boundedSource = FakeFrameSource(List(10) { blankFrame(it) })
        val bounded = RecordedHfrStreamingEstimate.estimate(
            source = boundedSource,
            config = streamingConfig(maxScannedFrames = 3),
        )
        val cancelledSource = FakeFrameSource(List(10) { blankFrame(it) })
        val cancelled = RecordedHfrStreamingEstimate.estimate(
            source = cancelledSource,
            config = streamingConfig(),
            cancellationSignal = ImportCancellationSignal { true },
        )

        assertEquals(VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED, assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, bounded.outcome).reason)
        assertEquals(3, bounded.scannedFrameCount)
        assertTrue(boundedSource.closed)
        assertEquals(VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED, assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, cancelled.outcome).reason)
        assertEquals(0, cancelled.scannedFrameCount)
        assertTrue(cancelledSource.closed)
    }

    @Test
    fun exactRecordedWindowFrameLimitCompletesDetectorInsteadOfResourceNoRead() {
        val windowSource = assertInstanceOf(
            RecordedHfrWindowFrameSource::class.java,
            assertInstanceOf(
                ImportValidationResult.Success::class.java,
                RecordedHfrWindowFrameSource.create(
                    upstream = FakeFrameSource(List(3) { index -> blankFrame(index, ptsNanos = index * 8_333_333L) }),
                    window = ContainerTimeWindow(
                        windowStartUs = 0,
                        windowEndUs = 30_000,
                        postImpactFrameCount = 3,
                        preImpactMarginFrames = 0,
                        maxFrames = 3,
                    ),
                ),
            ).value,
        )

        val result = RecordedHfrStreamingEstimate.estimate(
            source = windowSource,
            config = streamingConfig(
                maxScannedFrames = 3,
                timingMode = RecordedHfrStreamingTimingMode.CONTAINER_PTS_DELTAS,
            ),
        )

        val noRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, result.outcome)
        assertEquals(VisualEstimateNoReadReason.DETECTION_FAILED, noRead.reason)
        assertTrue(noRead.message.contains("black", ignoreCase = true), noRead.message)
        assertEquals(3, result.scannedFrameCount)
        assertTrue(result.proofThumbnails.isNotEmpty())
    }

    @Test
    fun retainedCandidateCapReturnsResourceNoReadWithoutCatchingOom() {
        val result = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(List(8) { index -> ballFrame(index = index, x = 2 + index) }),
            config = streamingConfig(maxRetainedCandidateFrames = 4),
        )

        val noRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, result.outcome)
        assertEquals(VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED, noRead.reason)
        assertEquals(4, result.retainedCandidateFrameCount)
        assertTrue(result.proofThumbnails.size <= 4)
    }

    @Test
    fun recordedBudgetCapsRunawayCandidateBlobsButAllowsDirectionalSelectionHeadroom() {
        val result = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(
                List(4) { frameIndex ->
                    multiBlobFrame(index = frameIndex, blobCount = 50)
                },
            ),
            config = streamingConfig(
                candidateReductionBudget = CandidateReductionBudget(
                    maxBlobsPerFrame = 64,
                    maxTotalCandidateBlobs = 150,
                    maxRansacCandidates = 90,
                    maxRansacPairHypotheses = 4_096,
                    ransacCancellationCheckInterval = 128,
                ),
            ),
        )

        val noRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, result.outcome)
        assertEquals(VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED, noRead.reason)
        assertTrue(noRead.message.contains("window cap"), noRead.message)
        assertTrue(result.retainedProofFrameCount > 0)
        assertFalse(noRead.toString().contains("mph", ignoreCase = true))
    }

    @Test
    fun physicalDetectorOptInMergesFragmentsBeforeRecordedHfrCandidateBudget() {
        val result = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(
                List(7) { index ->
                    physicalFragmentFrame(index = index, x = 18 + index * 5)
                },
            ),
            config = physicalStreamingConfig(
                candidateReductionBudget = CandidateReductionBudget(
                    maxBlobsPerFrame = 1,
                    maxTotalCandidateBlobs = 8,
                    maxRansacCandidates = 8,
                    maxRansacPairHypotheses = 28,
                    ransacCancellationCheckInterval = 4,
                ),
            ),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, result.outcome, result.outcome.toString())
        assertEquals(7, result.scannedFrameCount)
        assertTrue(result.retainedCandidateFrameCount >= 4)
        assertEquals(result.retainedCandidateFrameCount, result.candidateBlobCount)
        assertTrue(result.detectorTrace.frames.all { frame -> frame.candidates.all { it.physicalMetrics != null } })
        assertTrue(success.milesPerHour > 1.0)
    }

    @Test
    fun motionDetectorOptInFindsMovingObjectWithoutColorThreshold() {
        val result = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(motionCapsuleFrames(stepPx = 60)),
            config = motionStreamingConfig(pixelsPerFoot = 50.0, minEstimateMilesPerHour = null),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, result.outcome, result.outcome.toString())
        assertEquals(7, result.scannedFrameCount)
        assertEquals(7, result.retainedCandidateFrameCount)
        assertEquals(7, result.candidateBlobCount)
        assertTrue(result.detectorTrace.frames.all { frame -> frame.candidates.all { it.motionMetrics != null } })
        assertTrue(success.milesPerHour > 0.0, "motion mph=${success.milesPerHour}")
        val proof = VisualEstimateCaptureProofBuilder.buildFromThumbnails(
            attemptId = 1L,
            scannedFrameCount = result.scannedFrameCount,
            frameAvailableCallbackCount = result.scannedFrameCount,
            captureResultCallbackCount = result.scannedFrameCount,
            uniqueSensorTimestampCount = result.scannedFrameCount,
            readbackWidth = result.workingWidth,
            readbackHeight = result.workingHeight,
            trace = result.detectorTrace,
            thumbnails = result.proofThumbnails,
            sourceKind = "TEST_RECORDED_HFR",
            sourceWidth = result.sourceWidth,
            sourceHeight = result.sourceHeight,
            workingWidth = result.workingWidth,
            workingHeight = result.workingHeight,
            decodedFrameCount = result.scannedFrameCount,
            requestedFps = 120,
            dropGateVerdict = "PASS",
            cadenceGateVerdict = "PASS",
        )
        assertTrue(proof.frames.isNotEmpty())
        assertTrue(proof.frames.all { it.selected != null }, "proof thumbnails must show selected ball-track frames")
    }

    @Test
    fun motionScoutReducerFeedsOnlyPaddedHighTravelWindowToMotionDetector() {
        val result = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(motionScoutFrames()),
            config = motionStreamingConfig(
                pixelsPerFoot = 50.0,
                minEstimateMilesPerHour = null,
                maxScannedFrames = 64,
                motionScoutConfig = RecordedHfrMotionScoutConfig(
                    enabled = true,
                    scoutWidth = 80,
                    scoutHeight = 45,
                    lumaDifferenceThreshold = 7,
                    minComponentAreaPx = 3,
                    maxComponentAxisRatio = 4.0,
                    densePaddingFrames = 4,
                    minDenseFrameCount = 12,
                    minRunTravelPx = 8.0,
                ),
            ),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, result.outcome, result.outcome.toString())
        val selection = result.motionScoutSelection
        assertTrue(selection != null, "motion scout must select the moving ball interval")
        selection!!
        assertEquals(48, result.scannedFrameCount)
        assertTrue(selection.runStartIndex >= 26, "selection=$selection")
        assertTrue(selection.denseStartIndex > 16, "slow foreground run must be outside the detector input: $selection")
        assertTrue(selection.denseEndIndexInclusive < 47, "dense interval should not feed the whole source window: $selection")
        assertTrue(result.detectorTrace.frames.all { it.originalFrameIndex >= selection.denseStartIndex })
        assertTrue(result.retainedCandidateFrameCount <= 16, "candidate frames=${result.retainedCandidateFrameCount} selection=$selection")
        assertTrue(success.milesPerHour > 0.0)
    }

    @Test
    fun motionScoutRejectsWeakLateSpeckRunBeforeCroppingDetectorWindow() {
        val result = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(motionScoutFramesWithWeakLateSpecks()),
            config = motionStreamingConfig(
                pixelsPerFoot = 50.0,
                minEstimateMilesPerHour = null,
                maxScannedFrames = 64,
                motionScoutConfig = RecordedHfrMotionScoutConfig(
                    enabled = true,
                    scoutWidth = 80,
                    scoutHeight = 45,
                    lumaDifferenceThreshold = 7,
                    minComponentAreaPx = 3,
                    maxComponentAxisRatio = 4.0,
                    densePaddingFrames = 4,
                    minDenseFrameCount = 12,
                    minRunTravelPx = 8.0,
                    minRunMeanAreaPx = 8.0,
                ),
            ),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, result.outcome, result.outcome.toString())
        val selection = result.motionScoutSelection
        assertTrue(selection != null, "motion scout must select the stronger ball run")
        selection!!
        assertTrue(selection.runStartIndex in 8..16, "weak late specks must not define the crop: $selection")
        assertTrue(selection.denseEndIndexInclusive < 32, "detector window must stay around the real ball run: $selection")
        assertTrue(result.detectorTrace.frames.any { it.originalFrameIndex in 12..18 })
        assertTrue(success.milesPerHour > 0.0)
    }

    @Test
    fun optionalConfiguredMphFloorRunsAfterMotionShapeAndPathSelection() {
        val noFloor = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(motionCapsuleFrames(stepPx = 60)),
            config = motionStreamingConfig(pixelsPerFoot = 200.0, minEstimateMilesPerHour = null),
        )
        val configuredFloor = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(motionCapsuleFrames(stepPx = 60)),
            config = motionStreamingConfig(pixelsPerFoot = 200.0, minEstimateMilesPerHour = 35.0),
        )
        val fast = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(motionCapsuleFrames(stepPx = 60)),
            config = motionStreamingConfig(pixelsPerFoot = 50.0, minEstimateMilesPerHour = 35.0),
        )

        val noFloorSuccess = assertInstanceOf(VisualEstimateOutcome.Success::class.java, noFloor.outcome, noFloor.outcome.toString())
        val configuredNoRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, configuredFloor.outcome, configuredFloor.outcome.toString())
        val fastSuccess = assertInstanceOf(VisualEstimateOutcome.Success::class.java, fast.outcome, fast.outcome.toString())
        assertTrue(noFloorSuccess.milesPerHour < 35.0, "no-floor mph=${noFloorSuccess.milesPerHour}")
        assertEquals(VisualEstimateNoReadReason.AMBIGUOUS_TRACK, configuredNoRead.reason)
        assertTrue(configuredNoRead.message.contains("speed threshold"), configuredNoRead.message)
        assertTrue(configuredFloor.retainedCandidateFrameCount >= 4, "configured floor must run after shape/path candidate selection")
        assertTrue(fastSuccess.milesPerHour > 35.0, "fast mph=${fastSuccess.milesPerHour}")
    }

    @Test
    fun motionDetectorRejectsStationaryAndNonDirectionalForegroundBeforeMph() {
        val stationary = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(motionCapsuleFramesFromPositions(listOf(140, 140, 140, 140, 140, 140, 140))),
            config = motionStreamingConfig(pixelsPerFoot = 50.0, minEstimateMilesPerHour = null),
        )
        val sawTooth = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(motionCapsuleFramesFromPositions(listOf(40, 120, 200, 120, 40, 120, 200))),
            config = motionStreamingConfig(pixelsPerFoot = 50.0, minEstimateMilesPerHour = null),
        )

        val stationaryNoRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, stationary.outcome, stationary.outcome.toString())
        val sawToothNoRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, sawTooth.outcome, sawTooth.outcome.toString())
        assertEquals(VisualEstimateNoReadReason.NO_FOREGROUND_MOTION, stationaryNoRead.reason)
        assertEquals(VisualEstimateNoReadReason.AMBIGUOUS_TRACK, sawToothNoRead.reason)
        assertFalse(stationaryNoRead.toString().contains("mph", ignoreCase = true))
        assertFalse(sawToothNoRead.toString().contains("mph", ignoreCase = true))
    }

    @Test
    fun runModeRecordedHfrDoesNotHardCodeConfiguredMphFloor() {
        val noFloor = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(motionCapsuleFrames(stepPx = 60)),
            config = motionStreamingConfig(pixelsPerFoot = 200.0, minEstimateMilesPerHour = null),
        )

        val noFloorSuccess = assertInstanceOf(VisualEstimateOutcome.Success::class.java, noFloor.outcome, noFloor.outcome.toString())
        assertTrue(noFloorSuccess.milesPerHour < 40.0, "no-floor mph=${noFloorSuccess.milesPerHour}")
    }

    @Test
    fun motionAndPhysicalDetectorConfigConflictFailsLoudBeforeDecode() {
        val result = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(motionCapsuleFrames(stepPx = 60)),
            config = physicalStreamingConfig(
                candidateReductionBudget = CandidateReductionBudget(
                    maxBlobsPerFrame = 1,
                    maxTotalCandidateBlobs = 8,
                    maxRansacCandidates = 8,
                    maxRansacPairHypotheses = 28,
                    ransacCancellationCheckInterval = 4,
                ),
            ).copy(motionDetectorConfig = RecordedHfrMotionDetectorConfig()),
        )

        val noRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, result.outcome)
        assertEquals(VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED, noRead.reason)
        assertEquals(0, result.scannedFrameCount)
        assertTrue(noRead.message.contains("both physical and fixed-camera motion detectors"), noRead.message)
    }

    @Test
    fun proofOnlyWindowBuildsThumbnailsWithoutCandidateDetection() {
        val result = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(List(8) { index -> ballFrame(index = index, x = 2 + index) }),
            config = streamingConfig(proofOnly = true),
        )

        val noRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, result.outcome)
        assertEquals(VisualEstimateNoReadReason.EXCESSIVE_RESIDUAL, noRead.reason)
        assertEquals(8, result.scannedFrameCount)
        assertEquals(0, result.retainedCandidateFrameCount)
        assertEquals(0, result.candidateBlobCount)
        assertTrue(result.retainedProofFrameCount > 0)
        assertTrue(result.proofThumbnails.isNotEmpty())
    }

    private class FakeFrameSource(
        private val frames: List<ImportVideoFrame>,
    ) : ImportFrameSource {
        var closed: Boolean = false
            private set
        private var index: Int = 0

        override fun nextFrame(): ImportVideoFrame? =
            frames.getOrNull(index++)

        override fun close() {
            closed = true
        }
    }

    private fun streamingConfig(
        maxScannedFrames: Int = 120,
        maxRetainedCandidateFrames: Int = 40,
        maxProofFrames: Int = 8,
        timingMode: RecordedHfrStreamingTimingMode = RecordedHfrStreamingTimingMode.FRAME_INDEX_INTERVAL,
        candidateReductionBudget: CandidateReductionBudget? = null,
        proofOnly: Boolean = false,
    ): RecordedHfrStreamingEstimateConfig =
        RecordedHfrStreamingEstimateConfig(
            frameIntervalSeconds = 1.0 / 120.0,
            calibration = calibration(pixels = 9.0, feet = 1.0),
            framePipelineConfig = VisualEstimateFramePipelineConfig(
                trackConfig = TrackExtractionConfig(
                    detectorConfig = BlobDetectionConfig(
                        threshold = HsvThreshold(
                            center = HsvColor(120.0, 1.0, 1.0),
                            tolerance = HsvTolerance(10.0, 0.1, 0.1),
                        ),
                        roi = RegionOfInterest(0, 0, FRAME_WIDTH, FRAME_HEIGHT),
                        minAreaPx = 1,
                        maxAreaPx = 10,
                        bounds = FrameProcessingBounds(
                            maxWidth = FRAME_WIDTH,
                            maxHeight = FRAME_HEIGHT,
                            maxPixels = FRAME_WIDTH * FRAME_HEIGHT,
                            maxFrameCount = 120,
                            maxThresholdPixels = FRAME_WIDTH * FRAME_HEIGHT,
                            maxComponentsPerFrame = FRAME_WIDTH * FRAME_HEIGHT,
                            maxOperationsPerFrame = FRAME_WIDTH * FRAME_HEIGHT * 20,
                        ),
                    ),
                    maxFrameToFrameJumpPx = 100.0,
                    allowDirectionalCandidateSelection = true,
                    candidateReductionBudget = candidateReductionBudget,
                ),
                estimateConfig = VisualEstimatePipelineConfig(minEstimateMilesPerHour = 1.0),
            ),
            maxScannedFrames = maxScannedFrames,
            maxRetainedCandidateFrames = maxRetainedCandidateFrames,
            maxProofFrames = maxProofFrames,
            proofThumbnailMaxWidth = 16,
            proofThumbnailMaxHeight = 9,
            timingMode = timingMode,
            proofOnly = proofOnly,
        )

    private fun scaleGoldenConfig(
        width: Int,
        height: Int,
        pixelsPerFoot: Double,
        maxJumpPx: Double,
    ): RecordedHfrStreamingEstimateConfig =
        RecordedHfrStreamingEstimateConfig(
            frameIntervalSeconds = 1.0 / 120.0,
            calibration = calibration(pixels = pixelsPerFoot, feet = 1.0),
            framePipelineConfig = VisualEstimateFramePipelineConfig(
                trackConfig = TrackExtractionConfig(
                    detectorConfig = BlobDetectionConfig(
                        threshold = HsvThreshold(
                            center = HsvColor(120.0, 1.0, 1.0),
                            tolerance = HsvTolerance(10.0, 0.1, 0.1),
                        ),
                        roi = RegionOfInterest(0, 0, width, height),
                        minAreaPx = 1,
                        maxAreaPx = 128,
                        bounds = FrameProcessingBounds(
                            maxWidth = width,
                            maxHeight = height,
                            maxPixels = width * height,
                            maxFrameCount = 24,
                            maxThresholdPixels = width * height,
                            maxComponentsPerFrame = width * height,
                            maxOperationsPerFrame = width * height * 20,
                        ),
                    ),
                    maxFrameToFrameJumpPx = maxJumpPx,
                    allowDirectionalCandidateSelection = true,
                ),
                estimateConfig = VisualEstimatePipelineConfig(minEstimateMilesPerHour = 1.0),
            ),
            maxScannedFrames = 24,
            maxRetainedCandidateFrames = 24,
            maxProofFrames = 4,
            proofThumbnailMaxWidth = 96,
            proofThumbnailMaxHeight = 54,
        )

    private fun physicalStreamingConfig(
        candidateReductionBudget: CandidateReductionBudget,
    ): RecordedHfrStreamingEstimateConfig =
        RecordedHfrStreamingEstimateConfig(
            frameIntervalSeconds = 1.0 / 120.0,
            calibration = calibration(pixels = 10.0, feet = 1.0),
            framePipelineConfig = VisualEstimateFramePipelineConfig(
                trackConfig = TrackExtractionConfig(
                    detectorConfig = BlobDetectionConfig(
                        threshold = HsvThreshold(
                            center = HsvColor(120.0, 1.0, 1.0),
                            tolerance = HsvTolerance(10.0, 0.1, 0.1),
                        ),
                        roi = RegionOfInterest(0, 0, PHYSICAL_WIDTH, PHYSICAL_HEIGHT),
                        minAreaPx = 1,
                        maxAreaPx = PHYSICAL_WIDTH * PHYSICAL_HEIGHT,
                        bounds = FrameProcessingBounds(
                            maxWidth = PHYSICAL_WIDTH,
                            maxHeight = PHYSICAL_HEIGHT,
                            maxPixels = PHYSICAL_WIDTH * PHYSICAL_HEIGHT,
                            maxFrameCount = 24,
                            maxThresholdPixels = PHYSICAL_WIDTH * PHYSICAL_HEIGHT,
                            maxComponentsPerFrame = PHYSICAL_WIDTH * PHYSICAL_HEIGHT,
                            maxOperationsPerFrame = PHYSICAL_WIDTH * PHYSICAL_HEIGHT * 80,
                        ),
                    ),
                    maxFrameToFrameJumpPx = 80.0,
                    allowDirectionalCandidateSelection = true,
                    candidateReductionBudget = candidateReductionBudget,
                ),
                estimateConfig = VisualEstimatePipelineConfig(minEstimateMilesPerHour = 1.0),
            ),
            maxScannedFrames = 24,
            maxRetainedCandidateFrames = 12,
            maxProofFrames = 6,
            proofThumbnailMaxWidth = 32,
            proofThumbnailMaxHeight = 18,
            physicalDetectorConfig = RecordedHfrPhysicalDetectorConfig(),
        )

    private fun motionStreamingConfig(
        pixelsPerFoot: Double,
        minEstimateMilesPerHour: Double?,
        maxScannedFrames: Int = 24,
        motionScoutConfig: RecordedHfrMotionScoutConfig = RecordedHfrMotionScoutConfig(),
    ): RecordedHfrStreamingEstimateConfig =
        RecordedHfrStreamingEstimateConfig(
            frameIntervalSeconds = 1.0 / 120.0,
            calibration = calibration(pixels = pixelsPerFoot, feet = 1.0),
            framePipelineConfig = VisualEstimateFramePipelineConfig(
                trackConfig = TrackExtractionConfig(
                    detectorConfig = BlobDetectionConfig(
                        threshold = HsvThreshold(
                            center = HsvColor(120.0, 1.0, 1.0),
                            tolerance = HsvTolerance(4.0, 0.05, 0.05),
                        ),
                        roi = RegionOfInterest(0, 0, MOTION_WIDTH, MOTION_HEIGHT),
                        minAreaPx = 1,
                        maxAreaPx = MOTION_WIDTH * MOTION_HEIGHT,
                        bounds = FrameProcessingBounds(
                            maxWidth = MOTION_WIDTH,
                            maxHeight = MOTION_HEIGHT,
                            maxPixels = MOTION_WIDTH * MOTION_HEIGHT,
                            maxFrameCount = maxScannedFrames,
                            maxThresholdPixels = MOTION_WIDTH * MOTION_HEIGHT,
                            maxComponentsPerFrame = MOTION_WIDTH * MOTION_HEIGHT,
                            maxOperationsPerFrame = MOTION_WIDTH * MOTION_HEIGHT * 80,
                        ),
                    ),
                    maxFrameToFrameJumpPx = 100.0,
                    allowDirectionalCandidateSelection = true,
                    candidateReductionBudget = CandidateReductionBudget(
                        maxBlobsPerFrame = 2,
                        maxTotalCandidateBlobs = 16,
                        maxRansacCandidates = 16,
                        maxRansacPairHypotheses = 256,
                        ransacCancellationCheckInterval = 8,
                    ),
                ),
                estimateConfig = VisualEstimatePipelineConfig(
                    minEstimateMilesPerHour = minEstimateMilesPerHour,
                    minimumSpeedGatePolicy = MinimumSpeedGatePolicy.SAME_PLANE_ONLY,
                ),
            ),
            maxScannedFrames = maxScannedFrames,
            maxRetainedCandidateFrames = 12,
            maxProofFrames = 6,
            proofThumbnailMaxWidth = 64,
            proofThumbnailMaxHeight = 36,
            motionDetectorConfig = RecordedHfrMotionDetectorConfig(
                openRadiusPx = 0,
                closeRadiusPx = 1,
            ),
            motionScoutConfig = motionScoutConfig,
        )

    private fun calibration(pixels: Double, feet: Double): MeasurementCalibrationState =
        MeasurementCalibrationState(
            pointA = ImagePoint(0.0, 0.0),
            pointB = ImagePoint(pixels, 0.0),
            knownDistanceFeet = feet,
        )

    private fun ballFrame(index: Int, x: Int, ptsNanos: Long? = null): ImportVideoFrame {
        val pixels = IntArray(FRAME_WIDTH * FRAME_HEIGHT) { BLACK }
        pixels[BALL_Y * FRAME_WIDTH + x] = GREEN
        return ImportVideoFrame(
            frameIndex = index,
            presentationTimestampNanos = ptsNanos,
            width = FRAME_WIDTH,
            height = FRAME_HEIGHT,
            argbPixels = pixels,
        )
    }

    private fun multiBlobFrame(index: Int, blobCount: Int): ImportVideoFrame {
        val pixels = IntArray(FRAME_WIDTH * FRAME_HEIGHT) { BLACK }
        repeat(blobCount) { order ->
            val x = (order * 2) % FRAME_WIDTH
            val y = order % FRAME_HEIGHT
            pixels[y * FRAME_WIDTH + x] = GREEN
        }
        return ImportVideoFrame(
            frameIndex = index,
            presentationTimestampNanos = index * 8_333_333L,
            width = FRAME_WIDTH,
            height = FRAME_HEIGHT,
            argbPixels = pixels,
        )
    }

    private fun scaleGoldenFrames(
        width: Int,
        height: Int,
        y: Int,
        positions: List<Int>,
        blobRadius: Int,
    ): List<ImportVideoFrame> =
        positions.mapIndexed { index, x ->
            val pixels = IntArray(width * height) { DARK_GRAY }
            for (dy in -blobRadius..blobRadius) {
                for (dx in -blobRadius..blobRadius) {
                    val px = x + dx
                    val py = y + dy
                    if (px in 0 until width && py in 0 until height) {
                        pixels[py * width + px] = GREEN
                    }
                }
            }
            ImportVideoFrame(
                frameIndex = index,
                presentationTimestampNanos = null,
                width = width,
                height = height,
                argbPixels = pixels,
            )
        }

    private fun blankFrame(index: Int, ptsNanos: Long? = null): ImportVideoFrame =
        ImportVideoFrame(
            frameIndex = index,
            presentationTimestampNanos = ptsNanos,
            width = FRAME_WIDTH,
            height = FRAME_HEIGHT,
            argbPixels = IntArray(FRAME_WIDTH * FRAME_HEIGHT) { BLACK },
        )

    private fun physicalFragmentFrame(index: Int, x: Int): ImportVideoFrame {
        val pixels = IntArray(PHYSICAL_WIDTH * PHYSICAL_HEIGHT) { BLACK }
        fun set(px: Int, py: Int) {
            if (px in 0 until PHYSICAL_WIDTH && py in 0 until PHYSICAL_HEIGHT) {
                pixels[py * PHYSICAL_WIDTH + px] = GREEN
            }
        }
        fun disk(cx: Int, cy: Int, radius: Int) {
            for (py in cy - radius..cy + radius) {
                for (px in cx - radius..cx + radius) {
                    val dx = px - cx
                    val dy = py - cy
                    if (dx * dx + dy * dy <= radius * radius) set(px, py)
                }
            }
        }
        for (py in 12..38) {
            for (px in 0..20) set(px, py)
        }
        disk(x, 24, 4)
        disk(x + 6, 24, 4)
        return ImportVideoFrame(
            frameIndex = index,
            presentationTimestampNanos = index * 8_333_333L,
            width = PHYSICAL_WIDTH,
            height = PHYSICAL_HEIGHT,
            argbPixels = pixels,
        )
    }

    private fun motionCapsuleFrames(stepPx: Int): List<ImportVideoFrame> =
        motionCapsuleFramesFromPositions(List(7) { index -> 40 + index * stepPx })

    private fun motionCapsuleFramesFromPositions(leftPositions: List<Int>): List<ImportVideoFrame> =
        leftPositions.mapIndexed { index, left ->
            val pixels = IntArray(MOTION_WIDTH * MOTION_HEIGHT) { DARK_GRAY }
            val top = 160
            for (py in top until top + 40) {
                for (px in left until left + 40) {
                    if (px in 0 until MOTION_WIDTH && py in 0 until MOTION_HEIGHT) {
                        pixels[py * MOTION_WIDTH + px] = WHITE
                    }
                }
            }
            ImportVideoFrame(
                frameIndex = index,
                presentationTimestampNanos = index * 8_333_333L,
                width = MOTION_WIDTH,
                height = MOTION_HEIGHT,
                argbPixels = pixels,
            )
        }

    private fun motionScoutFrames(): List<ImportVideoFrame> =
        List(48) { index ->
            val pixels = IntArray(MOTION_WIDTH * MOTION_HEIGHT) { DARK_GRAY }
            if (index in 5..20) {
                drawRect(pixels, MOTION_WIDTH, left = 80 + (index - 5), top = 42, widthPx = 32, heightPx = 32, color = WHITE)
            }
            if (index in 28..38) {
                drawRect(pixels, MOTION_WIDTH, left = 40 + (index - 28) * 45, top = 160, widthPx = 40, heightPx = 40, color = WHITE)
            }
            ImportVideoFrame(
                frameIndex = index,
                presentationTimestampNanos = index * 8_333_333L,
                width = MOTION_WIDTH,
                height = MOTION_HEIGHT,
                argbPixels = pixels,
            )
        }

    private fun motionScoutFramesWithWeakLateSpecks(): List<ImportVideoFrame> =
        List(48) { index ->
            val pixels = IntArray(MOTION_WIDTH * MOTION_HEIGHT) { DARK_GRAY }
            if (index in 10..18) {
                drawRect(pixels, MOTION_WIDTH, left = 40 + (index - 10) * 45, top = 160, widthPx = 40, heightPx = 40, color = WHITE)
            }
            if (index in 28..42) {
                drawRect(pixels, MOTION_WIDTH, left = 10 + (index - 28) * 12, top = 30, widthPx = 6, heightPx = 6, color = WHITE)
            }
            ImportVideoFrame(
                frameIndex = index,
                presentationTimestampNanos = index * 8_333_333L,
                width = MOTION_WIDTH,
                height = MOTION_HEIGHT,
                argbPixels = pixels,
            )
        }

    private fun drawRect(
        pixels: IntArray,
        frameWidth: Int,
        left: Int,
        top: Int,
        widthPx: Int = 0,
        heightPx: Int = 0,
        color: Int,
    ) {
        val rectWidth = if (widthPx == 0) 1 else widthPx
        val rectHeight = if (heightPx == 0) 1 else heightPx
        for (py in top until top + rectHeight) {
            for (px in left until left + rectWidth) {
                if (px in 0 until MOTION_WIDTH && py in 0 until MOTION_HEIGHT) {
                    pixels[py * frameWidth + px] = color
                }
            }
        }
    }

    private companion object {
        const val FRAME_WIDTH = 40
        const val FRAME_HEIGHT = 9
        const val PHYSICAL_WIDTH = 96
        const val PHYSICAL_HEIGHT = 48
        const val MOTION_WIDTH = 640
        const val MOTION_HEIGHT = 360
        const val BALL_Y = 4
        const val BLACK = 0xff000000.toInt()
        const val DARK_GRAY = 0xff202020.toInt()
        const val GREEN = 0xff00ff00.toInt()
        const val WHITE = 0xffffffff.toInt()
    }
}
