package com.voiceassistant.core.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.voiceassistant.core.model.ChatMessage

/**
 * Banco de dados Room do aplicativo.
 * Ao incrementar a versão, forneça uma Migration para evitar perda de dados.
 */
@Database(
    entities = [ChatMessage::class],
    version = 1,
    exportSchema = true   // permite versionamento das migrações em /schemas
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        const val DATABASE_NAME = "voice_assistant.db"
    }
}
