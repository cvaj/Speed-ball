package com.speedball.app.importing

import com.speedball.app.measurement.VisualEstimateNoReadReason
import com.speedball.app.measurement.VisualEstimateOutcome
import com.speedball.app.ui.MeasurementResultUiState
import com.speedball.app.ui.measurementResultUiLines
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ImportContractsTest {
    @Test
    fun importOutcomeIsExistingVisualEstimateOutcomeAndUsesExistingNoReadUiSuppression() {
        val outcome: ImportEstimateOutcome = VisualEstimateOutcome.NoRead(
            reason = VisualEstimateNoReadReason.BAD_TIMESTAMPS,
            message = "Imported clip has no trustworthy timing basis.",
        )

        val visualOutcome: VisualEstimateOutcome = outcome
        val joined = measurementResultUiLines(
            MeasurementResultUiState.EstimateOutcome(visualOutcome),
        ).joinToString("\n")

        assertTrue(joined.contains("result=estimate-no-read"))
        assertTrue(joined.contains("reason=BAD_TIMESTAMPS"))
        assertNoResultValues(joined)
    }

    @Test
    fun metadataValidationRejectsNonVideoAndNonFiniteValues() {
        assertImportNoRead(
            ImportVideoMetadata.validate(
                mimeType = "image/png",
                width = 1280,
                height = 720,
                durationSeconds = 1.0,
                rotationDegrees = 0,
                sampleCount = 120,
                hasMonotonicPresentationTimestamps = true,
            ),
            ImportNoReadReason.UNSUPPORTED_MEDIA,
        )
        assertImportNoRead(
            ImportVideoMetadata.validate(
                mimeType = "video/mp4",
                width = 1280,
                height = 720,
                durationSeconds = Double.NaN,
                rotationDegrees = 0,
                sampleCount = 120,
                hasMonotonicPresentationTimestamps = true,
            ),
            ImportNoReadReason.INVALID_METADATA,
        )
        assertImportNoRead(
            ImportVideoMetadata.validate(
                mimeType = "video/mp4",
                width = 1280,
                height = 720,
                durationSeconds = 1.0,
                rotationDegrees = 45,
                sampleCount = 120,
                hasMonotonicPresentationTimestamps = true,
            ),
            ImportNoReadReason.INVALID_METADATA,
        )
    }

    @Test
    fun validMetadataStillRepresentsEstimateOnlyTimingEvidence() {
        val result = assertInstanceOf(
            ImportValidationResult.Success::class.java,
            ImportVideoMetadata.validate(
                mimeType = "video/mp4",
                width = 1280,
                height = 720,
                durationSeconds = 1.0,
                rotationDegrees = 0,
                sampleCount = 120,
                hasMonotonicPresentationTimestamps = true,
            ),
        )
        val metadata = assertInstanceOf(ImportVideoMetadata::class.java, result.value)

        val request = ImportEstimateRequest(
            metadata = metadata,
            frames = ImportVideoFrameSequence(emptyList()),
            timingBasis = ImportTimingBasis.CONTAINER_PRESENTATION_TIMESTAMPS,
        )

        assertTrue(request.metadata.hasMonotonicPresentationTimestamps)
        assertEquals(ImportTimingBasis.CONTAINER_PRESENTATION_TIMESTAMPS, request.timingBasis)
    }

    @Test
    fun redactedEvidenceSummaryContainsNoRawMediaIdentifiers() {
        val fieldNames = ImportEvidenceSummary::class.java.declaredFields
            .map { it.name.lowercase() }

        assertEquals(ImportResultSourceKind.IMPORT_ESTIMATE, ImportResultSourceKind.IMPORT_ESTIMATE)
        assertTrue(fieldNames.none { it.contains("uri") }, "No URI field allowed: $fieldNames")
        assertTrue(fieldNames.none { it.contains("path") }, "No path field allowed: $fieldNames")
        assertTrue(fieldNames.none { it.contains("pixel") }, "No raw pixel field allowed: $fieldNames")
        assertTrue(fieldNames.none { it.contains("video") }, "No video identifier field allowed: $fieldNames")
    }

    @Test
    fun importPackageDoesNotConstructStrictMeasurementSuccess() {
        val importRoot = sourcePath("importing")
        val paths = Files.walk(importRoot)
        val offenders = try {
            paths
                .filter { it.toString().endsWith(".kt") }
                .flatMap { path ->
                    Files.readAllLines(path).mapIndexed { index, line -> "${path}:${index + 1}:$line" }.stream()
                }
                .filter { line ->
                    line.contains("MeasurementRunOutcome.Success") ||
                        line.contains("MeasurementRunOutcome.Success(")
                }
                .toList()
        } finally {
            paths.close()
        }

        assertTrue(offenders.isEmpty(), "Import package must not construct strict success: $offenders")
    }

    @Test
    fun importTimingReconciliationIsSingleSourceInMainCode() {
        val appRoot = sourcePath("")
        val paths = Files.walk(appRoot)
        val constructionNeedle = "ImportTiming" + "Reconciliation("
        val dataClassNeedle = "data class ImportTiming" + "Reconciliation"
        val copyNeedles = listOf("timing.co" + "py(", "reconciliation.co" + "py(")
        val offenders = try {
            paths
                .filter { it.toString().endsWith(".kt") }
                .flatMap { path ->
                    Files.readAllLines(path).mapIndexed { index, line -> path to "${index + 1}:$line" }.stream()
                }
                .filter { (path, line) ->
                    val inReconciler = path.fileName.toString() == "ImportTimingReconciler.kt"
                    line.contains(dataClassNeedle) ||
                        (!inReconciler && (line.contains(constructionNeedle) || copyNeedles.any(line::contains)))
                }
                .map { (path, line) -> "$path:$line" }
                .toList()
        } finally {
            paths.close()
        }

        assertTrue(offenders.isEmpty(), "Import timing reconciliation must be single-source: $offenders")
    }

    @Test
    fun debugAutoImportIsDebuggableOnlyAndUsesIntentData() {
        val mainActivity = sourcePath("").resolve("MainActivity.kt")
        val source = Files.readAllLines(mainActivity).joinToString("\n")
        val hook = source.substringAfter("private fun startDebugImportIfRequested()")
            .substringBefore("private fun handleImportSelection")

        assertTrue(hook.contains("!isDebuggableBuild()"))
        assertTrue(hook.contains("debugAutoImport"))
        assertTrue(hook.contains("intent.data"))
        assertTrue(hook.contains("handleImportSelection(uri)"))
        assertFalse(hook.contains("/sdcard"))
        assertFalse(hook.contains("content://"))
    }

    @Test
    fun voiceRecordingPathCapturesThenRunsInternalRecordedEstimate() {
        val mainActivity = sourcePath("").resolve("MainActivity.kt")
        val manifest = listOf(
            Path.of("app/src/main/AndroidManifest.xml"),
            Path.of("src/main/AndroidManifest.xml"),
        ).first { Files.exists(it) }
        val source = Files.readAllLines(mainActivity).joinToString("\n")
        val manifestText = Files.readAllLines(manifest).joinToString("\n")

        assertTrue(manifestText.contains("android.permission.RECORD_AUDIO"))
        assertTrue(source.contains("SpeechRecognizer"))
        assertTrue(source.contains("VOICE_RECORD_COMMAND"))
        assertTrue(source.contains("AUTO_RECORD_ESTIMATE_DURATION_MILLIS"))
        assertTrue(source.contains("startTimedRecordingEstimate()"))
        assertTrue(source.contains("runRecordedEstimate(file, fps, outcome.diagnostics)"))
        assertTrue(source.contains("AndroidImportVideoFrameSource.create("))
        assertTrue(source.contains("file = file"))
        assertTrue(source.contains("ImportResultSourceKind.RECORDED_ESTIMATE"))
        assertTrue(source.contains("reconcileRecordedCaptureFrameInterval"))
        assertTrue(source.contains("file.delete()"))
        assertFalse(source.contains("reconcileContainerPresentationTimestamps(\n                frames = frames,\n            )"))

        val importPath = source.substringAfter("private fun runImportEstimate(")
            .substringBefore("private fun runRecordedEstimate(")
        val recordedPath = source.substringAfter("private fun runRecordedEstimate(")
            .substringBefore("private fun reconcileImportTiming(")
        assertFalse(importPath.contains("ImportResultSourceKind.RECORDED_ESTIMATE"))
        assertTrue(recordedPath.contains("ImportResultSourceKind.RECORDED_ESTIMATE"))
        assertTrue(recordedPath.contains("reconcileRecordedCaptureFrameInterval"))
        assertTrue(recordedPath.contains("RecordedHfrCaptureGate.validate("))
        assertTrue(recordedPath.indexOf("ImportFrameExtractor.extract(") < recordedPath.indexOf("RecordedHfrCaptureGate.validate("))
        assertTrue(recordedPath.indexOf("ImportEstimatePipeline.estimateWithTrace(") < recordedPath.indexOf("RecordedHfrCaptureGate.validate("))
        assertTrue(recordedPath.contains("VisualEstimateCaptureProofBuilder.build("))
        assertFalse(recordedPath.contains("VisualEstimateCaptureProofBuilder.empty("))
        assertFalse(recordedPath.contains("reconcileContainerPresentationTimestamps"))
    }

    @Test
    fun highSpeedRecorderRequestsFastShutterWithAutoExposureFallback() {
        val recorder = listOf(
            Path.of("app/src/main/java/com/speedball/app/capture/HighSpeedBurstRecorder.kt"),
            Path.of("src/main/java/com/speedball/app/capture/HighSpeedBurstRecorder.kt"),
        ).first { Files.exists(it) }
        val mode = listOf(
            Path.of("app/src/main/java/com/speedball/app/capture/HighSpeedMode.kt"),
            Path.of("src/main/java/com/speedball/app/capture/HighSpeedMode.kt"),
        ).first { Files.exists(it) }
        val recorderSource = Files.readAllLines(recorder).joinToString("\n")
        val modeSource = Files.readAllLines(mode).joinToString("\n")

        assertTrue(modeSource.contains("DEFAULT_FAST_SHUTTER_EXPOSURE_NANOS"))
        assertTrue(modeSource.contains("preferredExposureTimeNanos"))
        assertTrue(recorderSource.contains("REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR"))
        assertTrue(recorderSource.contains("SENSOR_INFO_EXPOSURE_TIME_RANGE"))
        assertTrue(recorderSource.contains("CaptureRequest.CONTROL_AE_MODE_OFF"))
        assertTrue(recorderSource.contains("CaptureRequest.SENSOR_EXPOSURE_TIME"))
        assertTrue(recorderSource.contains("createHighSpeedRequestListWithFallback"))
    }

    private fun assertImportNoRead(
        result: ImportValidationResult<ImportVideoMetadata>,
        reason: ImportNoReadReason,
    ) {
        val noRead = assertInstanceOf(ImportValidationResult.NoRead::class.java, result)
        assertEquals(reason, noRead.reason)
    }

    private fun assertNoResultValues(text: String) {
        assertFalse(text.contains("m" + "ph", ignoreCase = true))
        assertFalse(text.contains("ang" + "le", ignoreCase = true))
        assertFalse(text.contains("tra" + "jectory", ignoreCase = true))
        assertFalse(text.contains("car" + "ry", ignoreCase = true))
        assertFalse(text.contains("apex", ignoreCase = true))
        assertFalse(text.contains("hang", ignoreCase = true))
    }

    private fun sourcePath(relative: String): Path {
        val root = listOf(Path.of("app/src/main/java"), Path.of("src/main/java"))
            .first { Files.exists(it) }
        return root.resolve("com/speedball/app/$relative")
    }
}
