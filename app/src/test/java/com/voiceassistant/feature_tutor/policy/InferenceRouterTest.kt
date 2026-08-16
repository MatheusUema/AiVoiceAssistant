package com.voiceassistant.feature_tutor.policy

import android.app.Application
import com.voiceassistant.ai_cloud.model.CloudModelConfig
import com.voiceassistant.ai_cloud.service.CloudInferenceService
import com.voiceassistant.ai_local.manager.DeviceCapabilityChecker
import com.voiceassistant.ai_local.manager.LocalModelManager
import com.voiceassistant.ai_local.model.LocalModelConfig
import com.voiceassistant.ai_local.service.LocalInferenceException
import com.voiceassistant.ai_local.service.LocalInferenceService
import com.voiceassistant.ai_server.model.ServerConfig
import com.voiceassistant.ai_server.service.ServerInferenceService
import com.voiceassistant.ai_server.service.ServerResult
import com.voiceassistant.ai_server.service.ServerUnavailableException
import com.voiceassistant.core.device.DeviceProfileProvider
import com.voiceassistant.core.logging.DeviceProfileDao
import com.voiceassistant.core.logging.DeviceProfileEntry
import com.voiceassistant.core.logging.ModelLoadLogDao
import com.voiceassistant.core.logging.ModelLoadLogEntry
import com.voiceassistant.core.logging.RoutingLogDao
import com.voiceassistant.core.logging.RoutingLogEntry
import com.voiceassistant.core.logging.RoutingLogger
import com.voiceassistant.core.model.InferenceRequest
import com.voiceassistant.core.model.InferenceSource
import com.voiceassistant.core.model.PromptComplexity
import com.voiceassistant.core.model.TutorMode
import com.voiceassistant.core.model.UserSettings
import com.voiceassistant.core.network.NetworkMonitor
import com.voiceassistant.core.storage.UserSettingsDataStore
import com.voiceassistant.feature_tutor.prompt.TutorPromptBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Testes para [InferenceRouter.resolveRoute] — função pura sem dependências Android.
 *
 * Cada teste verifica uma regra específica do roteamento offline-first:
 *  1. Privacidade + local → LOCAL
 *  2. Privacidade + sem local → ERROR_PRIVACY
 *  3. Offline + local → LOCAL
 *  4. Offline + sem local → ERROR_OFFLINE
 *  5. Online + complexa + cloud → CLOUD
 *  6. Online + local → LOCAL_WITH_CLOUD_FALLBACK
 *  7. Online + sem local + cloud → CLOUD
 *  8. Nada → ERROR_UNAVAILABLE
 */
class InferenceRouterResolveRouteTest {

    // ── Regra 1: Privacidade + local disponível → LOCAL ───────────────────

    @Test
    fun `privacy mode ON and local available returns LOCAL`() {
        val decision = InferenceRouter.resolveRoute(
            isOnline = true,
            isLocalAvailable = true,
            isServerAvailable = false,
            isCloudAvailable = true,
            complexity = PromptComplexity.SIMPLE,
            privacyMode = true
        )
        assertEquals(RoutingDecision.LOCAL, decision)
    }

    @Test
    fun `privacy mode ignores cloud even for complex prompts`() {
        val decision = InferenceRouter.resolveRoute(
            isOnline = true,
            isLocalAvailable = true,
            isServerAvailable = false,
            isCloudAvailable = true,
            complexity = PromptComplexity.COMPLEX,
            privacyMode = true
        )
        assertEquals(RoutingDecision.LOCAL, decision)
    }

    @Test
    fun `privacy mode uses local even when offline`() {
        val decision = InferenceRouter.resolveRoute(
            isOnline = false,
            isLocalAvailable = true,
            isServerAvailable = false,
            isCloudAvailable = false,
            complexity = PromptComplexity.SIMPLE,
            privacyMode = true
        )
        assertEquals(RoutingDecision.LOCAL, decision)
    }

    // ── Regra 2: Privacidade + sem local → ERROR_PRIVACY ─────────────────

