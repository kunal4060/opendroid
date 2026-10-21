package com.opendroid.ai.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shape tokens (research-3-design-system.md §5.2).
 *
 * Passed to MaterialTheme in [OpenDroidTheme] so every stock M3 component
 * shares one radius language instead of improvising per screen:
 * cards / AI containers 18dp, dialogs & sheets 24dp, pills stay
 * CircleShape at the call site.
 */
val OpenDroidShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)
