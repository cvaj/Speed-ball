package com.speedball.app.measurement

import com.speedball.app.capture.DirectFrameProof
import com.speedball.app.capture.DirectProofRunId
import com.speedball.app.capture.DirectProofSessionShape
import com.speedball.app.capture.DirectProofTokenEligibility
import com.speedball.app.capture.DirectSequenceContentIdentity
import com.speedball.app.capture.DirectTimingSourceDiagnostics
import com.speedball.app.capture.DirectTimingSourceProofOutcome
import com.speedball.app.capture.MIN_DIRECT_PROOF_TOKEN_FRAMES
import com.speedball.app.capture.buildDirectPixelProofSignature
import com.speedball.app.capture.buildDirectSequenceContentIdentity
import com.speedball.core.measurement.MeasurementOptions
import com.speedball.core.model.ImagePoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.math.roundToLong

class DirectSourceMeasurementTimingProofTest {
    @Test
    fun factoryCreatesBoundInputFromDirectProofSuccess() {
        val proof = proofSuccess(runId = DirectProofRunId("run-a"))
        val input = DirectSourceMeasurementInput.fromProof(proof, TimedFrameSequence(goodFrames()))

        assertNotNull(input)
        assertEquals("direct-test", input!!.timingProof.evidenceLabel)
        assertEquals(proof.tokenEligibility.runId, input.timingProof.runId)
        assertEquals(proof.tokenEligibility.sequenceIdentity, input.timingProof.sequenceIdentity)
    }

    @Test
    fun boundDirectInputAllowsMeasurement() {
        val proof = proofSuccess(runId = DirectProofRunId("run-b"))
        val input = DirectSourceMeasurementInput.fromProof(proof, TimedFrameSequence(goodFrames()))!!

        val outcome = MeasurementPipeline.measureWithDirectProof(
            input = input,
            calibration = validCalibration(),
            config = pipelineConfig(),
        )

        assertInstanceOf(MeasurementRunOutcome.Success::class.java, outcome)
    }

    @Test
    fun directTokenCannotBeUsedThroughGenericPipeline() {
        val proof = proofSuccess(runId = DirectProofRunId("run-c"))
        val input = DirectSourceMeasurementInput.fromProof(proof, TimedFrameSequence(goodFrames()))!!

        val outcome = MeasurementPipeline.measureWithProvenTiming(
            sequence = input.sequence,
            calibration = validCalibration(),
            timingProof = input.timingProof,
            config = pipelineConfig(),
        )
        val noRead = assertInstanceOf(MeasurementRunOutcome.NoRead::class.java, outcome)

        assertEquals(MeasurementRunFailure.UNPROVEN_TIMING, noRead.reason)
        assertFalse(noRead.toString().contains("mph", ignoreCase = true))
    }

    @Test
    fun forgedSuccessWithMismatchedIdentityCannotBindSequence() {
        val runId = DirectProofRunId("run-d")
        val frames = directFrames()
        val forgedIdentity = buildDirectSequenceContentIdentity(directFrames(salt = 500))
        val proof = proofSuccess(runId = runId, frames = frames, identity = forgedIdentity)

        assertNull(DirectSourceMeasurementInput.fromProof(proof, TimedFrameSequence(goodFrames())))
    }

    @Test
    fun genuineProofCannotBindDifferentMeasuredSequence() {
        val proof = proofSuccess(runId = DirectProofRunId("run-sequence-binding"))
        val unrelatedSequence = TimedFrameSequence(goodFrames().map { it.copy(timestampSeconds = it.timestampSeconds + 0.001) })

        assertNull(DirectSourceMeasurementInput.fromProof(proof, unrelatedSequence))
    }

    @Test
    fun insufficientDirectFramesCannotBecomeProofSuccess() {
        val frames = directFrames().take(MIN_DIRECT_PROOF_TOKEN_FRAMES - 1)
        val identity = buildDirectSequenceContentIdentity(frames)
        val eligibility = DirectProofTokenEligibility.fromProvenSequence(
            runId = DirectProofRunId("run-e"),
            sequenceIdentity = identity,
            evidenceLabel = "direct-test",
        )

        assertThrows(IllegalArgumentException::class.java) {
            DirectTimingSourceProofOutcome.Success(
                frames = frames,
                diagnostics = diagnostics(DirectProofRunId("run-e"), frames, identity, eligibility),
                tokenEligibility = eligibility,
            )
        }
    }

    @Test
    fun directTokenBoundaryHasNoCallerSettableMainSourceEscapeHatches() {
        val sourceRoot = listOf(Path.of("app/src/main/java"), Path.of("src/main/java")).first { Files.exists(it) }
        val proofSource = sourcePath("measurement/DirectSourceMeasurementTimingProof.kt")
        val captureProofSource = sourcePath("capture/DirectTimingSourceProof.kt")
        val runnerSource = sourcePath("capture/DirectTimingSourceProofRunner.kt")
        val captureSource = sourcePath("capture/DirectTimingSourceCapture.kt")
        val contractsSource = sourcePath("measurement/MeasurementContracts.kt")

        assertTrue(readText(proofSource).contains("class DirectSourceMeasurementTimingProof private constructor"))
        assertTrue(readText(proofSource).contains("class DirectSourceMeasurementInput private constructor"))
        assertFalse(readText(contractsSource).contains("sourceProofIdentity"))
        assertFalse(readText(contractsSource).contains("sourceProofRunId"))

        val paths = Files.walk(sourceRoot)
        val illegalLines = try {
            paths
                .filter { it.toString().endsWith(".kt") }
                .flatMap { path ->
                    Files.readAllLines(path).mapIndexed { index, line -> path to "${path}:${index + 1}:$line" }.stream()
                }
                .filter { (path, line) ->
                    val isAllowlisted =
                        path.normalize() == proofSource.normalize() ||
                            path.normalize() == captureProofSource.normalize() ||
                            path.normalize() == runnerSource.normalize() ||
                            path.normalize() == captureSource.normalize()
                    !isAllowlisted &&
                        (
                            line.contains("DirectProofTokenEligibility(") ||
                                line.contains("fromProvenSequence(") ||
                                line.contains("fromEligibility(") ||
                                line.contains("fromProof(") ||
                                line.contains("fromVerifiedDirectProof(") ||
                                line.contains("sourceProofIdentity =") ||
                                line.contains("sourceProofRunId =") ||
                                line.contains("DirectSourceMeasurementInput(") ||
                                line.contains("DirectTimingSourceProofOutcome.Success(") ||
                                line.contains("DirectFrameProof(")
                            )
                }
                .map { it.second }
                .toList()
        } finally {
            paths.close()
        }

        assertTrue(illegalLines.isEmpty(), "Direct proof escape hatches must stay allowlisted: $illegalLines")
    }

