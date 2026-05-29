package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.StashMixRecipeEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StashMixRecipeDaoDeleteCustomTest {
    private lateinit var db: StashDatabase
    private lateinit var dao: StashMixRecipeDao
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), StashDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.stashMixRecipeDao()
    }
    @After fun tearDown() { db.close() }

    @Test fun `deleteCustom removes a custom recipe`() = runTest {
        val id = dao.insert(StashMixRecipeEntity(name = "My Mix", isBuiltin = false))
        assertNotNull(dao.getById(id))
        dao.deleteCustom(id)
        assertNull(dao.getById(id))
    }
    @Test fun `deleteCustom does NOT delete builtins`() = runTest {
        val id = dao.insert(StashMixRecipeEntity(name = "Deep Cuts", isBuiltin = true))
        dao.deleteCustom(id)
        assertEquals("builtin must survive", "Deep Cuts", dao.getById(id)?.name)
    }
}
