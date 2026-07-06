package com.stash.feature.settings.libraryhealth

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.db.dao.LibraryHealthBucket
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.files.LocalFileOps
import com.stash.core.data.sync.workers.QualityInfoBackfillWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs the Library Health screen.
 *
 * Two responsibilities:
 *  1. Surface the current downloaded-library breakdown (format × kbps band)
 *     so the user can see what they have at a glance and measure format-141
 *     yield empirically when running the MAX-tier experiment.
 *  2. Run a one-time on-device backfill that ffprobes (via
 *     `MediaMetadataRetriever`) every track still sitting at the historical
 *     `file_format = "opus"` / `quality_kbps = 0` defaults, writing the
 *     real values back. After the backfill the breakdown reflects truth.
 */
@HiltViewModel
class LibraryHealthViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val trackDao: TrackDao,
    private val metadataExtractor: AudioDurationExtractor,
    private val localFileOps: LocalFileOps,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryHealthState())
    val state: StateFlow<LibraryHealthState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val buckets = withContext(Dispatchers.IO) {
                runCatching { trackDao.getLibraryHealthBuckets() }
                    .onFailure { Log.w(TAG, "getLibraryHealthBuckets failed", it) }
                    .getOrDefault(emptyList())
            }
            _state.update { it.copy(buckets = buckets) }
        }
    }

    /**
     * Walks every downloaded track that's still at default format/kbps,
     * reads the file's actual codec/bitrate, and writes them to the DB.
     * Idempotent — safe to re-run; rows already populated are skipped by
     * the SQL filter, not by per-row checks.
     */
    fun runBackfill() {
        if (_state.value.backfill is BackfillStatus.Running) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Two passes back-to-back. Pass 1 (format/bitrate) reads from
                // MMR; pass 2 (file size) reads from disk via File.length().
                // Both deduplicated at the DAO level — re-running is idempotent.
                val formatRows = runCatching { trackDao.getRowsNeedingFormatBackfill() }
                    .onFailure { Log.w(TAG, "getRowsNeedingFormatBackfill failed", it) }
                    .getOrDefault(emptyList())
                val sizeRows = runCatching { trackDao.getRowsNeedingSizeBackfill() }
                    .onFailure { Log.w(TAG, "getRowsNeedingSizeBackfill failed", it) }
                    .getOrDefault(emptyList())

                val total = formatRows.size + sizeRows.size
                if (total == 0) {
                    _state.update { it.copy(backfill = BackfillStatus.Done(processed = 0, total = 0)) }
                    return@withContext
                }

                _state.update { it.copy(backfill = BackfillStatus.Running(processed = 0, total = total)) }

                var processed = 0
                var written = 0

                // ── Pass 1: format + bitrate via MMR ────────────────────
                for (row in formatRows) {
                    val meta = metadataExtractor.extract(row.filePath)
                    // Mirror the v0.9.1 download-path fix: write the format
                    // whenever it's known, even when MMR couldn't compute a
                    // bitrate. FLAC is variable-bitrate and MMR routinely
                    // returns 0 for it, so the prior gate `bitrateKbps > 0`
                    // skipped every misclassified-FLAC row — the exact
                    // scenario this backfill exists to fix. Library Health's
                    // bucket UI already renders qualityKbps=0 as `—`.
                    if (meta != null && meta.format != "unknown") {
                        runCatching {
                            trackDao.setFormatAndQuality(
                                trackId = row.id,
                                fileFormat = meta.format,
                                qualityKbps = meta.bitrateKbps,
                            )
                            written++
                        }.onFailure { e ->
                            Log.w(TAG, "setFormatAndQuality failed for trackId=${row.id}", e)
                        }
                    }
                    processed++
                    if (processed % 25 == 0) {
                        _state.update {
                            it.copy(backfill = BackfillStatus.Running(processed = processed, total = total))
                        }
                    }
                }

                // ── Pass 2: file_size_bytes via File.length() ───────────
                // Many older download paths didn't populate this column.
                // Without a real size SUM(file_size_bytes) understates the
                // Home "Storage" stat — typically by ~70-80% when most of
                // the library is legacy rows. Reading directly from disk
                // is cheap (~ microseconds per file) and exact.
                for (row in sizeRows) {
                    val sizeBytes = runCatching { java.io.File(row.filePath).length() }
                        .getOrDefault(0L)
                    if (sizeBytes > 0) {
                        runCatching {
                            trackDao.setFileSize(trackId = row.id, sizeBytes = sizeBytes)
                            written++
                        }.onFailure { e ->
                            Log.w(TAG, "setFileSize failed for trackId=${row.id}", e)
                        }
                    }
                    processed++
                    if (processed % 25 == 0) {
                        _state.update {
                            it.copy(backfill = BackfillStatus.Running(processed = processed, total = total))
                        }
                    }
                }

                Log.i(TAG, "backfill complete: processed=$processed written=$written (format=${formatRows.size}, size=${sizeRows.size})")
                _state.update { it.copy(backfill = BackfillStatus.Done(processed = written, total = total)) }
            }
            refresh()
        }
    }

    /**
     * Scans for files the user replaced out-of-band and re-points the DB at
     * them. Scenario: Stash downloaded `song.m4a`, the user found a better
     * FLAC elsewhere, deleted the m4a and dropped `song.flac` into the same
     * folder under the same base name. The recorded `file_path` now points at
     * a file that no longer exists, so the track is effectively broken.
     *
     * For every downloaded track whose stored path is missing on disk, this
     * looks in the same directory for a sibling with the same base name but a
     * different (audio) extension. When it finds one it rewrites `file_path`,
     * `file_format`, `file_size_bytes`, `quality_kbps`, and — when readable —
     * `sample_rate_hz` / `bits_per_sample`, so the swapped-in file plays and
     * its quality badge reflects the new encode.
     *
     * Runs inline (not as a worker) so the user gets immediate progress; the
     * candidate set is only the missing-file rows, so it's cheap. SAF-aware
     * via [LocalFileOps] — `content://` external-storage libraries are walked
     * with [android.provider.DocumentsContract], not java.io.File.
     */
    fun runRelinkScan() {
        if (_state.value.relink is RelinkStatus.Running) return
        // Flip to Running synchronously, before the IO work, so the button
        // gives instant feedback. The existence-check phase below is the slow
        // part (each SAF file is a content-resolver round-trip), so without
        // this the UI sat on "Scan" for seconds before anything happened.
        _state.update { it.copy(relink = RelinkStatus.Running(processed = 0, total = 0)) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val refs = runCatching { trackDao.getDownloadedFileRefs() }
                    .onFailure { Log.w(TAG, "getDownloadedFileRefs failed", it) }
                    .getOrDefault(emptyList())

                // Phase 1: find downloaded rows whose recorded file is gone
                // (the user replaced it with a different-format file). Progress
                // is reported across this loop since it's where the time goes.
                _state.update { it.copy(relink = RelinkStatus.Running(processed = 0, total = refs.size)) }
                val missing = mutableListOf<Pair<Long, String>>()
                refs.forEachIndexed { i, ref ->
                    val path = ref.filePath
                    if (path != null && !localFileOps.exists(path)) missing += ref.id to path
                    if ((i + 1) % 25 == 0) {
                        _state.update { it.copy(relink = RelinkStatus.Running(processed = i + 1, total = refs.size)) }
                    }
                }

                // Phase 2: relink each missing file to a same-name replacement.
                val relinkedNames = mutableListOf<String>()
                for ((id, oldPath) in missing) {
                    val newPath = localFileOps.findReplacementSibling(oldPath, AUDIO_EXTENSIONS) ?: continue
                    val meta = metadataExtractor.extract(newPath)
                    val format = meta?.format?.takeIf { it.isNotBlank() && it != "unknown" }
                        ?: newPath.substringAfterLast('.', "").lowercase()
                    runCatching {
                        trackDao.relinkReplacedFile(
                            trackId = id,
                            filePath = newPath,
                            fileFormat = format,
                            sizeBytes = localFileOps.sizeBytes(newPath),
                            qualityKbps = meta?.bitrateKbps ?: 0,
                            sampleRateHz = meta?.sampleRateHz,
                            bitsPerSample = meta?.bitsPerSample,
                        )
                        relinkedNames += fileNameOf(newPath)
                    }.onFailure { e -> Log.w(TAG, "relinkReplacedFile failed for trackId=$id", e) }
                }

                Log.i(TAG, "relink scan complete: scanned=${refs.size} relinked=${relinkedNames.size}")
                _state.update {
                    it.copy(
                        relink = RelinkStatus.Done(
                            relinked = relinkedNames.size,
                            scanned = missing.size,
                            relinkedNames = relinkedNames,
                        ),
                    )
                }
            }
            refresh()
        }
    }

    /**
     * Readable file name from a stored path for the post-scan "what changed"
     * list. Handles SAF `content://` document URIs (percent-decoded, then the
     * last path segment) and plain file paths alike.
     */
    private fun fileNameOf(path: String): String {
        val decoded = runCatching { java.net.URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
        return decoded.substringAfterLast('/').substringAfterLast(':')
    }

    /**
     * Enqueues [QualityInfoBackfillWorker] without WorkManager constraints
     * — the user explicitly opted in by tapping the row, so we don't gate
     * on battery state. The worker self-re-enqueues if the library has
     * more than 500 lossless rows missing quality info.
     */
    fun runQualityInfoBackfill() {
        WorkManager.getInstance(appContext).enqueue(
            OneTimeWorkRequestBuilder<QualityInfoBackfillWorker>().build()
        )
    }

    companion object {
        private const val TAG = "LibraryHealthVM"

        /**
         * Audio extensions the relink scan will accept as a replacement,
         * ordered best-first: lossless codecs ahead of lossy ones. The order
         * is also the preference ranking when a folder holds more than one
         * candidate for the same base name.
         */
        private val AUDIO_EXTENSIONS = listOf(
            "flac", "alac", "wav", "aiff", "ape", "tta", "wv",
            "m4a", "aac", "mp3", "ogg", "opus",
        )
    }
}

