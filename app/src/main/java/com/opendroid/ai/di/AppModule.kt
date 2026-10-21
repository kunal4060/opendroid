package com.opendroid.ai.di

import android.content.Context
import com.opendroid.ai.core.security.AndroidAppLockStore
import com.opendroid.ai.core.security.AndroidProviderCredentialStore
import com.opendroid.ai.core.security.AndroidSensitiveMemoryStore
import com.opendroid.ai.core.security.AndroidUserProfileStore
import com.opendroid.ai.core.security.AppLockManager
import com.opendroid.ai.core.security.AppLockStore
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.core.security.SensitiveMemoryStore
import com.opendroid.ai.core.security.UserProfileStore
import com.opendroid.ai.core.settings.AndroidAppSettingsStore
import com.opendroid.ai.core.settings.AppSettingsStore
import com.opendroid.ai.accessibility.AndroidCallFlowVerifier
import com.opendroid.ai.accessibility.CallFlowVerifier
import com.opendroid.ai.actions.AndroidMediaPlaybackVerifier
import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.actions.MediaPlaybackVerifier
import com.opendroid.ai.core.agent.ActionSequenceExecutor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context {
        return context
    }

    @Provides
    @Singleton
    fun provideProviderCredentialStore(@ApplicationContext context: Context): ProviderCredentialStore {
        return AndroidProviderCredentialStore(context)
    }

    @Provides
    @Singleton
    fun provideUserProfileStore(@ApplicationContext context: Context): UserProfileStore {
        return AndroidUserProfileStore(context)
    }

    @Provides
    @Singleton
    fun provideSensitiveMemoryStore(@ApplicationContext context: Context): SensitiveMemoryStore {
        return AndroidSensitiveMemoryStore(context)
    }

    @Provides
    @Singleton
    fun provideAppSettingsStore(@ApplicationContext context: Context): AppSettingsStore {
        return AndroidAppSettingsStore(context)
    }

    @Provides
    @Singleton
    fun provideAppLockStore(@ApplicationContext context: Context): AppLockStore {
        return AndroidAppLockStore(context)
    }

    @Provides
    @Singleton
    fun provideAppLockManager(appLockStore: AppLockStore): AppLockManager {
        return AppLockManager(appLockStore)
    }

    @Provides
    @Singleton
    fun provideSocialCredentialStore(@ApplicationContext context: Context): com.opendroid.ai.core.security.SocialCredentialStore {
        return com.opendroid.ai.core.security.AndroidSocialCredentialStore(context)
    }

    @Provides
    @Singleton
    fun provideCallFlowVerifier(): CallFlowVerifier = AndroidCallFlowVerifier()

    @Provides
    @Singleton
    fun provideMediaPlaybackVerifier(): MediaPlaybackVerifier = AndroidMediaPlaybackVerifier()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The one TTS engine for the whole process. OpenDroidService used to
     * construct its own; chat read-aloud (A3) needs the same engine — same
     * ElevenLabs/local pipeline, same settings — so it is provided here as a
     * singleton and injected into both. Being app-scoped, it is no longer
     * destroyed with the service; it lives for the process lifetime.
     */
    @Provides
    @Singleton
    fun provideTextToSpeechEngine(
        @ApplicationContext context: Context,
        settingsRepository: com.opendroid.ai.data.repository.SettingsRepository
    ): com.opendroid.ai.core.voice.TextToSpeechEngine {
        return com.opendroid.ai.core.voice.TextToSpeechEngine(context, settingsRepository)
    }

    @Provides
    @Singleton
    fun provideActionSequenceExecutor(
        actionDispatcher: dagger.Lazy<ActionDispatcher>
    ): ActionSequenceExecutor = ActionSequenceExecutor(
        executeAction = { action, params, context ->
            actionDispatcher.get().execute(action, params, context)
        },
        hasAction = { action -> actionDispatcher.get().hasAction(action) }
    )
}