    @Test
    fun `privacy mode without local returns ERROR_PRIVACY`() {
        val decision = InferenceRouter.resolveRoute(
            isOnline = true,
            isLocalAvailable = false,
            isServerAvailable = false,
            isCloudAvailable = true,
            complexity = PromptComplexity.SIMPLE,
            privacyMode = true
        )
        assertEquals(RoutingDecision.ERROR_PRIVACY, decision)
    }

    @Test
    fun `privacy mode without local returns ERROR_PRIVACY even offline`() {
        val decision = InferenceRouter.resolveRoute(
            isOnline = false,
            isLocalAvailable = false,
            isServerAvailable = false,
            isCloudAvailable = false,
            complexity = PromptComplexity.SIMPLE,
            privacyMode = true
        )
        assertEquals(RoutingDecision.ERROR_PRIVACY, decision)
    }

    // ── Regra 3: Offline + local disponível → LOCAL ───────────────────────

    @Test
    fun `offline with local available returns LOCAL`() {
        val decision = InferenceRouter.resolveRoute(
            isOnline = false,
            isLocalAvailable = true,
            isServerAvailable = false,
            isCloudAvailable = false,
            complexity = PromptComplexity.SIMPLE,
            privacyMode = false
        )
        assertEquals(RoutingDecision.LOCAL, decision)
    }

    @Test
    fun `offline with local returns LOCAL even for complex prompts`() {
        val decision = InferenceRouter.resolveRoute(
            isOnline = false,
            isLocalAvailable = true,
            isServerAvailable = false,
            isCloudAvailable = false,
            complexity = PromptComplexity.COMPLEX,
            privacyMode = false
        )
        assertEquals(RoutingDecision.LOCAL, decision)
    }

    // ── Regra 4: Offline + sem local → ERROR_OFFLINE ──────────────────────

    @Test
    fun `offline without local returns ERROR_OFFLINE`() {
        val decision = InferenceRouter.resolveRoute(
            isOnline = false,
            isLocalAvailable = false,
            isServerAvailable = false,
            isCloudAvailable = false,
            complexity = PromptComplexity.SIMPLE,
            privacyMode = false
        )
        assertEquals(RoutingDecision.ERROR_OFFLINE, decision)
    }

    // ── Regra 5: Online + complexa + cloud → CLOUD ───────────────────────

    @Test
    fun `online complex prompt with cloud returns CLOUD`() {
        val decision = InferenceRouter.resolveRoute(
            isOnline = true,
            isLocalAvailable = true,
            isServerAvailable = false,
            isCloudAvailable = true,
            complexity = PromptComplexity.COMPLEX,
            privacyMode = false
        )
        assertEquals(RoutingDecision.CLOUD, decision)
    }

    @Test
    fun `online complex prompt without cloud uses LOCAL`() {
        // Sem cloud disponível, não há para onde escalar/fazer fallback → LOCAL puro.
        val decision = InferenceRouter.resolveRoute(
            isOnline = true,
            isLocalAvailable = true,
            isServerAvailable = false,
            isCloudAvailable = false,
            complexity = PromptComplexity.COMPLEX,
            privacyMode = false
        )
        assertEquals(RoutingDecision.LOCAL, decision)
    }

    // ── Regra 6: Online + local → LOCAL_WITH_CLOUD_FALLBACK ──────────────

    @Test
    fun `online simple prompt with local returns LOCAL_WITH_CLOUD_FALLBACK`() {
        val decision = InferenceRouter.resolveRoute(
            isOnline = true,
            isLocalAvailable = true,
            isServerAvailable = false,
            isCloudAvailable = true,
            complexity = PromptComplexity.SIMPLE,
            privacyMode = false
        )
        assertEquals(RoutingDecision.LOCAL_WITH_CLOUD_FALLBACK, decision)
    }

    @Test
    fun `online moderate prompt with local returns LOCAL_WITH_CLOUD_FALLBACK`() {
        val decision = InferenceRouter.resolveRoute(
            isOnline = true,
            isLocalAvailable = true,
            isServerAvailable = false,
            isCloudAvailable = true,
            complexity = PromptComplexity.MODERATE,
            privacyMode = false
        )
        assertEquals(RoutingDecision.LOCAL_WITH_CLOUD_FALLBACK, decision)
    }

    // ── Regra 7: Online + sem local + cloud → CLOUD ──────────────────────

