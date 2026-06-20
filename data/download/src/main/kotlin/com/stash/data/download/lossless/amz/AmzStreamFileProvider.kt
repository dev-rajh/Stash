package com.stash.data.download.lossless.amz

import android.content.Context
import android.util.Log
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessUrlDownloader
import com.stash.data.download.lossless.SourceResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decrypt-to-cache seam for amz streaming. amz serves CENC-encrypted CMAF that
 * cannot be progressively streamed (the per-track AES key requires a whole-file
 * `ffmpeg -decryption_key` pass), so to "stream" an amz track we fetch the
 * encrypted bytes, decrypt them to a local FLAC in a dedicated cache dir, and
 * hand the player a `file://` path — it then plays like a downloaded file.
 *
 * Fetch+decrypt is delegated to [LosslessUrlDownloader] (the same slice-3 path
 * the download flow uses: fetch encrypted → `.enc` temp → AmzDecryptor → clear
 * FLAC), so the decrypt logic lives in exactly one place. This provider adds the
 * streaming-only concerns on top: a content-addressed cache (so replays and the
 * next-track prefetch are instant) and LRU size-capped eviction (decrypted FLACs
 * are 17–150 MB; without a cap the dir would grow unbounded).
 *
 * Seamless playback falls out of the existing infra: `PrefetchOrchestrator`
 * already resolves the *next* queue track at 60 %, which runs this decrypt ahead
 * of auto-advance, so only the first tapped amz track waits behind the streaming
 * spinner.
 */
@Singleton
class AmzStreamFileProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloader: LosslessUrlDownloader,
) {
    /** Size cap for the decrypted-stream cache dir. Test seam (see eviction). */
    internal var maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES

    private val cacheDir: File
        get() = File(context.cacheDir, CACHE_SUBDIR)

    /**
     * Return a local, decrypted FLAC [File] for [asin], fetching+decrypting the
     * encrypted CMAF at [encryptedStreamUrl] with [key] on a cache miss. Returns
     * null if the fetch/decrypt fails (caller falls through to the next source).
     */
    suspend fun resolveLocalFile(asin: String, encryptedStreamUrl: String, key: String): File? {
        val dir = cacheDir.apply { mkdirs() }
        val target = File(dir, "$asin.flac")
        if (target.exists() && target.length() > 0) {
            // Touch so a replayed/just-prefetched track survives eviction.
            target.setLastModified(System.currentTimeMillis())
            return target
        }

        val source = SourceResult(
            sourceId = AmzSource.SOURCE_ID,
            downloadUrl = encryptedStreamUrl,
            decryptionKey = key,
            format = AudioFormat(codec = "flac", bitrateKbps = 0, sampleRateHz = 0, bitsPerSample = 0),
            confidence = 1f,
        )
        val result = downloader.download(source, target)
        val file = result.getOrElse { e ->
            Log.w(TAG, "amz stream decrypt failed for $asin: ${e.message}")
            return null
        }
        evictToCap(keep = file)
        return file
    }

    /**
     * Trim the cache dir to [maxCacheBytes], deleting oldest-modified `.flac`
     * files first. [keep] (the just-written current track) is never evicted.
     */
    private fun evictToCap(keep: File) {
        val files = cacheDir.listFiles { f -> f.isFile && f.extension == "flac" }?.toMutableList()
            ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxCacheBytes) return
        files.sortBy { it.lastModified() } // oldest first
        for (f in files) {
            if (total <= maxCacheBytes) break
            if (f.absolutePath == keep.absolutePath) continue
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    companion object {
        private const val TAG = "AmzStreamFileProvider"
        private const val CACHE_SUBDIR = "amz_stream"

        /** 1.5 GB — a handful of hi-res FLACs; throwaway, OS may also evict. */
        private const val DEFAULT_MAX_CACHE_BYTES = 1_500L * 1024 * 1024
    }
}
