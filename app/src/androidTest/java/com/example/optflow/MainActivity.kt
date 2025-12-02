package com.example.optflow.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // UI Components
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var metricsText: TextView
    private lateinit var alertText: TextView

    // Camera
    private var imageAnalysis: ImageAnalysis? = null
    private lateinit var cameraExecutor: ExecutorService

    // TTS
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    // OpenCV
    private var prevGray: Mat? = null
    private var rxn: Mat? = null
    private var ryn: Mat? = null

    // Detection State
    private var smoothMag = 0.0
    private var temporalIntegrator = 0.0
    private var detectedState = false
    private var lastAlertTime = 0L
    private var frameCount = 0
    private val baselineSamples = ArrayDeque<Double>(30)
    private val directionHistory = ArrayDeque<String>(5)

    // Constants
    companion object {
        private const val TAG = "ObstacleDetection"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        // Detection Parameters
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize OpenCV
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV initialization failed!")
            Toast.makeText(this, "OpenCV initialization failed!", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Log.d(TAG, "OpenCV initialized successfully")

        // Initialize UI
        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        metricsText = findViewById(R.id.metricsText)
        alertText = findViewById(R.id.alertText)

        // Initialize TTS
        tts = TextToSpeech(this, this)

        // Initialize Camera Executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Check permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // Image Analysis
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(RESIZE_WIDTH, RESIZE_HEIGHT))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ObstacleAnalyzer())
                }

            // Select back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Camera binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // Image Analyzer
    private inner class ObstacleAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            val bitmap = imageProxy.toBitmap() ?: run {
                imageProxy.close()
                return
            }

            processFrame(bitmap)
            imageProxy.close()
        }
    }

    private fun processFrame(bitmap: Bitmap) {
        frameCount++

        // Convert to OpenCV Mat
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        // Convert to grayscale
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)

        // Initialize radial grid on first frame
        if (prevGray == null) {
            prevGray = gray.clone()
            precomputeRadialGrid(gray.size())
            mat.release()
            return
        }

        // Calculate Optical Flow
        val flow = Mat()
        Video.calcOpticalFlowFarneback(
            prevGray!!, gray, flow,
            0.5, 3, 15, 3, 5, 1.2, 0
        )

        // Get magnitude
        val flowSplit = ArrayList<Mat>()
        Core.split(flow, flowSplit)
        val u = flowSplit[0]
        val v = flowSplit[1]

        val mag = Mat()
        val angle = Mat()
        Core.cartToPolar(u, v, mag, angle)

        // Apply blur
        Imgproc.GaussianBlur(mag, mag, Size(5.0, 5.0), 0.0)

        // Calculate metrics
        val avgMagnitude = Core.mean(mag).`val`[0]
        val loomingScore = calculateLoomingScore(u, v)
        val (direction, leftMag, centerMag, rightMag) = calculateDirection(mag)

        // Smooth magnitude
        smoothMag = ALPHA_SMOOTH * avgMagnitude + (1 - ALPHA_SMOOTH) * smoothMag

        // Temporal integrator
        if (!detectedState) {
            temporalIntegrator = ALPHA_INTEGRATE * temporalIntegrator + avgMagnitude
        } else {
            temporalIntegrator = max(0.0, temporalIntegrator * 0.95)
        }

        // Update baseline
        if (!detectedState) {
            baselineSamples.addLast(smoothMag)
            if (baselineSamples.size > 30) baselineSamples.removeFirst()
        }

        val baseline = if (baselineSamples.size >= 10) {
            baselineSamples.sorted()[baselineSamples.size / 2]
        } else null

        // Adaptive thresholds
        val thresholdHigh = if (baseline != null && baseline > 0.3) {
            min(max(baseline * 1.4, THRESHOLD_HIGH * 0.6), THRESHOLD_HIGH * 1.5)
        } else THRESHOLD_HIGH

        val thresholdLow = if (baseline != null && baseline > 0.3) {
            min(max(baseline * 1.0, THRESHOLD_LOW * 0.6), THRESHOLD_LOW * 1.5)
        } else THRESHOLD_LOW

        // Detection logic
        val prevState = detectedState
        val magDetected = smoothMag > thresholdHigh && smoothMag > MIN_MAGNITUDE
        val intDetected = temporalIntegrator > INTEGRATION_THRESHOLD
        val loomDetected = loomingScore > LOOMING_THRESHOLD

        val detectionMode = when {
            magDetected || intDetected || loomDetected -> {
                detectedState = true
                when {
                    magDetected -> "MAG"
                    intDetected -> "INT"
                    else -> "LOOM"
                }
            }
            smoothMag < thresholdLow && temporalIntegrator < INTEGRATION_THRESHOLD * 0.5 -> {
                detectedState = false
                "NONE"
            }
            else -> if (detectedState) "HOLD" else "NONE"
        }

        // Direction stabilization
        val stableDirection = if (detectedState) {
            directionHistory.addLast(direction)
            if (directionHistory.size > 5) directionHistory.removeFirst()
            directionHistory.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "depan"
        } else {
            directionHistory.clear()
            "depan"
        }

        // Alert
        val currentTime = System.currentTimeMillis()
        if (detectedState && (!prevState || currentTime - lastAlertTime > COOLDOWN_MS)) {
            val message = "Halangan terdeteksi di $stableDirection!"
            speakOut(message)
            lastAlertTime = currentTime

            runOnUiThread {
                alertText.text = message
                alertText.visibility = View.VISIBLE
            }
        } else if (!detectedState) {
            runOnUiThread {
                alertText.visibility = View.GONE
            }
        }

        // Update UI
        runOnUiThread {
            statusText.text = if (detectedState) "🔴 DETECTED ($detectionMode)" else "🟢 CLEAR"
            metricsText.text = """
                Frame: $frameCount
                Mag: ${"%.2f".format(smoothMag)}
                Int: ${"%.1f".format(temporalIntegrator)}
                Loom: ${"%.3f".format(loomingScore)}
                Dir: $stableDirection
            """.trimIndent()
        }

        // Cleanup
        prevGray = gray.clone()
        mat.release()
        gray.release()
        flow.release()
        mag.release()
        u.release()
        v.release()
        angle.release()
    }

    private fun precomputeRadialGrid(size: Size) {
        val h = size.height.toInt()
        val w = size.width.toInt()
        val cx = w / 2.0
        val cy = h / 2.0

        rxn = Mat(h, w, CvType.CV_32FC1)
        ryn = Mat(h, w, CvType.CV_32FC1)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x - cx
                val dy = y - cy
                val r = sqrt(dx * dx + dy * dy) + 1e-6

                rxn!!.put(y, x, dx / r)
                ryn!!.put(y, x, dy / r)
            }
        }
    }

    private fun calculateLoomingScore(u: Mat, v: Mat): Double {
        var sum = 0.0
        var count = 0

        for (y in 0 until u.rows()) {
            for (x in 0 until u.cols()) {
                val uVal = u.get(y, x)[0]
                val vVal = v.get(y, x)[0]
                val rxVal = rxn!!.get(y, x)[0]
                val ryVal = ryn!!.get(y, x)[0]

                val radialProj = uVal * rxVal + vVal * ryVal
                if (radialProj > 0) {
                    sum += radialProj
                    count++
                }
            }
        }

        return if (count > 0) sum / count else 0.0
    }

    private fun calculateDirection(mag: Mat): DirectionResult {
        val w = mag.cols()
        val wLeft = (w * 0.30).toInt()
        val wRight = (w * 0.70).toInt()

        val leftMag = Core.mean(mag.submat(0, mag.rows(), 0, wLeft)).`val`[0]
        val centerMag = Core.mean(mag.submat(0, mag.rows(), wLeft, wRight)).`val`[0]
        val rightMag = Core.mean(mag.submat(0, mag.rows(), wRight, w)).`val`[0]

        val maxMag = maxOf(leftMag, centerMag, rightMag)

        val direction = when {
            centerMag >= maxMag * 0.9 -> "depan"
            leftMag > rightMag * (1 + DIRECTION_MARGIN) -> "kiri"
            rightMag > leftMag * (1 + DIRECTION_MARGIN) -> "kanan"
            else -> "depan"
        }

        return DirectionResult(direction, leftMag, centerMag, rightMag)
    }

    // TTS
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("id", "ID"))
            isTtsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!isTtsReady) {
                Log.e(TAG, "Indonesian language not supported")
            } else {
                Log.d(TAG, "TTS ready with Indonesian language")
            }
        }
    }

    private fun speakOut(text: String) {
        if (isTtsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "")
            Log.d(TAG, "Speaking: $text")
        }
    }

    // Permissions
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        tts?.stop()
        tts?.shutdown()
        prevGray?.release()
        rxn?.release()
        ryn?.release()
    }

    // Helper
    data class DirectionResult(
        val direction: String,
        val leftMag: Double,
        val centerMag: Double,
        val rightMag: Double
    )
}

// Extension: Convert ImageProxy to Bitmap
fun ImageProxy.toBitmap(): Bitmap? {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)

    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, this.width, this.height), 100, out)
    val imageBytes = out.toByteArray()
    return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}