package com.voiceassistant.core.telemetry

import android.os.Debug
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mede o **pico** de memória do processo durante uma operação (métrica H4 do plano).
 *
 * Por que amostrar em vez de ler no fim: a inferência aloca e libera buffers de compute
 * ao longo do decode, então o valor no instante final não é o pico. O plano pede um
 * sampler paralelo justamente por isso.
 *
 * Usa PSS (proportional set size), que atribui a memória compartilhada proporcionalmente —
 * é a medida certa para "quanto este app custa ao aparelho". Como os pesos entram por
 * `mmap`, boa parte aparece como páginas *clean*: contam no PSS mas o kernel pode
 * descartá-las sob pressão em vez de matar o processo. Essa distinção é o que decide se um
 * modelo grande sobrevive num aparelho pequeno.
 */
@Singleton
class ProcessRamSampler @Inject constructor() {

    /**
     * Executa [block] amostrando a RAM do processo em paralelo.
     *
     * @return o resultado de [block] e o pico observado em MB
     *         ([RAM_UNAVAILABLE] se a amostragem falhar).
     */
    suspend fun <T> measurePeak(
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        block: suspend () -> T
    ): Pair<T, Long> = coroutineScope {
        val peakKb = AtomicLong(0L)

        // Dispatchers.Default: a amostragem não pode competir com o thread de inferência.
        val sampler = launch(Dispatchers.Default) {
            while (isActive) {
                readPssKb()?.let { kb -> peakKb.accumulateAndGet(kb, ::maxOf) }
                delay(intervalMs)
            }
        }

        try {
            // Uma leitura imediata garante ao menos uma amostra em gerações curtas,
            // que poderiam terminar antes do primeiro tick do laço.
            readPssKb()?.let { kb -> peakKb.accumulateAndGet(kb, ::maxOf) }
            val result = block()
            result to toMb(peakKb.get())
        } finally {
            sampler.cancel()
        }
    }

    private fun readPssKb(): Long? = try {
        Debug.getPss()
    } catch (e: Throwable) {
        Log.w(TAG, "Falha ao ler PSS: ${e.message}")
        null
    }

    private fun toMb(kb: Long): Long = if (kb > 0) kb / 1024 else RAM_UNAVAILABLE

    companion object {
        private const val TAG = "ProcessRamSampler"

        /** ~100 ms: fino o bastante para pegar o pico, barato o bastante para não pesar. */
        const val DEFAULT_INTERVAL_MS: Long = 100L

        const val RAM_UNAVAILABLE: Long = -1L
    }
}
