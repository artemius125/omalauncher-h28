package dev.omalauncher.widgets.runtime

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dev.omalauncher.system.registerNotExported
import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.upgrade.mapObjects
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import org.json.JSONArray
import org.json.JSONObject

/** Battery broadcasts give the history without polling or keeping the device awake. */
internal class BatteryHistory(context: Context) {
    private data class Point(val time: Long, val level: Int, val charging: Boolean, val startsRun: Boolean)

    private val prefs = context.getSharedPreferences("battery-history", Context.MODE_PRIVATE)
    private var points = (Versioned.read(prefs.getString("points", null), 1) as? JSONArray)
        ?.mapObjects { o -> Point(o.getLong("time"), o.getInt("level"), o.getBoolean("charging"), o.getBoolean("start")) }
        .orEmpty()
    private var received = false

    init {
        context.registerNotExported(IntentFilter(Intent.ACTION_BATTERY_CHANGED)) { intent ->
            val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0)
            if (raw < 0 || scale <= 0) return@registerNotExported
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val time = now()
            val level = (raw * 100 / scale).coerceIn(0, 100)
            val previous = points.lastOrNull()
            val startsRun = !received || (previous != null && previous.time > time)
            received = true
            if (!startsRun && previous?.level == level && previous.charging == charging) return@registerNotExported
            points = (window(time, points.filter { it.time <= time }) + Point(time, level, charging, startsRun)).takeLast(360)
            val data = JSONArray()
            for (p in points) {
                data.put(JSONObject().put("time", p.time).put("level", p.level).put("charging", p.charging).put("start", p.startsRun))
            }
            prefs.edit().putString("points", Versioned.write(1, data)).apply()
        }
    }

    fun value(): Value {
        val time = now()
        val history = window(time, points.filter { it.time <= time })
        val last = history.lastOrNull()
        val shown = if (received && last != null && last.time < time) history + last.copy(time = time, startsRun = false) else history
        return Lst(shown.map { p ->
            Lst(listOf(Num(p.time.toDouble()), Num(p.level.toDouble()), bool(p.charging), bool(p.startsRun)))
        })
    }

    private fun window(time: Long, history: List<Point>): List<Point> {
        val firstInside = history.indexOfFirst { it.time >= time - 3 * 3600 }
        return if (firstInside < 0) history.takeLast(1) else history.drop((firstInside - 1).coerceAtLeast(0))
    }

    private fun now() = System.currentTimeMillis() / 1000
}
