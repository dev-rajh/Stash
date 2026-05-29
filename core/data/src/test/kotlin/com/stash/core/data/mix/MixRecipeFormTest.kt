package com.stash.core.data.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixRecipeFormTest {
    @Test fun `maps genres moods era discovery to a TAG_GRAPH custom recipe`() {
        val form = MixRecipeForm(
            name = "Late Night Jazz",
            genreTags = setOf("jazz", "soul"),
            moodKeys = setOf("chill"),
            eraStartYear = 1990, eraEndYear = 1999,
            discoveryRatio = 0.85f,
        )
        val r = form.toRecipe(existingId = null)
        assertEquals("Late Night Jazz", r.name)
        assertEquals("jazz,soul", r.includeTagsCsv)
        assertEquals("chill", r.moodKeysCsv)
        assertEquals(1990, r.eraStartYear); assertEquals(1999, r.eraEndYear)
        assertEquals(0.85f, r.discoveryRatio, 0f)
        assertEquals("TAG_GRAPH", r.seedStrategy)
        assertFalse(r.isBuiltin)
        assertTrue(r.isActive)
        assertEquals(0L, r.id)
    }
    @Test fun `preserves id when editing`() {
        val r = MixRecipeForm(name = "X", genreTags = setOf("rock")).toRecipe(existingId = 42L)
        assertEquals(42L, r.id)
    }
    @Test fun `isValid requires a name and at least one genre or mood`() {
        assertFalse(MixRecipeForm(name = "", genreTags = setOf("rock")).isValid)
        assertFalse(MixRecipeForm(name = "X").isValid)
        assertTrue(MixRecipeForm(name = "X", genreTags = setOf("rock")).isValid)
        assertTrue(MixRecipeForm(name = "X", moodKeys = setOf("chill")).isValid)
    }
}
