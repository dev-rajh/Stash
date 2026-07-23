package com.stash.core.data.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Environment
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.stash.core.common.constants.StashConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Health of a downloaded row's backing local file, for the repair sweep. */
enum class LocalFileState {
    /** Present and large enough to be real audio — keep as-is. */
    OK,

    /** Present but below the floor (a failed download's garbage body) — delete + un-mark. */
    TOO_SMALL,

    /** Reliably absent (null path, or an internal file that doesn't exist) — un-mark, nothing to delete. */
    MISSING,

    /**
     * Couldn't determine — a SAF document whose provider didn't report a size,
     * or a transient read failure at cold start. The sweep must do NOTHING with
     * these: deleting or un-marking on an ambiguous read would damage a real
     * external-storage library on a flaky boot.
     */
    INCONCLUSIVE,
}

/**
 * SAF-aware helpers for the size, health, and deletion of a local download file.
 *
 * A track's `filePath` can be a plain filesystem path, a `file://` URI, or a
 * `content://` SAF document (when the user picked an external download tree),
 * so every operation branches on the scheme. Plain `File.length()`/`delete()`
 * return 0 / no-op for a `content://` string, which would silently mis-handle
 * SAF downloads — hence this single SAF-aware seam, shared by the playback
 * floor, the download validation gate, and the startup repair sweep.
 */