    @Test
    fun `online without local but with cloud returns CLOUD`() {
        val decision = InferenceRouter.resolveRoute(
            isOnline = true,
            isLocalAvailable = false,
            isServerAvailable = false,
            isCloudAvailable = true,
            complexity = PromptComplexity.SIMPLE,
            privacyMode = false
        )
        assertEquals(RoutingDecision.CLOUD, decision)
    }

    @Test
    fun `online without local moderate complexity returns CLOUD`() {
        val decision = InferenceRouter.resolveRoute(
            isOnline = true,
            isLocalAvailable = false,
            isServerAvailable = false,
            isCloudAvailable = true,
            complexity = PromptComplexity.MODERATE,
            privacyMode = false
        )
        assertEquals(RoutingDecision.CLOUD, decision)
    }

    // ── Regra 8: Nada disponível → ERROR_UNAVAILABLE ─────────────────────

    @Test
    fun `online but nothing available returns ERROR_UNAVAILABLE`() {
        val decision = InferenceRouter.resolveRoute(
            isOnline = true,
            isLocalAvailable = false,
            isServerAvailable = false,
            isCloudAvailable = false,
            complexity = PromptComplexity.SIMPLE,
            privacyMode = false
        )
        assertEquals(RoutingDecision.ERROR_UNAVAILABLE, decision)
    }

    // ── Tier servidor ─────────────────────────────────────────────────────

    @Test
    fun `server available with cloud returns SERVER_WITH_CLOUD_ESCALATION`() {
        val decision = InferenceRouter.resolveRoute(
            isOnline = true,
            isLocalAvailable = true,
            isServerAvailable = true,
            isCloudAvailable = true,
            complexity = PromptComplexity.SIMPLE,
            privacyMode = false
        )
        assertEquals(RoutingDecision.SERVER_WITH_CLOUD_ESCALATION, decision)
    }

    @Test
    fun `server available without cloud returns SERVER`() {
        val decision = InferenceRouter.resolveRoute(
            isOnline = true,
            isLocalAvailable = true,
            isServerAvailable = true,
            isCloudAvailable = false,
            complexity = PromptComplexity.SIMPLE,
            privacyMode = false
        )
        assertEquals(RoutingDecision.SERVER, decision)
    }

    @Test
    fun `server reachable on LAN while offline still routes to SERVER`() {
        // Servidor na LAN é acessível mesmo sem internet.
        val decision = InferenceRouter.resolveRoute(
            isOnline = false,
            isLocalAvailable = true,
            isServerAvailable = true,
            isCloudAvailable = false,
            complexity = PromptComplexity.SIMPLE,
            privacyMode = false
        )
        assertEquals(RoutingDecision.SERVER, decision)
    }

    @Test
    fun `complex prompt with cloud prefers CLOUD over server`() {
        val decision = InferenceRouter.resolveRoute(
            isOnline = true,
            isLocalAvailable = true,
            isServerAvailable = true,
            isCloudAvailable = true,
            complexity = PromptComplexity.COMPLEX,
            privacyMode = false
        )
        assertEquals(RoutingDecision.CLOUD, decision)
    }

    @Test
    fun `privacy mode ignores available server`() {
        val decision = InferenceRouter.resolveRoute(
            isOnline = true,
            isLocalAvailable = true,
            isServerAvailable = true,
            isCloudAvailable = true,
            complexity = PromptComplexity.SIMPLE,
            privacyMode = true
        )
        assertEquals(RoutingDecision.LOCAL, decision)
    }

    @Test
    fun `privacy mode without local ignores server and errors`() {
        val decision = InferenceRouter.resolveRoute(
            isOnline = true,
            isLocalAvailable = false,
            isServerAvailable = true,
            isCloudAvailable = true,
            complexity = PromptComplexity.SIMPLE,
            privacyMode = true
        )
        assertEquals(RoutingDecision.ERROR_PRIVACY, decision)
    }
}

// ══════════════════════════════════════════════════════════════════════════
// Testes de execução — verifica fallback, source e exceções
// ══════════════════════════════════════════════════════════════════════════

class InferenceRouterExecutionTest {

