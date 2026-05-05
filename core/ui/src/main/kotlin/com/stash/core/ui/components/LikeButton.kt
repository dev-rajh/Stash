package com.stash.core.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * v0.9.13: Heart icon for liking a track. Tap fires fan-out to user's
 * default destinations; long-press opens [LikeDestinationSheet] for
 * per-track override. State (filled vs outlined) reflects whether ANY
 * destination is already saved — dedup is per-destination at the
 * dispatcher level.
 *
 * The visible icon is rendered at [size] (default 24.dp) but the
 * clickable surface is expanded to the Material3 minimum interactive
 * size (48.dp) for accessibility / touch-target compliance.
 *
 * @param likedAny     True if the track is saved to at least one
 *                     destination (Stash, Spotify, or YT Music).
 * @param onTap        Fires the default-destination fan-out.
 * @param onLongPress  Opens the per-track override sheet.
 * @param unlikedTint  Icon tint when NOT liked. The liked state always
 *                     uses the theme's `error` (red) so it pops
 *                     against any background — this parameter has no
 *                     effect when [likedAny] is true. Pass `null`
 *                     (default) to use `MaterialTheme.colorScheme.onSurface`.
 * @param size         Visible icon size. Default 24.dp matches Material3
 *                     IconButton. The clickable hit area is always at
 *                     least 48.dp regardless of [size].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LikeButton(
    likedAny: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    unlikedTint: Color? = null,
    size: Dp = 24.dp,
) {
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
                role = Role.Button,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (likedAny) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = if (likedAny) "Unlike" else "Like",
            tint = if (likedAny) {
                MaterialTheme.colorScheme.error
            } else {
                unlikedTint ?: MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.size(size),
        )
    }
}
