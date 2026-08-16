package com.voiceassistant.core.logging

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voiceassistant.core.device.DeviceProfileProvider
import com.voiceassistant.core.model.InferenceSource
import com.voiceassistant.core.model.InferenceTelemetry
import com.voiceassistant.core.model.PromptComplexity
import com.voiceassistant.core.model.TutorMode
import com.voiceassistant.core.storage.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O marco da Fase 3: os CSVs saem e **casam por `device_id`**.
 *
 * Sem esse casamento cada arquivo é uma ilha — não dá para dizer que aquele TTFT foi
 * medido naquele aparelho, nem associar uma falha de carga ao hardware que a causou.
 * O teste escreve pelas mesmas classes que o app usa em produção e confere que a chave
 * atravessa todos os arquivos, inclusive o `answers.csv` de conferência manual.
 */
@RunWith(AndroidJUnit4::class)
class CsvExportTest {

    private lateinit var db: AppDatabase
    private lateinit var logger: RoutingLogger
    private lateinit var deviceProfile: DeviceProfileProvider

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        logger = RoutingLogger(db.routingLogDao(), db.modelLoadLogDao(), db.deviceProfileDao())
        deviceProfile = DeviceProfileProvider(context, db.deviceProfileDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun osTresCsvsCasamPeloDeviceId() = runBlocking {
        val deviceId = deviceProfile.captureAndStore()
        assertTrue("a ficha do aparelho não foi gravada", deviceId != null)

        logger.log(
            sessionId = "s1",
            questionText = "Quanto é 17 x 4?",
            complexity = PromptComplexity.SIMPLE,
            routeDecision = "LOCAL",
            confidence = -1f,
            confidenceMethod = "none",
            finalSource = InferenceSource.LOCAL,
            mode = TutorMode.EXPLAIN,
            latencyMs = 2151,
            modelId = "gemma-4-e2b-it-q4_k_m",
            connectivity = "offline",
            deviceId = deviceId!!,
            telemetry = InferenceTelemetry(
                modelId = "gemma-4-e2b-it-q4_k_m",
                runtime = "llamacpp",
                promptTokens = 33,
                generatedTokens = 2,
                reasoningTokens = 0,
                ttftMs = 2484.0,
                ingestionMs = 2479.0,
                generationMs = 247.0,
                peakProcessRamMb = 2027,
                threads = 4,
                backends = "CPU",
                stopReason = "END_OF_GENERATION"
            ),
            responseText = "Portanto, a resposta correta é C: 68.",
            expectedAnswer = "C"
        )

        logger.logModelLoad(
            ModelLoadLogEntry(
                deviceId = deviceId,
                modelId = "gemma-4-e2b-it-q4_k_m",
                modelSizeBytes = 3_427_880_384L,
                loadMs = 3896,
                warmupMs = 2022,
                outcome = "SUCCESS",
                runtime = "llamacpp",
                abi = "arm64-v8a",
                threads = 4,
                contextSize = 2048,
                backends = "CPU",
                totalRamMb = 11343,
                availableRamMb = 3043
            )
        )

        val csvs = logger.exportAll()
        assertEquals(4, csvs.size)
        csvs.forEach { (name, content) -> Log.i(TAG, "── $name ──\n$content") }

        // Cada arquivo tem cabeçalho + exatamente uma linha de dados.
        csvs.forEach { (name, content) ->
            assertEquals("$name deveria ter 1 linha de dados", 2, content.lines().size)
        }

        // O que realmente importa: a chave atravessa os três arquivos.
        csvs.forEach { (name, content) ->
            assertTrue(
                "device_id '$deviceId' ausente em $name — os CSVs não casam",
                content.lines()[1].contains(deviceId)
            )
        }

        // E os números de hardware chegaram até o CSV, em vez de virarem -1 pelo caminho.
        val routingRow = csvs.getValue("routing_log.csv").lines()[1]
        assertTrue("tokens de prompt ausentes: $routingRow", routingRow.contains(",33,"))
        assertTrue("pico de RAM ausente: $routingRow", routingRow.contains(",2027,"))
        assertTrue("stop reason ausente: $routingRow", routingRow.contains("END_OF_GENERATION"))

        // A resposta na íntegra é o dado bruto da acurácia: sem ela não há o que
        // classificar depois, e a fronteira de Pareto fica só com o eixo de custo.
        assertTrue("resposta ausente: $routingRow", routingRow.contains("a resposta correta é C"))

        val answersRow = csvs.getValue("answers.csv").lines()[1]
        assertTrue("gabarito ausente: $answersRow", answersRow.contains("\"C\""))
        assertTrue("pergunta ausente: $answersRow", answersRow.contains("Quanto é 17 x 4?"))
        assertTrue("resposta ausente: $answersRow", answersRow.contains("a resposta correta é C"))
        // A coluna de classificação manual sai vazia, logo após o gabarito.
        assertTrue("coluna manual deveria estar vazia: $answersRow", answersRow.contains("\"C\",,"))

        // E o CSV não pode trazer palpite automático de alternativa: uma sugestão ao lado
        // da coluna de preenchimento ancora quem classifica e vira resultado por inércia.
        val answersHeader = csvs.getValue("answers.csv").lines()[0]
        assertTrue(
            "o answers.csv não deve sugerir alternativa: $answersHeader",
            !answersHeader.contains("predicted_answer")
        )
    }

    private companion object {
        const val TAG = "CsvExportTest"
    }
}
