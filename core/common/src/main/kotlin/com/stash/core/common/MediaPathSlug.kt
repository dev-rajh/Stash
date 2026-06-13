package com.stash.core.common

/**
 * Canonical filesystem-path slug for the music library's
 * `<artist>/<album>/<title>` on-disk layout.
 *
 * Single source of truth shared by two sides that MUST agree byte-for-byte:
 *  - the download pipeline (`:data:download` [FileOrganizer]) that **writes**
 *    every track to `<artistSlug>/<albumSlug>/<titleSlug>.<ext>`, and
 *  - the external-library rescan (`:core:data` `rescanExternalDownloads`) that
 *    **reconstructs** those same paths to re-link files already on disk after a
 *    reinstall / data-clear (the DB download flags are gone but SAF files
 *    survive).
 *
 * If the two used different slug code the rescan would silently match nothing,
 * so the algorithm lives here once rather than being duplicated across modules.
 *
 * NOTE: deliberately distinct from
 * [com.stash.core.common.extensions.toSlug], which is an *identity/matching*
 * slug (strips diacritics, parentheticals, `feat.`, takes 80) used to compare
 * track names. That transformation is lossy and would not reproduce the paths
 * the download writer actually created — do not swap one for the other.
 */
object MediaPathSlug {

    /**
     * Lowercases, strips characters outside `[a-z0-9\s-]`, collapses runs of
     * whitespace into single hyphens, trims leading/trailing hyphens, and caps
     * the result at 60 characters.
     */
    fun slugify(input: String): String =
        input.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')
            .take(60)
}
