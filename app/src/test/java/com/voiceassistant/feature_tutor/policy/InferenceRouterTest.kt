package com.voiceassistant.feature_tutor.policy

import android.app.Application
import com.voiceassistant.ai_cloud.service.CloudInferenceService
import com.voiceassistant.ai_local.manager.DeviceCapabilityChecker
import com.voiceassistant.ai_local.manager.LocalModelManager
import com.voiceassistant.ai_local.model.LocalModelConfig
import com.voiceassistant.ai_local.service.LocalInferenceException
import com.voiceassistant.ai_local.service.LocalInferenceService
import com.voiceassistant.core.model.InferenceRequest
import com.voiceassistant.core.model.InferenceSource
import com.voiceassistant.core.model.PromptComplexity
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
            isCloudAvailable = true,
            complexity = PromptComplexity.COMPLEX,
            privacyMode = false
        )
        assertEquals(RoutingDecision.CLOUD, decision)
    }

    @Test
    fun `online complex prompt without cloud falls to local fallback`() {
        val decision = InferenceRouter.resolveRoute(
            isOnline = true,
            isLocalAvailable = true,
            isCloudAvailable = false,
            complexity = PromptComplexity.COMPLEX,
            privacyMode = false
        )
        assertEquals(RoutingDecision.LOCAL_WITH_CLOUD_FALLBACK, decision)
    }

    // ── Regra 6: Online + local → LOCAL_WITH_CLOUD_FALLBACK ──────────────

    @Test
    fun `online simple prompt with local returns LOCAL_WITH_CLOUD_FALLBACK`() {
        val decision = InferenceRouter.resolveRoute(
            isOnline = true,
            isLocalAvailable = true,
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
            isCloudAvailable = false,
            complexity = PromptComplexity.SIMPLE,
            privacyMode = false
        )
        assertEquals(RoutingDecision.ERROR_UNAVAILABLE, decision)
    }
}

// ══════════════════════════════════════════════════════════════════════════
// Testes de execução — verifica fallback, source e exceções
// ══════════════════════════════════════════════════════════════════════════

class InferenceRouterExecutionTest {

    private lateinit var fakeLocal: FakeLocalInferenceService
    private lateinit var fakeCloud: FakeCloudInferenceService

    @Before
    fun setUp() {
        fakeLocal = FakeLocalInferenceService()
        fakeCloud = FakeCloudInferenceService()
    }

    private fun buildRouter(
        localAvailable: Boolean = true,
        online: Boolean = true,
        settings: UserSettings = UserSettings()
    ): InferenceRouter {
        val ctx = Application()
        return InferenceRouter(
            localService = fakeLocal,
            cloudService = fakeCloud,
            localModelManager = FakeLocalModelManager(ctx, localAvailable),
            networkMonitor = FakeNetworkMonitor(ctx, online),
            userSettingsDataStore = FakeUserSettingsDataStore(ctx, settings),
            promptBuilder = TutorPromptBuilder()
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

private class FakeLocalModelManager(
    ctx: android.content.Context,
    private val available: Boolean
) : LocalModelManager(
    context = ctx,
    localInferenceService = FakeLocalInferenceService(),
    deviceCapabilityChecker = DeviceCapabilityChecker(ctx, LocalModelConfig()),
    config = LocalModelConfig()
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
