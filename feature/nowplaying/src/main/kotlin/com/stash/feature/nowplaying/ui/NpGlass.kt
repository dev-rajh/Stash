package com.stash.feature.nowplaying.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The mockup's glass (`.glass-pill` / `.glass-card`): a translucent fill over a soft ground with
 * a hairline light border and a small shadow. Plain translucency, not Haze — the player sheet's
 * own content is not the scaffold's haze source (a real backdrop blur would frost the screens
 * *behind* the sheet), and the grounds these sit on (the cream wash, the dark ambient) are
 * already soft, so a blur would add nothing the eye could see.
 */

/** White glass for light grounds: the mockup's 55 % fill under a 70 % hairline. */
private val GlassFillLight = Color.White.copy(alpha = 0.55f)

private val GlassRingLight = Color.White.copy(alpha = 0.70f)

/** White glass for the dark ambient: a faint 7 % fill under a 10 % hairline. */
private val GlassFillDark = Color.White.copy(alpha = 0.07f)

private val GlassRingDark = Color.White.copy(alpha = 0.10f)

/**
 * The Now Playing glass on this element: clipped to [shape], the theme's translucent white fill
 * and hairline (dark ambient: fainter), and an optional soft [elevation] shadow. Draw the
 * element's content after it.
 */
@Composable
internal fun Modifier.npGlass(shape: Shape, elevation: Dp = 0.dp): Modifier {
    val light = npIsLight()
    return this
        .shadow(elevation, shape, clip = false)
        .background(if (light) GlassFillLight else GlassFillDark, shape)
        .border(1.dp, if (light) GlassRingLight else GlassRingDark, shape)
}
