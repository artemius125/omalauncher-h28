package dev.omalauncher.navigation

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log

/** A separate OMA task whose child is the Android app that the user chose to open. */
class ManagedAppActivity : Activity() {
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launched = savedInstanceState?.getBoolean("launched") == true
    }

    override fun onPostResume() {
        super.onPostResume()
        val app = target(intent)
        if (launched || app == null) {
            finishAndRemoveTask()
            return
        }
        launched = true
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(app)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        } catch (e: SecurityException) {
            Log.w("OmaManagedApp", "Cannot launch ${app.flattenToShortString()}", e)
            finishAndRemoveTask()
        } catch (e: android.content.ActivityNotFoundException) {
            // The app was disabled or removed between selection and launch.
            finishAndRemoveTask()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("launched", launched)
        super.onSaveInstanceState(outState)
    }

    companion object {
        fun target(intent: Intent): ComponentName? =
            intent.data?.takeIf { it.scheme == "oma-app" }?.schemeSpecificPart?.let(ComponentName::unflattenFromString)

        fun intent(context: Context, app: ComponentName): Intent =
            Intent(context, ManagedAppActivity::class.java)
                .setData(Uri.fromParts("oma-app", app.flattenToString(), null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