    private fun proofSuccess(
        runId: DirectProofRunId,
        frames: List<DirectFrameProof> = directFramesForSequence(goodFrames()),
        identity: DirectSequenceContentIdentity = buildDirectSequenceContentIdentity(frames),
    ): DirectTimingSourceProofOutcome.Success {
        val eligibility = DirectProofTokenEligibility.fromProvenSequence(
            runId = runId,
            sequenceIdentity = identity,
            evidenceLabel = "direct-test",
        )
        return DirectTimingSourceProofOutcome.Success(
            frames = frames,
            diagnostics = diagnostics(runId, frames, identity, eligibility),
            tokenEligibility = eligibility,
        )
    }

    private fun diagnostics(
        runId: DirectProofRunId,
        frames: List<DirectFrameProof>,
        identity: DirectSequenceContentIdentity,
        eligibility: DirectProofTokenEligibility,
    ): DirectTimingSourceDiagnostics =
        DirectTimingSourceDiagnostics(
            runId = runId,
            sessionShape = DirectProofSessionShape.COMPANION_ENCODER,
            requestedFps = 120,
            directTimestampCount = frames.size,
            sensorTimestampCount = frames.size,
            pixelProofCount = frames.size,
            sequenceIdentity = identity,
            tokenEligibility = eligibility,
        )

    private fun directFrames(salt: Int = 0): List<DirectFrameProof> =
        directFramesForSequence(goodFrames(extraPixel = salt != 0))

    private fun directFramesForSequence(
        frames: List<RgbFrame>,
    ): List<DirectFrameProof> =
        frames.mapIndexed { index, frame ->
            val timestamp = BASE_DIRECT_TIMESTAMP_NANOS + (frame.timestampSeconds * NANOS_PER_SECOND).roundToLong()
            directFrame(index = index, timestamp = timestamp, frame = frame)
        }

    private fun directFrame(index: Int, timestamp: Long, frame: RgbFrame): DirectFrameProof =
        DirectFrameProof(
            frameIndex = index,
            timestampNanos = timestamp,
            width = frame.width,
            height = frame.height,
            pixelSignature = buildDirectPixelProofSignature(
                timestampNanos = timestamp,
                frameWidth = frame.width,
                frameHeight = frame.height,
                tileLeft = 0,
                tileTop = 0,
                tileWidth = frame.width,
                tileHeight = frame.height,
                argbPixels = frame.argbPixels,
            ),
        )

    private fun goodFrames(extraPixel: Boolean = false): List<RgbFrame> =
        List(MIN_DIRECT_PROOF_TOKEN_FRAMES) { index ->
            val redPixels = buildSet {
                add((2 + index) to (15 - index))
                if (extraPixel) add(0 to 0)
            }
            frameWithRedPixels(
                width = PROOF_FRAME_WIDTH,
                height = PROOF_FRAME_HEIGHT,
                redPixels = redPixels,
                timestampSeconds = index * DIRECT_FRAME_INTERVAL_SECONDS,
            )
        }

    private fun validCalibration(): MeasurementCalibrationState =
        MeasurementCalibrationState(
            pointA = ImagePoint(0.0, 0.0),
            pointB = ImagePoint(10.0, 0.0),
            knownDistanceFeet = 10.0,
        )

    private fun pipelineConfig(): MeasurementPipelineConfig =
        MeasurementPipelineConfig(
            trackConfig = TrackExtractionConfig(
                detectorConfig = defaultConfig(PROOF_FRAME_WIDTH, PROOF_FRAME_HEIGHT),
                maxFrameToFrameJumpPx = 10.0,
            ),
            measurementOptions = MeasurementOptions(
                maxRmsResidualPx = 2.0,
                minTimeSpreadSecondsSquared = 1.0e-6,
            ),
        )

    private fun sourcePath(relativeFileName: String): Path {
        val appPath = Path.of("app/src/main/java/com/speedball/app/$relativeFileName")
        return if (appPath.exists()) {
            appPath
        } else {
            Path.of("src/main/java/com/speedball/app/$relativeFileName")
        }
    }

    private fun readText(path: Path): String =
        Files.readAllBytes(path).toString(Charsets.UTF_8)

    private companion object {
        private const val BASE_DIRECT_TIMESTAMP_NANOS = 1_000_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val DIRECT_FRAME_INTERVAL_SECONDS = 8_333_333.0 / NANOS_PER_SECOND
        private const val PROOF_FRAME_WIDTH = 20
        private const val PROOF_FRAME_HEIGHT = 20
    }
}
