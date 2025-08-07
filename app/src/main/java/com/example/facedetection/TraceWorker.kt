package com.example.facedetection

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log

class TraceWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // Simulate a long-running task
            withContext(Dispatchers.IO) {
                Log.d("TraceWorker", "Background task started")
                Thread.sleep(3000) // Simulating work
                Log.d("TraceWorker", "Background task completed successfully")
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("TraceWorker", "Background task failed: ${e.message}", e)
            Result.failure()
        }
    }
}