package com.stash.data.spotify

import com.stash.core.auth.TokenManager
import com.stash.core.auth.spotify.SpotifyAuthManager
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import io.mockk.mockk
import kotlinx.serialization.json.Json

class SpotifyApiClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = SpotifyApiClient(
        okHttpClient = OkHttpClient(),
        tokenManager = mockk<TokenManager>(relaxed = true),
        spotifyAuthManager = mockk<SpotifyAuthManager>(relaxed = true),
    )

    @Test
    fun `parseLibraryResponse extracts playlists nested inside folders`() {
        val response = json.parseToJsonElement(
            """
            {
              "data": {
                "me": {
                  "libraryV3": {
                    "items": [
                      {
                        "item": {
                          "data": {
                            "__typename": "Playlist",
                            "uri": "spotify:playlist:top-level",
                            "name": "Top Level",
                            "ownerV2": { "data": { "username": "raj" } },
                            "content": { "totalCount": 10 }
                          }
                        }
                      },
                      {
                        "item": {
                          "data": {
                            "__typename": "Folder",
                            "name": "Folder A"
                          },
                          "children": {
                            "items": [
                              {
                                "item": {
                                  "data": {
                                    "__typename": "Playlist",
                                    "uri": "spotify:playlist:child-one",
                                    "name": "Child One",
                                    "ownerV2": { "data": { "username": "friend" } },
                                    "content": { "totalCount": 5 }
                                  }
                                }
                              },
                              {
                                "item": {
                                  "data": {
                                    "__typename": "Playlist",
                                    "uri": "spotify:playlist:child-two",
                                    "name": "Child Two",
                                    "ownerV2": { "data": { "id": "spotify", "name": "Spotify" } },
                                    "content": { "totalCount": 7 }
                                  }
                                }
                              }
                            ]
                          }
                        }
                      }
                    ]
                  }
                }
              }
            }
            """.trimIndent(),
        ).let { it as kotlinx.serialization.json.JsonObject }

        val page = client.parseLibraryResponse(response)
        val playlists = page.playlists

        assertEquals(listOf("top-level", "child-one", "child-two"), playlists.map { it.id })
        assertEquals(listOf("Top Level", "Child One", "Child Two"), playlists.map { it.name })
        assertEquals("spotify", playlists.last().owner.id)
        assertEquals("Spotify", playlists.last().owner.display_name)
        assertEquals(2, page.rawItemCount)
    }

    @Test
    fun `parseLibraryResponse deduplicates repeated nested playlists`() {
        val response = json.parseToJsonElement(
            """
            {
              "data": {
                "me": {
                  "libraryV3": {
                    "items": [
                      {
                        "item": {
                          "data": {
                            "__typename": "Playlist",
                            "uri": "spotify:playlist:dup",
                            "name": "Duplicate",
                            "ownerV2": { "data": { "username": "raj" } }
                          },
                          "children": {
                            "items": [
                              {
                                "item": {
                                  "data": {
                                    "__typename": "Playlist",
                                    "uri": "spotify:playlist:dup",
                                    "name": "Duplicate",
                                    "ownerV2": { "data": { "username": "raj" } }
                                  }
                                }
                              }
                            ]
                          }
                        }
                      }
                    ]
                  }
                }
              }
            }
            """.trimIndent(),
        ).let { it as kotlinx.serialization.json.JsonObject }

        val playlists = client.parseLibraryResponse(response).playlists

        assertEquals(1, playlists.size)
        assertEquals("dup", playlists.single().id)
    }

    @Test
    fun `parseLibraryResponse ignores non playlist library features`() {
        val response = json.parseToJsonElement(
            """
            {
              "data": {
                "me": {
                  "libraryV3": {
                    "items": [
                      {
                        "item": {
                          "data": {
                            "__typename": "CollectionItem",
                            "uri": "spotify:collection:tracks",
                            "name": "Liked Songs"
                          }
                        }
                      }
                    ]
                  }
                }
              }
            }
            """.trimIndent(),
        ).let { it as kotlinx.serialization.json.JsonObject }

        val playlists = client.parseLibraryResponse(response).playlists

        assertEquals(emptyList<String>(), playlists.map { it.id })
        assertNull(playlists.firstOrNull())
    }
}
