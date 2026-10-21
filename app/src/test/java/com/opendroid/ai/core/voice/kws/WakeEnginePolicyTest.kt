package com.opendroid.ai.core.voice.kws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeEnginePolicyTest {

    @Test
    fun `offline engine runs when its model is bundled`() {
        assertEquals(
            WakeEngineKind.OFFLINE_SHERPA,
            WakeEnginePolicy.resolve(WakeEngineKind.OFFLINE_SHERPA, offlineModelAvailable = true)
        )
    }

    @Test
    fun `offline engine with a missing model resolves to no engine - never a silent legacy swap`() {
        assertNull(WakeEnginePolicy.resolve(WakeEngineKind.OFFLINE_SHERPA, offlineModelAvailable = false))
    }

    @Test
    fun `legacy engine is available only as the explicit user choice`() {
        assertEquals(
            WakeEngineKind.LEGACY_SPEECH,
            WakeEnginePolicy.resolve(WakeEngineKind.LEGACY_SPEECH, offlineModelAvailable = false)
        )
        assertEquals(
            WakeEngineKind.LEGACY_SPEECH,
            WakeEnginePolicy.resolve(WakeEngineKind.LEGACY_SPEECH, offlineModelAvailable = true)
        )
    }

    @Test
    fun `settings ids round trip and unknown ids default to offline`() {
        assertEquals(WakeEngineKind.OFFLINE_SHERPA, WakeEngineKind.fromSettingsId("OFFLINE"))
        assertEquals(WakeEngineKind.LEGACY_SPEECH, WakeEngineKind.fromSettingsId("LEGACY"))
        assertEquals(WakeEngineKind.OFFLINE_SHERPA, WakeEngineKind.fromSettingsId(null))
        assertEquals(WakeEngineKind.OFFLINE_SHERPA, WakeEngineKind.fromSettingsId("sherpa"))
    }

    @Test
    fun `status holder publishes state engine and detail`() {
        WakeWordStatus.publish(WakeWordEngineState.Starting, WakeEngineKind.OFFLINE_SHERPA)
        assertEquals(WakeWordEngineState.Starting, WakeWordStatus.info.value.state)
        assertEquals(WakeEngineKind.OFFLINE_SHERPA, WakeWordStatus.info.value.engine)

        WakeWordStatus.publish(
            WakeWordEngineState.Listening, WakeEngineKind.OFFLINE_SHERPA,
            detail = "listening for the default phrase"
        )
        assertEquals(WakeWordEngineState.Listening, WakeWordStatus.info.value.state)
        assertEquals("listening for the default phrase", WakeWordStatus.info.value.detail)

        WakeWordStatus.publishDetail(null)
        assertNull(WakeWordStatus.info.value.detail)
        assertEquals(WakeWordEngineState.Listening, WakeWordStatus.info.value.state)

        WakeWordStatus.publish(
            WakeWordEngineState.Error("model missing"), WakeEngineKind.OFFLINE_SHERPA
        )
        val error = WakeWordStatus.info.value.state as WakeWordEngineState.Error
        assertEquals("model missing", error.message)
    }
}
