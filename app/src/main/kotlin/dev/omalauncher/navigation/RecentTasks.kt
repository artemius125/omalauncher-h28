package dev.omalauncher.navigation

import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class OpenTask(
    val id: Int,
    val component: ComponentName,
    val window: String? = null,
    val owl: String? = null,
)

class RecentTasks(
    private val context: Context,
    private val scope: CoroutineScope,
    private val windowExists: (String) -> Boolean,
    private val closeWindow: (String) -> Unit,
) {
    val supported = Build.VERSION.SDK_INT == Build.VERSION_CODES.R
    var gesturesEnabled by mutableStateOf(supported && OmaNavigationService.enabled(context))
        private set
    private var reportedTasks by mutableStateOf(emptyList<OpenTask>())
    // Android can keep the activity after its Wayland window has gone. Observe
    // the compositor so Recents and the strip both update when the window closes.
    val tasks: List<OpenTask>
        get() = reportedTasks.filter { it.window == null || windowExists(it.window) }
    private val manager = context.getSystemService(ActivityManager::class.java)
    private val updates = Mutex()

    /** Apps with their own task policy keep Android's normal launch behaviour. */
    fun openAndroid(component: ComponentName): Boolean {
        if (!supported || !OmaNavigationService.enabled(context) ||
            !context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME) ||
            component.packageName == context.packageName
        ) return false

        val info = runCatching { context.packageManager.getActivityInfo(component, 0) }.getOrNull() ?: return false
        if ((info.launchMode != ActivityInfo.LAUNCH_MULTIPLE && info.launchMode != ActivityInfo.LAUNCH_SINGLE_TOP) ||
            info.documentLaunchMode == ActivityInfo.DOCUMENT_LAUNCH_ALWAYS ||
            info.documentLaunchMode == ActivityInfo.DOCUMENT_LAUNCH_INTO_EXISTING ||
            info.flags and ActivityInfo.FLAG_NO_HISTORY != 0
        ) return false

        scope.launch {
            updates.withLock {
                val resumed = withContext(Dispatchers.IO) {
                    read().firstOrNull { (task, _) -> task.component == component }
                        ?.let { (_, handle) -> runCatching { handle.moveToFront() }.isSuccess } == true
                }
                if (!resumed) {
                    runCatching { context.startActivity(ManagedAppActivity.intent(context, component)) }
                        .onFailure { Log.w("OmaRecentTasks", "Could not open ${component.flattenToShortString()}", it) }
                }
            }
        }
        return true
    }

    suspend fun whileHomeVisible() {
        while (supported && kotlin.coroutines.coroutineContext.isActive) {
            gesturesEnabled = OmaNavigationService.enabled(context)
            updates.withLock { refresh() }
            delay(900)
        }
    }

    fun open(task: OpenTask, onOpened: () -> Unit) {
        operate(setOf(task.id), onOpened) { moveToFront() }
    }

    fun close(task: OpenTask) = close(listOf(task))

    fun close(tasks: List<OpenTask>) {
        val androidTasks = mutableSetOf<Int>()
        for (task in tasks) {
            val window = task.window
            if (window == null) androidTasks += task.id else closeWindow(window)
        }
        if (androidTasks.isNotEmpty()) operate(androidTasks) { finishAndRemoveTask() }
    }

    private fun operate(ids: Set<Int>, onSuccess: () -> Unit = {}, action: ActivityManager.AppTask.() -> Unit) {
        scope.launch {
            updates.withLock {
                val success = withContext(Dispatchers.IO) {
                    var success = false
                    for ((task, handle) in read()) {
                        if (task.id in ids) success = runCatching { handle.action() }.isSuccess || success
                    }
                    success
                }
                refresh()
                if (success) onSuccess()
                else Toast.makeText(context, R.string.oma_task_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun refresh() {
        reportedTasks = withContext(Dispatchers.IO) { read().map { it.first } }
    }

    private fun read(): List<Pair<OpenTask, ActivityManager.AppTask>> =
        manager.appTasks.mapNotNull { handle ->
            val info = runCatching { handle.taskInfo }.getOrNull() ?: return@mapNotNull null
            if (!info.isRunning || info.numActivities == 0) return@mapNotNull null
            val intent = info.baseIntent
            val managed = ManagedAppActivity.target(intent)
            val data = intent.data
            val window = data?.takeIf { it.scheme == "omarchy" && it.host == "window" }?.lastPathSegment
            val owl = data?.takeIf { it.scheme == "owl" }?.schemeSpecificPart
            val component = when {
                managed != null && info.numActivities > 1 -> managed
                window != null || owl != null -> intent.component ?: return@mapNotNull null
                else -> return@mapNotNull null
            }
            OpenTask(info.taskId, component, window, owl) to handle
        }
}
