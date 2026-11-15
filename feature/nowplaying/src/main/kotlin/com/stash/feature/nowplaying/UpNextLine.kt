package com.stash.feature.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.feature.nowplaying.ui.npGlass
import com.stash.feature.nowplaying.ui.npInk

/** The glass card's height, taken whether or not it shows (see below). */
private val UpNextCardHeight = 44.dp

/**
 * "UP NEXT · Title — Artist" under Now Playing's transport, the mockup's glass card: the label
 * in bold caps, the song beside it, the queue glyph at the end. A tap opens the queue sheet, or
 * the Session sheet in a Listen Together room (its Up next lives there). Empty, but still
 * holding its height, when nothing is coming ([peek] null); see [upNextPeek] and
 * [roomUpNextPeek] for what counts as next under shuffle, repeat, radio and a room.
 *
 * Centred whatever the title's alignment, as the View lyrics bar is: it belongs to the controls
 * above it, not to the title block.
 *
 * One TalkBack node: "Up next: Title by Artist. Opens the queue.", a button.
 */
@Composable
internal fun UpNextLine(
    peek: UpNextPeek?,
    inRoom: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The card's height, taken whether or not it shows: the queue running out on its last song
    // must not grow the art under the listener's eyes. 44 dp: the mockup's glass card, roomy
    // without costing the art a full 48.
    if (peek == null) {
        Box(modifier = modifier.fillMaxWidth().height(UpNextCardHeight))
        return
    }
    val description = upNextDescription(peek, inRoom)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(UpNextCardHeight)
            .clip(RoundedCornerShape(12.dp))
            .npGlass(RoundedCornerShape(12.dp), elevation = 2.dp)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = description }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Up next".uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            ),
            color = npInk().copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "·",
            style = MaterialTheme.typography.labelMedium,
            color = npInk().copy(alpha = 0.4f),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = upNextDetail(peek),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = npInk().copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // The row's description says it all, and where a tap goes.
            modifier = Modifier.weight(1f).clearAndSetSemantics { },
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
            contentDescription = null,
            tint = npInk().copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp),
        )
    }
}
