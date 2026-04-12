package com.voiceassistant.core.storage

import androidx.room.TypeConverter
import com.voiceassistant.core.model.InferenceSource
import com.voiceassistant.core.model.MessageRole

/**
 * Conversores de tipo para o Room — permitem armazenar enums como Strings no SQLite.
 */
class Converters {

    @TypeConverter
    fun fromMessageRole(role: MessageRole): String = role.name

    @TypeConverter
    fun toMessageRole(value: String): MessageRole = MessageRole.valueOf(value)

    @TypeConverter
    fun fromInferenceSource(source: InferenceSource?): String? = source?.name

    @TypeConverter
    fun toInferenceSource(value: String?): InferenceSource? =
        value?.let { InferenceSource.valueOf(it) }
}
