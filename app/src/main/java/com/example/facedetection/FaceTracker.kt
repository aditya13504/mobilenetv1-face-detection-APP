package com.example.facedetection

import android.graphics.RectF
import kotlin.math.sqrt

data class TrackedFace(
    val id: Int,
    var face: DetectedFace,
    val trackingHistory: MutableList<RectF> = mutableListOf(),
    var missedFrames: Int = 0
) {
    companion object {
        const val MAX_MISSED_FRAMES = 10
    }

    fun updatePosition(newFace: DetectedFace) {
        face = newFace
        trackingHistory.add(newFace.boundingBox)
        missedFrames = 0

        // Keep history size manageable
        if (trackingHistory.size > 10) {
            trackingHistory.removeAt(0)
        }
    }

    fun incrementMissedFrames() {
        missedFrames++
    }

    fun isLost(): Boolean = missedFrames > MAX_MISSED_FRAMES

    fun getPredictedPosition(): RectF {
        if (trackingHistory.size < 2) {
            return face.boundingBox
        }

        // Simple linear prediction based on last two positions
        val current = trackingHistory.last()
        val previous = trackingHistory[trackingHistory.size - 2]

        val deltaX = current.centerX() - previous.centerX()
        val deltaY = current.centerY() - previous.centerY()

        return RectF(
            current.left + deltaX,
            current.top + deltaY,
            current.right + deltaX,
            current.bottom + deltaY
        )
    }
}

class FaceTracker {
    private var nextId = 0
    private val activeTracks = mutableListOf<TrackedFace>()
    private val maxTrackingDistance = 150f
    private val minConfidenceForNewTrack = 0.6f

    fun updateTracks(detectedFaces: List<DetectedFace>): List<TrackedFace> {
        val unmatchedDetections = detectedFaces.toMutableList()
        val updatedTracks = mutableListOf<TrackedFace>()

        // First pass: Match existing tracks with detections
        for (track in activeTracks) {
            var bestMatch: DetectedFace? = null
            var bestDistance = Float.MAX_VALUE

            val predictedPosition = track.getPredictedPosition()

            for (detection in unmatchedDetections) {
                val distance = calculateDistance(predictedPosition, detection.boundingBox)
                if (distance < bestDistance && distance < maxTrackingDistance) {
                    bestDistance = distance
                    bestMatch = detection
                }
            }

            if (bestMatch != null) {
                // Update existing track
                track.updatePosition(bestMatch)
                updatedTracks.add(track)
                unmatchedDetections.remove(bestMatch)
            } else {
                // No match found, increment missed frames
                track.incrementMissedFrames()
                if (!track.isLost()) {
                    updatedTracks.add(track)
                }
            }
        }

        // Second pass: Create new tracks for high-confidence unmatched detections
        for (detection in unmatchedDetections) {
            if (detection.confidence >= minConfidenceForNewTrack) {
                val newTrack = TrackedFace(nextId++, detection)
                newTrack.updatePosition(detection)
                updatedTracks.add(newTrack)
            }
        }

        // Update active tracks
        activeTracks.clear()
        activeTracks.addAll(updatedTracks)

        return updatedTracks
    }

    private fun calculateDistance(rect1: RectF, rect2: RectF): Float {
        val centerX1 = rect1.centerX()
        val centerY1 = rect1.centerY()
        val centerX2 = rect2.centerX()
        val centerY2 = rect2.centerY()

        return sqrt((centerX1 - centerX2) * (centerX1 - centerX2) + (centerY1 - centerY2) * (centerY1 - centerY2))
    }

    fun getActiveTrackCount(): Int = activeTracks.size

    fun clearTracks() {
        activeTracks.clear()
        nextId = 0
    }
}