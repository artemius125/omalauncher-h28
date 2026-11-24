package dev.omalauncher.navigation

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.omalauncher.R
import dev.omalauncher.Runtime
import dev.omalauncher.ui.BracketAction
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.TextSize
import dev.omalauncher.ui.frame

@Composable
fun RecentOverview(runtime: Runtime, dismiss: () -> Unit) {
    val context = LocalContext.current
    val recent = runtime.recentTasks
    val navigationRoom = if (recent.gesturesEnabled) OmaNavigationService.HANDLE_HEIGHT_DP.dp else 0.dp
    BackHandler(onBack = dismiss)
    Column(
        Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding().padding(bottom = navigationRoom).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                stringResource(R.string.oma_recent),
                Modifier.weight(1f),
                style = Mono.copy(fontSize = TextSize.Title),
            )
            BracketAction(stringResource(R.string.oma_home), Palette.accent, onClick = dismiss)
        }
        when {
            recent.tasks.isEmpty() -> BasicText(stringResource(R.string.oma_tasks_empty), style = Mono)
            else -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recent.tasks, key = { it.id }) { task -> TaskCard(runtime, task, dismiss) }
                }
            }
        }
        BracketAction(stringResource(R.string.oma_navigation), Palette.accent) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
private fun TaskCard(runtime: Runtime, task: OpenTask, dismiss: () -> Unit) {
    val context = LocalContext.current
    val app = runtime.androidApps.apps.firstOrNull { it.component == task.component && it.profile == null }
        ?: runtime.androidApps.apps.firstOrNull { it.component.packageName == task.component.packageName && it.profile == null }
    val window = runtime.wm.windowByHandle(task.window)
    val label = when {
        window != null -> runtime.displayName(window.appId, window.title)
        task.owl != null -> runtime.widgets.apps().firstOrNull { it.file.name == task.owl }?.name ?: task.owl
        app != null -> app.spoken
        else -> remember(task.component) {
            val pm = context.packageManager
            runCatching { pm.getActivityInfo(task.component, 0).loadLabel(pm).toString() }
                .getOrDefault(task.component.packageName)
        }
    }
    val icon = when {
        window != null -> runtime.iconNamed(label)
        app != null -> runtime.androidApps.icons[app.key]
        else -> null
    }
    val close by rememberUpdatedState { runtime.recentTasks.close(task) }
    var drag by remember(task.id) { mutableFloatStateOf(0f) }
    Column(
        Modifier.width(176.dp).height(168.dp)
            .pointerInput(task.id) {
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragCancel = { drag = 0f },
                    onDragEnd = {
                        if (drag < -56.dp.toPx()) close()
                        drag = 0f
                    },
                ) { change, amount ->
                    change.consume()
                    drag = (drag + amount).coerceAtMost(0f)
                }
            }
            .graphicsLayer { translationY = drag.coerceAtLeast(-32.dp.toPx()) / 2 }
            .frame(1.dp, Palette.accent.copy(alpha = 0.35f), Palette.surface, radius = 14.dp)
            .clickable { runtime.recentTasks.open(task, dismiss) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(44.dp))
        BasicText(label, Modifier.padding(top = 10.dp), style = Mono.copy(fontSize = TextSize.Item), maxLines = 2)
        BracketAction(stringResource(R.string.oma_close_app), Palette.muted, onClick = close)
    }
}
