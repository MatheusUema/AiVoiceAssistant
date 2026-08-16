package com.voiceassistant.feature_benchmark

import com.voiceassistant.ai_local.manager.LocalModelManager
import com.voiceassistant.core.logging.RoutingLogger
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
}
