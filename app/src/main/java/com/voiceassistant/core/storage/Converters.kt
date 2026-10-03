package com.voiceassistant.core.storage

import androidx.room.TypeConverter
import com.voiceassistant.core.model.InferenceSource
import com.voiceassistant.core.model.MessageRole
import com.voiceassistant.core.model.ResponseMode

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

    // Nullable nas duas direções, como o par de InferenceSource acima: é o que faz o Room
    // declarar a coluna como TEXT sem NOT NULL, e `null` aqui significa "não há faixa",
    // que é diferente de qualquer um dos três modos.
    @TypeConverter
    fun fromResponseMode(mode: ResponseMode?): String? = mode?.name

    @TypeConverter
    fun toResponseMode(value: String?): ResponseMode? =
        value?.let { ResponseMode.valueOf(it) }
}
