package com.stash.feature.nowplaying

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlin.math.abs
import com.stash.core.model.RepeatMode
import com.stash.core.model.isFlac
import com.stash.core.ui.components.SaveToPlaylistSheet
import com.stash.feature.nowplaying.ui.AmbientBackground
import com.stash.feature.nowplaying.ui.GlowingProgressBar
import com.stash.feature.nowplaying.ui.LiveLyricsBar
import com.stash.feature.nowplaying.ui.LyricsBottomSheet
import com.stash.feature.nowplaying.ui.NowPlayingOptionsSheet
import com.stash.feature.nowplaying.ui.QueueBottomSheet

/**
 * Full-screen Now Playing screen with premium visual design.
 *
 * Displays album art with ambient background, playback controls, progress bar,
 * and track information. Colors are extracted from album art via Palette API.
 *
 * @param onDismiss Callback invoked when the user taps the dismiss (down arrow) button.
 * @param onNavigateToPlaylist Callback invoked with a playlist id when the user taps
 *   one of the "Appears in" playlists; opens that playlist's track list.
 * @param viewModel The [NowPlayingViewModel] provided by Hilt.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NowPlayingScreen(
    onDismiss: () -> Unit,
    onNavigateToPlaylist: (Long) -> Unit = {},
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sleepTimerState by viewModel.sleepTimerState.collectAsStateWithLifecycle()
    val track = uiState.currentTrack
    var showQueue by remember { mutableStateOf(false) }
    var showSaveSheet by remember { mutableStateOf(false) }
    // Overflow ("…") options sheet — holds the per-track actions that used to
    // live as inline icons in the top bar (like, add to playlist, sleep timer,
    // download, flag).
    var showOptions by remember { mutableStateOf(false) }
    // "This song is wrong" dialog — shown when the flag icon is tapped.
    // Decouples the Flag button (which is just "there's a problem") from
    // the action (find a replacement / delete / delete + block).
    var showWrongMatchDialog by remember { mutableStateOf(false) }

    // Scroll state is intentionally not keyed by track — on tall screens
    // content doesn't overflow so scroll stays at 0; on narrow screens the
    // user's scroll position aligns with controls and we want it preserved
    // across track changes.
    val scrollState = rememberScrollState()

    // One-shot Toast confirmation for the "wrong match" flag action. Toast
    // instead of Snackbar so we don't have to restructure the screen into
    // a Scaffold — the full-screen ambient background would fight with
    // Material's Snackbar surface anyway.
    val toastContext = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.userMessages.collect { msg ->
            android.widget.Toast.makeText(toastContext, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // Queue bottom sheet
    if (showQueue) {
        QueueBottomSheet(
            queue = uiState.queue,
            currentIndex = uiState.currentIndex,
            accentColor = uiState.vibrantColor,
            onDismiss = { showQueue = false },
            onTrackClick = { index ->
                viewModel.onSkipToQueueIndex(index)
                showQueue = false
            },
            onRemoveTrack = viewModel::onRemoveFromQueue,
            onMoveTrack = viewModel::onMoveInQueue,
        )
    }

    // Overflow options sheet — opened from the top-bar "…" button. Only
    // shown when a track is loaded (the actions all operate on the current
    // track). Add-to-playlist and Flag hand off to their own sheet/dialog,
    // which is why those open after the options sheet dismisses.
    if (showOptions && track != null) {
        NowPlayingOptionsSheet(
            track = track,
            isLiked = track.stashLikedAt != null,
            isDownloaded = track.isDownloaded,
            accentColor = uiState.vibrantColor,
            sleepTimerState = sleepTimerState,
            onToggleLike = viewModel::onLikeTap,
            onAddToPlaylist = { showSaveSheet = true },
            onSetSleepTimer = viewModel::setSleepTimer,
            onCancelSleepTimer = viewModel::cancelSleepTimer,
            onToggleDownload = viewModel::toggleDownloadForCurrentTrack,
            onFlag = { showWrongMatchDialog = true },
            onDismiss = { showOptions = false },
        )
    }

    // Lyrics bottom sheet — opened by tapping the Lyrics quick-action chip.
    val showLyrics by viewModel.lyricsSheetOpen.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyricsViewState.collectAsStateWithLifecycle()
    val lyricsPositionMs by viewModel.currentPositionMs.collectAsStateWithLifecycle()
    if (showLyrics) {
        LyricsBottomSheet(
            state = lyricsState,
            currentPositionMs = lyricsPositionMs,
            onSeek = viewModel::onLyricsLineSeek,
            onRetry = viewModel::onLyricsRetry,
            onDismiss = viewModel::onDismissLyrics,
        )
    }

    // Save to playlist bottom sheet
    if (showSaveSheet && track != null) {
        SaveToPlaylistSheet(
            playlists = uiState.userPlaylists,
            onSaveToPlaylist = { playlistId ->
                viewModel.saveTrackToPlaylist(track.id, playlistId)
            },
            onCreatePlaylist = { name ->
                viewModel.createPlaylistAndAddTrack(name, track.id)
            },
            onDismiss = { showSaveSheet = false },
        )
    }

    // "This song is wrong" — 3-option dialog triggered by the flag icon.
    // Separated from the icon's direct action so the same entry point
    // covers three very different outcomes: mark for replacement, delete
    // the file, delete + permanently block.
    if (showWrongMatchDialog && track != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showWrongMatchDialog = false },
            title = {
                androidx.compose.material3.Text(
                    text = "What's wrong with this song?",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
            },
            text = {
                androidx.compose.foundation.layout.Column(
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.Text(
                        text = "Pick what should happen to '${track.title}'.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.layout.Spacer(
                        modifier = Modifier.height(4.dp),
                    )
                    if (!track.isFlac) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                viewModel.findInFlacForCurrentTrack()
                                showWrongMatchDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            androidx.compose.material3.Text("Find in FLAC")
                        }
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            viewModel.flagCurrentTrackAsWrongMatch()
                            showWrongMatchDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.material3.Text("Find a better match")
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            viewModel.deleteCurrentTrack(alsoBlock = false)
                            showWrongMatchDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.material3.Text("Delete from library")
                    }
                    androidx.compose.material3.Button(
                        onClick = {
                            viewModel.deleteCurrentTrack(alsoBlock = true)
                            showWrongMatchDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        androidx.compose.material3.Text("Delete and block forever")
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showWrongMatchDialog = false },
                ) {
                    androidx.compose.material3.Text("Cancel")
                }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Ambient animated background behind everything.
        AmbientBackground(
            dominantColor = uiState.dominantColor,
            vibrantColor = uiState.vibrantColor,
            mutedColor = uiState.mutedColor,
            modifier = Modifier.fillMaxSize(),
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .statusBarsPadding()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            // -- Top bar: dismiss, "NOW PLAYING" + album context, overflow "…" --
            TopBar(
                onDismiss = onDismiss,
                onOptionsClick = { showOptions = true },
                hasTrack = uiState.hasTrack,
                contextTitle = track?.album?.takeIf { it.isNotBlank() },
            )

            Spacer(modifier = Modifier.height(24.dp))

            // -- Album art --
            AlbumArtSection(
                albumArtUrl = track?.albumArtUrl,
                albumArtPath = track?.albumArtPath,
                accentColor = uiState.vibrantColor,
                onBitmapLoaded = viewModel::onAlbumArtLoaded,
                onSwipeNext = viewModel::onSkipNext,
                onSwipePrevious = viewModel::onSkipPrevious,
                onSwipeDownDismiss = onDismiss,
            )

            Spacer(modifier = Modifier.height(32.dp))

            // -- Track info --
            // The FLAC badge that used to sit beside the title is gone \u2014 the
            // same quality info already shows on the line below. Long titles
            // marquee-scroll instead of truncating; same for the artist line.
            Text(
                text = track?.title ?: "Not Playing",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(),
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = buildString {
                    if (track != null) {
                        append(track.artist)
                        if (track.album.isNotBlank()) {
                            append(" \u2022 ")
                            append(track.album)
                        }
                    }
                },
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(),
            )

            // Quality line — codec + bit-depth/sample-rate + bitrate, when known.
            // Sized smaller than the artist/album line; degrades gracefully when
            // some fields are missing (returns a partial line, not nothing).
            // When the active MediaItem is sourced from an http(s) URI (Kennyy
            // stream rather than a local file), a small wifi glyph prefixes
            // the line so the user knows playback is using their connection.
            if (track != null) {
                // Prefer the DB's file size; fall back to the size the
                // ViewModel resolved from disk/SAF for the current track
                // (SAF content:// rows never get file_size_bytes backfilled).
                val effectiveSize = track.fileSizeBytes.takeIf { it > 0 }
                    ?: uiState.currentFileSizeBytes
                val qualityText = trackQualityText(track, effectiveSize)
                if (qualityText != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    QualityLine(
                        qualityText = qualityText,
                        isStreaming = uiState.isStreaming,
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // -- Progress bar --
            GlowingProgressBar(
                progress = uiState.progressFraction,
                accentColor = uiState.vibrantColor,
                elapsedMs = uiState.currentPositionMs,
                totalMs = uiState.durationMs,
                onSeek = viewModel::onSeekTo,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(20.dp))

            // -- Playback controls --
            PlaybackControls(
                isPlaying = uiState.isPlaying,
                isBuffering = uiState.isBuffering,
                shuffleEnabled = uiState.shuffleEnabled,
                repeatMode = uiState.repeatMode,
                accentColor = uiState.vibrantColor,
                onPlayPauseClick = viewModel::onPlayPauseClick,
                onSkipNext = viewModel::onSkipNext,
                onSkipPrevious = viewModel::onSkipPrevious,
                onToggleShuffle = viewModel::onToggleShuffle,
                onCycleRepeatMode = viewModel::onCycleRepeatMode,
            )

            // -- Quick actions: Queue / Lyrics --
            // The two "playback context" surfaces, surfaced as a pair of
            // filled chips below the transport controls (per the redesign).
            // Hidden when no track is loaded so they don't open empty sheets.
            if (uiState.hasTrack) {
                Spacer(modifier = Modifier.height(24.dp))
                QuickActionsRow(
                    queueSize = uiState.queueSize,
                    onQueueClick = { showQueue = true },
                    onLyricsClick = viewModel::onShowLyrics,
                )
            }

            // -- Song file path on disk (cleaned for display) --
            val displayedPath = track?.filePath
                ?.takeIf { it.isNotBlank() }
                ?.let(::displayPath)
            if (displayedPath != null) {
                Spacer(modifier = Modifier.height(32.dp))
                FilePathSection(path = displayedPath)
            }

            // -- "Appears in" playlists for the current track --
            if (uiState.containingPlaylists.isNotEmpty()) {
                Spacer(modifier = Modifier.height(if (displayedPath != null) 24.dp else 36.dp))
                PlaylistsSection(
                    playlists = uiState.containingPlaylists,
                    accentColor = uiState.vibrantColor,
                    onPlaylistClick = onNavigateToPlaylist,
                )
            }

                Spacer(modifier = Modifier.height(48.dp))
            }

            LiveLyricsBar(
                state = lyricsState,
                currentPositionMs = lyricsPositionMs,
                accentColor = uiState.vibrantColor,
                onTap = viewModel::onShowLyrics,
            )
        }
    }
}

/**
 * "Appears in" — the list of synced/imported/custom playlists the current
 * track belongs to. Each row opens that playlist's track list. Hidden by the
 * caller when the list is empty (e.g. streaming-only tracks).
 */
