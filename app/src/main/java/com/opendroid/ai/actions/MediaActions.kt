package com.opendroid.ai.actions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import androidx.core.net.toUri
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.service.OpenDroidNotificationListener
import com.opendroid.ai.core.util.DeviceCapabilities
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

@Singleton
class MediaActions @Inject constructor(
    private val playbackVerifier: MediaPlaybackVerifier
) {

    fun getActions(): List<Action> = listOf(
        PlayMusicAction(playbackVerifier),
        PauseMusicAction(),
        ResumeMusicAction(),
        NextTrackAction(),
        PrevTrackAction(),
        SetVolumeMusicAction(),
        PlayYoutubeAction(playbackVerifier),
        TakePhotoAction(),
        RecordVideoAction()
    )

    private class PlayMusicAction(
        private val playbackVerifier: MediaPlaybackVerifier
    ) : Action {
        override val name: String = "PLAY_MUSIC"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val query = params["query"] ?: ""
            val app = params["app"] ?: "spotify"
            return try {
                val normalizedApp = app.lowercase()
                if (normalizedApp !in setOf("spotify", "youtube", "local")) {
                    return ActionResult(false, null, "That music app is not supported.")
                }
                val encQuery = URLEncoder.encode(query, "UTF-8")
                val intent = when (normalizedApp) {
                    "spotify" -> {
                        Intent(Intent.ACTION_VIEW, "spotify:search:$encQuery".toUri()).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                    "youtube" -> {
                        Intent(Intent.ACTION_VIEW, "https://music.youtube.com/search?q=$encQuery".toUri()).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                    else -> {
                        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                            putExtra(MediaStore.EXTRA_MEDIA_TITLE, query)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                }
                if (intent.resolveActivity(context.packageManager) == null) {
                    return ActionResult(false, null, "The requested music app isn't available on this device.")
                }
                context.startActivity(intent)
                if (playbackVerifier.awaitVerifiedPlayback(context, normalizedApp, query)) {
                    ActionResult(true, if (query.isNotEmpty()) "Playing '$query' for you!" else "Music is playing!", null)
                } else {
                    ActionResult(false, null, "The music app opened, but playback could not be verified.")
                }
            } catch (e: Exception) {
                Log.e("PlayMusic", "Music action failed: ${e.javaClass.simpleName}")
                ActionResult(false, null, "Couldn't play that right now. Try again?")
            }
        }
    }

    private class PauseMusicAction : Action {
        override val name: String = "PAUSE_MUSIC"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                // Verify by reading the media session state back — the old
                // code claimed success the instant the key event was sent,
                // even with no active session or the event ignored.
                val before = playbackSnapshot(context)
                    ?: return ActionResult(false, null, "Couldn't check playback state to pause safely.")
                if (!before.hasSession) {
                    return ActionResult(false, null, "Nothing is playing right now.")
                }
                sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
                val paused = awaitPlaybackCondition(context, timeoutMs = 1500) { snapshot ->
                    snapshot.hasSession && !snapshot.anyPlaying
                }
                if (paused) {
                    ActionResult(true, "Music paused!", null)
                } else {
                    ActionResult(false, null, "I sent the pause command, but playback is still going.")
                }
            } catch (e: Exception) {
                Log.e("PauseMusic", "Pause failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't pause the music.")
            }
        }
    }

    private class ResumeMusicAction : Action {
        override val name: String = "RESUME_MUSIC"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val before = playbackSnapshot(context)
                    ?: return ActionResult(false, null, "Couldn't check playback state to resume safely.")
                if (!before.hasSession) {
                    return ActionResult(false, null, "There's no paused playback session to resume.")
                }
                sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PLAY)
                val resumed = awaitPlaybackCondition(context, timeoutMs = 2000) { snapshot ->
                    snapshot.anyPlaying
                }
                if (resumed) {
                    ActionResult(true, "Music resumed!", null)
                } else {
                    ActionResult(false, null, "I sent the play command, but playback didn't start.")
                }
            } catch (e: Exception) {
                Log.e("ResumeMusic", "Resume failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't resume the music.")
            }
        }
    }

    private class NextTrackAction : Action {
        override val name: String = "NEXT_TRACK"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val before = playbackSnapshot(context)
                    ?: return ActionResult(false, null, "Couldn't check playback state to skip safely.")
                if (!before.hasSession) {
                    return ActionResult(false, null, "Nothing is playing right now.")
                }
                sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_NEXT)
                // Verified when a track title appears that wasn't there before.
                val skipped = awaitPlaybackCondition(context, timeoutMs = 2000) { snapshot ->
                    snapshot.titles.isNotEmpty() && snapshot.titles != before.titles
                }
                if (skipped) {
                    ActionResult(true, "Skipped to the next song!", null)
                } else {
                    ActionResult(false, null, "I sent the skip command, but the track didn't change.")
                }
            } catch (e: Exception) {
                Log.e("NextTrack", "Skip failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't skip the track.")
            }
        }
    }

    private class PrevTrackAction : Action {
        override val name: String = "PREV_TRACK"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val before = playbackSnapshot(context)
                    ?: return ActionResult(false, null, "Couldn't check playback state to go back safely.")
                if (!before.hasSession) {
                    return ActionResult(false, null, "Nothing is playing right now.")
                }
                sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                val wentBack = awaitPlaybackCondition(context, timeoutMs = 2000) { snapshot ->
                    snapshot.titles.isNotEmpty() && snapshot.titles != before.titles
                }
                if (wentBack) {
                    ActionResult(true, "Going back to the previous song!", null)
                } else {
                    ActionResult(false, null, "I sent the previous-track command, but the track didn't change.")
                }
            } catch (e: Exception) {
                Log.e("PrevTrack", "Back failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't go back.")
            }
        }
    }

    private class SetVolumeMusicAction : Action {
        override val name: String = "SET_VOLUME_MUSIC"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val level = params["level"]?.toIntOrNull() ?: 50
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return try {
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val targetVolume = (level * maxVolume) / 100
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI)
                ActionResult(true, "Music volume is at $level% now.", null)
            } catch (e: Exception) {
                Log.e("SetVolumeMusic", "Volume failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't change the music volume.")
            }
        }
    }

    private class PlayYoutubeAction(
        private val playbackVerifier: MediaPlaybackVerifier
    ) : Action {
        override val name: String = "PLAY_YOUTUBE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val query = params["query"] ?: ""
            return try {
                val encQuery = URLEncoder.encode(query, "UTF-8")
                val uri = "https://www.youtube.com/results?search_query=$encQuery".toUri()
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.youtube")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    if (playbackVerifier.awaitVerifiedPlayback(context, "youtube", query)) {
                        ActionResult(true, if (query.isNotEmpty()) "Playing '$query' on YouTube!" else "YouTube is playing!", null)
                    } else {
                        ActionResult(false, null, "YouTube opened, but playback could not be verified.")
                    }
                } else {
                    val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                    ActionResult(false, null, "The YouTube app isn't installed; I opened the search in your browser, but playback could not be verified.")
                }
            } catch (e: Exception) {
                Log.e("PlayYoutube", "YouTube action failed: ${e.javaClass.simpleName}")
                ActionResult(false, null, "Couldn't open YouTube right now.")
            }
        }
    }

    private class TakePhotoAction : Action {
        override val name: String = "TAKE_PHOTO"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            // Camera hardware is optional (see AndroidManifest uses-feature).
            if (!DeviceCapabilities.hasCamera(context)) {
                return ActionResult(false, null, "This device doesn't have a camera.")
            }
            val camera = params["camera"] ?: "back"
            return try {
                val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (camera.lowercase() == "front") {
                        putExtra("android.intent.extras.CAMERA_FACING", 1)
                        putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                        putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                    } else {
                        putExtra("android.intent.extras.CAMERA_FACING", 0)
                        putExtra("android.intent.extras.LENS_FACING_BACK", 1)
                    }
                }
                context.startActivity(intent)
                ActionResult(true, "Camera is ready — snap away!", null)
            } catch (e: Exception) {
                Log.e("TakePhoto", "Camera failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open the camera.")
            }
        }
    }

    private class RecordVideoAction : Action {
        override val name: String = "RECORD_VIDEO"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            // Camera hardware is optional (see AndroidManifest uses-feature).
            if (!DeviceCapabilities.hasCamera(context)) {
                return ActionResult(false, null, "This device doesn't have a camera.")
            }
            val camera = params["camera"] ?: "back"
            return try {
                val intent = Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (camera.lowercase() == "front") {
                        putExtra("android.intent.extras.CAMERA_FACING", 1)
                    } else {
                        putExtra("android.intent.extras.CAMERA_FACING", 0)
                    }
                }
                context.startActivity(intent)
                ActionResult(true, "Camera is ready for video!", null)
            } catch (e: Exception) {
                Log.e("RecordVideo", "Video camera failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open the video camera.")
            }
        }
    }

    companion object {
        /** Point-in-time view of the device's active media sessions. */
        private data class PlaybackSnapshot(
            val hasSession: Boolean,
            val anyPlaying: Boolean,
            val titles: Set<String>
        )

        /**
         * Reads the active media sessions (the same listener-component
         * mechanism the playback verifier uses). Returns null when the
         * state cannot be inspected — callers must fail honestly instead
         * of assuming a dispatched key event worked.
         */
        private fun playbackSnapshot(context: Context): PlaybackSnapshot? {
            val sessionManager = context.getSystemService(MediaSessionManager::class.java) ?: return null
            val listenerComponent = ComponentName(context, OpenDroidNotificationListener::class.java)
            val controllers = try {
                sessionManager.getActiveSessions(listenerComponent)
            } catch (_: SecurityException) {
                return null
            } catch (_: RuntimeException) {
                return null
            }
            val titles = controllers.mapNotNull {
                it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            }.toSet()
            val anyPlaying = controllers.any {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            }
            return PlaybackSnapshot(controllers.isNotEmpty(), anyPlaying, titles)
        }

        /** Polls [playbackSnapshot] until [condition] holds or the timeout passes. */
        private suspend fun awaitPlaybackCondition(
            context: Context,
            timeoutMs: Long,
            condition: (PlaybackSnapshot) -> Boolean
        ): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val snapshot = playbackSnapshot(context) ?: return false
                if (condition(snapshot)) return true
                if (System.currentTimeMillis() >= deadline) return false
                delay(150)
            }
        }

        private fun sendMediaKeyEvent(context: Context, keyCode: Int) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val eventTime = SystemClock.uptimeMillis()
            
            val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
            audioManager.dispatchMediaKeyEvent(downEvent)
            
            val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
            audioManager.dispatchMediaKeyEvent(upEvent)
        }
    }
}