    private lateinit var fakeLocal: FakeLocalInferenceService
    private lateinit var fakeCloud: FakeCloudInferenceService
    private lateinit var fakeServer: FakeServerInferenceService
    private lateinit var fakeLogDao: FakeRoutingLogDao

    @Before
    fun setUp() {
        fakeLocal = FakeLocalInferenceService()
        fakeCloud = FakeCloudInferenceService()
        fakeServer = FakeServerInferenceService()
        fakeLogDao = FakeRoutingLogDao()
    }

    private fun buildRouter(
        localAvailable: Boolean = true,
        online: Boolean = true,
        serverEnabled: Boolean = false,
        settings: UserSettings = UserSettings()
    ): InferenceRouter {
        val ctx = Application()
        // O tier servidor é ligado via settings (fonte de verdade em runtime).
        val effectiveSettings = settings.copy(serverTierEnabled = serverEnabled)
        return InferenceRouter(
            localService = fakeLocal,
            cloudService = fakeCloud,
            serverService = fakeServer,
            serverConfig = ServerConfig(),
            localModelConfig = LocalModelConfig(),
            cloudModelConfig = CloudModelConfig(),
            localModelManager = FakeLocalModelManager(ctx, localAvailable),
            networkMonitor = FakeNetworkMonitor(ctx, online),
            userSettingsDataStore = FakeUserSettingsDataStore(ctx, effectiveSettings),
            promptBuilder = TutorPromptBuilder(),
            routingLogger = fakeLogger(fakeLogDao),
            deviceProfileProvider = DeviceProfileProvider(ctx, FakeDeviceProfileDao())
        )
    }

    private fun simpleRequest(
        complexity: PromptComplexity = PromptComplexity.SIMPLE
    ) = InferenceRequest(
        prompt = "O que é fotossíntese?",
        sessionId = "test-session",
        complexity = complexity
    )

    // ── Source: LOCAL ─────────────────────────────────────────────────────

    @Test
    fun `offline local generates response with source LOCAL`() = runTest {
        fakeLocal.generateResult = "Fotossíntese é o processo..."
        val result = buildRouter(localAvailable = true, online = false)
            .infer(simpleRequest())

        assertEquals("Fotossíntese é o processo...", result.text)
        assertEquals(InferenceSource.LOCAL, result.source)
        assertTrue(result.latencyMs >= 0)
    }

    @Test
    fun `privacy mode with local generates LOCAL response`() = runTest {
        fakeLocal.generateResult = "Resposta privada"
        val result = buildRouter(
            localAvailable = true,
            settings = UserSettings(privacyModeEnabled = true)
        ).infer(simpleRequest(PromptComplexity.COMPLEX))

        assertEquals("Resposta privada", result.text)
        assertEquals(InferenceSource.LOCAL, result.source)
    }

    // ── Source: CLOUD ────────────────────────────────────────────────────

    @Test
    fun `complex prompt goes to cloud`() = runTest {
        fakeCloud.generateResult = "Resposta detalhada da cloud"
        val result = buildRouter(localAvailable = true, online = true)
            .infer(simpleRequest(PromptComplexity.COMPLEX))

        assertEquals("Resposta detalhada da cloud", result.text)
        assertEquals(InferenceSource.CLOUD, result.source)
    }

    @Test
    fun `no local available goes to cloud`() = runTest {
        fakeCloud.generateResult = "Resposta cloud"
        val result = buildRouter(localAvailable = false, online = true)
            .infer(simpleRequest())

        assertEquals("Resposta cloud", result.text)
        assertEquals(InferenceSource.CLOUD, result.source)
    }

    // ── Source: FALLBACK ─────────────────────────────────────────────────

    @Test
    fun `local failure triggers cloud fallback with FALLBACK source`() = runTest {
        fakeLocal.shouldFail = true
        fakeCloud.generateResult = "Resposta fallback"
        val result = buildRouter(localAvailable = true, online = true)
            .infer(simpleRequest())

        assertEquals("Resposta fallback", result.text)
        assertEquals(InferenceSource.FALLBACK, result.source)
    }

