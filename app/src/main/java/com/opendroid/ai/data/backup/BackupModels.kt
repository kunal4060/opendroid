package com.opendroid.ai.data.backup

import com.opendroid.ai.data.db.entities.ChatSessionEntity
import com.opendroid.ai.data.db.entities.ConversationEntity
import com.opendroid.ai.data.db.entities.HabitRoutineEntity
import com.opendroid.ai.data.db.entities.MacroEntity
import com.opendroid.ai.data.db.entities.MemoryEntity
import com.opendroid.ai.data.db.entities.PlanEntity
import kotlinx.serialization.Serializable

/**
 * The decrypted payload of a backup file (feature B2) — one JSON document,
 * serialised with kotlinx.serialization and carried inside the AES-GCM
 * envelope written by `core/backup/BackupCrypto`.
 *
 * Rows travel as DTOs, not as Room entities and never as a database file:
 * a restore inserts DTOs through the current DAOs, so a backup made by an
 * older DB version restores cleanly into a newer schema (the header's
 * dbVersion gate only refuses backups from the *future*). The knowledge
 * graph needs no special section — it lives in `memories` rows (the
 * `pkg_node_*` JSON rows and the `pkg_relations_index` row), which are
 * copied verbatim like every other memory.
 *
 * Deliberately NOT in the payload: task history, notifications, crash
 * logs, downloaded-model records, habit *events* (raw telemetry), and the
 * social tables — a backup protects the user's knowledge and work, not
 * exhaust data. The profile store (name/DOB) is likewise separate.
 */
@Serializable
data class BackupPayload(
    val formatVersion: Int,
    val exportedAt: Long,
    val appVersion: String,
    val dbVersion: Int,
    val sessions: List<ChatSessionBackup> = emptyList(),
    val conversations: List<ConversationBackup> = emptyList(),
    val memories: List<MemoryBackup> = emptyList(),
    val macros: List<MacroBackup> = emptyList(),
    val plans: List<PlanBackup> = emptyList(),
    val routines: List<RoutineBackup> = emptyList(),
    /** Settings DataStore values, keyed by preference name. Null = section not in this backup. */
    val settings: Map<String, BackupSettingValue>? = null,
    /** AppSettingsStore semantic values. Null = section not in this backup. */
    val appSettings: AppSettingsBackup? = null,
    /** Provider keys + sensitive memories. Null unless the user opted in at export time. */
    val credentials: CredentialsBackup? = null
) {
    /** Section names present in this payload, mirrored into the plaintext file header. */
    fun sections(): List<String> = buildList {
        add(SECTION_CHATS)
        add(SECTION_MEMORIES)
        add(SECTION_MACROS)
        add(SECTION_PLANS)
        add(SECTION_ROUTINES)
        if (settings != null) add(SECTION_SETTINGS)
        if (appSettings != null) add(SECTION_APP_SETTINGS)
        if (credentials != null) add(SECTION_CREDENTIALS)
    }

    fun summary(headerCreatedAt: Long = exportedAt): BackupSummary = BackupSummary(
        createdAt = headerCreatedAt,
        appVersion = appVersion,
        dbVersion = dbVersion,
        sessionCount = sessions.size,
        messageCount = conversations.size,
        memoryCount = memories.size,
        macroCount = macros.size,
        planCount = plans.size,
        routineCount = routines.size,
        includesSettings = settings != null || appSettings != null,
        includesCredentials = credentials != null
    )

    companion object {
        const val SECTION_CHATS = "chats"
        const val SECTION_MEMORIES = "memories"
        const val SECTION_MACROS = "macros"
        const val SECTION_PLANS = "plans"
        const val SECTION_ROUTINES = "routines"
        const val SECTION_SETTINGS = "settings"
        const val SECTION_APP_SETTINGS = "appSettings"
        const val SECTION_CREDENTIALS = "credentials"
    }
}

/** Human-checkable counts of what a backup holds, shown before a restore is confirmed. */
data class BackupSummary(
    val createdAt: Long,
    val appVersion: String,
    val dbVersion: Int,
    val sessionCount: Int,
    val messageCount: Int,
    val memoryCount: Int,
    val macroCount: Int,
    val planCount: Int,
    val routineCount: Int,
    val includesSettings: Boolean,
    val includesCredentials: Boolean
)

