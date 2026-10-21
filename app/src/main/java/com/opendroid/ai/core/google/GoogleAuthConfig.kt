package com.opendroid.ai.core.google

import com.opendroid.ai.BuildConfig

/**
 * D6 — the ONE place Google OAuth configuration is read.
 *
 * The web client ID comes from `BuildConfig.GOOGLE_WEB_CLIENT_ID`, which the
 * build fills from the developer-supplied `GOOGLE_WEB_CLIENT_ID` Gradle
 * property (see app/build.gradle and gradle.properties.example). It is empty
 * in every build whose developer has not created a Google Cloud project —
 * a fully supported state: [GoogleAccountStore]/UI check [isConfigured] and
 * show the honest setup-needed state instead of a dead Connect button.
 *
 * The matching *Android* OAuth client (same Cloud project, package
 * `com.opendroid.aiagent` + the signing certificate's SHA-1) is matched by
 * Google Play services automatically; its ID is never pasted anywhere.
 */
object GoogleAuthConfig {

    /** The OAuth web ("server") client ID for Credential Manager sign-in. */
    val webClientId: String get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()

    /** True when a developer has configured a web client ID for this build. */
    val isConfigured: Boolean get() = isConfiguredValue(webClientId)

    /** Pure gating rule, split out so it is unit-testable without BuildConfig. */
    fun isConfiguredValue(value: String?): Boolean = !value.isNullOrBlank()

    /**
     * A configured value that does not look like a Google client ID at all is
     * almost certainly a paste mistake (e.g. the Android client's ID or a
     * stray secret). The UI warns instead of failing sign-in mysteriously.
     */
    fun looksLikeWebClientId(value: String): Boolean =
        value.trim().endsWith(".apps.googleusercontent.com")
}

/** OAuth scopes D6 uses, and the human labels the UI shows for granted ones. */
object GoogleScopes {

    /** Read + create events on the user's calendars (Google "Sensitive" tier). */
    const val CALENDAR_EVENTS = "https://www.googleapis.com/auth/calendar.events"

    /**
     * Read/search mail (Google "Restricted" tier — verification + CASA for a
     * published app; works for named test users while the Cloud project is in
     * Testing status, with grants expiring after 7 days).
     */
    const val GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly"

    /** Scopes requested at first connect: calendar only (incremental auth). */
    val CONNECT_SCOPES: List<String> = listOf(CALENDAR_EVENTS)

    /** Scopes requested when the user explicitly enables Gmail access. */
    val MAIL_SCOPES: List<String> = listOf(CALENDAR_EVENTS, GMAIL_READONLY)

    fun labelFor(scope: String): String = when (scope) {
        CALENDAR_EVENTS -> "Google Calendar — view and create events"
        GMAIL_READONLY -> "Gmail — read and search your mail (read-only)"
        else -> scope
    }
}
