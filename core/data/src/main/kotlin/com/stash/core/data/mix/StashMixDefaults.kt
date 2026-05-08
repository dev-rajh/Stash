package com.stash.core.data.mix

import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.entity.StashMixRecipeEntity

/**
 * Ships Stash's built-in mix recipes. v0.9.16 expands the surface from
 * the single "Stash Discover" flagship to a three-recipe set that exercises
 * each [com.stash.core.data.mix.MixSeedStrategy]:
 *
 *  - **Daily Discover** — affinity-biased blend of library + new finds
 *    seeded from `artist.getSimilar`.
 *  - **Deep Cuts** — pure-library rediscovery, library-only (no
 *    discovery), surfaces tracks the user used to love but hasn't heard
 *    in a while.
 *  - **First Listen** — pure-discovery, seeded from the user's top tags
 *    via the tag-graph generator. Wider net than Daily Discover.
 *
 * Only seeds when [StashMixRecipeDao.countBuiltins] is zero, so users
 * don't get defaults re-inserted every launch. Upgrades from pre-0.9.16
 * installs that already have the old single-recipe builtin go through
 * `StashApplication.maybeReseedStashMixes` which clears the old set
 * first and then runs this seed.
 */
object StashMixDefaults {

    suspend fun seedIfNeeded(dao: StashMixRecipeDao) {
        if (dao.countBuiltins() > 0) return
        ALL.forEach { dao.insert(it) }
    }

    val ALL: List<StashMixRecipeEntity> = listOf(
        StashMixRecipeEntity(
            name = "Daily Discover",
            description = "Personalized blend of your library + fresh finds.",
            affinityBias = 0.3f,
            freshnessWindowDays = 7,
            discoveryRatio = 0.4f,
            targetLength = 50,
            seedStrategy = "ARTIST_SIMILAR",
            isBuiltin = true,
        ),
        StashMixRecipeEntity(
            name = "Deep Cuts",
            description = "Tracks you used to love that haven't been on rotation.",
            affinityBias = 0.6f,
            freshnessWindowDays = 90,
            discoveryRatio = 0f,
            targetLength = 50,
            seedStrategy = "NONE",
            isBuiltin = true,
        ),
        StashMixRecipeEntity(
            name = "First Listen",
            description = "Tracks you've never heard. Wider net.",
            affinityBias = 0.0f,
            freshnessWindowDays = 14,
            discoveryRatio = 1.0f,
            targetLength = 50,
            seedStrategy = "TAG_GRAPH",
            isBuiltin = true,
        ),
    )
}
