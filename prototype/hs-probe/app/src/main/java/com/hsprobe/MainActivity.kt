package com.hsprobe

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal de-risk probe: open the back camera via Camera2, create a CONSTRAINED_HIGH_SPEED
 * session at 1080p, and record a short clip at a requested fps (120 or 240).
 *
 * Drive it from adb, e.g.:
 *   am start -n com.hsprobe/.MainActivity --ei fps 240 --el durMs 2500
 *
 * All diagnostics log under tag HSPROBE; the clip lands in the app's external files dir.
 */
class MainActivity : Activity() {
    private val tag = "HSPROBE"
    private lateinit var surfaceView: SurfaceView
    private var cameraDevice: CameraDevice? = null
    private var session: CameraConstrainedHighSpeedCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler
    private var outputPath: String = ""
    private var targetFps = 240
    private var durMs = 2500L
    private var reqW = 1920
    private var reqH = 1080
    private var slowmo = false
    private var started = false
    private val frameCount = AtomicInteger(0)
    private var firstFrameNs = 0L
    private var lastFrameNs = 0L
    private val timestamps = ArrayList<Long>(2048)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetFps = intent.getIntExtra("fps", 240)
        durMs = intent.getLongExtra("durMs", 2500L)
        reqW = intent.getIntExtra("w", 1920)
        reqH = intent.getIntExtra("h", 1080)
        slowmo = intent.getIntExtra("slowmo", 0) == 1
        Log.i(tag, "onCreate requested fps=$targetFps durMs=$durMs size=${reqW}x${reqH}")

        bgThread = HandlerThread("cam").also { it.start() }
        bgHandler = Handler(bgThread.looper)

        surfaceView = SurfaceView(this)
        setContentView(surfaceView)
        surfaceView.holder.setFixedSize(reqW, reqH)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (started) return
                started = true
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(tag, "CAMERA permission missing; requesting")
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
                } else {
                    bgHandler.post { openAndRecord(holder.surface) }
                }
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) {
            bgHandler.post { openAndRecord(surfaceView.holder.surface) }
        } else {
            Log.e(tag, "permission denied")
        }
    }

    private fun backCameraId(cm: CameraManager): String {
        for (id in cm.cameraIdList) {
            val c = cm.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return cm.cameraIdList[0]
    }

    @Suppress("MissingPermission")
    private fun openAndRecord(previewSurface: Surface) {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = backCameraId(cm)
        val chars = cm.getCameraCharacteristics(camId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) as StreamConfigurationMap

        val hsSizes = map.highSpeedVideoSizes
        Log.i(tag, "camId=$camId highSpeedVideoSizes=${hsSizes.joinToString { "${it.width}x${it.height}" }}")
        for (s in hsSizes) {
            val ranges = map.getHighSpeedVideoFpsRangesFor(s)
            Log.i(tag, "  ${s.width}x${s.height} -> ${ranges.joinToString()}")
        }

        val size = hsSizes.firstOrNull { it.width == reqW && it.height == reqH }
            ?: hsSizes.maxByOrNull { it.width * it.height }
            ?: Size(1280, 720)
        val ranges = map.getHighSpeedVideoFpsRangesFor(size)
        val avail = ranges.any { it.upper >= targetFps }
        Log.i(tag, "chosen size=${size.width}x${size.height} requestedFps=$targetFps available=$avail")

        val sm = if (slowmo) "_sm" else ""
        outputPath = File(getExternalFilesDir(null), "hs_${targetFps}_${size.width}x${size.height}$sm.mp4").absolutePath
        try {
            recorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(outputPath)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(size.width, size.height)
                // slowmo: tag playback at 30fps so the encoder can drain the high-speed
                // burst over a longer wall-clock window instead of real-time at targetFps.
                setVideoFrameRate(if (slowmo) 30 else targetFps)
                setCaptureRate(targetFps.toDouble())
                setVideoEncodingBitRate(40_000_000)
                prepare()
            }
        } catch (e: Exception) {
            Log.e(tag, "MediaRecorder prepare failed: $e")
            return
        }
        val recorderSurface = recorder!!.surface

        cm.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                val outputs = listOf(previewSurface, recorderSurface)
                try {
                    device.createConstrainedHighSpeedCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            val hs = s as CameraConstrainedHighSpeedCaptureSession
                            session = hs
                            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            req.addTarget(previewSurface)
                            req.addTarget(recorderSurface)
                            req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))
                            try {
                                val burst = hs.createHighSpeedRequestList(req.build())
                                val counter = object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
                                        if (firstFrameNs == 0L) firstFrameNs = ts
                                        lastFrameNs = ts
                                        synchronized(timestamps) { timestamps.add(ts) }
                                        frameCount.incrementAndGet()
                                    }
                                }
                                hs.setRepeatingBurst(burst, counter, bgHandler)
                                recorder!!.start()
                                Log.i(tag, "RECORDING fps=$targetFps -> $outputPath")
                                bgHandler.postDelayed({ stopAll() }, durMs)
                            } catch (e: Exception) {
                                Log.e(tag, "high-speed request failed: $e")
                                stopAll()
                            }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            Log.e(tag, "session configure FAILED for fps=$targetFps")
                        }
                    }, bgHandler)
                } catch (e: Exception) {
                    Log.e(tag, "createConstrainedHighSpeedCaptureSession threw: $e")
                }
            }
            override fun onDisconnected(device: CameraDevice) { device.close() }
            override fun onError(device: CameraDevice, error: Int) {
                Log.e(tag, "camera error $error"); device.close()
            }
        }, bgHandler)
    }

    private fun stopAll() {
        try { session?.stopRepeating() } catch (e: Exception) { Log.e(tag, "stopRepeating: $e") }
        try { recorder?.stop() } catch (e: Exception) { Log.e(tag, "recorder.stop: $e") }
        try { recorder?.reset(); recorder?.release() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        recorder = null
        val f = File(outputPath)
        val spanSec = (lastFrameNs - firstFrameNs) / 1e9
        val n = frameCount.get()
        val camFps = if (spanSec > 0) (n - 1) / spanSec else 0.0
        // Distinguish true sensor rate from any callback double-firing: count UNIQUE
        // timestamps and the median gap between consecutive unique frames.
        val sorted = synchronized(timestamps) { timestamps.toLongArray() }.also { it.sort() }
        val uniq = sorted.distinct()
        val deltasMs = uniq.zipWithNext { a, b -> (b - a) / 1e6 }.sorted()
        val medMs = if (deltasMs.isNotEmpty()) deltasMs[deltasMs.size / 2] else 0.0
        Log.i(tag, "DONE fps=$targetFps file=$outputPath exists=${f.exists()} bytes=${f.length()}")
        Log.i(tag, "CAMERA_DELIVERED callbacks=$n uniqueTs=${uniq.size} spanSec=${"%.3f".format(spanSec)} " +
                "effFps=${"%.1f".format(camFps)} uniqueFps=${"%.1f".format(if (spanSec>0) (uniq.size-1)/spanSec else 0.0)} " +
                "medianGapMs=${"%.2f".format(medMs)}")
    }
}
