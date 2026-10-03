package com.voiceassistant.core.storage

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voiceassistant.core.storage.AppMigrations.MIGRATION_1_2
import com.voiceassistant.core.storage.AppMigrations.MIGRATION_2_3
import com.voiceassistant.core.storage.AppMigrations.MIGRATION_3_4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Testa as migrações do Room com dados reais dentro.
 *
 * Por que isso não é opcional aqui: o banco é construído com
 * `fallbackToDestructiveMigration()`, então uma migração malfeita **não** dá erro — ela
 * apaga o banco e recria. Num app de pesquisa isso significa perder coleta em silêncio,
 * e só se descobre quando os dados que deveriam existir não estão lá.
 *
 * Cada teste insere linhas na versão antiga e confere que sobreviveram à migração.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migra2Para3PreservandoAsLinhasJaColetadas() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO routing_log (timestamp, sessionId, questionText, " +
                    "complexityPreFilter, routeDecision, confidenceScore, confidenceMethod, " +
                    "finalTier, pedagogicalMode, latencyMs, modelId, connectivity) VALUES " +
                    "(1700000000000, 's1', 'O que é fotossíntese?', 'SIMPLE', 'LOCAL', " +
                    "-1.0, 'none', 'LOCAL', 'EXPLAIN', 1234, 'gemma3-1b', 'offline')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        db.query("SELECT sessionId, latencyMs FROM routing_log").use { cursor ->
            assertTrue("a linha coletada na v2 sumiu na migração", cursor.moveToFirst())
            assertEquals(1, cursor.count)
            assertEquals("s1", cursor.getString(0))
            assertEquals(1234, cursor.getInt(1))
        }

        // Colunas novas em linhas antigas têm que valer -1 (indisponível), não 0:
        // aquelas perguntas foram respondidas antes da instrumentação existir, e
        // registrá-las como "custo zero" enviesaria qualquer média.
        db.query("SELECT promptTokens, ttftMs, peakProcessRamMb FROM routing_log").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(-1, c.getInt(0))
            assertEquals(-1.0, c.getDouble(1), 0.001)
            assertEquals(-1L, c.getLong(2))
        }
    }

    @Test
    fun migra2Para3CriandoAsTabelasNovasVazias() {
        helper.createDatabase(TEST_DB, 2).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        for (table in listOf("model_load_log", "device_profile")) {
            db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue("tabela $table não foi criada", cursor.moveToFirst())
                assertEquals("tabela $table deveria nascer vazia", 0, cursor.getInt(0))
            }
        }
    }

    /**
     * A cadeia completa a partir da v1 — o caminho de quem tem o app instalado desde
     * antes do log de roteamento existir.
     */
    @Test
    fun migra1Para3PreservandoOHistoricoDeConversas() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO chat_messages (id, sessionId, role, content, timestamp, " +
                    "inferenceSource, latencyMs) VALUES " +
                    "('m1', 's1', 'USER', 'olá', 1700000000000, 'LOCAL', 42)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_1_2, MIGRATION_2_3)

        db.query("SELECT content FROM chat_messages").use { cursor ->
            assertTrue("histórico de conversas perdido na migração", cursor.moveToFirst())
            assertEquals("olá", cursor.getString(0))
        }
    }

    /**
     * v3 → v4: o eixo de acurácia. As linhas da v3 são coleta real (a bateria de energia
     * do Device 1 já rodou nesse schema) — perdê-las custaria refazer horas de medição.
     */
    @Test
    fun migra3Para4PreservandoAsMetricasDeHardware() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                // TODAS as colunas NOT NULL sem default da v3 precisam de valor: o
                // SQLite recusa o INSERT, e o teste morria em `SQLiteConstraintException`
                // antes de exercitar a migração. Estava assim desde que a v3 ganhou as
                // colunas de hardware — passou despercebido porque o `androidTest` compila
                // e roda separado do `test`, e esta suíte não era executada.
                "INSERT INTO routing_log (timestamp, sessionId, questionText, " +
                    "complexityPreFilter, routeDecision, confidenceScore, confidenceMethod, " +
                    "finalTier, pedagogicalMode, latencyMs, modelId, connectivity, " +
                    "deviceId, promptTokens, generatedTokens, reasoningTokens, ttftMs, " +
                    "ingestionMs, generationMs, tokensPerSec, peakProcessRamMb, threads, " +
                    "truncated) " +
                    "VALUES (1700000000000, 'dev1-gemma4-energia3', 'questão', 'SIMPLE', " +
                    "'LOCAL', -1.0, 'none', 'LOCAL', 'EXPLAIN', 21222, 'gemma-4-e2b', " +
                    "'offline', 'dev1', 395, 161, 158, 20543.9, -1.0, -1.0, -1.0, " +
                    "4190, 4, 1)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        db.query("SELECT sessionId, promptTokens, ttftMs, peakProcessRamMb FROM routing_log")
            .use { c ->
                assertTrue("a coleta feita na v3 sumiu na migração", c.moveToFirst())
                assertEquals(1, c.count)
                assertEquals("dev1-gemma4-energia3", c.getString(0))
                assertEquals(395, c.getInt(1))
                assertEquals(20543.9, c.getDouble(2), 0.1)
                assertEquals(4190L, c.getLong(3))
            }

        // A linha antiga não foi graduada — e tem que aparecer assim, não como erro.
        db.query("SELECT isCorrect, expectedAnswer, predictedAnswer, responseText FROM routing_log")
            .use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("linha pré-acurácia tem que ser -1, não 0", -1, c.getInt(0))
                assertTrue("expectedAnswer deveria ser nulo", c.isNull(1))
                assertTrue("predictedAnswer deveria ser nulo", c.isNull(2))
                assertEquals("", c.getString(3))
            }
    }

    /**
     * v5→v6: as colunas da POLÍTICA de roteamento (Bloco A, metade b).
     *
     * A `routing_log` desta versão guarda as coletas das 389 nos quatro aparelhos — dias
     * de aparelho que não se refazem. Recriar a tabela as apagaria, então a migração é por
     * ALTER TABLE, e este teste existe para que uma regressão nisso falhe aqui e não em
     * campo, depois de a coleta já ter sumido.
     */
    @Test
    fun migra5Para6PreservandoAsColetasDasPoliticas() {
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(
                "INSERT INTO routing_log (timestamp, sessionId, questionText, " +
                    "complexityPreFilter, routeDecision, confidenceScore, confidenceMethod, " +
                    "finalTier, pedagogicalMode, latencyMs, modelId, connectivity, " +
                    "deviceId, promptTokens, generatedTokens, reasoningTokens, ttftMs, " +
                    "ingestionMs, generationMs, tokensPerSec, peakProcessRamMb, threads, " +
                    "truncated, responseText, isCorrect, " +
                    "questionId, questionYear, questionArea, expectedAnswer) " +
                    "VALUES (1700000000000, 'dev2-qwen-389', 'questão', 'SIMPLE', " +
                    "'LOCAL', 0.42, 'logprobs_mean', 'LOCAL', 'EXPLAIN', 113000, " +
                    "'qwen2.5-1.5b-instruct-q4_k_m', 'offline', 'ginkgo-f8174388', " +
                    "555, 279, 0, 61393.0, 40495.0, 72000.0, 3.9, 1251, 4, 0, " +
                    "'Resposta: B', -1, " +
                    "'questao_01', 2023, 'LC', 'B')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, AppMigrations.MIGRATION_5_6)

        db.query(
            "SELECT sessionId, questionId, latencyMs, confidenceScore FROM routing_log"
        ).use { c ->
            assertTrue("a coleta das 389 sumiu na migração", c.moveToFirst())
            assertEquals(1, c.count)
            assertEquals("dev2-qwen-389", c.getString(0))
            assertEquals("questao_01", c.getString(1))
            assertEquals(113000L, c.getLong(2))
            assertEquals(0.42, c.getDouble(3), 1e-6)
        }

        // As linhas antigas não têm política — e têm que aparecer assim. Os defaults
        // repetem a convenção do projeto: -1 é "indisponível", nunca 0. Um `preScore` 0
        // seria lido pela análise como "o modelo pontuou zero", que é uma afirmação falsa.
        db.query(
            "SELECT policyName, preScore, cascadeScore, escalated, policyDecisionMs, " +
                "escalationLatencyMs FROM routing_log"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("policyName deveria ser nulo numa linha pré-política", c.isNull(0))
            assertEquals("preScore tem que ser -1, não 0", -1.0, c.getDouble(1), 1e-9)
            assertEquals("cascadeScore tem que ser -1, não 0", -1.0, c.getDouble(2), 1e-9)
            assertEquals(0, c.getInt(3))
            assertEquals(-1L, c.getLong(4))
            assertEquals(-1L, c.getLong(5))
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
