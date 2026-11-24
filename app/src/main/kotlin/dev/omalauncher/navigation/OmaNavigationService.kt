package dev.omalauncher.navigation

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import dev.omalauncher.MainActivity
import dev.omalauncher.R
import dev.omalauncher.system.registerNotExported
import kotlin.math.abs
import kotlin.math.roundToInt

/** Turns the visible Android gesture handle into an OMA touch target without inspecting app windows. */
class OmaNavigationService : AccessibilityService() {
    companion object {
        internal const val HANDLE_HEIGHT_DP = 22

        fun enabled(context: Context): Boolean {
            val component = ComponentName(context, OmaNavigationService::class.java)
            return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
        }
    }

    private val windows get() = getSystemService(WindowManager::class.java)
    private var handle: View? = null
    private var connected = false
    private var lockReceiver: BroadcastReceiver? = null
    private val locked get() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        if (lockReceiver == null) {
            val changes = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            lockReceiver = registerNotExported(changes) { intent ->
                if (intent.action == Intent.ACTION_SCREEN_OFF) removeHandle() else showHandle()
            }
        }
        showHandle()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        removeHandle()
        showHandle()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        connected = false
        lockReceiver?.let(::unregisterReceiver)
        lockReceiver = null
        removeHandle()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun showHandle() {
        if (!connected || handle != null || locked) return
        val systemGestureHandle = Build.VERSION.SDK_INT == Build.VERSION_CODES.R &&
            Settings.Secure.getInt(contentResolver, "navigation_mode", 0) == 2
        val indicator = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(228, 228, 235))
                setStroke(dp(1), Color.rgb(50, 50, 58))
                cornerRadius = dp(3).toFloat()
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // Wait for the first inset dispatch before drawing a replacement.
            if (systemGestureHandle) visibility = View.INVISIBLE
        }
        val bar = object : FrameLayout(this) {
            private var x = 0f
            private var y = 0f
            private var crossed: Long? = null

            override fun performClick(): Boolean = super.performClick()

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        x = event.rawX
                        y = event.rawY
                        crossed = null
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val rise = y - event.rawY
                        if (rise >= dp(44) && rise > abs(event.rawX - x)) {
                            if (crossed == null) crossed = event.eventTime
                        } else {
                            crossed = null
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        val rise = y - event.rawY
                        if (rise >= dp(44) && rise > abs(event.rawX - x)) {
                            navigate(crossed?.let { event.eventTime - it >= 300 } == true)
                        } else if (abs(rise) <= dp(8) && abs(event.rawX - x) <= dp(8)) {
                            if (event.eventTime - event.downTime >= 500) performLongClick() else performClick()
                        }
                        crossed = null
                    }
                    MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                        crossed = null
                        x = Float.POSITIVE_INFINITY
                    }
                }
                return true
            }
        }.apply {
            contentDescription = getString(R.string.oma_navigation)
            setOnClickListener { navigate(overview = false) }
            setOnLongClickListener { navigate(overview = true); true }
            addView(indicator, FrameLayout.LayoutParams(dp(42), dp(5), Gravity.CENTER))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && systemGestureHandle) {
                setOnApplyWindowInsetsListener { _, insets ->
                    val nav = WindowInsets.Type.navigationBars()
                    val systemHandleVisible = insets.isVisible(nav) && insets.getInsets(nav).bottom > 0
                    indicator.visibility = if (systemHandleVisible) View.INVISIBLE else View.VISIBLE
                    insets
                }
            }
        }
        val params = WindowManager.LayoutParams(
            dp(88), dp(HANDLE_HEIGHT_DP), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = if (systemGestureHandle) {
                    WindowInsets.Type.statusBars() or WindowInsets.Type.captionBar()
                } else {
                    WindowInsets.Type.systemBars() or WindowInsets.Type.ime()
                }
            }
            setTitle("OMA Navigation")
        }
        try {
            windows.addView(bar, params)
            handle = bar
        } catch (e: WindowManager.BadTokenException) {
            Log.w("OmaNavigation", "Navigation window could not be attached", e)
        }
    }

    private fun removeHandle() {
        handle?.let { windows.removeViewImmediate(it) }
        handle = null
    }

    private fun navigate(overview: Boolean) {
        if (locked) return
        if (!getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)) return
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(ComponentName(this, MainActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.SHOW_OMA_RECENTS, overview),
        )
    }
}
