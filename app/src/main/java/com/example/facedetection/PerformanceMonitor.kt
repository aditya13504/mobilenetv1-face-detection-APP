package com.example.facedetection

import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.sqrt

class PerformanceMonitor {
    private val processingTimes = ConcurrentLinkedQueue<Long>()
    private val maxSamples = 100
    private var totalFrames = 0L
    private var droppedFrames = 0L

    companion object {
        private const val TAG = "PerformanceMonitor"
        private const val WARNING_THRESHOLD_MS = 100L
        private const val CRITICAL_THRESHOLD_MS = 200L
    }

    fun addSample(processingTime: Long) {
        processingTimes.offer(processingTime)
        totalFrames++

        // Keep only recent samples
        while (processingTimes.size > maxSamples) {
            processingTimes.poll()
        }

        // Log performance warnings
        when {
            processingTime > CRITICAL_THRESHOLD_MS -> {
                Log.w(TAG, "Critical processing time: ${processingTime}ms")
            }
            processingTime > WARNING_THRESHOLD_MS -> {
                Log.d(TAG, "High processing time: ${processingTime}ms")
            }
        }
    }

    fun recordDroppedFrame() {
        droppedFrames++
    }

    fun getAverageProcessingTime(): Double {
        val times = processingTimes.toList()
        return if (times.isNotEmpty()) {
            times.average()
        } else 0.0
    }

    fun getMinProcessingTime(): Long {
        return processingTimes.minOrNull() ?: 0L
    }

    fun getMaxProcessingTime(): Long {
        return processingTimes.maxOrNull() ?: 0L
    }

    fun getStandardDeviation(): Double {
        val times = processingTimes.toList()
        if (times.size < 2) return 0.0

        val mean = times.average()
        val variance = times.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance)
    }

    fun getFrameDropRate(): Double {
        return if (totalFrames > 0) {
            (droppedFrames.toDouble() / totalFrames) * 100
        } else 0.0
    }

    fun getPerformanceReport(): PerformanceReport {
        val times = processingTimes.toList()
        return PerformanceReport(
            sampleCount = times.size,
            averageTime = getAverageProcessingTime(),
            minTime = getMinProcessingTime(),
            maxTime = getMaxProcessingTime(),
            standardDeviation = getStandardDeviation(),
            frameDropRate = getFrameDropRate(),
            totalFrames = totalFrames,
            droppedFrames = droppedFrames
        )
    }

    fun reset() {
        processingTimes.clear()
        totalFrames = 0L
        droppedFrames = 0L
    }

    fun logPerformanceReport() {
        val report = getPerformanceReport()
        Log.i(TAG, """
            Performance Report:
            - Sample Count: ${report.sampleCount}
            - Average Time: ${String.format("%.2f", report.averageTime)}ms
            - Min Time: ${report.minTime}ms
            - Max Time: ${report.maxTime}ms
            - Std Deviation: ${String.format("%.2f", report.standardDeviation)}ms
            - Frame Drop Rate: ${String.format("%.2f", report.frameDropRate)}%
            - Total Frames: ${report.totalFrames}
            - Dropped Frames: ${report.droppedFrames}
        """.trimIndent())
    }
}

data class PerformanceReport(
    val sampleCount: Int,
    val averageTime: Double,
    val minTime: Long,
    val maxTime: Long,
    val standardDeviation: Double,
    val frameDropRate: Double,
    val totalFrames: Long,
    val droppedFrames: Long
)