@Serializable
data class ChatSessionBackup(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isCurrent: Boolean,
    val providerOverride: String? = null,
    val modelOverride: String? = null
) {
    fun toEntity(): ChatSessionEntity = ChatSessionEntity(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isCurrent = isCurrent,
        providerOverride = providerOverride,
        modelOverride = modelOverride
    )

    companion object {
        fun from(entity: ChatSessionEntity): ChatSessionBackup = ChatSessionBackup(
            id = entity.id,
            title = entity.title,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            isCurrent = entity.isCurrent,
            providerOverride = entity.providerOverride,
            modelOverride = entity.modelOverride
        )
    }
}

@Serializable
data class ConversationBackup(
    val id: String,
    val text: String,
    val sender: String,
    val timestamp: Long,
    val modelBadge: String? = null,
    val contactPickerData: String? = null,
    val sessionId: String,
    // B1 attachment fields (Room v11). Defaulted so pre-v11 backups decode
    // with null attachments, exactly as their rows were stored.
    val imageBase64: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentName: String? = null
) {
    fun toEntity(): ConversationEntity = ConversationEntity(
        id = id,
        text = text,
        sender = sender,
        timestamp = timestamp,
        modelBadge = modelBadge,
        contactPickerData = contactPickerData,
        sessionId = sessionId,
        imageBase64 = imageBase64,
        attachmentMimeType = attachmentMimeType,
        attachmentName = attachmentName
    )

    companion object {
        fun from(entity: ConversationEntity): ConversationBackup = ConversationBackup(
            id = entity.id,
            text = entity.text,
            sender = entity.sender,
            timestamp = entity.timestamp,
            modelBadge = entity.modelBadge,
            contactPickerData = entity.contactPickerData,
            sessionId = entity.sessionId,
            imageBase64 = entity.imageBase64,
            attachmentMimeType = entity.attachmentMimeType,
            attachmentName = entity.attachmentName
        )
    }
}

@Serializable
data class MemoryBackup(
    val key: String,
    val value: String,
    val type: String,
    val timestamp: Long,
    val ttlHours: Int,
    val category: String
) {
    fun toEntity(): MemoryEntity = MemoryEntity(
        key = key,
        value = value,
        type = type,
        timestamp = timestamp,
        ttlHours = ttlHours,
        category = category
    )

    companion object {
        fun from(entity: MemoryEntity): MemoryBackup = MemoryBackup(
            key = entity.key,
            value = entity.value,
            type = entity.type,
            timestamp = entity.timestamp,
            ttlHours = entity.ttlHours,
            category = entity.category
        )
    }
}

@Serializable
data class MacroBackup(
    val id: String,
    val name: String,
    val trigger: String,
    val stepsJson: String,
    val isSystem: Boolean,
    val isEnabled: Boolean
) {
    fun toEntity(): MacroEntity = MacroEntity(
        id = id,
        name = name,
        trigger = trigger,
        stepsJson = stepsJson,
        isSystem = isSystem,
        isEnabled = isEnabled
    )

    companion object {
        fun from(entity: MacroEntity): MacroBackup = MacroBackup(
            id = entity.id,
            name = entity.name,
            trigger = entity.trigger,
            stepsJson = entity.stepsJson,
            isSystem = entity.isSystem,
            isEnabled = entity.isEnabled
        )
    }
}

@Serializable
data class PlanBackup(
    val planId: String,
    val goal: String,
    val estimatedDuration: String,
    val estimatedSteps: Int,
    val stepsJson: String,
    val status: String,
    val createdAt: Long
) {
    fun toEntity(): PlanEntity = PlanEntity(
        planId = planId,
        goal = goal,
        estimatedDuration = estimatedDuration,
        estimatedSteps = estimatedSteps,
        stepsJson = stepsJson,
        status = status,
        createdAt = createdAt
    )

    companion object {
        fun from(entity: PlanEntity): PlanBackup = PlanBackup(
            planId = entity.planId,
            goal = entity.goal,
            estimatedDuration = entity.estimatedDuration,
            estimatedSteps = entity.estimatedSteps,
            stepsJson = entity.stepsJson,
            status = entity.status,
            createdAt = entity.createdAt
        )
    }
}

