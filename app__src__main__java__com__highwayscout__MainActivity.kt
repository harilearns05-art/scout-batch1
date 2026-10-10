package com.highwayscout

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private lateinit var go: Button; private lateinit var banner: TextView; private lateinit var info: TextView
    private lateinit var lang: Button; private lateinit var sens: Button; private lateinit var solo: Button
    private lateinit var walk: Button; private lateinit var logv: TextView
    private lateinit var sleepBtn: Button; private lateinit var awakeBtn: Button; private lateinit var fb: LinearLayout
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // Crash catcher: if the app crashes, the next start shows the error text on screen so it can be fixed.
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { getSharedPreferences("hs-crash", 0).edit().putString("t", android.util.Log.getStackTraceString(e)).commit() } catch (x: Throwable) {}
            if (prev != null) prev.uncaughtException(t, e)
        }
        val saved = getSharedPreferences("hs-crash", 0).getString("t", null)
        if (saved != null) {
            getSharedPreferences("hs-crash", 0).edit().clear().commit()
            showCrash(saved)
            return
        }
        try { build() } catch (e: Throwable) { showCrash(android.util.Log.getStackTraceString(e)) }
    }

    private fun showCrash(msg: String) {
        val tv = TextView(this)
        tv.text = "Highway Scout crashed. Please send a screenshot of this screen.\n\n" + msg
        tv.textSize = 12f; tv.setTextColor(Color.WHITE); tv.setPadding(24, 60, 24, 24); tv.setTextIsSelectable(true)
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF7F1D1D.toInt()); addView(tv) })
    }

    private fun build() {
        Settings.init(this); Store.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 56, 32, 40); setBackgroundColor(0xFF0B0F14.toInt()) }
        fun lp(top: Int = 20) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top }
        fun text(size: Float, color: Int = 0xFFF5F7FA.toInt()) = TextView(this).apply { textSize = size; setTextColor(color); root.addView(this, lp()) }
        fun button(color: Int, fg: Int = Color.WHITE, size: Float = 20f, click: () -> Unit) = Button(this).apply {
            textSize = size; setTextColor(fg); setBackgroundColor(color); setAllCaps(false); setOnClickListener { click() }; root.addView(this, lp()) }

        fun rowOf(vararg items: Pair<String, () -> Unit>): LinearLayout {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (item in items) {
                val b = Button(this)
                b.text = item.first; b.textSize = 16f; b.setAllCaps(false); b.setTextColor(Color.WHITE)
                b.setBackgroundColor(0xFF1E293B.toInt()); b.setOnClickListener { item.second() }
                r.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 0, 4, 0) })
            }
            root.addView(r, lp())
            return r
        }

        text(26f).text = "🚧 Highway Scout"
        banner = text(30f, Color.WHITE).apply { gravity = Gravity.CENTER; setBackgroundColor(0xFFEF4444.toInt()); setPadding(20, 40, 20, 40); visibility = View.GONE }
        awakeBtn = button(0xFFF59E0B.toInt(), 0xFF1A1200.toInt(), 30f) { startService(Intent(this, ScoutService::class.java).setAction("awake")) }
        awakeBtn.text = "😮 I'M AWAKE"; awakeBtn.visibility = View.GONE
        fb = rowOf("👍 Yes, it was there" to { vote(true) }, "👎 Not there" to { vote(false) })
        fb.visibility = View.GONE
        go = button(0xFF22C55E.toInt(), 0xFF04130A.toInt(), 38f) { toggle() }.apply { minHeight = 340 }
        info = text(20f)
        text(16f, 0xFF9AA7B5.toInt()).text = "MARK THIS SPOT FOR OTHER DRIVERS"
        rowOf("🚪 Gate" to { mark("gate") }, "✚ Junction" to { mark("junction") })
        rowOf("💧 Water" to { mark("water") }, "⚠ Slippery" to { mark("slippery") })
        lang = button(0xFF161D27.toInt()) { Settings.lang = (Settings.lang + 1) % Phrases.names.size; labels() }
        sens = button(0xFF161D27.toInt()) { Settings.sens = (Settings.sens + 1) % 3; labels() }
        solo = button(0xFF161D27.toInt()) { Settings.solo = !Settings.solo; labels() }
        walk = button(0xFF161D27.toInt()) { Settings.walk = !Settings.walk; labels() }
        sleepBtn = button(0xFF161D27.toInt()) { Settings.sleep = !Settings.sleep; labels() }
        button(0xFF38BDF8.toInt(), 0xFF001018.toInt()) {
            if (Scout.running) startService(Intent(this, ScoutService::class.java).putExtra("test", true))
            else Toast.makeText(this, "Press START first, then test", Toast.LENGTH_SHORT).show() }.text = "🔊 Test voice alert"
        button(0xFF38BDF8.toInt(), 0xFF001018.toInt()) {
            if (Scout.running) startService(Intent(this, ScoutService::class.java).setAction("ask"))
            else Toast.makeText(this, "Press START first, then test", Toast.LENGTH_SHORT).show() }.text = "😴 Test sleepy check"
        text(16f, 0xFF9AA7B5.toInt()).text = "EVENT LOG"
        logv = text(15f)
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF0B0F14.toInt()); addView(root) })
        labels(); tick()
    }

    private fun labels() {
        lang.text = "Voice language: " + Phrases.names[Settings.lang]
        sens.text = "Sensitivity: " + listOf("High", "Medium", "Low")[Settings.sens]
        solo.text = "Solo test (1 hit = danger): " + if (Settings.solo) "ON" else "off"
        walk.text = "Walk test (ignore speed limit): " + if (Settings.walk) "ON" else "off"
        sleepBtn.text = "Sleepy check (asks if you are awake): " + if (Settings.sleep) "ON" else "off"
    }

    private fun tick() {
        go.text = if (Scout.running) "STOP" else "START"
        go.setBackgroundColor(if (Scout.running) 0xFFEF4444.toInt() else 0xFF22C55E.toInt())
        go.setTextColor(if (Scout.running) Color.WHITE else 0xFF04130A.toInt())
        val zones = Store.snapshot().count { it.hits.size >= need(it) }
        info.text = "Speed %.0f km/h   ·   GPS ±%s m\nYour hits %d   ·   Danger zones %d\nShock %.1f  (trigger > %.1f)\nData: %s".format(
            Scout.speed, if (Scout.acc >= 0) Scout.acc.toInt().toString() else "–", Scout.hits, zones, Scout.vert, Scout.thr,
            if (Store.online) "shared database" else "this phone only")
        banner.visibility = if (System.currentTimeMillis() - Scout.alertT < 6000) View.VISIBLE else View.GONE
        banner.text = "⚠ " + Scout.alertText
        logv.text = Scout.logs.joinToString("\n")
        awakeBtn.visibility = if (Scout.awakeAskT > 0L) View.VISIBLE else View.GONE
        fb.visibility = if (Scout.lastId.isNotEmpty() && System.currentTimeMillis() - Scout.alertT < 20000) View.VISIBLE else View.GONE
        h.postDelayed({ tick() }, 500)
    }

    private fun mark(cat: String) {
        if (Scout.running) startService(Intent(this, ScoutService::class.java).setAction("mark").putExtra("cat", cat))
        else Toast.makeText(this, "Press START first", Toast.LENGTH_SHORT).show()
    }
    private fun vote(yes: Boolean) {
        val id = Scout.lastId
        if (id.isEmpty()) return
        Store.vote(id, Scout.sid, yes)
        Scout.lastId = ""
        Toast.makeText(this, if (yes) "Thanks - confirmed" else "Thanks - noted", Toast.LENGTH_SHORT).show()
    }

    private fun toggle() {
        if (Scout.running) { stopService(Intent(this, ScoutService::class.java)); return }
        val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { requestPermissions(need.toTypedArray(), 1); return }
        launch()
    }
    private fun launch() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) try {
            startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {}
        startForegroundService(Intent(this, ScoutService::class.java))
    }
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) launch()
        else Toast.makeText(this, "Location permission is required", Toast.LENGTH_LONG).show()
    }
    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }
}