@Composable
private fun PlaylistsSection(
    playlists: List<com.stash.core.model.Playlist>,
    accentColor: Color,
    onPlaylistClick: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Appears in",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        playlists.forEach { playlist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onPlaylistClick(playlist.id) }
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!playlist.artUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = playlist.artUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(accentColor.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playlist.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = playlistSubtitle(playlist),
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** Source + track-count subtitle for an "Appears in" playlist row. */
private fun playlistSubtitle(playlist: com.stash.core.model.Playlist): String {
    val source = when (playlist.source) {
        com.stash.core.model.MusicSource.SPOTIFY -> "Spotify"
        com.stash.core.model.MusicSource.YOUTUBE -> "YouTube Music"
        com.stash.core.model.MusicSource.LOCAL -> "Local"
        com.stash.core.model.MusicSource.BOTH -> "Stash"
    }
    val count = playlist.trackCount
    return if (count > 0) "$source • $count songs" else source
}

// ---------------------------------------------------------------------------
// Private composables
// ---------------------------------------------------------------------------

/**
 * Top bar: dismiss arrow on the left, a centered "NOW PLAYING" label with the
 * album/context title beneath it, and an overflow ("…") button on the right
 * that opens the [NowPlayingOptionsSheet].
 *
 * The per-track actions that used to live here as inline icons (like, save,
 * download, flag, lyrics, queue) moved into the options sheet and the
 * quick-actions row as part of the redesign.
 *
 * @param onDismiss      Callback when the down-arrow is tapped.
 * @param onOptionsClick Callback when the overflow "…" button is tapped.
 * @param hasTrack       Whether a track is loaded (overflow button is hidden otherwise).
 * @param contextTitle   Secondary line under "NOW PLAYING" — the album, when known.
 */
@Composable
private fun TopBar(
    onDismiss: () -> Unit,
    onOptionsClick: () -> Unit,
    hasTrack: Boolean,
    contextTitle: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Dismiss",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "NOW PLAYING",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.6f),
                letterSpacing = 1.5.sp,
            )
            if (contextTitle != null) {
                Text(
                    text = contextTitle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Overflow "…" — opens the per-track options sheet. Hidden when no
        // track is loaded (an empty options sheet would have nothing to act
        // on). A spacer keeps the title centred when the button is absent.
        if (hasTrack) {
            IconButton(onClick = onOptionsClick) {
                Icon(
                    imageVector = Icons.Default.MoreHoriz,
                    contentDescription = "More options",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            Spacer(modifier = Modifier.width(48.dp))
        }
    }
}

/**
 * The Queue / Lyrics quick-action chips shown beneath the transport controls.
 * Both open a "playback context" surface — what's coming up next, and what
 * the singer is saying right now.
 */
@Composable
private fun QuickActionsRow(
    queueSize: Int,
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QuickActionChip(
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            label = "Queue",
            contentDescription = "Queue ($queueSize tracks)",
            onClick = onQueueClick,
            modifier = Modifier.weight(1f),
        )
        QuickActionChip(
            icon = Icons.Outlined.Lyrics,
            label = "Lyrics",
            contentDescription = "Lyrics",
            onClick = onLyricsClick,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A single filled quick-action chip (icon + label). */
@Composable
private fun QuickActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(Color.White.copy(alpha = 0.08f))
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

/**
 * Album art with a colored glow shadow behind it.
 *
 * Uses Coil 3 [AsyncImage] to load the art. When the image is loaded
 * successfully, the bitmap is forwarded to [onBitmapLoaded] for palette
 * extraction.
 */
@Composable
private fun AlbumArtSection(
    albumArtUrl: String?,
    albumArtPath: String?,
    accentColor: Color,
    onBitmapLoaded: (android.graphics.Bitmap?) -> Unit,
    onSwipeNext: () -> Unit = {},
    onSwipePrevious: () -> Unit = {},
    onSwipeDownDismiss: () -> Unit = {},
) {
    val context = LocalContext.current
    val artModel = albumArtPath ?: albumArtUrl

    // Swipe the artwork to drive playback: left/right changes track, a
    // downward swipe collapses Now Playing back to the mini player. The
    // dominant axis + a distance threshold decide which action fires on
    // release, so a small wobble while tapping never triggers anything.
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.pointerInput(Unit) {
            val threshold = 64.dp.toPx()
            var totalX = 0f
            var totalY = 0f
            detectDragGestures(
                onDragStart = { totalX = 0f; totalY = 0f },
                onDrag = { change, drag ->
                    totalX += drag.x
                    totalY += drag.y
                    change.consume()
                },
                onDragEnd = {
                    when {
                        abs(totalX) > abs(totalY) && abs(totalX) > threshold ->
                            if (totalX < 0) onSwipeNext() else onSwipePrevious()
                        totalY > threshold && totalY > abs(totalX) ->
                            onSwipeDownDismiss()
                    }
                },
            )
        },
    ) {
        // Glow behind the artwork.
        Box(
            modifier = Modifier
                .size(260.dp)
                .shadow(
                    elevation = 40.dp,
                    shape = RoundedCornerShape(20.dp),
                    ambientColor = accentColor.copy(alpha = 0.25f),
                    spotColor = accentColor.copy(alpha = 0.25f),
                ),
        )

        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(artModel)
                .allowHardware(false) // Required for Palette bitmap extraction.
                .build(),
            contentDescription = "Album art",
            contentScale = ContentScale.Crop,
            onState = { state ->
                if (state is AsyncImagePainter.State.Success) {
                    try {
                        val bitmap = state.result.image.toBitmap()
                        onBitmapLoaded(bitmap)
                    } catch (_: Exception) {
                        // Bitmap extraction failed; palette will use defaults.
                        onBitmapLoaded(null)
                    }
                }
            },
            modifier = Modifier
                .size(280.dp)
                .clip(RoundedCornerShape(20.dp)),
        )
    }
}

/**
 * Playback controls row: shuffle, previous, play/pause, next, repeat.
 *
 * The sleep timer moved into the overflow options sheet as part of the
 * redesign, so this row is back to the five core transport controls, spread
 * edge-to-edge with shuffle and repeat anchoring the ends.
 */
@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    accentColor: Color,
    onPlayPauseClick: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Shuffle
        IconButton(onClick = onToggleShuffle) {
            Icon(
                imageVector = Icons.Default.Shuffle,
                contentDescription = "Shuffle",
                tint = if (shuffleEnabled) accentColor else Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp),
            )
        }

        // Previous
        IconButton(onClick = onSkipPrevious) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "Previous",
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }

        // Play / Pause — large gradient circle. While the track is still
        // resolving/buffering, show a spinner in place of the icon so it
        // doesn't look frozen — but the button STAYS enabled: a slow or hung
        // stream resolve must never lock the user out of pausing (Media3's
        // COMMAND_PLAY_PAUSE is valid during STATE_BUFFERING).
        IconButton(
            onClick = onPlayPauseClick,
            modifier = Modifier
                .size(64.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(accentColor, accentColor.copy(alpha = 0.7f)),
                    ),
                    shape = CircleShape,
                ),
        ) {
            if (isBuffering) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        // Next
        IconButton(onClick = onSkipNext) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "Next",
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }

        // Repeat
        IconButton(onClick = onCycleRepeatMode) {
            Icon(
                imageVector = when (repeatMode) {
                    RepeatMode.ONE -> Icons.Default.RepeatOne
                    else -> Icons.Default.Repeat
                },
                contentDescription = "Repeat",
                tint = when (repeatMode) {
                    RepeatMode.OFF -> Color.White.copy(alpha = 0.6f)
                    else -> accentColor
                },
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Formats a one-line quality summary for the Now Playing screen.
 *
 * Examples:
 *   - All four fields known:  `FLAC · 24-bit/96.0 kHz · 4233 kbps`
 *   - Codec + bitrate only:    `OPUS · 160 kbps`
 *   - Codec only:              `FLAC` (data not yet backfilled)
 *
 * Returns null only when the codec is blank — in that case the caller
 * should render no line at all.
 */
private fun trackQualityText(track: com.stash.core.model.Track, fileSizeBytes: Long): String? {
    // v0.9.13 fix: tracks downloaded before format-tracking was wired (pre-v0.9.11)
    // default to file_format = "opus" regardless of the actual codec — so a FLAC
    // file would render "OPUS · 4233 kbps", which is the source of "every track says
    // Opus" complaints. The Library Health backfill writes correct values from disk
    // but only when the user opens that screen. Cheap interim correction: if the
    // track has a downloaded filePath, prefer the file extension as canonical.
    val extension = track.filePath
        ?.takeIf { it.isNotBlank() }
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase()
    val codec = when (extension) {
        "flac", "alac", "wav", "ape", "tta", "wv", "aiff" -> extension!!.uppercase()
        "opus", "m4a", "mp3", "ogg", "aac" -> extension!!.uppercase()
        else -> track.fileFormat.takeIf { it.isNotBlank() }?.uppercase() ?: return null
    }
    val bitDepth = track.bitsPerSample
    val sampleRateKHz = track.sampleRateHz?.let { it / 1000.0 }
    val bitrate = track.qualityKbps.takeIf { it > 0 }
    val fileSize = fileSizeBytes.takeIf { it > 0 }?.let(::humanReadableSize)
    return buildList {
        add(codec)
        if (bitDepth != null && sampleRateKHz != null) {
            add("${bitDepth}-bit/${"%.1f".format(sampleRateKHz)} kHz")
        }
        if (bitrate != null) add("$bitrate kbps")
        // File size sits right after the bitrate so the two on-disk metrics
        // read together. Only present for downloaded tracks (streams = 0).
        if (fileSize != null) add(fileSize)
        // Flag the YouTube fallback so the user can tell when a track is
        // playing from yt-dlp/InnerTube extraction rather than Qobuz. The
        // codec ("AAC") alone doesn't convey this — Qobuz also serves AAC
        // at MP3_320 tier. Only the streamOrigin field distinguishes the
        // two. We don't badge "via Kennyy" / "via squid" because those
        // are the expected primary sources; only the lossy fallback
        // deserves a callout.
        if (track.streamOrigin == "youtube") add("via YT")
    }.joinToString(" · ")
}

/**
 * Formats a byte count as a compact human-readable size for the quality
 * line, e.g. `28.4 MB` / `912 KB`. Uses binary (1024) units to match what
 * file managers report for the same files on disk.
 */
private fun humanReadableSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%d KB".format(bytes / 1024L)
    else -> "$bytes B"
}

/**
 * Turns the stored file path into something readable. Absolute file paths
 * pass through unchanged. SAF `content://` document URIs are decoded down
 * to just the storage-relative path:
 *
 *   content://com.android.externalstorage.documents/tree/primary%3ASongs/
 *     document/primary%3ASongs%2Fanurag-kulkarni%2F…%2Fpillaa-raa.m4a
 *
 * becomes `Songs/anurag-kulkarni/…/pillaa-raa.m4a` — the `content://…/document/`
 * wrapper and the `primary:` volume prefix are dropped and `%2F`/`%3A` are
 * decoded to `/` and `:`.
 */
private fun displayPath(rawPath: String): String {
    if (!rawPath.startsWith("content://")) return rawPath
    // The document id is the authoritative full path; fall back to the tree id.
    val encoded = rawPath.substringAfterLast("/document/", "")
        .ifEmpty { rawPath.substringAfterLast("/tree/", "") }
        .ifEmpty { return rawPath }
    val decoded = runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }
        .getOrDefault(encoded)
    // Drop the "primary:" / "<volume>:" storage prefix, keep the relative path.
    return decoded.substringAfter(':', decoded)
}

/**
 * Bottom-area "File" block showing the cleaned on-disk path in full. No
 * truncation or ellipsis — the path wraps across as many lines as it needs
 * so the user can read all of it.
 */
@Composable
private fun FilePathSection(path: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "File",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = path,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.6f),
            softWrap = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Renders the codec/bitrate quality line beneath the artist · album row.
 * When [isStreaming] is `true` a small wifi glyph is prefixed so the
 * user can tell at a glance that playback is coming from the network
 * rather than a local file. The icon picks up
 * [MaterialTheme.colorScheme.primary] so it stands out against the
 * white-on-ambient quality text without clashing with the album-art
 * palette.
 *
 * Centered as a Row so the prefix-icon variant stays visually balanced
 * with the icon-less variant — the original `Text(textAlign = Center)`
 * call is preserved when there is nothing to prefix.
 */
@Composable
private fun QualityLine(
    qualityText: String,
    isStreaming: Boolean,
) {
    if (isStreaming) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = "Streaming",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = qualityText,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Text(
            text = qualityText,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    name = "QualityLine — streaming",
    showBackground = true,
    backgroundColor = 0xFF101012,
)
@Composable
private fun PreviewQualityLineStreaming() {
    com.stash.core.ui.theme.StashTheme {
        QualityLine(
            qualityText = "OPUS \u00B7 160 kbps",
            isStreaming = true,
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    name = "QualityLine — local",
    showBackground = true,
    backgroundColor = 0xFF101012,
)
@Composable
private fun PreviewQualityLineLocal() {
    com.stash.core.ui.theme.StashTheme {
        QualityLine(
            qualityText = "FLAC \u00B7 24-bit/96.0 kHz \u00B7 4233 kbps",
            isStreaming = false,
        )
    }
}