    @Test
    fun `both local and cloud failure throws with combined message`() = runTest {
        fakeLocal.shouldFail = true
        fakeCloud.shouldFail = true
        try {
            buildRouter(localAvailable = true, online = true)
                .infer(simpleRequest())
            fail("Deveria lançar InferenceUnavailableException")
        } catch (e: InferenceUnavailableException) {
            assertTrue(e.message!!.contains("Local:"))
            assertTrue(e.message!!.contains("Cloud:"))
        }
    }

    // ── Tier servidor: confiança e escalonamento ─────────────────────────

    @Test
    fun `server high confidence delivers SERVER source with confidence`() = runTest {
        fakeServer.generateResult = "Resposta do servidor"
        fakeServer.confidence = 0.85f
        fakeCloud.available = false // decisão SERVER (sem escalonamento)
        val result = buildRouter(online = true, serverEnabled = true)
            .infer(simpleRequest())

        assertEquals("Resposta do servidor", result.text)
        assertEquals(InferenceSource.SERVER, result.source)
        assertEquals(0.85f, result.confidence, 0.0001f)
    }

    @Test
    fun `server low confidence escalates to cloud preserving original confidence`() = runTest {
        fakeServer.confidence = 0.15f
        fakeCloud.generateResult = "Resposta da cloud escalonada"
        val result = buildRouter(online = true, serverEnabled = true)
            .infer(simpleRequest())

        assertEquals("Resposta da cloud escalonada", result.text)
        assertEquals(InferenceSource.CLOUD, result.source)
        // A confiança original medida no servidor é preservada para o log de pesquisa.
        assertEquals(0.15f, result.confidence, 0.0001f)
    }

    @Test
    fun `server medium confidence delivers SERVER without escalation`() = runTest {
        fakeServer.generateResult = "Resposta média"
        fakeServer.confidence = 0.5f
        val result = buildRouter(online = true, serverEnabled = true)
            .infer(simpleRequest())

        assertEquals("Resposta média", result.text)
        assertEquals(InferenceSource.SERVER, result.source)
        assertEquals(0.5f, result.confidence, 0.0001f)
    }

    @Test
    fun `server unavailable confidence does not escalate to cloud`() = runTest {
        fakeServer.generateResult = "Resposta sem logprobs"
        fakeServer.confidence = -1f // sem logprobs
        val result = buildRouter(online = true, serverEnabled = true)
            .infer(simpleRequest())

        assertEquals("Resposta sem logprobs", result.text)
        assertEquals(InferenceSource.SERVER, result.source)
    }

    @Test
    fun `server failure falls back to local tagged FALLBACK`() = runTest {
        fakeServer.shouldFail = true
        fakeLocal.generateResult = "Resposta local de fallback"
        val result = buildRouter(online = true, serverEnabled = true, localAvailable = true)
            .infer(simpleRequest())

        assertEquals("Resposta local de fallback", result.text)
        // Provenance: servidor (tier primário) falhou → FALLBACK, não LOCAL.
        assertEquals(InferenceSource.FALLBACK, result.source)
    }

    @Test
    fun `server failure without local falls back to cloud as FALLBACK`() = runTest {
        fakeServer.shouldFail = true
        fakeCloud.generateResult = "Resposta cloud de fallback"
        val result = buildRouter(online = true, serverEnabled = true, localAvailable = false)
            .infer(simpleRequest())

        assertEquals("Resposta cloud de fallback", result.text)
        assertEquals(InferenceSource.FALLBACK, result.source)
    }

    @Test
    fun `confidence exactly at high threshold delivers SERVER`() = runTest {
        fakeServer.confidence = 0.7f // == confidenceThresholdHigh
        val result = buildRouter(online = true, serverEnabled = true).infer(simpleRequest())
        assertEquals(InferenceSource.SERVER, result.source)
    }

    @Test
    fun `confidence exactly at low threshold does not escalate`() = runTest {
        fakeServer.confidence = 0.3f // == confidenceThresholdLow (não é < low)
        val result = buildRouter(online = true, serverEnabled = true).infer(simpleRequest())
        assertEquals(InferenceSource.SERVER, result.source)
    }

