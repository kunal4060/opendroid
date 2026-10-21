package com.opendroid.ai.widget

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.opendroid.ai.R
import com.opendroid.ai.core.launch.ExternalIntents
import com.opendroid.ai.core.launch.ExternalLaunch
import com.opendroid.ai.data.db.entities.MacroEntity
import com.opendroid.ai.data.models.HabitRoutine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes the dynamic half of the launcher shortcuts: the user's most
 * relevant routines (and, if there is room, macros) as long-press shortcuts
 * that *run the item*, through the same [ExternalLaunch] intents the static
 * shortcuts use.
 *
 * Launchers display at most 4 shortcuts in total and the static set already
 * claims 3 slots, so this publishes at most [MAX_DYNAMIC] — routines first
 * (most recently run, then most recently detected), enabled macros fill any
 * remaining slot. `setDynamicShortcuts` replaces the previous set wholesale,
 * so a deleted or disabled item can never linger as a dead shortcut.
 */
@Singleton
class DynamicShortcutPublisher @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        /** Launchers show ~4 shortcuts total; 3 are static, keep dynamic tight. */
        const val MAX_DYNAMIC = 2
        private const val SHORT_LABEL_LIMIT = 25
        private const val LONG_LABEL_LIMIT = 35
    }

    /** Replaces all dynamic shortcuts with the current best set. */
    fun publish(activeRoutines: List<HabitRoutine>, macros: List<MacroEntity>) {
        val shortcuts = mutableListOf<ShortcutInfoCompat>()

        val routinesByRecency = activeRoutines.sortedWith(
            compareByDescending<HabitRoutine> { it.lastExecutedAt ?: Long.MIN_VALUE }
                .thenByDescending { it.lastDetectedAt }
        )
        for (routine in routinesByRecency) {
            if (shortcuts.size >= MAX_DYNAMIC) break
            shortcuts += ShortcutInfoCompat.Builder(context, "routine_${routine.id}")
                .setShortLabel(routine.name.take(SHORT_LABEL_LIMIT))
                .setLongLabel("Run ${routine.name}".take(LONG_LABEL_LIMIT))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_run))
                .setIntent(ExternalIntents.intent(context, ExternalLaunch.RunRoutine(routine.id)))
                .setRank(shortcuts.size)
                .build()
        }

        for (macro in macros.filter { it.isEnabled && !it.isSystem }) {
            if (shortcuts.size >= MAX_DYNAMIC) break
            shortcuts += ShortcutInfoCompat.Builder(context, "macro_${macro.id}")
                .setShortLabel(macro.name.take(SHORT_LABEL_LIMIT))
                .setLongLabel("Run ${macro.name}".take(LONG_LABEL_LIMIT))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_run))
                .setIntent(ExternalIntents.intent(context, ExternalLaunch.RunMacro(macro.id)))
                .setRank(shortcuts.size)
                .build()
        }

        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }
}
