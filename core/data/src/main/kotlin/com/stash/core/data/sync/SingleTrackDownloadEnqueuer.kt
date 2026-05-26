package com.stash.core.data.sync

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.stash.core.data.sync.workers.TrackDownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues a one-shot [TrackDownloadWorker] run for a single
 * `download_queue` row. Used by the Failed Downloads viewer to
 * immediately retry a row the user tapped, without waiting for the
 * next scheduled sync.
 *
 * Uses a unique work name keyed by `queueId` so a second tap on the
 * same row coalesces with the in-flight retry instead of queueing two.
 *
 * Concurrency for "Retry all" / "Retry group" comes from the caller
 * fanning out N independent enqueue calls — each one runs its own
 * `TrackDownloadWorker` instance. WorkManager's per-worker queue +
 * the OS scheduler cap concurrency naturally; no semaphore needed
 * here.
 */
@Singleton
class SingleTrackDownloadEnqueuer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun enqueue(queueId: Long) {
        val request = OneTimeWorkRequestBuilder<TrackDownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putLong(TrackDownloadWorker.KEY_QUEUE_ID, queueId)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "single_track_$queueId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
