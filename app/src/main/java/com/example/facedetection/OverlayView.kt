package com.example.facedetection

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boundingBoxPaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.GREEN
        textSize = 42f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private val landmarkPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 8f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val backgroundPaint = Paint().apply {
        color = Color.BLACK
        alpha = 128
        style = Paint.Style.FILL
    }

    private var detectedFaces: List<DetectedFace> = emptyList()
    private var scaleX = 1f
    private var scaleY = 1f
    private var showLandmarks = true
    private var showConfidence = true

    fun updateDetections(faces: List<DetectedFace>, imageWidth: Int, imageHeight: Int) {
        detectedFaces = faces

        if (width > 0 && height > 0 && imageWidth > 0 && imageHeight > 0) {
            scaleX = width.toFloat() / imageWidth
            scaleY = height.toFloat() / imageHeight
        }

        invalidate()
    }

    fun setShowLandmarks(show: Boolean) {
        showLandmarks = show
        invalidate()
    }

    fun setShowConfidence(show: Boolean) {
        showConfidence = show
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (detectedFaces.isEmpty()) return

        for ((index, face) in detectedFaces.withIndex()) {
            val paddingX = (face.boundingBox.width() * 0.1f).toInt()  // 10% horizontal padding
            val paddingY = (face.boundingBox.height() * 0.1f).toInt() // 10% vertical padding

            // Calculate scaled bounding box
            val scaledRect = RectF(
                (face.boundingBox.left - paddingX) * scaleX,
                (face.boundingBox.top - paddingY) * scaleY,
                (face.boundingBox.right + paddingX) * scaleX,
                (face.boundingBox.bottom + paddingY) * scaleY
            )

            // Ensure the rectangle is within bounds
            scaledRect.intersect(0f, 0f, width.toFloat(), height.toFloat())

            if (scaledRect.width() > 0 && scaledRect.height() > 0) {
                // Draw bounding box
                canvas.drawRect(scaledRect, boundingBoxPaint)

                // Draw confidence score and face ID
                if (showConfidence) {
                    val confidence = String.format("Face %d: %.2f", index + 1, face.confidence)
                    val textBounds = Rect()
                    textPaint.getTextBounds(confidence, 0, confidence.length, textBounds)

                    val textX = scaledRect.left
                    val textY = scaledRect.top - 10f

                    // Draw background for text
                    val backgroundRect = RectF(
                        textX - 8f,
                        textY - textBounds.height() - 8f,
                        textX + textBounds.width() + 16f,
                        textY + 8f
                    )
                    canvas.drawRect(backgroundRect, backgroundPaint)

                    // Draw text
                    canvas.drawText(confidence, textX, textY, textPaint)
                }

                // Draw landmarks if available
                if (showLandmarks && face.landmarks != null && face.landmarks.size >= 10) {
                    for (i in 0 until 5) {
                        val x = face.landmarks[i * 2] * scaleX
                        val y = face.landmarks[i * 2 + 1] * scaleY

                        // Ensure landmarks are within bounds
                        if (x >= 0 && x <= width && y >= 0 && y <= height) {
                            canvas.drawCircle(x, y, 10f, landmarkPaint)

                            // Draw landmark labels for debugging
                            val landmarkLabels = arrayOf("LE", "RE", "N", "LM", "RM") // Left Eye, Right Eye, Nose, Left Mouth, Right Mouth
                            if (i < landmarkLabels.size) {
                                canvas.drawText(
                                    landmarkLabels[i],
                                    x + 15f,
                                    y + 5f,
                                    textPaint.apply { textSize = 24f }
                                )
                            }
                        }
                    }
                    // Reset text size
                    textPaint.textSize = 42f
                }
            }
        }

        // Draw performance info
        drawPerformanceInfo(canvas)
    }

    private fun drawPerformanceInfo(canvas: Canvas) {
        val info = "Faces: ${detectedFaces.size}"
        val infoPaint = Paint().apply {
            color = Color.WHITE
            textSize = 60f
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        val offsetX = 40f  // shift right
        val offsetY = 100f // shift down
        val textBounds = Rect()
        infoPaint.getTextBounds(info, 0, info.length, textBounds)

        val padding = 20f
        val backgroundRect = RectF(
            offsetX - padding,
            offsetY - textBounds.height() - padding / 2,
            offsetX + textBounds.width() + padding,
            offsetY + padding / 2
        )
        canvas.drawRect(backgroundRect, backgroundPaint)
        canvas.drawText(info, offsetX, offsetY, infoPaint)
    }
}