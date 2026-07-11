package com.voiceassistant.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.voiceassistant.core.logging.RoutingLogDao
import com.voiceassistant.core.storage.AppDatabase
import com.voiceassistant.core.storage.ChatMessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * v1 → v2: adiciona a tabela `routing_log` (log de pesquisa) **preservando** o
 * histórico de conversas (`chat_messages`). DDL idêntico ao gerado pelo Room
 * (ver app/schemas/.../2.json), senão a validação de schema na abertura falharia.
 *
 * Definida em nível de arquivo (não como campo do @Module) porque o Dagger/KSP não
 * valida bem um objeto anônimo dentro de um `@Module object`.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `routing_log` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, `sessionId` TEXT NOT NULL, " +
                "`questionText` TEXT NOT NULL, `complexityPreFilter` TEXT NOT NULL, " +
                "`routeDecision` TEXT NOT NULL, `confidenceScore` REAL NOT NULL, " +
                "`confidenceMethod` TEXT NOT NULL, `finalTier` TEXT NOT NULL, " +
                "`pedagogicalMode` TEXT NOT NULL, `latencyMs` INTEGER NOT NULL, " +
                "`modelId` TEXT NOT NULL, `connectivity` TEXT NOT NULL)"
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .addMigrations(MIGRATION_1_2)
            // Rede de segurança para saltos de versão sem migração (ex.: dev). Migrações
            // reais devem ser adicionadas acima para preservar dados em produção.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideChatMessageDao(db: AppDatabase): ChatMessageDao = db.chatMessageDao()

    @Provides
    fun provideRoutingLogDao(db: AppDatabase): RoutingLogDao = db.routingLogDao()
}