    @Test
    fun `low confidence without cloud delivers SERVER instead of escalating`() = runTest {
        fakeServer.confidence = 0.15f
        fakeCloud.available = false // decisão SERVER (sem escalonamento possível)
        val result = buildRouter(online = true, serverEnabled = true).infer(simpleRequest())
        assertEquals(InferenceSource.SERVER, result.source)
        assertEquals(0.15f, result.confidence, 0.0001f)
    }

    @Test
    fun `disabled server never routes to SERVER even if reachable`() = runTest {
        fakeServer.reachable = true
        fakeLocal.generateResult = "Resposta local"
        // serverEnabled = false (default) → tier servidor ignorado
        val result = buildRouter(online = true, serverEnabled = false, localAvailable = true)
            .infer(simpleRequest())

        assertEquals(InferenceSource.LOCAL, result.source)
    }

    // ── Log de pesquisa ──────────────────────────────────────────────────

    @Test
    fun `server route logs tier confidence method and connectivity`() = runTest {
        fakeServer.generateResult = "Resposta"
        fakeServer.confidence = 0.85f
        fakeCloud.available = false // decisão SERVER
        buildRouter(online = true, serverEnabled = true).infer(simpleRequest())

        val entry = fakeLogDao.entries.single()
        assertEquals("SERVER", entry.finalTier)
        assertEquals("SERVER", entry.routeDecision)
        assertEquals(0.85f, entry.confidenceScore, 0.0001f)
        assertEquals("logprobs_mean", entry.confidenceMethod)
        assertEquals("internet", entry.connectivity)
        assertEquals("O que é fotossíntese?", entry.questionText)
    }

    @Test
    fun `offline local logs connectivity offline and method none`() = runTest {
        fakeLocal.generateResult = "Resposta"
        buildRouter(online = false, localAvailable = true).infer(simpleRequest())

        val entry = fakeLogDao.entries.single()
        assertEquals("LOCAL", entry.finalTier)
        assertEquals("offline", entry.connectivity)
        assertEquals("none", entry.confidenceMethod)
        assertEquals(-1f, entry.confidenceScore, 0.0001f)
    }

    @Test
    fun `server on LAN while offline logs connectivity lan`() = runTest {
        fakeServer.confidence = 0.8f
        buildRouter(online = false, serverEnabled = true, localAvailable = true)
            .infer(simpleRequest())

        val entry = fakeLogDao.entries.single()
        assertEquals("SERVER", entry.finalTier)
        assertEquals("lan", entry.connectivity)
    }

    @Test
    fun `escalation logs CLOUD tier with escalation route and original confidence`() = runTest {
        fakeServer.confidence = 0.15f
        fakeCloud.generateResult = "Resposta cloud"
        buildRouter(online = true, serverEnabled = true).infer(simpleRequest())

        val entry = fakeLogDao.entries.single()
        assertEquals("CLOUD", entry.finalTier)
        assertEquals("SERVER_WITH_CLOUD_ESCALATION", entry.routeDecision)
        assertEquals(0.15f, entry.confidenceScore, 0.0001f)
    }

    // ── Exceções tipadas ─────────────────────────────────────────────────

    @Test
    fun `privacy mode without local throws PrivacyModeException`() = runTest {
        try {
            buildRouter(
                localAvailable = false,
                settings = UserSettings(privacyModeEnabled = true)
            ).infer(simpleRequest())
            fail("Deveria lançar PrivacyModeException")
        } catch (e: PrivacyModeException) {
            assertTrue(e.message!!.contains("privacidade"))
        }
    }

    @Test
    fun `offline without local throws InferenceUnavailableException`() = runTest {
        try {
            buildRouter(localAvailable = false, online = false)
                .infer(simpleRequest())
            fail("Deveria lançar InferenceUnavailableException")
        } catch (e: InferenceUnavailableException) {
            assertTrue(e.message!!.contains("internet"))
        }
    }

    @Test
    fun `nothing available throws InferenceUnavailableException`() = runTest {
        fakeCloud.available = false
        try {
            buildRouter(localAvailable = false, online = true)
                .infer(simpleRequest())
            fail("Deveria lançar InferenceUnavailableException")
        } catch (e: InferenceUnavailableException) {
            assertTrue(e.message!!.contains("serviço"))
        }
    }