@Serializable
data class RoutineBackup(
    val id: String,
    val name: String,
    val description: String,
    val triggerLabel: String,
    val triggerCron: String,
    val detectedActionsJson: String,
    val suggestedStepsJson: String,
    val repetitionCount: Int,
    val confidence: Float,
    val status: String,
    val suggestionMessage: String,
    val createdAt: Long,
    val lastDetectedAt: Long,
    val lastExecutedAt: Long? = null,
    val macroId: String? = null
) {
    fun toEntity(): HabitRoutineEntity = HabitRoutineEntity(
        id = id,
        name = name,
        description = description,
        triggerLabel = triggerLabel,
        triggerCron = triggerCron,
        detectedActionsJson = detectedActionsJson,
        suggestedStepsJson = suggestedStepsJson,
        repetitionCount = repetitionCount,
        confidence = confidence,
        status = status,
        suggestionMessage = suggestionMessage,
        createdAt = createdAt,
        lastDetectedAt = lastDetectedAt,
        lastExecutedAt = lastExecutedAt,
        macroId = macroId
    )

    companion object {
        fun from(entity: HabitRoutineEntity): RoutineBackup = RoutineBackup(
            id = entity.id,
            name = entity.name,
            description = entity.description,
            triggerLabel = entity.triggerLabel,
            triggerCron = entity.triggerCron,
            detectedActionsJson = entity.detectedActionsJson,
            suggestedStepsJson = entity.suggestedStepsJson,
            repetitionCount = entity.repetitionCount,
            confidence = entity.confidence,
            status = entity.status,
            suggestionMessage = entity.suggestionMessage,
            createdAt = entity.createdAt,
            lastDetectedAt = entity.lastDetectedAt,
            lastExecutedAt = entity.lastExecutedAt,
            macroId = entity.macroId
        )
    }
}

/**
 * One DataStore preference value, tagged with its kind so a restore can
 * rebuild the correctly-typed `Preferences.Key`. Scalars travel as their
 * canonical string form; string sets travel as a list. This indirection
 * keeps the backup stable even if a value's Kotlin type is an Int today
 * and a Long tomorrow — the kind recorded at export wins at restore.
 */
@Serializable
data class BackupSettingValue(
    val kind: String,
    val scalar: String? = null,
    val stringSet: List<String>? = null
) {
    companion object {
        const val KIND_BOOLEAN = "BOOLEAN"
        const val KIND_INT = "INT"
        const val KIND_LONG = "LONG"
        const val KIND_FLOAT = "FLOAT"
        const val KIND_DOUBLE = "DOUBLE"
        const val KIND_STRING = "STRING"
        const val KIND_STRING_SET = "STRING_SET"

        /** Converts a live DataStore value; throws on a type backups cannot represent. */
        fun fromAny(value: Any): BackupSettingValue = when (value) {
            is Boolean -> BackupSettingValue(KIND_BOOLEAN, scalar = value.toString())
            is Int -> BackupSettingValue(KIND_INT, scalar = value.toString())
            is Long -> BackupSettingValue(KIND_LONG, scalar = value.toString())
            is Float -> BackupSettingValue(KIND_FLOAT, scalar = value.toString())
            is Double -> BackupSettingValue(KIND_DOUBLE, scalar = value.toString())
            is String -> BackupSettingValue(KIND_STRING, scalar = value)
            is Set<*> -> BackupSettingValue(
                KIND_STRING_SET,
                stringSet = value.map { it.toString() }
            )
            else -> throw IllegalArgumentException(
                "Unsupported settings value type for backup: ${value.javaClass.name}"
            )
        }
    }
}

/** The two semantic values of `core/settings/AppSettingsStore`, exported via its read APIs. */
@Serializable
data class AppSettingsBackup(
    val onboardingCompleted: Boolean,
    val huggingFaceLastVerified: String? = null
)

/**
 * Secrets, included only when the user explicitly opted in at export.
 * On restore these are written back *through the stores' own write APIs*,
 * so each value is re-encrypted under the restoring device's Keystore key
 * — the backup never carries device-bound ciphertext.
 */
@Serializable
data class CredentialsBackup(
    val providerApiKeys: Map<String, String> = emptyMap(),
    val elevenLabsApiKey: String? = null,
    val huggingFaceToken: String? = null,
    val sensitiveMemories: Map<String, String> = emptyMap()
)
