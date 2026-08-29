package com.voiceassistant.core.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Energia consumida por **bloco** de questões (métrica H5 do plano).
 *
 * Por bloco e não por questão porque o `BATTERY_PROPERTY_CHARGE_COUNTER` tem resolução
 * grosseira (µAh, atualizado a cada poucos segundos): o consumo de uma única inferência
 * fica abaixo do ruído do instrumento. Medindo N questões entre duas leituras e dividindo,
 * o sinal fica utilizável.
 *
 * Condições que precisam ser controladas pelo operador, e que o medidor **não** garante:
 * brilho de tela fixo, aparelho fora do carregador, e nada mais rodando. Por isso
 * [BlockEnergy.charging] é registrado — uma medição feita no carregador é inválida e
 * precisa ser descartável na análise, não descoberta depois.
 */
@Singleton
class EnergyMeter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val batteryManager by lazy {
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    }

    /** Leitura instantânea dos contadores de bateria. */
    fun snapshot(): BatterySnapshot {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperatureTenths = status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0

        return BatterySnapshot(
            timestampMs = System.currentTimeMillis(),
            chargeCounterUah = readProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            capacityPercent = readProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            currentNowUa = readProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            temperatureCelsius = if (temperatureTenths > 0) temperatureTenths / 10.0 else -1.0,
            charging = plugged != 0
        )
    }

    /**
     * Energia consumida entre duas leituras.
     *
     * @param questions quantas questões couberam no intervalo, para derivar o custo unitário.
     */
    fun between(start: BatterySnapshot, end: BatterySnapshot, questions: Int): BlockEnergy {
        val validCounters = start.chargeCounterUah > 0 && end.chargeCounterUah > 0
        // O contador *decresce* ao descarregar; a diferença invertida é o consumo.
        val consumedUah = if (validCounters) start.chargeCounterUah - end.chargeCounterUah else -1L

        return BlockEnergy(
            startMs = start.timestampMs,
            endMs = end.timestampMs,
            startChargeUah = start.chargeCounterUah,
            endChargeUah = end.chargeCounterUah,
            questions = questions,
            consumedUah = consumedUah,
            perQuestionUah = if (consumedUah > 0 && questions > 0) {
                consumedUah.toDouble() / questions
            } else -1.0,
            startCapacityPercent = start.capacityPercent,
            endCapacityPercent = end.capacityPercent,
            startTemperatureCelsius = start.temperatureCelsius,
            endTemperatureCelsius = end.temperatureCelsius,
            // Carregando em qualquer ponto invalida o bloco: o contador sobe em vez de
            // descer e a "energia consumida" sai negativa ou absurda.
            charging = start.charging || end.charging
        )
    }

    private fun readProperty(property: Int): Long = try {
        batteryManager.getLongProperty(property).takeIf { it != Long.MIN_VALUE } ?: -1L
    } catch (e: Exception) {
        Log.w(TAG, "Falha ao ler propriedade $property da bateria: ${e.message}")
        -1L
    }

    private companion object {
        const val TAG = "EnergyMeter"
    }
}

/** Leitura instantânea dos contadores de bateria. */
data class BatterySnapshot(
    val timestampMs: Long,
    /** Carga restante em µAh. -1 se o aparelho não expõe. */
    val chargeCounterUah: Long,
    val capacityPercent: Long,
    /** Corrente instantânea em µA (negativa ao descarregar, em muitos aparelhos). */
    val currentNowUa: Long,
    val temperatureCelsius: Double,
    val charging: Boolean
)

/** Energia de um bloco de medição. */
data class BlockEnergy(
    val startMs: Long,
    val endMs: Long,
    /** Contadores brutos, guardados para que a conta possa ser refeita sem recoletar. */
    val startChargeUah: Long = -1L,
    val endChargeUah: Long = -1L,
    val questions: Int,
    /** Consumo total em µAh. -1 se indisponível. */
    val consumedUah: Long,
    /** Consumo por questão em µAh. -1 se indisponível. */
    val perQuestionUah: Double,
    val startCapacityPercent: Long,
    val endCapacityPercent: Long,
    val startTemperatureCelsius: Double,
    val endTemperatureCelsius: Double,
    /** True se o aparelho esteve no carregador — **bloco inválido** para energia. */
    val charging: Boolean
) {
    val durationMs: Long get() = endMs - startMs

    /** Aquecimento durante o bloco; entrada para descartar runs sob throttling (S1). */
    val temperatureDeltaCelsius: Double
        get() = if (startTemperatureCelsius > 0 && endTemperatureCelsius > 0) {
            endTemperatureCelsius - startTemperatureCelsius
        } else -1.0

    val isValid: Boolean get() = !charging && consumedUah > 0
}