/**
 * UI state for the Library Health screen. [buckets] is the histogram
 * served by the DAO (already grouped by format + kbps band, sorted by
 * count desc). [backfill] tracks the one-time fixup pass for legacy rows.
 */
data class LibraryHealthState(
    val buckets: List<LibraryHealthBucket> = emptyList(),
    val backfill: BackfillStatus = BackfillStatus.Idle,
    val relink: RelinkStatus = RelinkStatus.Idle,
)

/**
 * Lifecycle of the metadata-backfill action. [Running.processed] /
 * [Running.total] drive the progress indicator; [Done.processed] is the
 * count of rows that actually got new values written (some files are
 * missing on disk and are skipped without erroring).
 */
sealed interface BackfillStatus {
    data object Idle : BackfillStatus
    data class Running(val processed: Int, val total: Int) : BackfillStatus
    data class Done(val processed: Int, val total: Int) : BackfillStatus
}

/**
 * Lifecycle of the "relink replaced files" scan. [Running] drives the
 * progress bar over the missing-file candidate set; [Done.relinked] is how
 * many rows were successfully re-pointed at a same-name replacement file,
 * [Done.scanned] the number of missing-file rows examined.
 */
sealed interface RelinkStatus {
    data object Idle : RelinkStatus
    data class Running(val processed: Int, val total: Int) : RelinkStatus
    data class Done(
        val relinked: Int,
        val scanned: Int,
        val relinkedNames: List<String> = emptyList(),
    ) : RelinkStatus
}
