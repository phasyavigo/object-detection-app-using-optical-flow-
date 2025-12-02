package com.example.optflow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.opencv.android.CameraBridgeViewBase
import org.opencv.android.JavaCameraView
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.util.*
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity(), CameraBridgeViewBase.CvCameraViewListener2 {

    companion object {
        private const val TAG = "OptFlow"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        // Tuned parameters from Python script
        private const val RESIZE_WIDTH = 480
        private const val RESIZE_HEIGHT = 360
        private const val THRESHOLD_HIGH = 2.0
        private const val THRESHOLD_LOW = 1.4
        private const val INTEGRATION_THRESHOLD = 15.0
        private const val LOOMING_THRESHOLD = 0.3
        private const val COOLDOWN_MS = 2000L
        private const val ALPHA_SMOOTH = 0.5
        private const val ALPHA_INTEGRATE = 0.88
        private const val MIN_MAGNITUDE = 0.5
        private const val DIRECTION_MARGIN = 0.4
    }

    private lateinit var cameraView: JavaCameraView
    private lateinit var overlayCanvas: ImageView
    private lateinit var statusText: TextView
    private lateinit var metricsText: TextView
    private lateinit var alertText: TextView

    private var prevGray: Mat? = null
    private var smoothMag = 0.0
    private var temporalIntegrator = 0.0
    private var detectedState = false
    private var detectionMode = "NONE"
    private var frameCount = 0
    private var lastAlertTime = 0L

    private val baselineSamples = LinkedList<Double>()
    private val directionHistory = LinkedList<String>()
    private var radialX: Mat? = null
    private var radialY: Mat? = null

    // Audio feedback (TTS)
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var lastSpeakTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraView = findViewById(R.id.cameraView)
        overlayCanvas = findViewById(R.id.overlayCanvas)
        statusText = findViewById(R.id.statusText)
        metricsText = findViewById(R.id.metricsText)
        alertText = findViewById(R.id.alertText)

        cameraView.visibility = View.VISIBLE
        cameraView.setCvCameraViewListener(this)

        try {
            cameraView.enableFpsMeter()
        } catch (e: Exception) {
            Log.d(TAG, "enableFpsMeter not available: ${e.message}")
        }

        if (allPermissionsGranted()) {
            initOpenCVAndStart()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        
        // Initialize Text-to-Speech for audio feedback
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts.setLanguage(Locale("id", "ID")) // Bahasa Indonesia
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "⚠️ Bahasa Indonesia tidak tersedia, menggunakan default")
                    tts.language = Locale.US
                } else {
                    Log.i(TAG, "✅ TTS initialized with Bahasa Indonesia")
                }
                ttsReady = true
            } else {
                Log.e(TAG, "❌ TTS initialization failed")
                ttsReady = false
            }
        }
    }

    private fun allPermissionsGranted() =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
        }

    @Suppress("DEPRECATION")
    private fun initOpenCVAndStart() {
        val ok = OpenCVLoader.initDebug()
        if (!ok) {
            Log.e(TAG, "OpenCV init failed")
        } else {
            Log.d(TAG, "OpenCV init OK")
            cameraView.setCameraPermissionGranted()
            cameraView.enableView()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                initOpenCVAndStart()
            } else {
                Log.e(TAG, "Camera permission denied")
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::cameraView.isInitialized) cameraView.disableView()
    }

    override fun onResume() {
        super.onResume()
        @Suppress("DEPRECATION")
        if (OpenCVLoader.initDebug()) {
            cameraView.enableView()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraView.isInitialized) cameraView.disableView()
        prevGray?.release()
        radialX?.release()
        radialY?.release()
        
        // Shutdown TTS
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }

    override fun onCameraViewStarted(width: Int, height: Int) {
        prevGray = null
        precomputeRadialGrid(Size(RESIZE_WIDTH.toDouble(), RESIZE_HEIGHT.toDouble()))
    }

    override fun onCameraViewStopped() {
        prevGray?.release()
        prevGray = null
    }

    override fun onCameraFrame(inputFrame: CameraBridgeViewBase.CvCameraViewFrame?): Mat {
        val rgba = inputFrame?.rgba() ?: return Mat()
        val gray = inputFrame.gray()

        val smallGray = Mat()
        val processingSize = Size(RESIZE_WIDTH.toDouble(), RESIZE_HEIGHT.toDouble())
        Imgproc.resize(gray, smallGray, processingSize)

        try {
            frameCount++
            if (prevGray == null) {
                prevGray = smallGray.clone()
                return rgba
            }

            val flow = Mat()
            Video.calcOpticalFlowFarneback(prevGray, smallGray, flow, 0.5, 3, 15, 3, 5, 1.2, 0)

            val flowSplit = ArrayList<Mat>()
            Core.split(flow, flowSplit)
            val u = flowSplit[0]
            val v = flowSplit[1]

            val mag = Mat()
            val ang = Mat() 
            Core.cartToPolar(u, v, mag, ang, false)
            Imgproc.GaussianBlur(mag, mag, Size(5.0, 5.0), 0.0)

            val avgMagnitude = Core.mean(mag).`val`[0]
            val loomingScore = calculateLoomingScoreFast(u, v)
            val (direction, leftMag, centerMag, rightMag) = calculateDirection(mag)
            
            smoothMag = ALPHA_SMOOTH * avgMagnitude + (1 - ALPHA_SMOOTH) * smoothMag

            if (!detectedState) {
                temporalIntegrator = ALPHA_INTEGRATE * temporalIntegrator + avgMagnitude
                baselineSamples.addLast(smoothMag)
                if (baselineSamples.size > 30) baselineSamples.removeFirst()
            } else {
                temporalIntegrator = max(0.0, temporalIntegrator * 0.95)
            }

            val baseline = if (baselineSamples.size >= 10) baselineSamples.sorted()[baselineSamples.size / 2] else null
            val thresholdHigh = baseline?.let { min(max(it * 1.4, THRESHOLD_HIGH * 0.6), THRESHOLD_HIGH * 1.5) } ?: THRESHOLD_HIGH
            val thresholdLow = baseline?.let { min(max(it * 1.0, THRESHOLD_LOW * 0.6), THRESHOLD_LOW * 1.5) } ?: THRESHOLD_LOW

            val prevState = detectedState
            val magDetected = smoothMag > thresholdHigh && smoothMag > MIN_MAGNITUDE
            val intDetected = temporalIntegrator > INTEGRATION_THRESHOLD
            val loomDetected = loomingScore > LOOMING_THRESHOLD

            if (magDetected || intDetected || loomDetected) {
                detectedState = true
                if (magDetected) {
                    detectionMode = "MAG"
                } else if (intDetected) {
                    detectionMode = "INT"
                } else {
                    detectionMode = "LOOM"
                }
            } else if (smoothMag < thresholdLow && temporalIntegrator < INTEGRATION_THRESHOLD * 0.5) {
                detectedState = false
                detectionMode = "NONE"
            }

            val stableDirection = if (detectedState) {
                directionHistory.addLast(direction)
                if (directionHistory.size > 5) directionHistory.removeFirst()
                directionHistory.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "depan"
            } else {
                directionHistory.clear()
                "depan"
            }

            val currentTime = System.currentTimeMillis()
            if (detectedState && (!prevState || currentTime - lastAlertTime > COOLDOWN_MS)) {
                val message = getString(R.string.halangan_detected, stableDirection)
                
                runOnUiThread {
                    alertText.text = message
                    alertText.visibility = View.VISIBLE
                }
                
                // Audio feedback (async, thread-safe)
                speakAsync(message)
                
                lastAlertTime = currentTime
            } else if (!detectedState) {
                runOnUiThread { alertText.visibility = View.GONE }
            }

            drawOverlay(rgba, smoothMag, temporalIntegrator, loomingScore, detectedState, 
                       stableDirection, leftMag, centerMag, rightMag)
            updateTextUI(smoothMag, temporalIntegrator, loomingScore, stableDirection)

            // Release all intermediate mats
            flow.release()
            mag.release()
            ang.release()
            flowSplit.forEach { it.release() }

        } catch (e: Exception) {
            Log.e(TAG, "Error during frame processing: ${e.message}")
        } finally {
            prevGray?.release()
            prevGray = smallGray.clone()
            smallGray.release()
        }

        return rgba
    }

    private fun precomputeRadialGrid(size: Size) {
        val w = size.width.toInt()
        val h = size.height.toInt()
        val cx = w / 2.0
        val cy = h / 2.0

        radialX?.release(); radialY?.release()

        val xs = Mat(h, w, CvType.CV_32F)
        val ys = Mat(h, w, CvType.CV_32F)
        val tmp = FloatArray(1)
        for (y in 0 until h) {
            for (x in 0 until w) {
                tmp[0] = (x - cx).toFloat()
                xs.put(y, x, tmp)
                tmp[0] = (y - cy).toFloat()
                ys.put(y, x, tmp)
            }
        }

        val rmag = Mat()
        Core.magnitude(xs, ys, rmag)
        Core.add(rmag, Scalar(1e-6), rmag)
        radialX = Mat()
        radialY = Mat()
        Core.divide(xs, rmag, radialX)
        Core.divide(ys, rmag, radialY)

        xs.release(); ys.release(); rmag.release()
    }

    /**
     * Thread-safe audio feedback dengan cooldown
     * Matches Python: AudioManager.speak_async()
     */
    private fun speakAsync(message: String): Boolean {
        if (!ttsReady || !::tts.isInitialized) {
            Log.w(TAG, "⚠️ TTS not ready")
            return false
        }
        
        val currentTime = System.currentTimeMillis()
        
        // Check cooldown and speaking state
        if (currentTime - lastSpeakTime < COOLDOWN_MS || tts.isSpeaking) {
            return false
        }
        
        // Speak the message
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
        lastSpeakTime = currentTime
        
        Log.i(TAG, "🔊 TTS: $message")
        return true
    }


    private fun calculateDirection(mag: Mat): Quad<String, Double, Double, Double> {
        val w = mag.cols()
        val h = mag.rows()
        val wLeft = (w * 0.3).toInt()
        val wRight = (w * 0.7).toInt()

        val left = Core.mean(mag.submat(0, h, 0, wLeft)).`val`[0]
        val center = Core.mean(mag.submat(0, h, wLeft, wRight)).`val`[0]
        val right = Core.mean(mag.submat(0, h, wRight, w)).`val`[0]

        val maxMag = max(max(left, center), right)
        val dir = when {
            center >= maxMag * 0.9 -> "depan"
            left > right * (1 + DIRECTION_MARGIN) -> "kiri"
            right > left * (1 + DIRECTION_MARGIN) -> "kanan"
            else -> "depan"
        }
        return Quad(dir, left, center, right)
    }

    private fun calculateLoomingScoreFast(u: Mat, v: Mat): Double {
        if (radialX == null || radialY == null) return 0.0
        val projX = Mat(); val projY = Mat(); val proj = Mat()
        Core.multiply(u, radialX, projX)
        Core.multiply(v, radialY, projY)
        Core.add(projX, projY, proj)
        Core.max(proj, Scalar(0.0), proj)
        val mean = Core.mean(proj).`val`[0]
        proj.release(); projX.release(); projY.release()
        return mean
    }

    private fun drawOverlay(
        frame: Mat, smoothMag: Double, integrator: Double,
        looming: Double, detected: Boolean, direction: String,
        left: Double, center: Double, right: Double
    ) {
        val h = frame.rows()
        val w = frame.cols()
        
        // ═══════════════════════════════════════════════════
        // STATUS INDICATOR (top-right circle)
        // ═══════════════════════════════════════════════════
        val statusColor = if (detected) 
            Scalar(0.0, 0.0, 255.0, 255.0) // Red = DETECTED
        else 
            Scalar(0.0, 255.0, 0.0, 255.0) // Green = CLEAR
            
        Imgproc.circle(frame, Point((w - 20).toDouble(), 20.0), 8, statusColor, -1)
        
        // ═══════════════════════════════════════════════════
        // DETECTION MODE INDICATOR (top-right text)
        // ═══════════════════════════════════════════════════
        val modeColors = mapOf(
            "MAG" to Scalar(255.0, 255.0, 0.0, 255.0),   // Cyan (fast motion)
            "INT" to Scalar(255.0, 165.0, 0.0, 255.0),   // Orange (slow approach)
            "LOOM" to Scalar(0.0, 255.0, 255.0, 255.0),  // Yellow (looming)
            "NONE" to Scalar(200.0, 200.0, 200.0, 255.0) // Gray
        )
        val modeColor = modeColors[detectionMode] ?: Scalar(255.0, 255.0, 255.0, 255.0)
        
        Imgproc.putText(
            frame, detectionMode,
            Point((w - 80).toDouble(), 25.0),
            Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, modeColor, 2, Imgproc.LINE_AA
        )
        
        // ═══════════════════════════════════════════════════
        // METRIC BARS (top area)
        // ═══════════════════════════════════════════════════
        var barY = 10
        val barH = 8
        val barMaxWidth = w - 120
        
        // 1. MAGNITUDE BAR (Cyan)
        var barW = ((smoothMag / 5.0) * barMaxWidth).toInt()
        barW = barW.coerceIn(0, barMaxWidth)
        if (barW > 0) {
            Imgproc.rectangle(
                frame,
                Point(10.0, barY.toDouble()),
                Point((10 + barW).toDouble(), (barY + barH).toDouble()),
                Scalar(255.0, 255.0, 0.0, 255.0), -1
            )
        }
        Imgproc.putText(
            frame, "Mag:${"%.2f".format(smoothMag)}",
            Point(10.0, (barY - 2).toDouble()),
            Imgproc.FONT_HERSHEY_SIMPLEX, 0.3, Scalar(255.0, 255.0, 255.0, 255.0), 1
        )
        
        // 2. INTEGRATOR BAR (Orange)
        barY += 12
        var intW = ((integrator / 30.0) * barMaxWidth).toInt()
        intW = intW.coerceIn(0, barMaxWidth)
        if (intW > 0) {
            Imgproc.rectangle(
                frame,
                Point(10.0, barY.toDouble()),
                Point((10 + intW).toDouble(), (barY + barH).toDouble()),
                Scalar(0.0, 165.0, 255.0, 255.0), -1
            )
        }
        Imgproc.putText(
            frame, "Int:${"%.1f".format(integrator)}",
            Point(10.0, (barY - 2).toDouble()),
            Imgproc.FONT_HERSHEY_SIMPLEX, 0.3, Scalar(255.0, 255.0, 255.0, 255.0), 1
        )
        
        // 3. LOOMING BAR (Yellow)
        barY += 12
        var loomW = ((looming / 0.5) * barMaxWidth).toInt()
        loomW = loomW.coerceIn(0, barMaxWidth)
        if (loomW > 0) {
            Imgproc.rectangle(
                frame,
                Point(10.0, barY.toDouble()),
                Point((10 + loomW).toDouble(), (barY + barH).toDouble()),
                Scalar(0.0, 255.0, 255.0, 255.0), -1
            )
        }
        Imgproc.putText(
            frame, "Loom:${"%.3f".format(looming)}",
            Point(10.0, (barY - 2).toDouble()),
            Imgproc.FONT_HERSHEY_SIMPLEX, 0.3, Scalar(255.0, 255.0, 255.0, 255.0), 1
        )
        
        // ═══════════════════════════════════════════════════
        // DIRECTION INFO (bottom-left)
        // ═══════════════════════════════════════════════════
        val info = "Dir:$direction"
        // Shadow for readability
        Imgproc.putText(
            frame, info,
            Point(10.0, (h - 30).toDouble()),
            Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, 
            Scalar(0.0, 0.0, 0.0, 255.0), 3, Imgproc.LINE_AA
        )
        // Main text
        Imgproc.putText(
            frame, info,
            Point(10.0, (h - 30).toDouble()),
            Imgproc.FONT_HERSHEY_SIMPLEX, 0.6,
            Scalar(0.0, 255.0, 0.0, 255.0), 2, Imgproc.LINE_AA
        )
        
        // ═══════════════════════════════════════════════════
        // ZONE BARS (bottom edge) - showing spatial distribution
        // ═══════════════════════════════════════════════════
        val zoneBarH = 6
        val yPos = h - 10
        val maxZone = maxOf(left, center, right, 0.1)
        
        // LEFT ZONE (Red)
        val leftW = ((left / maxZone) * (w * 0.3)).toInt()
        if (leftW > 0) {
            Imgproc.rectangle(
                frame,
                Point(0.0, yPos.toDouble()),
                Point(leftW.toDouble(), (yPos + zoneBarH).toDouble()),
                Scalar(0.0, 0.0, 255.0, 255.0), -1
            )
        }
        
        // CENTER ZONE (Yellow)
        val centerStart = (w * 0.3).toInt()
        val centerW = ((center / maxZone) * (w * 0.4)).toInt()
        if (centerW > 0) {
            Imgproc.rectangle(
                frame,
                Point(centerStart.toDouble(), yPos.toDouble()),
                Point((centerStart + centerW).toDouble(), (yPos + zoneBarH).toDouble()),
                Scalar(0.0, 255.0, 255.0, 255.0), -1
            )
        }
        
        // RIGHT ZONE (Blue)
        val rightStart = (w * 0.7).toInt()
        val rightW = ((right / maxZone) * (w * 0.3)).toInt()
        if (rightW > 0) {
            Imgproc.rectangle(
                frame,
                Point(rightStart.toDouble(), yPos.toDouble()),
                Point((rightStart + rightW).toDouble(), (yPos + zoneBarH).toDouble()),
                Scalar(255.0, 0.0, 0.0, 255.0), -1
            )
        }
    }

    private fun updateTextUI(mag: Double, integrator: Double, loom: Double, dir: String) {
        runOnUiThread {
            val status = if (detectedState) getString(R.string.status_detected, detectionMode) else getString(R.string.status_clear)
            val magS = "%.2f".format(mag)
            val intS = "%.1f".format(integrator)
            val loomS = "%.3f".format(loom)
            statusText.text = status
            metricsText.text = getString(R.string.metrics_template, frameCount, magS, intS, loomS, dir)
        }
    }

    data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
