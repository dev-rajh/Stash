package com.stash.data.download.files

import com.stash.core.common.MediaPathSlug

/**
 * Filesystem-safe slug helpers lifted out of [FileOrganizer].
 *
 * v0.9.36: the lyrics-sidecar writer (`:data:lyrics`) needs to derive
 * the same `<artist>/<album>/<title>` directory layout as the download
 * pipeline when writing a `.lrc` next to a SAF-tree audio file.
 *
 * The slug algorithm itself now lives in [MediaPathSlug] (`:core:common`)
 * so that the external-library rescan in `:core:data` — which cannot depend
 * on this download module — reconstructs the exact same paths the download
 * writer produced. This object stays as the established `:data:*` entry point
 * and simply delegates, keeping one definition with no drift.
 */
object FileOrganizerSlugs {

    /**
     * Converts a human-readable string into a filesystem-safe slug. See
     * [MediaPathSlug.slugify] for the exact transformation.
     */
    fun slugify(input: String): String = MediaPathSlug.slugify(input)
}
