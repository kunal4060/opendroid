package com.opendroid.ai.core.share

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.opendroid.ai.MainActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Publishes the long-lived dynamic shortcut that lets OpenDroid appear in the
 * Direct Share row of other apps' share sheets (feature C2's optional layer).
 *
 * The shortcut's category must match the `<share-target>` category declared
 * in res/xml/shortcuts.xml; when a user picks this row, MainActivity receives
 * the share exactly as if OpenDroid had been chosen from the plain share
 * sheet — the ACTION_SEND manifest filter and this shortcut are two doors
 * into the same receive path. Without the shortcut the plain entry still
 * works; publication is best-effort and never blocks startup.
 */
object SharingShortcutPublisher {

    const val SHORTCUT_ID = "ask_opendroid_share"
    const val CATEGORY_ASK_AGENT = "com.opendroid.ai.category.ASK_AGENT"

    private val published = AtomicBoolean(false)

    /** Idempotent per process: safe to call from every MainActivity creation. */
    fun publishOnce(context: Context) {
        if (!published.compareAndSet(false, true)) return
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                setClass(context, MainActivity::class.java)
                type = "text/plain"
            }
            val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
                .setShortLabel("Ask OpenDroid")
                .setLongLived(true)
                .setCategories(setOf(CATEGORY_ASK_AGENT))
                .setIntent(intent)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        } catch (e: Exception) {
            // Direct Share is an enhancement; the manifest share target is the
            // feature. Never let a shortcut failure surface as an app failure,
            // but do allow a later launch to retry within this process.
            published.set(false)
        }
    }
}