    // ── Response cleaning ─────────────────────────────────────────────────

    @Test
    fun `strips Tutor prefix from response`() = runTest {
        fakeCloud.generateResult = "Tutor: A fotossíntese é o processo biológico."
        val result = buildRouter(localAvailable = false, online = true)
            .infer(simpleRequest())

        assertEquals("A fotossíntese é o processo biológico.", result.text)
    }

    @Test
    fun `strips echoed conversation with Tutor prefix`() = runTest {
        fakeLocal.generateResult =
            "O que é fotossíntese?\n\nTutor: A fotossíntese é o processo biológico."
        val result = buildRouter(localAvailable = true, online = false)
            .infer(simpleRequest())

        assertEquals("A fotossíntese é o processo biológico.", result.text)
    }

    @Test
    fun `preserves clean response without artifacts`() = runTest {
        fakeCloud.generateResult = "A fotossíntese é o processo biológico."
        val result = buildRouter(localAvailable = false, online = true)
            .infer(simpleRequest())

        assertEquals("A fotossíntese é o processo biológico.", result.text)
    }
}

// ══════════════════════════════════════════════════════════════════════════
// Testes do RoutingLogger — export CSV
// ══════════════════════════════════════════════════════════════════════════

class RoutingLoggerCsvTest {

    @Test
    fun `exportCsv writes header and one row per entry`() = runTest {
        val dao = FakeRoutingLogDao()
        val logger = fakeLogger(dao)
        logger.log(
            sessionId = "s1",
            questionText = "O que é fotossíntese?",
            complexity = PromptComplexity.SIMPLE,
            routeDecision = "SERVER",
            confidence = 0.8f,
            confidenceMethod = "logprobs_mean",
            finalSource = InferenceSource.SERVER,
            mode = TutorMode.EXPLAIN,
            latencyMs = 123,
            modelId = "gemma3-1b",
            connectivity = "lan"
        )

        val lines = logger.exportCsv().lines()
        assertEquals(
            "timestamp,session_id,device_id,question,complexity,route,confidence,method," +
                "tier,mode,latency_ms,model,connectivity,runtime,prompt_tokens," +
                "generated_tokens,reasoning_tokens,ttft_ms,ingestion_ms,generation_ms," +
                "tokens_per_sec,peak_ram_mb,threads,backends,stop_reason,truncated," +
                "block_id,run_index,question_id,question_year,question_area,expected_answer," +
                "predicted_answer,answer_method,is_correct,response",
            lines.first()
        )
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains(",SERVER,0.8,logprobs_mean,SERVER,EXPLAIN,123,"))
        // Sem telemetria, as colunas de hardware saem como -1 (indisponível) e não 0:
        // a análise não pode confundir "não medido" com "custou zero".
        assertTrue("esperava -1 nas colunas de hardware", lines[1].contains(",-1,-1,-1,"))
    }

    @Test
    fun `exportCsv escapes quotes and neutralizes commas inside fields`() = runTest {
        val dao = FakeRoutingLogDao()
        val logger = fakeLogger(dao)
        logger.log(
            sessionId = "s1",
            questionText = "Diga \"olá\", por favor",
            complexity = PromptComplexity.SIMPLE,
            routeDecision = "LOCAL",
            confidence = -1f,
            confidenceMethod = "none",
            finalSource = InferenceSource.LOCAL,
            mode = TutorMode.HINT,
            latencyMs = 10,
            modelId = "m",
            connectivity = "offline"
        )

        val csv = logger.exportCsv()
        // Aspas internas viram "" e o campo inteiro fica entre aspas (vírgula neutralizada).
        assertTrue(csv.contains("\"Diga \"\"olá\"\", por favor\""))
    }
}

// ══════════════════════════════════════════════════════════════════════════
// Fakes — implementações mínimas para testes JVM
// ══════════════════════════════════════════════════════════════════════════

private class FakeLocalInferenceService : LocalInferenceService {
    var generateResult: String = "resposta local"
    var shouldFail: Boolean = false
    override var isModelLoaded: Boolean = true
    override val isAvailable: Boolean get() = isModelLoaded
    override suspend fun generate(prompt: String): String {
        if (shouldFail) throw LocalInferenceException("Falha local simulada")
        return generateResult
    }
    override suspend fun loadModel(modelPath: String) {}
    override suspend fun warmup(prompt: String) {}
    override fun unloadModel() {}
}

