package com.stash.feature.nowplaying.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.media.sleep.SleepTimerOption
import com.stash.core.media.sleep.SleepTimerState
import com.stash.core.model.Track

/**
 * Bottom-sheet menu of per-track actions for the currently-playing song,
 * opened from the Now Playing three-dot (overflow) button.
 *
 * Replaces the row of inline icons that used to live in the Now Playing top
 * bar. Only actions that are actually backed by the ViewModel are listed —
 * Like, Add to playlist, Sleep Timer, Download/Remove, and Flag.
 *
 * The Sleep Timer row expands inline into the duration list rather than
 * opening a nested menu, so the whole interaction stays within this sheet.
 *
 * @param track          The song the actions apply to (drives the header + Like/Download state).
 * @param isLiked        Whether the track is in Stash Liked Songs (toggles the Like row label/icon).
 * @param isDownloaded   Whether the track is on disk (toggles the Download row label/icon).
 * @param accentColor    Album-art accent, used for armed/active states.
 * @param sleepTimerState Current sleep-timer status, shown as a subtitle on the Sleep Timer row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingOptionsSheet(
    track: Track,
    isLiked: Boolean,
    isDownloaded: Boolean,
    accentColor: Color,
    sleepTimerState: SleepTimerState,
    radioActive: Boolean,
    onToggleLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onSetSleepTimer: (SleepTimerOption) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onToggleDownload: () -> Unit,
    onStartRadio: () -> Unit,
    onStopRadio: () -> Unit,
    onFlag: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSleepOptions by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            // -- Header: art + title + artist --
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OptionsArt(track)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )

            // -- Like (toggle) --
            OptionRow(
                icon = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                label = if (isLiked) "Remove from Liked Songs" else "Add to Liked Songs",
                iconTint = if (isLiked) accentColor else null,
            ) {
                onToggleLike()
                onDismiss()
            }

            // -- Add to playlist --
            OptionRow(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                label = "Add to playlist",
            ) {
                onAddToPlaylist()
                onDismiss()
            }

            // -- Sleep Timer (expands inline) --
            val timerActive = sleepTimerState !is SleepTimerState.Inactive
            OptionRow(
                icon = Icons.Default.Bedtime,
                label = "Sleep Timer",
                subtitle = sleepTimerSubtitle(sleepTimerState),
                iconTint = if (timerActive) accentColor else null,
            ) {
                showSleepOptions = !showSleepOptions
            }
            AnimatedVisibility(visible = showSleepOptions) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (timerActive) {
                        SleepOption(
                            label = "Turn off",
                            selected = false,
                            accentColor = accentColor,
                        ) {
                            onCancelSleepTimer()
                            showSleepOptions = false
                        }
                    }
                    SleepTimerOption.entries.forEach { option ->
                        SleepOption(
                            label = option.label,
                            selected = sleepOptionMatches(option, sleepTimerState),
                            accentColor = accentColor,
                        ) {
                            onSetSleepTimer(option)
                            showSleepOptions = false
                        }
                    }
                }
            }

            // -- Download (toggle) --
            OptionRow(
                icon = if (isDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                label = if (isDownloaded) "Remove from Download" else "Download",
                iconTint = if (isDownloaded) accentColor else null,
            ) {
                onToggleDownload()
                onDismiss()
            }

            // -- Radio (toggle) --
            OptionRow(
                icon = Icons.Default.Radio,
                label = if (radioActive) "Stop radio" else "Start radio",
                iconTint = if (radioActive) accentColor else null,
            ) {
                if (radioActive) onStopRadio() else onStartRadio()
                onDismiss()
            }

            // -- Flag the song --
            OptionRow(
                icon = Icons.Default.Flag,
                label = "Flag the song",
            ) {
                onFlag()
                onDismiss()
            }
        }
    }
}

/** A single tappable action row: leading icon, label, optional subtitle. */
@Composable
private fun OptionRow(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    iconTint: Color? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Indented duration row shown when the Sleep Timer entry is expanded. */
@Composable
private fun SleepOption(
    label: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 64.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) accentColor else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Active",
                tint = accentColor,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun OptionsArt(track: Track) {
    val artUrl = track.albumArtPath ?: track.albumArtUrl
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (artUrl != null) {
            AsyncImage(artUrl, null, Modifier.size(56.dp), contentScale = ContentScale.Crop)
        } else {
            Icon(
                Icons.Default.MusicNote,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Subtitle for the Sleep Timer row reflecting the current armed state. */
private fun sleepTimerSubtitle(state: SleepTimerState): String? = when (state) {
    is SleepTimerState.Inactive -> null
    is SleepTimerState.EndOfTrack -> "Stops at end of track"
    is SleepTimerState.Timed -> {
        val remainingMs = (state.endAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val totalMinutes = (remainingMs / 60_000L).toInt()
        val seconds = ((remainingMs % 60_000L) / 1_000L).toInt()
        "Stops in %d:%02d".format(totalMinutes, seconds)
    }
}

/**
 * Whether [option] is the one currently armed. Only "End of track" can be
 * matched back from the state; the timed durations all collapse to the same
 * [SleepTimerState.Timed] so they can't be individually re-identified — they
 * simply never show a checkmark.
 */
private fun sleepOptionMatches(option: SleepTimerOption, state: SleepTimerState): Boolean =
    option == SleepTimerOption.END_OF_TRACK && state is SleepTimerState.EndOfTrack
