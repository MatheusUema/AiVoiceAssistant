package com.voiceassistant.di

import android.content.Context
import androidx.room.Room
import com.voiceassistant.core.logging.BlockEnergyDao
import com.voiceassistant.core.logging.DeviceProfileDao
import com.voiceassistant.core.logging.ModelLoadLogDao
import com.voiceassistant.core.logging.RoutingLogDao
import com.voiceassistant.core.storage.AppDatabase
import com.voiceassistant.core.storage.AppMigrations
import com.voiceassistant.core.storage.ChatMessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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
            // As migrações vivem em AppMigrations, junto do banco, e são cobertas por
            // MigrationTest — necessário porque o fallback abaixo apagaria o banco em
            // silêncio se alguma delas divergisse do schema gerado pelo Room.
            .addMigrations(*AppMigrations.ALL)
            // Rede de segurança para saltos de versão sem migração (ex.: dev). Migrações
            // reais devem ser adicionadas acima para preservar dados em produção.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideChatMessageDao(db: AppDatabase): ChatMessageDao = db.chatMessageDao()

    @Provides
    fun provideRoutingLogDao(db: AppDatabase): RoutingLogDao = db.routingLogDao()

    @Provides
    fun provideModelLoadLogDao(db: AppDatabase): ModelLoadLogDao = db.modelLoadLogDao()

    @Provides
    fun provideDeviceProfileDao(db: AppDatabase): DeviceProfileDao = db.deviceProfileDao()

    @Provides
    fun provideBlockEnergyDao(db: AppDatabase): BlockEnergyDao = db.blockEnergyDao()
}
