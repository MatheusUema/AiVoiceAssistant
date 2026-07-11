package com.voiceassistant.core.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.voiceassistant.core.logging.RoutingLogDao
import com.voiceassistant.core.logging.RoutingLogEntry
import com.voiceassistant.core.model.ChatMessage

/**
 * Banco de dados Room do aplicativo.
 * Ao incrementar a versão, forneça uma Migration para evitar perda de dados.
 *
 * v2: adiciona a tabela `routing_log` (log de pesquisa de roteamento).
 */
@Database(
    entities = [ChatMessage::class, RoutingLogEntry::class],
    version = 2,
    exportSchema = true   // permite versionamento das migrações em /schemas
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun routingLogDao(): RoutingLogDao

    companion object {
        const val DATABASE_NAME = "voice_assistant.db"
    }
}
