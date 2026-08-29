package com.voiceassistant.core.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.voiceassistant.core.logging.BlockEnergyDao
import com.voiceassistant.core.logging.BlockEnergyEntry
import com.voiceassistant.core.logging.DeviceProfileDao
import com.voiceassistant.core.logging.DeviceProfileEntry
import com.voiceassistant.core.logging.ModelLoadLogDao
import com.voiceassistant.core.logging.ModelLoadLogEntry
import com.voiceassistant.core.logging.RoutingLogDao
import com.voiceassistant.core.logging.RoutingLogEntry
import com.voiceassistant.core.model.ChatMessage

/**
 * Banco de dados Room do aplicativo.
 * Ao incrementar a versão, forneça uma Migration para evitar perda de dados.
 *
 * v2: adiciona a tabela `routing_log` (log de pesquisa de roteamento).
 * v3: métricas de hardware na `routing_log` + tabelas `model_load_log` e
 *     `device_profile` — as três tabelas do estudo de elasticidade (doc 06 §3),
 *     casáveis por `deviceId`.
 * v4: eixo de acurácia na `routing_log` (resposta completa, gabarito, letra extraída
 *     e como foi extraída) — o outro eixo da fronteira de Pareto.
 * v5: tabela `block_energy` — a energia por bloco era calculada e só ia para o logcat,
 *     e três blocos da coleta do Device 1 se perderam na rotação do buffer.
 */
@Database(
    entities = [
        ChatMessage::class,
        RoutingLogEntry::class,
        ModelLoadLogEntry::class,
        DeviceProfileEntry::class,
        BlockEnergyEntry::class
    ],
    version = 5,
    exportSchema = true   // permite versionamento das migrações em /schemas
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun routingLogDao(): RoutingLogDao
    abstract fun modelLoadLogDao(): ModelLoadLogDao
    abstract fun deviceProfileDao(): DeviceProfileDao
    abstract fun blockEnergyDao(): BlockEnergyDao

    companion object {
        const val DATABASE_NAME = "voice_assistant.db"
    }
}
