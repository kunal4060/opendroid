package com.opendroid.ai.core.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The ExternalLaunch wire format is the contract between everything outside
 * the app (widget, static shortcuts in res/xml/shortcuts.xml, future share /
 * assistant / automation surfaces) and the navigation layer. These tests pin
 * the mapping both ways so a constants rename breaks loudly here instead of
 * silently dead-ending a widget tap.
 */
class ExternalLaunchCodecTest {

    private fun roundTrip(launch: ExternalLaunch): ExternalLaunch? {
        val extras = mutableMapOf<String, String?>(
            ExternalLaunchCodec.EXTRA_KIND to ExternalLaunchCodec.kindOf(launch)
        )
        extras.putAll(ExternalLaunchCodec.extrasOf(launch))
        return ExternalLaunchCodec.parse(ExternalLaunchCodec.ACTION, extras)
    }

    @Test
    fun `every launch kind survives a codec round trip`() {
        val launches = listOf(
            ExternalLaunch.OpenChat,
            ExternalLaunch.VoiceCommand,
            ExternalLaunch.NewChat,
            ExternalLaunch.MorningBriefing,
            ExternalLaunch.OpenRoutines,
            ExternalLaunch.RunRoutine("routine-42"),
            ExternalLaunch.RunMacro("macro-7")
        )
        launches.forEach { launch ->
            assertEquals(launch, roundTrip(launch))
        }
    }

    @Test
    fun `non launch actions are not ours`() {
        assertNull(
            ExternalLaunchCodec.parse(
                "android.intent.action.MAIN",
                mapOf(ExternalLaunchCodec.EXTRA_KIND to ExternalLaunchCodec.KIND_VOICE)
            )
        )
        assertNull(ExternalLaunchCodec.parse(null, emptyMap()))
    }

    @Test
    fun `parameterised kinds without their id degrade to null`() {
        assertNull(
            ExternalLaunchCodec.parse(
                ExternalLaunchCodec.ACTION,
                mapOf(ExternalLaunchCodec.EXTRA_KIND to ExternalLaunchCodec.KIND_RUN_ROUTINE)
            )
        )
        assertNull(
            ExternalLaunchCodec.parse(
                ExternalLaunchCodec.ACTION,
                mapOf(
                    ExternalLaunchCodec.EXTRA_KIND to ExternalLaunchCodec.KIND_RUN_MACRO,
                    ExternalLaunchCodec.EXTRA_MACRO_ID to "  "
                )
            )
        )
    }

    @Test
    fun `unknown kinds are ignored`() {
        assertNull(
            ExternalLaunchCodec.parse(
                ExternalLaunchCodec.ACTION,
                mapOf(ExternalLaunchCodec.EXTRA_KIND to "teleport_home")
            )
        )
    }
}
