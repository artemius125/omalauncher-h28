package dev.omalauncher.widgets.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryDischargeTest {
    @Test fun dividesRemainingChargeByMeasuredCurrent() {
        val tracker = BatteryDischarge()
        for (i in 0 until 12) assertNull(tracker.update(i * 5_000L, true, 64, 2_628_100, -500_000, null))
        val result = requireNotNull(tracker.update(60_000, true, 64, 2_628_100, -500_000, null))
        assertEquals(315.372, result.minutes, 0.001)
        assertEquals(2628, result.chargeMah)
        assertEquals(500, result.currentMa)
    }

    @Test fun integratesIrregularlySpacedCurrentSamples() {
        val tracker = BatteryDischarge()
        for (time in listOf(0L, 5L, 10L, 15L, 30L)) {
            tracker.update(time * 1_000, true, 60, 2_300_000, if (time < 30) -200_000 else -800_000, null)
        }
        val result = requireNotNull(tracker.update(60_000, true, 60, 2_300_000, -800_000, null))
        assertEquals(575, result.currentMa)
        assertEquals(240.0, result.minutes, 0.001)
    }

    @Test fun startingAgainAfterAChargerOrSamplingGapRequiresFreshData() {
        val tracker = BatteryDischarge()
        for (i in 0..12) tracker.update(i * 5_000L, true, 64, 2_628_100, -500_000, null)
        assertNull(tracker.update(95_000, true, 64, 2_620_000, -500_000, null))
        for (i in 1..12) tracker.update(95_000 + i * 5_000L, true, 64, 2_620_000, -500_000, null)
        assertNull(tracker.update(160_000, false, 64, 2_620_000, 500_000, null))
        assertNull(tracker.update(165_000, true, 64, 2_620_000, -500_000, null))
    }

    @Test fun usesHardwareAverageWhenInstantaneousCurrentIsMissing() {
        val tracker = BatteryDischarge()
        for (i in 0 until 12) tracker.update(i * 5_000L, true, 50, 2_000_000, null, 400_000)
        val result = requireNotNull(tracker.update(60_000, true, 50, 2_000_000, null, 400_000))
        assertEquals(300.0, result.minutes, 0.001)
    }

    @Test fun rejectsImplausibleGaugeUnits() {
        val tracker = BatteryDischarge()
        for (i in 0..16) {
            assertNull(tracker.update(i * 5_000L, true, 64, 2628, -500_000, null))
        }
        for (i in 0..16) {
            assertNull(tracker.update(100_000 + i * 5_000L, true, 64, 2_628_100, -2000, null))
        }
    }
}
