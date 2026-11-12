package com.primegame.aimguide

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private data class TargetApp(val label: String, val packageName: String)

    private var selectedApp: TargetApp? = null
    private var waiting = false
    private var notifAsked = false
    private var captureAsked = false
    private var capCode = 0
    private var capData: Intent? = null
    private lateinit var selectedLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildHome()
        selectedApp = findCarromApps().firstOrNull()
        refreshSelectedLabel()
    }

    private fun buildHome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(32), dp(24), dp(32))
            setBackgroundColor(Color.rgb(9, 7, 17))
        }
        val title = TextView(this).apply {
            text = "AIM LOCK"
            textSize = 32f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(255, 215, 0))
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "CARROM VISUAL AIM ASSIST"
            textSize = 12f
            letterSpacing = 0.16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        selectedLabel = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(26), 0, dp(18))
        }
        val select = homeButton("SELECT APP") { chooseApp() }
        val start = homeButton("START AIM LOCK") {
            if (selectedApp == null) chooseApp() else {
                waiting = true
                captureAsked = false
                capData = null
                stepPermissions()
            }
        }
        val stop = homeButton("STOP OVERLAY") {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "AIM LOCK stopped", Toast.LENGTH_SHORT).show()
        }
        val note = TextView(this).apply {
            text = "Screen capture is used only to draw a visual trajectory guide. It does not control or move game pieces."
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(24), dp(8), 0)
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(selectedLabel, LinearLayout.LayoutParams(-1, -2))
        root.addView(select, matchWidth())
        root.addView(start, matchWidth())
        root.addView(stop, matchWidth())
        root.addView(note, matchWidth())
        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(10)
    }

    private fun homeButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.BLACK)
        setBackgroundColor(Color.rgb(255, 215, 0))
        setOnClickListener { action() }
    }

    private fun findCarromApps(): List<TargetApp> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return packageManager.queryIntentActivities(launcher, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == packageName) return@mapNotNull null
                val label = info.loadLabel(packageManager)?.toString() ?: pkg
                TargetApp(label, pkg)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun chooseApp() {
        val apps = findCarromApps()
        if (apps.isEmpty()) {
            Toast.makeText(this, "No launchable apps found", Toast.LENGTH_LONG).show()
            return
        }
        val labels = apps.map { "${it.label}\n${it.packageName}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select game")
            .setItems(labels) { _, which ->
                selectedApp = apps[which]
                refreshSelectedLabel()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun refreshSelectedLabel() {
        selectedLabel.text = selectedApp?.let { "SELECTED APP\n${it.label}\n${it.packageName}" }
            ?: "No app selected — tap SELECT APP"
    }

    override fun onResume() {
        super.onResume()
        if (waiting) stepPermissions()
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (waiting) stepPermissions()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                capCode = resultCode
                capData = data
            } else {
                waiting = false
                Toast.makeText(this, "Screen capture permission denied. Visual scan is unavailable.", Toast.LENGTH_LONG).show()
                return
            }
            if (waiting) stepPermissions()
        }
    }

    @Suppress("DEPRECATION")
    private fun stepPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow Display over other apps, then return here", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !notifAsked &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifAsked = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        if (!captureAsked) {
            captureAsked = true
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
            return
        }
        val target = selectedApp
        if (target == null) {
            waiting = false
            chooseApp()
            return
        }
        waiting = false
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            putExtra("code", capCode)
            putExtra("data", capData)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
        val launch = packageManager.getLaunchIntentForPackage(target.packageName)
        if (launch != null) startActivity(launch)
        else Toast.makeText(this, "Selected app cannot be launched", Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val REQUEST_CAPTURE = 100
        private const val REQUEST_NOTIFICATIONS = 101
    }
}
