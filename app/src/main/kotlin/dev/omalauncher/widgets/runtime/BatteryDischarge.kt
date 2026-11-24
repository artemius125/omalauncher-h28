package dev.omalauncher.widgets.runtime

import kotlin.math.abs
import kotlin.math.roundToInt

/** Projects remaining gauge charge at the recent, time-weighted battery current. */
internal class BatteryDischarge {
    data class Estimate(val minutes: Double, val chargeMah: Int, val currentMa: Int)

    private data class Sample(val atMs: Long, val currentUa: Double)

    private val samples = ArrayDeque<Sample>()

    fun update(
        nowMs: Long,
        onBattery: Boolean,
        level: Int?,
        chargeUah: Int?,
        currentUa: Int?,
        averageUa: Int?,
    ): Estimate? {
        val current = magnitude(currentUa) ?: magnitude(averageUa)
        // Some gauges report mAh while Android specifies µAh. Leave the estimate to level
        // history if the implied full capacity is outside the range of a phone or tablet.
        val fullChargeUah = if (level != null && level in 1..100 && chargeUah != null) chargeUah * 100.0 / level else null
        if (!onBattery || current == null || fullChargeUah == null || fullChargeUah !in 100_000.0..30_000_000.0) {
            samples.clear()
            return null
        }

        val gap = samples.lastOrNull()?.let { nowMs - it.atMs }
        if (gap != null && (gap < 0 || gap > 30_000)) samples.clear()
        if (samples.isEmpty() || nowMs - samples.last().atMs >= 1_000) samples.addLast(Sample(nowMs, current))
        val cutoff = nowMs - 180_000
        while (samples.size > 2 && samples[1].atMs <= cutoff) samples.removeFirst()
        val start = maxOf(cutoff, samples.first().atMs)
        val elapsed = samples.last().atMs - start
        if (elapsed < 60_000 || samples.size < 6) return null

        // Integrate between samples. Time spent at a load matters, even if it contains fewer readings.
        var integral = 0.0
        for (i in 1 until samples.size) {
            val previous = samples[i - 1]
            val next = samples[i]
            val begin = maxOf(start, previous.atMs)
            val from = previous.currentUa +
                (next.currentUa - previous.currentUa) * (begin - previous.atMs) / (next.atMs - previous.atMs)
            integral += (from + next.currentUa) * (next.atMs - begin) / 2
        }
        val smoothedUa = integral / elapsed
        val minutes = 60.0 * requireNotNull(chargeUah) / smoothedUa
        if (minutes > 72 * 60) return null
        return Estimate(minutes, (chargeUah / 1000.0).roundToInt(), (smoothedUa / 1000).roundToInt())
    }

    // A few vendors report the opposite sign. Only use magnitude while unplugged.
    private fun magnitude(value: Int?): Double? =
        value?.let { abs(it.toLong()) }?.takeIf { it in 1_000..20_000_000 }?.toDouble()
}
