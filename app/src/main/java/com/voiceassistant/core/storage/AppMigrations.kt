package com.voiceassistant.core.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migrações do [AppDatabase].
 *
 * Ficam junto do banco (e não no módulo de DI) porque descrevem o schema, não a injeção
 * de dependências — e porque precisam ser públicas para o `MigrationTestHelper` alcançá-las.
 *
 * Todo DDL aqui tem que ser idêntico ao que o Room gera (ver os JSON em `app/schemas`): o
 * banco é construído com `fallbackToDestructiveMigration()`, então uma migração
 * divergente **não** dá erro — apaga o banco e recria. Num app de pesquisa isso é perda
 * silenciosa de coleta. `MigrationTest` existe para que isso falhe em teste, não em campo.
 */
object AppMigrations {

    /**
     * v1 → v2: adiciona a tabela `routing_log` (log de pesquisa) **preservando** o
     * histórico de conversas (`chat_messages`).
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `routing_log` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL, `sessionId` TEXT NOT NULL, " +
                    "`questionText` TEXT NOT NULL, `complexityPreFilter` TEXT NOT NULL, " +
                    "`routeDecision` TEXT NOT NULL, `confidenceScore` REAL NOT NULL, " +
                    "`confidenceMethod` TEXT NOT NULL, `finalTier` TEXT NOT NULL, " +
                    "`responseMode` TEXT NOT NULL, `latencyMs` INTEGER NOT NULL, " +
                    "`modelId` TEXT NOT NULL, `connectivity` TEXT NOT NULL)"
            )
        }
    }

    /**
     * v2 → v3: as três tabelas do estudo de elasticidade (doc 06 §3).
     *
     * A `routing_log` **não** é recriada: ALTER TABLE preserva as linhas já coletadas,
     * que são dados de pesquisa. Os defaults usam -1 (a convenção do projeto para
     * "indisponível") em vez de 0, para que as linhas antigas — coletadas antes da
     * instrumentação existir — não apareçam na análise como se tivessem custado zero.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val newColumns = listOf(
                "`deviceId` TEXT NOT NULL DEFAULT ''",
                "`runtime` TEXT",
                "`promptTokens` INTEGER NOT NULL DEFAULT -1",
                "`generatedTokens` INTEGER NOT NULL DEFAULT -1",
                "`reasoningTokens` INTEGER NOT NULL DEFAULT -1",
                "`ttftMs` REAL NOT NULL DEFAULT -1.0",
                "`ingestionMs` REAL NOT NULL DEFAULT -1.0",
                "`generationMs` REAL NOT NULL DEFAULT -1.0",
                "`tokensPerSec` REAL NOT NULL DEFAULT -1.0",
                "`peakProcessRamMb` INTEGER NOT NULL DEFAULT -1",
                "`threads` INTEGER NOT NULL DEFAULT -1",
                "`backends` TEXT",
                "`stopReason` TEXT",
                "`truncated` INTEGER NOT NULL DEFAULT 0",
                "`blockId` TEXT",
                "`runIndex` INTEGER"
            )
            for (column in newColumns) {
                db.execSQL("ALTER TABLE `routing_log` ADD COLUMN $column")
            }

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `model_load_log` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL, `deviceId` TEXT NOT NULL, " +
                    "`modelId` TEXT NOT NULL, `modelSizeBytes` INTEGER NOT NULL, " +
                    "`loadMs` INTEGER NOT NULL, `warmupMs` INTEGER NOT NULL, " +
                    "`outcome` TEXT NOT NULL, `reason` TEXT, `runtime` TEXT NOT NULL, " +
                    "`abi` TEXT NOT NULL, `threads` INTEGER NOT NULL, " +
                    "`contextSize` INTEGER NOT NULL, `backends` TEXT, " +
                    "`vulkanEnabled` INTEGER NOT NULL, `totalRamMb` INTEGER NOT NULL, " +
                    "`availableRamMb` INTEGER NOT NULL)"
            )

            // PRIMARY KEY como restrição de tabela no fim, e não inline na coluna: é a
            // forma que o Room gera (ver 3.json). São equivalentes no SQLite, mas copiar
            // o formato dele elimina qualquer dúvida na validação de schema.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `device_profile` (" +
                    "`deviceId` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                    "`manufacturer` TEXT NOT NULL, `model` TEXT NOT NULL, " +
                    "`deviceName` TEXT NOT NULL, `androidVersion` TEXT NOT NULL, " +
                    "`apiLevel` INTEGER NOT NULL, `soc` TEXT NOT NULL, `abi` TEXT NOT NULL, " +
                    "`totalRamMb` INTEGER NOT NULL, `availableRamMb` INTEGER NOT NULL, " +
                    "`nominalRamGb` INTEGER NOT NULL, `extendedRamGb` INTEGER NOT NULL, " +
                    "`cpuCores` INTEGER NOT NULL, `cpuMaxGhz` REAL NOT NULL, " +
                    "`availableStorageMb` INTEGER NOT NULL, `capturedAt` INTEGER NOT NULL, " +
                    "`notes` TEXT, PRIMARY KEY(`deviceId`))"
            )
        }
    }

    /**
     * v3 → v4: eixo de acurácia na `routing_log` + `questionId`.
     *
     * ALTER TABLE de novo, pelo mesmo motivo: as linhas já coletadas são dados de
     * pesquisa. As colunas nascem nulas nas linhas antigas — o que é correto, porque
     * aquelas inferências realmente não foram graduadas. `isCorrect` usa -1 ("não
     * graduado") e não 0, senão o histórico inteiro entraria na análise como erro.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val newColumns = listOf(
                "`questionId` TEXT",
                "`questionYear` INTEGER",
                "`questionArea` TEXT",
                "`responseText` TEXT NOT NULL DEFAULT ''",
                "`expectedAnswer` TEXT",
                "`predictedAnswer` TEXT",
                "`answerMethod` TEXT",
                "`isCorrect` INTEGER NOT NULL DEFAULT -1"
            )
            for (column in newColumns) {
                db.execSQL("ALTER TABLE `routing_log` ADD COLUMN $column")
            }
        }
    }

    /**
     * v4 → v5: tabela `block_energy`.
     *
     * Só cria tabela nova; a `routing_log` não é tocada, então a coleta já feita
     * sobrevive intacta. Os blocos medidos antes desta versão não têm como ser
     * recuperados — existiam apenas no logcat.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `block_energy` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`blockId` TEXT NOT NULL, `deviceId` TEXT NOT NULL, " +
                    "`modelId` TEXT NOT NULL, `scenario` TEXT NOT NULL, " +
                    "`questions` INTEGER NOT NULL, " +
                    "`chargeStartUah` INTEGER NOT NULL, `chargeEndUah` INTEGER NOT NULL, " +
                    "`energyUahTotal` INTEGER NOT NULL, " +
                    "`energyUahPerQuestion` REAL NOT NULL, " +
                    "`capacityStartPercent` INTEGER NOT NULL, " +
                    "`capacityEndPercent` INTEGER NOT NULL, " +
                    "`tempStartCelsius` REAL NOT NULL, `tempEndCelsius` REAL NOT NULL, " +
                    "`deltaTempCelsius` REAL NOT NULL, " +
                    "`timestampStart` INTEGER NOT NULL, `timestampEnd` INTEGER NOT NULL, " +
                    "`charging` INTEGER NOT NULL, `valid` INTEGER NOT NULL)"
            )
        }
    }

    /**
     * Colunas da POLÍTICA de roteamento (Bloco A, metade b).
     *
     * ALTER TABLE de novo, pelo mesmo motivo das migrações 2→3 e 3→4: a `routing_log`
     * guarda coletas que custaram dias de aparelho, e recriar a tabela as apagaria.
     *
     * O que estas colunas acrescentam, e que nenhuma análise offline consegue dar:
     *  - `policyName` separa as execuções por política, que é o eixo do experimento;
     *  - `preScore`/`cascadeScore` guardam o número que DECIDIU, permitindo refazer a
     *    decisão com outro corte sem recoletar;
     *  - `escalated` distingue escalonamento por POLÍTICA de fallback por FALHA — sem
     *    isso os dois viram a mesma linha de nuvem no log;
     *  - `policyDecisionMs` é o custo da própria decisão. É o número que a Fase 1 não pode
     *    dar: offline não há como saber se o pré-filtro se paga;
     *  - `escalationLatencyMs` é o tempo do segundo tier, que só existe quando escala.
     *
     * Os defaults repetem a convenção do projeto: -1 é "indisponível", nunca 0 — um zero
     * em `preScore` seria lido pela análise como "o modelo pontuou zero", que é uma
     * afirmação, e falsa.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val colunas = listOf(
                "`policyName` TEXT",
                "`preScore` REAL NOT NULL DEFAULT -1.0",
                "`cascadeScore` REAL NOT NULL DEFAULT -1.0",
                "`escalated` INTEGER NOT NULL DEFAULT 0",
                "`policyDecisionMs` INTEGER NOT NULL DEFAULT -1",
                "`escalationLatencyMs` INTEGER NOT NULL DEFAULT -1"
            )
            for (coluna in colunas) {
                db.execSQL("ALTER TABLE `routing_log` ADD COLUMN $coluna")
            }
        }
    }

    /**
     * v6 → v7: o eixo VERTICAL da elasticidade na `chat_messages` (Bloco D parte 1).
     *
     * ALTER TABLE de novo, e aqui o motivo é outro: a `chat_messages` guarda a conversa do
     * aluno. Recriar a tabela apagaria o histórico de quem estivesse usando o aplicativo
     * — não é dado de pesquisa, mas é dado de alguém.
     *
     * A coluna é **nullable sem default**, e não -1 como as numéricas da 5→6: `null` aqui
     * significa "esta mensagem não tem faixa", que é o estado verdadeiro de toda linha
     * anterior a esta versão e também o de qualquer resposta que venha de um tier sem
     * logprobs. Um default de 'DIRETO' afirmaria retroativamente que todas as respostas
     * antigas foram entregues sem ressalva — o que é literalmente verdade na tela de
     * então, mas afirmaria também que **houve uma decisão** de não ressalvar, e não houve.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `responseMode` TEXT")
        }
    }

    /** Todas as migrações, na ordem — passe para o `Room.databaseBuilder`. */
    val ALL: Array<Migration> =
        arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7
        )
}
