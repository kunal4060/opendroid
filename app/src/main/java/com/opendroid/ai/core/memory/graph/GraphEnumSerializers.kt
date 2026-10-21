package com.opendroid.ai.core.memory.graph

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads the raw enum name from [decoder]. The value must be read exactly
 * once per [deserialize] call: the streaming JSON decoder advances past
 * the string token on read, so a second read lands on the following comma
 * and throws JsonDecodingException. For JSON the value is pulled through
 * the element tree (the documented route for custom serializers);
 * non-JSON decoders keep the plain call.
 */
private fun decodeEnumName(decoder: Decoder): String =
    if (decoder is JsonDecoder) {
        decoder.decodeJsonElement().jsonPrimitive.content
    } else {
        decoder.decodeString()
    }

/**
 * Forward-tolerant serializers for the knowledge-graph payload enums.
 *
 * Node JSON lives in plain memory rows and crosses app versions through
 * backups: a row written by a newer build can carry a category, tier or
 * origin kind this build has never heard of. The default enum serializer
 * throws on such a value, and both graph read paths used to skip the whole
 * node — the memory was still in the database but invisible everywhere.
 * These serializers decode an unknown constant to a safe fallback instead
 * (the same defence-in-depth [com.opendroid.ai.data.repository.MemoryRepository]
 * applies to the memory-type column):
 *
 * - category → [KnowledgeCategory.NOTE_FACT], the generic fact bucket;
 * - tier → [MemoryTier.LONG_TERM]: visible and kept — never SENSITIVE
 *   (would hide the node behind the encrypted-store gates) and never
 *   TEMPORARY (session clears would destroy it);
 * - origin kind → [OriginKind.UNKNOWN], so provenance stays honest
 *   ("recorded, but not a kind this version recognises") instead of
 *   fabricating a plausible creation path.
 *
 * Encoding is unchanged (the constant's name), so the wire format is
 * identical for every value this build knows.
 */
object KnowledgeCategorySerializer : KSerializer<KnowledgeCategory> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("KnowledgeCategory", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: KnowledgeCategory) =
        encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): KnowledgeCategory {
        // Read the token ONCE: the predicate below must not re-read the
        // decoder per constant — the second read would hit the comma after
        // the value and throw JsonDecodingException.
        val name = decodeEnumName(decoder)
        return KnowledgeCategory.values().firstOrNull { it.name == name }
            ?: KnowledgeCategory.NOTE_FACT
    }
}

object MemoryTierSerializer : KSerializer<MemoryTier> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("MemoryTier", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: MemoryTier) =
        encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): MemoryTier {
        // Read the token ONCE: the predicate below must not re-read the
        // decoder per constant — the second read would hit the comma after
        // the value and throw JsonDecodingException.
        val name = decodeEnumName(decoder)
        return MemoryTier.values().firstOrNull { it.name == name }
            ?: MemoryTier.LONG_TERM
    }
}

object OriginKindSerializer : KSerializer<OriginKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("OriginKind", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: OriginKind) =
        encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): OriginKind {
        // Read the token ONCE: the predicate below must not re-read the
        // decoder per constant — the second read would hit the comma after
        // the value and throw JsonDecodingException.
        val name = decodeEnumName(decoder)
        return OriginKind.values().firstOrNull { it.name == name }
            ?: OriginKind.UNKNOWN
    }
}