@Singleton
class LocalFileOps @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Mount-state probe for the volume containing a plain path. Overridable in
     * unit tests (Environment is Android framework). Defaults to "is the
     * volume MOUNTED"; any resolution failure reads as not-mounted so the
     * sweep never acts on a path it can't attribute to a live volume.
     */
    internal var volumeMounted: (File) -> Boolean = { f ->
        try {
            Environment.getExternalStorageState(f) == Environment.MEDIA_MOUNTED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * True when [f]'s absence can be trusted as a real deletion. Internal app
     * storage (`/data/...`) never unmounts, so `exists()` is reliable there.
     * Any other plain path (`/storage/XXXX-XXXX/...` on an SD card, USB-OTG)
     * only counts when its volume is currently mounted: with the card ejected
     * every file on it "doesn't exist", and classifying that as MISSING would
     * un-mark the user's whole library — permanently, since the sweep result
     * sticks after the card is reinserted (issue #98).
     */
    private fun absenceIsReliable(f: File): Boolean =
        // File() normalizes to the platform separator; compare with '/' so the
        // prefix check also holds in JVM unit tests on Windows.
        f.path.replace(File.separatorChar, '/').startsWith("/data/") || volumeMounted(f)

    /** Size of the file behind [path] in bytes; 0 if null/blank/missing/unreadable. */
    fun sizeBytes(path: String?): Long {
        if (path.isNullOrBlank()) return 0L
        return runCatching {
            if (path.startsWith("content://")) {
                DocumentFile.fromSingleUri(context, path.toUri())?.length() ?: 0L
            } else {
                File(plainPath(path)).length()
            }
        }.getOrDefault(0L)
    }

    /**
     * Classifies the file behind [path] against [minBytes], distinguishing a
     * reliably-absent/junk file (safe to act on) from an inconclusive read
     * (must be left alone). Used by the repair sweep, which deletes files —
     * so it errs hard toward [LocalFileState.INCONCLUSIVE] for SAF, whose
     * `exists()`/`length()` can transiently fail at process start.
     */
    fun classify(path: String?, minBytes: Long): LocalFileState {
        if (path.isNullOrBlank()) return LocalFileState.MISSING
        return try {
            if (path.startsWith("content://")) {
                val df = DocumentFile.fromSingleUri(context, path.toUri())
                    ?: return LocalFileState.INCONCLUSIVE
                if (!df.exists()) return LocalFileState.INCONCLUSIVE // SAF exists() is unreliable; don't reset
                val len = df.length()
                when {
                    len in 1 until minBytes -> LocalFileState.TOO_SMALL
                    len >= minBytes -> LocalFileState.OK
                    else -> LocalFileState.INCONCLUSIVE // len <= 0: provider may not report size
                }
            } else {
                val f = File(plainPath(path))
                when {
                    !f.exists() ->
                        if (absenceIsReliable(f)) LocalFileState.MISSING
                        else LocalFileState.INCONCLUSIVE // volume unmounted (e.g. SD ejected) — don't touch
                    f.length() < minBytes -> LocalFileState.TOO_SMALL // includes a 0-byte present file
                    else -> LocalFileState.OK
                }
            }
        } catch (e: Exception) {
            LocalFileState.INCONCLUSIVE // any read error -> do not touch
        }
    }

    /**
     * Validation gate for a just-committed download. Returns true if the file
     * is a real download (>= [StashConstants.MIN_PLAYABLE_LOCAL_BYTES]).
     * Otherwise it's a failed download's garbage body (e.g. a ~274-byte yt-dlp
     * error written to a `.webm`): the file is DELETED and false returned, so
     * the caller routes it as a failure instead of marking the track
     * downloaded. Apply this at EVERY `markAsDownloaded` site so junk can never
     * masquerade as a completed download.
     */
    fun acceptDownloadOrDelete(path: String?): Boolean {
        if (sizeBytes(path) >= StashConstants.MIN_PLAYABLE_LOCAL_BYTES) return true
        delete(path)
        return false
    }

    /**
     * Whether the file behind [path] currently resolves. SAF-aware. Used by
     * the user-initiated relink scan to decide whether a row's recorded file
     * is gone (the user replaced it out-of-band). Unlike [classify], this is
     * NOT used by the destructive repair sweep, so a SAF `exists()` false
     * negative only means "look for a replacement," never "delete" — safe.
     */
    fun exists(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return runCatching {
            if (path.startsWith("content://")) {
                DocumentFile.fromSingleUri(context, path.toUri())?.exists() == true
            } else {
                File(plainPath(path)).exists()
            }
        }.getOrDefault(false)
    }

    /**
     * Finds a replacement file the user swapped in for a now-missing [path]:
     * a sibling in the SAME directory with the SAME base name but a DIFFERENT
     * extension drawn from [audioExtensions] (e.g. `song.m4a` → `song.flac`).
     * SAF-aware — `content://` paths are walked via [DocumentsContract], plain
     * paths via [File].
     *
     * When several candidates exist (e.g. both `.flac` and `.wav`), prefers the
     * one whose extension ranks earliest in [audioExtensions] (lossless first),
     * tie-breaking on the largest file. Returns the new path (a `content://`
     * document URI string for SAF, an absolute path otherwise) or null when
     * nothing matches.
     */
    fun findReplacementSibling(path: String?, audioExtensions: List<String>): String? {
        if (path.isNullOrBlank()) return null
        return runCatching {
            if (path.startsWith("content://")) {
                findSafReplacement(path.toUri(), audioExtensions)
            } else {
                findPlainReplacement(File(plainPath(path)), audioExtensions)
            }
        }.getOrNull()
    }

    private fun findPlainReplacement(oldFile: File, exts: List<String>): String? {
        val dir = oldFile.parentFile ?: return null
        val base = oldFile.nameWithoutExtension
        val oldExt = oldFile.extension.lowercase()
        val candidates = (dir.listFiles() ?: return null).filter { f ->
            f.isFile && f.nameWithoutExtension == base &&
                f.extension.lowercase().let { it != oldExt && it in exts }
        }
        return candidates
            .minWithOrNull(compareBy({ exts.indexOf(it.extension.lowercase()) }, { -it.length() }))
            ?.absolutePath
    }

    private fun findSafReplacement(uri: Uri, exts: List<String>): String? {
        val authority = uri.authority ?: return null
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        if (!docId.contains('/')) return null  // file sits at the tree root with no parent to scan
        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, treeId)

        val lastSegment = docId.substringAfterLast('/')
        val base = lastSegment.substringBeforeLast('.', lastSegment)
        val oldExt = lastSegment.substringAfterLast('.', "").lowercase()
        val parentDocId = docId.substringBeforeLast('/')

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)

        data class Candidate(val docId: String, val ext: String, val size: Long)
        val candidates = mutableListOf<Candidate>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null, null, null,
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            while (c.moveToNext()) {
                val name = c.getString(nameIdx) ?: continue
                val nBase = name.substringBeforeLast('.', name)
                val nExt = name.substringAfterLast('.', "").lowercase()
                if (nBase == base && nExt != oldExt && nExt in exts) {
                    candidates += Candidate(c.getString(idIdx), nExt, c.getLong(sizeIdx))
                }
            }
        }
        val best = candidates
            .minWithOrNull(compareBy({ exts.indexOf(it.ext) }, { -it.size }))
            ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, best.docId).toString()
    }

    /** Best-effort delete of the file behind [path]. No-op on null/blank/missing. */
    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            if (path.startsWith("content://")) {
                DocumentFile.fromSingleUri(context, path.toUri())?.delete()
            } else {
                File(plainPath(path)).delete()
            }
        }
    }

    private fun plainPath(path: String): String =
        if (path.startsWith("file://")) path.toUri().path ?: path.removePrefix("file://") else path
}
