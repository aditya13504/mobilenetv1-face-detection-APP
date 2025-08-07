package com.example.facedetection

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import java.util.UUID
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.facedetection.TraceWorker
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaDrm
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.example.facedetection.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis

class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var faceDetectionModel: FaceDetectionModel
    private lateinit var sharedPreferences: SharedPreferences

    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_FRONT

    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var fps = 0.0
    private var lastProcessingTime = 0L
    private var targetFps = 30
    private val performanceMonitor = PerformanceMonitor()

    private var showLandmarks = true
    private var showPerformanceInfo = true
    private var confidenceThreshold = 0.5f
    private var useGpu = true

    private val faceTracker = FaceTracker()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required for face detection", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("AppCrash", "Uncaught exception in thread ${thread.name}: ${throwable.message}", throwable)
        }

        try {
            val widevine: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
            val drm = MediaDrm(widevine)
            val level = drm.getPropertyString("securityLevel")
            if (level != "L1") {
                drm.setPropertyString("securityLevel", "L3")
            }
        } catch (e: Exception) {
            Log.e("DRM", "DRM fallback failed: ${e.message}", e)
        }

        val request = OneTimeWorkRequestBuilder<TraceWorker>().build()
        WorkManager.getInstance(this).enqueue(request)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        }

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        loadSettings()

        try {
            faceDetectionModel = FaceDetectionModel(this)
            faceDetectionModel.setConfidenceThreshold(confidenceThreshold)
        } catch (e: NoClassDefFoundError) {
            Log.e(TAG, "TensorFlow GPU delegate error. Using CPU fallback.", e)
            Toast.makeText(this, "Device does not support GPU acceleration. Falling back to CPU.", Toast.LENGTH_LONG).show()
            useGpu = false
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.overlayView.setShowLandmarks(showLandmarks)
        binding.overlayView.setShowConfidence(true)

        binding.tvFpsCounter.visibility = if (showPerformanceInfo)
            android.view.View.VISIBLE else android.view.View.GONE

        setupUIListeners()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun loadSettings() {
        confidenceThreshold = sharedPreferences.getInt("confidence_threshold", 50) / 100f
        showLandmarks = sharedPreferences.getBoolean("show_landmarks", true)
        useGpu = sharedPreferences.getBoolean("use_gpu", true)
        showPerformanceInfo = sharedPreferences.getBoolean("show_performance_info", true)
        targetFps = sharedPreferences.getString("target_fps", "30")?.toIntOrNull() ?: 30

        Log.d(TAG, "Settings loaded - Confidence: $confidenceThreshold, GPU: $useGpu, Landmarks: $showLandmarks")
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            "confidence_threshold" -> {
                confidenceThreshold = sharedPreferences?.getInt(key, 50)?.div(100f) ?: 0.5f
                faceDetectionModel.setConfidenceThreshold(confidenceThreshold)
            }
            "show_landmarks" -> {
                showLandmarks = sharedPreferences?.getBoolean(key, true) ?: true
                binding.overlayView.setShowLandmarks(showLandmarks)
            }
            "show_performance_info" -> {
                showPerformanceInfo = sharedPreferences?.getBoolean(key, true) ?: true
                binding.tvFpsCounter.visibility = if (showPerformanceInfo)
                    android.view.View.VISIBLE else android.view.View.GONE
            }
            "use_gpu" -> {
                // GPU setting requires model reload
                Toast.makeText(this, "GPU setting changed. Restart app to take effect.", Toast.LENGTH_LONG).show()
            }
            "target_fps" -> {
                targetFps = sharedPreferences?.getString(key, "30")?.toIntOrNull() ?: 30
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_performance_report -> {
                performanceMonitor.logPerformanceReport()
                showPerformanceDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showPerformanceDialog() {
        val report = performanceMonitor.getPerformanceReport()
        val message = """
            Performance Statistics:
            
            Average Processing: ${String.format("%.1f", report.averageTime)}ms
            Min/Max: ${report.minTime}ms / ${report.maxTime}ms
            Frame Drop Rate: ${String.format("%.1f", report.frameDropRate)}%
            Total Frames: ${report.totalFrames}
            
            Current FPS: ${String.format("%.1f", fps)}
        """.trimIndent()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Performance Report")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("Reset Stats") { _, _ ->
                performanceMonitor.reset()
                Toast.makeText(this, "Performance stats reset", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun initializeComponents() {
        // Initialize model with current GPU setting
        faceDetectionModel = FaceDetectionModel(this)
        faceDetectionModel.setConfidenceThreshold(confidenceThreshold)

        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Configure overlay
        binding.overlayView.setShowLandmarks(showLandmarks)
        binding.overlayView.setShowConfidence(true)

        // Configure FPS counter visibility
        binding.tvFpsCounter.visibility = if (showPerformanceInfo)
            android.view.View.VISIBLE else android.view.View.GONE

        Log.d(TAG, "Components initialized successfully")
    }

    private fun setupUIListeners() {
        binding.btnToggleCamera.setOnClickListener {
            toggleCamera()
        }
        binding.toggleLandmarks.setOnCheckedChangeListener { _, isChecked ->
            showLandmarks = isChecked
            binding.overlayView.setShowLandmarks(showLandmarks)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (exc: Exception) {
                Log.e(TAG, "Camera provider initialization failed", exc)
                Toast.makeText(this, "Camera initialization failed", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = this.cameraProvider ?: run {
            Log.e(TAG, "Camera provider is null")
            return
        }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        // Preview use case
        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()
            .also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

        // Image analysis use case for face detection
        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, FaceDetectionAnalyzer())
            }

        // Image capture use case (optional for taking photos)
        imageCapture = ImageCapture.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()

        try {
            // Unbind all use cases before rebinding
            cameraProvider.unbindAll()

            // Bind use cases to camera
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageCapture,
                imageAnalyzer
            )

            Log.d(TAG, "Camera bound successfully")

        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            Toast.makeText(this, "Camera binding failed: ${exc.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }

        bindCameraUseCases()
    }

    private inner class FaceDetectionAnalyzer : ImageAnalysis.Analyzer {

        override fun analyze(imageProxy: ImageProxy) {
            val currentTime = System.currentTimeMillis()
            val minProcessingInterval = 1000 / targetFps

            // Throttle processing to maintain performance
            if (currentTime - lastProcessingTime < minProcessingInterval) {
                performanceMonitor.recordDroppedFrame()
                imageProxy.close()
                return
            }

            lastProcessingTime = currentTime

            // Process the image asynchronously
            lifecycleScope.launch(Dispatchers.Default) {
                val processingTime = measureTimeMillis {
                    val bitmap = imageProxyToBitmap(imageProxy)
                    if (bitmap != null) {
                        try {
                            val detectedFaces = faceDetectionModel.detectFaces(bitmap)

                            // Apply face tracking for consistent detection
                            val trackedFaces = faceTracker.updateTracks(detectedFaces)

                            withContext(Dispatchers.Main) {
                                binding.overlayView.updateDetections(
                                    trackedFaces.map { it.face },
                                    bitmap.width,
                                    bitmap.height
                                )
                                updateFpsCounter()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error during face detection", e)
                        } finally {
                            bitmap?.recycle()
                        }
                    }
                }

                performanceMonitor.addSample(processingTime)
                Log.d(TAG, "Face detection processing time: ${processingTime}ms")
                imageProxy.close()
            }
        }

        private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
            return try {
                val bitmap = when (imageProxy.format) {
                    ImageFormat.YUV_420_888 -> {
                        yuvToBitmap(imageProxy)
                    }
                    else -> {
                        // Fallback for other formats
                        val buffer = imageProxy.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }

                bitmap?.let { handleBitmapRotation(it, imageProxy.imageInfo.rotationDegrees) }
            } catch (e: Exception) {
                Log.e(TAG, "Error converting ImageProxy to Bitmap", e)
                null
            }
        }

        private fun yuvToBitmap(imageProxy: ImageProxy): Bitmap? {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
            val imageBytes = out.toByteArray()

            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        }

        private fun handleBitmapRotation(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
            if (rotationDegrees == 0) {
                return if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                    // Mirror front camera
                    val matrix = Matrix().apply { postScale(-1f, 1f) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                } else {
                    bitmap
                }
            }

            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
                if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                    postScale(-1f, 1f) // Mirror front camera
                }
            }

            return Bitmap.createBitmap(
                bitmap, 0, 0,
                bitmap.width, bitmap.height,
                matrix, true
            ).also {
                if (it != bitmap) {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun updateFpsCounter() {
        frameCount++
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastFpsTime >= 1000) {
            fps = frameCount * 1000.0 / (currentTime - lastFpsTime)
            if (showPerformanceInfo) {
                binding.tvFpsCounter.text = String.format("FPS: %.1f", fps)
            }
            frameCount = 0
            lastFpsTime = currentTime
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        // Resume camera if permissions are granted
        if (allPermissionsGranted() && cameraProvider == null) {
            startCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        // Optionally pause camera to save resources
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
            cameraExecutor.shutdown()
            faceDetectionModel.close()
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
    companion object {
        private const val TAG = "FaceDetectionApp"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}