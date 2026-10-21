package com.opendroid.ai.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing tokens (research-3-design-system.md §5.1): a 4dp grid.
 *
 * Screen-level rules from the spec are carried here too, so screens stop
 * improvising margins: horizontal screen margin 20dp, chat transcript
 * inset 16dp, 16dp between messages and 24dp before a reply group,
 * 16dp card padding (20dp for plan/permission cards), 56dp list-row
 * minimum height, 12dp composer bottom padding.
 */
object OpenDroidSpacing {
    val space0: Dp = 0.dp
    val space1: Dp = 4.dp
    val space2: Dp = 8.dp
    val space3: Dp = 12.dp
    val space4: Dp = 16.dp
    val space5: Dp = 20.dp
    val space6: Dp = 24.dp
    val space8: Dp = 32.dp
    val space10: Dp = 40.dp
    val space12: Dp = 48.dp

    /** Horizontal screen margin (§5.1) — one notch wider than the M3 16dp default. */
    val screenHorizontalMargin: Dp = 20.dp

    val chatTranscriptInset: Dp = 16.dp
    val messageGap: Dp = 16.dp
    val replyGroupGap: Dp = 24.dp
    val cardPadding: Dp = 16.dp
    val planCardPadding: Dp = 20.dp
    val listRowMinHeight: Dp = 56.dp
    val composerBottomPadding: Dp = 12.dp
}

/** Horizontal screen margin constant (§5.1): 20dp. */
val ScreenHorizontalMargin: Dp = OpenDroidSpacing.screenHorizontalMargin
