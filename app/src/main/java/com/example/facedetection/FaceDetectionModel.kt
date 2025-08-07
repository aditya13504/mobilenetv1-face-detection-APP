package com.example.facedetection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

data class DetectedFace(
    val boundingBox: RectF,
    val confidence: Float,
    val landmarks: FloatArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DetectedFace

        if (boundingBox != other.boundingBox) return false
        if (confidence != other.confidence) return false
        if (landmarks != null) {
            if (other.landmarks == null) return false
            if (!landmarks.contentEquals(other.landmarks)) return false
        } else if (other.landmarks != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = boundingBox.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + (landmarks?.contentHashCode() ?: 0)
        return result
    }
}

class FaceDetectionModel(private val context: Context) {
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private val inputSize = 640
    private val numPriors = 16800

    // Model parameters
    private val varianceValues = floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f)
    private var confidenceThreshold = 0.5f
    private val nmsThreshold = 0.4f
    private var useGpu = true

    companion object {
        private const val TAG = "FaceDetectionModel"
        private const val MODEL_FILE = "FaceDetector.tflite"
    }

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)
            val options = createInterpreterOptions()
            interpreter = Interpreter(modelBuffer, options)
            Log.d(TAG, "Model loaded successfully with ${if (useGpu) "GPU" else "CPU"} backend")
        } catch (e: IOException) {
            Log.e(TAG, "Error loading model file: ${e.message}")
            e.printStackTrace()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing interpreter: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun createInterpreterOptions(): Interpreter.Options {
        val options = Interpreter.Options()

        if (useGpu) {
            try {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
                Log.d(TAG, "GPU delegate added successfully")
            } catch (e: NoClassDefFoundError) {
                Log.w(TAG, "GPU delegate class not found, falling back to CPU: ${e.message}")
                useGpu = false
                options.numThreads = 4
            } catch (e: Exception) {
                Log.w(TAG, "GPU delegate not available, falling back to CPU: ${e.message}")
                useGpu = false
                options.numThreads = 4
            }
        } else {
            options.numThreads = 4
        }

        return options
    }

    fun setConfidenceThreshold(threshold: Float) {
        confidenceThreshold = threshold.coerceIn(0.1f, 0.9f)
    }

    fun detectFaces(bitmap: Bitmap): List<DetectedFace> {
        val interpreter = this.interpreter
        if (interpreter == null) {
            Log.e(TAG, "Interpreter not initialized")
            return emptyList()
        }

        return try {
            val startTime = System.currentTimeMillis()

            // Preprocess image
            val inputBuffer = preprocessImage(bitmap)

            // Prepare output arrays
            val bboxOutput = Array(1) { Array(numPriors) { FloatArray(4) } }
            val confOutput = Array(1) { Array(numPriors) { FloatArray(2) } }
            val landmarkOutput = Array(1) { Array(numPriors) { FloatArray(10) } }

            // Run inference
            val inputs = arrayOf(inputBuffer)
            val outputs = mapOf(
                0 to bboxOutput,
                1 to confOutput,
                2 to landmarkOutput
            )

            interpreter.runForMultipleInputsOutputs(inputs, outputs)

            // Post-process results
            val detections = postProcessResults(
                bboxOutput[0],
                confOutput[0],
                landmarkOutput[0],
                bitmap.width,
                bitmap.height
            )

            val processingTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "Detection completed in ${processingTime}ms, found ${detections.size} faces")

            detections
        } catch (e: Exception) {
            Log.e(TAG, "Error during face detection: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val resizedBitmap = if (bitmap.width != inputSize || bitmap.height != inputSize) {
            Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, false)
        } else {
            bitmap
        }

        val inputBuffer = ByteBuffer.allocateDirect(4 * 3 * inputSize * inputSize)
        inputBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        val mean = floatArrayOf(104f, 117f, 123f)

        for (c in 0 until 3) {
            for (y in 0 until inputSize) {
                for (x in 0 until inputSize) {
                    val pixel = pixels[y * inputSize + x]
                    val channelValue = when (c) {
                        0 -> (pixel and 0xFF).toFloat()
                        1 -> ((pixel shr 8) and 0xFF).toFloat()
                        2 -> ((pixel shr 16) and 0xFF).toFloat()
                        else -> 0f
                    }
                    inputBuffer.putFloat(channelValue - mean[c])
                }
            }
        }

        return inputBuffer
    }

    private fun postProcessResults(
        bboxes: Array<FloatArray>,
        confidences: Array<FloatArray>,
        landmarks: Array<FloatArray>,
        imageWidth: Int,
        imageHeight: Int
    ): List<DetectedFace> {

        val detections = mutableListOf<DetectedFace>()
        val priors = generatePriors()

        for (i in bboxes.indices) {
            val confidence = confidences[i][1]

            if (confidence > confidenceThreshold) {
                val prior = priors[i]
                val bbox = decodeBoundingBox(bboxes[i], prior)

                val scaledBbox = RectF(
                    (bbox.left * imageWidth).coerceIn(0f, imageWidth.toFloat()),
                    (bbox.top * imageHeight).coerceIn(0f, imageHeight.toFloat()),
                    (bbox.right * imageWidth).coerceIn(0f, imageWidth.toFloat()),
                    (bbox.bottom * imageHeight).coerceIn(0f, imageHeight.toFloat())
                )

                if (scaledBbox.width() > 0 && scaledBbox.height() > 0) {
                    val decodedLandmarks = decodeLandmarks(landmarks[i], prior, imageWidth, imageHeight)
                    detections.add(DetectedFace(scaledBbox, confidence, decodedLandmarks))
                }
            }
        }

        return applyNMS(detections)
    }

    private fun generatePriors(): List<FloatArray> {
        val priors = mutableListOf<FloatArray>()
        val steps = intArrayOf(8, 16, 32)
        val minSizes = arrayOf(
            intArrayOf(16, 32),
            intArrayOf(64, 128),
            intArrayOf(256, 512)
        )

        for (k in steps.indices) {
            val step = steps[k]
            val minSize = minSizes[k]

            val featureMapSize = inputSize / step

            for (i in 0 until featureMapSize) {
                for (j in 0 until featureMapSize) {
                    for (size in minSize) {
                        val s_kx = size.toFloat() / inputSize
                        val s_ky = size.toFloat() / inputSize
                        val cx = (j + 0.5f) * step / inputSize
                        val cy = (i + 0.5f) * step / inputSize

                        priors.add(floatArrayOf(cx, cy, s_kx, s_ky))
                    }
                }
            }
        }

        return priors
    }

    private fun decodeBoundingBox(bbox: FloatArray, prior: FloatArray): RectF {
        val cx = prior[0] + bbox[0] * varianceValues[0] * prior[2]
        val cy = prior[1] + bbox[1] * varianceValues[1] * prior[3]
        val w = prior[2] * exp(bbox[2] * varianceValues[2])
        val h = prior[3] * exp(bbox[3] * varianceValues[3])

        return RectF(
            cx - w / 2f,
            cy - h / 2f,
            cx + w / 2f,
            cy + h / 2f
        )
    }

    private fun decodeLandmarks(landmarks: FloatArray, prior: FloatArray, imageWidth: Int, imageHeight: Int): FloatArray {
        val decodedLandmarks = FloatArray(10)

        for (i in 0 until 5) {
            val x = prior[0] + landmarks[i * 2] * varianceValues[0] * prior[2]
            val y = prior[1] + landmarks[i * 2 + 1] * varianceValues[1] * prior[3]

            decodedLandmarks[i * 2] = (x * imageWidth).coerceIn(0f, imageWidth.toFloat())
            decodedLandmarks[i * 2 + 1] = (y * imageHeight).coerceIn(0f, imageHeight.toFloat())
        }

        return decodedLandmarks
    }

    private fun applyNMS(detections: List<DetectedFace>): List<DetectedFace> {
        if (detections.isEmpty()) return emptyList()

        val sortedDetections = detections.sortedByDescending { it.confidence }
        val keep = mutableListOf<DetectedFace>()

        for (detection in sortedDetections) {
            var shouldKeep = true

            for (keptDetection in keep) {
                val iou = calculateIoU(detection.boundingBox, keptDetection.boundingBox)
                if (iou > nmsThreshold) {
                    shouldKeep = false
                    break
                }
            }

            if (shouldKeep) {
                keep.add(detection)
            }
        }

        return keep
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = maxOf(box1.left, box2.left)
        val intersectionTop = maxOf(box1.top, box2.top)
        val intersectionRight = minOf(box1.right, box2.right)
        val intersectionBottom = minOf(box1.bottom, box2.bottom)

        if (intersectionLeft >= intersectionRight || intersectionTop >= intersectionBottom) {
            return 0f
        }

        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val box1Area = box1.width() * box1.height()
        val box2Area = box2.width() * box2.height()
        val unionArea = box1Area + box2Area - intersectionArea

        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    fun close() {
        try {
            interpreter?.close()
            gpuDelegate?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing resources: ${e.message}")
        } finally {
            interpreter = null
            gpuDelegate = null
        }
    }
}