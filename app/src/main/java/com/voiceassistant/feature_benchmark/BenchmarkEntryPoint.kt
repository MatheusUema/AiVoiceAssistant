package com.voiceassistant.feature_benchmark

import com.voiceassistant.ai_local.manager.LocalModelManager
import com.voiceassistant.core.logging.RoutingLogger
import com.voiceassistant.core.storage.UserSettingsDataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Acesso ao grafo real do app a partir de fora dele.
 *
 * Existe para que a bateria de medição seja disparada por `adb shell am instrument`, sem
 * depender de UI. Num estudo com vários aparelhos e vários modelos isso importa: a coleta
 * vira um script, roda com a tela apagada e não depende de alguém tocar no celular — que
 * é justamente o tipo de interferência que contamina medição de energia e térmica.
 *
 * Usa o grafo de produção, não um de teste: os números medidos precisam vir do mesmo
 * caminho que o app usa de verdade.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BenchmarkEntryPoint {
    fun benchmarkRunner(): BenchmarkRunner
    fun localModelManager(): LocalModelManager
    fun routingLogger(): RoutingLogger

    /**
     * Para fixar o cenário antes da coleta — na prática, ligar o modo privacidade para
     * medir **só** o tier local.
     *
     * O jeito óbvio de isolar o local seria o modo avião, mas ele derruba a depuração por
     * Wi-Fi e portanto a própria coleta. Além disso, com o aparelho online o roteador usa
     * o orçamento curto de tempo (`generationTimeoutWithFallbackMs`, 30 s) porque existe
     * nuvem para onde escalar — o que mede a política de fallback, não o aparelho. O modo
     * privacidade resolve os dois: força a rota local e devolve o orçamento inteiro.
     */
    fun userSettings(): UserSettingsDataStore
}