private class FakeCloudInferenceService : CloudInferenceService {
    var generateResult: String = "resposta cloud"
    var shouldFail: Boolean = false
    var available: Boolean = true
    override val isAvailable: Boolean get() = available
    override suspend fun generate(prompt: String): String {
        if (shouldFail) throw Exception("Falha cloud simulada")
        return generateResult
    }
}

private class FakeRoutingLogDao : RoutingLogDao {
    val entries = mutableListOf<RoutingLogEntry>()
    override suspend fun insert(entry: RoutingLogEntry) { entries.add(entry) }
    override suspend fun getAll(): List<RoutingLogEntry> = entries.toList()
    override suspend fun getRecent(limit: Int): List<RoutingLogEntry> = entries.takeLast(limit)
    override suspend fun count(): Int = entries.size
    override suspend fun getBySession(sessionId: String): List<RoutingLogEntry> =
        entries.filter { it.sessionId == sessionId }
    override suspend fun clear() { entries.clear() }
}

private class FakeModelLoadLogDao : ModelLoadLogDao {
    val entries = mutableListOf<ModelLoadLogEntry>()
    override suspend fun insert(entry: ModelLoadLogEntry) { entries.add(entry) }
    override suspend fun getAll(): List<ModelLoadLogEntry> = entries.toList()
    override suspend fun count(): Int = entries.size
    override suspend fun clear() { entries.clear() }
}

private class FakeDeviceProfileDao : DeviceProfileDao {
    val entries = mutableMapOf<String, DeviceProfileEntry>()
    override suspend fun upsert(entry: DeviceProfileEntry) { entries[entry.deviceId] = entry }
    override suspend fun getAll(): List<DeviceProfileEntry> = entries.values.toList()
    override suspend fun get(deviceId: String): DeviceProfileEntry? = entries[deviceId]
    override suspend fun annotate(deviceId: String, label: String, notes: String?) {
        entries[deviceId]?.let { entries[deviceId] = it.copy(label = label, notes = notes) }
    }
}

/** Logger com os três DAOs falsos — o construtor real exige os três. */
private fun fakeLogger(dao: RoutingLogDao = FakeRoutingLogDao()) =
    RoutingLogger(dao, FakeModelLoadLogDao(), FakeDeviceProfileDao())

private class FakeServerInferenceService : ServerInferenceService(ServerConfig()) {
    var reachable: Boolean = true
    var generateResult: String = "resposta servidor"
    var confidence: Float = 0.8f
    var shouldFail: Boolean = false

    // Não constrói cliente HTTP real nos testes.
    override fun configure(baseUrl: String) {}

    override suspend fun isServerReachable(): Boolean = reachable

    override suspend fun generateWithConfidence(prompt: String): ServerResult {
        if (shouldFail) throw ServerUnavailableException("Falha servidor simulada")
        return ServerResult(
            text = generateResult,
            confidence = confidence,
            tokenCount = 10,
            latencyMs = 5
        )
    }
}

private class FakeLocalModelManager(
    ctx: android.content.Context,
    private val available: Boolean
) : LocalModelManager(
    context = ctx,
    localInferenceService = FakeLocalInferenceService(),
    deviceCapabilityChecker = DeviceCapabilityChecker(ctx, LocalModelConfig()),
    config = LocalModelConfig(),
    routingLogger = fakeLogger(),
    deviceProfileProvider = DeviceProfileProvider(ctx, FakeDeviceProfileDao())
) {
    override val isAvailable: Boolean get() = available
}

private class FakeNetworkMonitor(
    ctx: android.content.Context,
    private val online: Boolean
) : NetworkMonitor(context = ctx) {
    override fun isCurrentlyOnline(): Boolean = online
}

private class FakeUserSettingsDataStore(
    ctx: android.content.Context,
    private val fixedSettings: UserSettings
) : UserSettingsDataStore(context = ctx) {
    override val settings: Flow<UserSettings> get() = flowOf(fixedSettings)
}
