package com.highwayscout

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.speech.tts.TextToSpeech
import java.util.*
import kotlin.math.*

class ScoutService : Service(), SensorEventListener, LocationListener {
    private val h = Handler(Looper.getMainLooper())
    private val sid = UUID.randomUUID().toString().take(8)
    private var tts: TextToSpeech? = null
    private var wl: PowerManager.WakeLock? = null
    private lateinit var sm: SensorManager
    private lateinit var lm: LocationManager
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_ALARM, 100) }

    // location state
    private var lat = 0.0; private var lng = 0.0; private var fixT = 0L; private var hasFix = false
    private var speed = 0.0; private var heading = -1.0; private var acc = 99f
    private var pLat = 0.0; private var pLng = 0.0; private var hasPrev = false
    // motion state
    private var gx = 0.0; private var gy = 0.0; private var gz = 0.0; private var gInit = false; private var gT = 0L
    private class Smp(val t: Long, val v: Double)
    private val buf = ArrayList<Smp>()
    private var base = 1.0; private var pend = false; private var lastSpike = 0L
    private var spikeLat = 0.0; private var spikeLng = 0.0; private var spikeT = 0L
    private class Appr(var min: Double, var spike: Boolean = false, var done: Boolean = false)
    private val appr = HashMap<String, Appr>()
    private val alerted = HashMap<String, Boolean>()
    // road-learning state (slow-down zones, hard braking, sharp turns)
    private class Fix(val t: Long, val lat: Double, val lng: Double, val sp: Double, val hd: Double)
    private val fixes = ArrayList<Fix>()
    private var lastSlow = 0L; private var lastBrake = 0L; private var lastTurn = 0L; private var lastAlert = 0L
    // sleepy check state
    private var driveSec = 0L; private var stopSec = 0L; private var asking = false
    private val sleepTick = object : Runnable {
        override fun run() {
            val kmh = speed * 3.6
            if (kmh >= 15) { driveSec += 5; stopSec = 0 } else if (kmh < 3) { stopSec += 5; if (stopSec >= 600) driveSec = 0 }
            if (asking && kmh < 3) { asking = false; Scout.awakeAskT = 0L; nm().cancel(2); Scout.log("Vehicle stopped - check cancelled") }
            if (Settings.sleep && !asking && driveSec >= limitMin() * 60L) askAwake()
            h.postDelayed(this, 5000)
        }
    }
    private val refresher = object : Runnable {
        override fun run() {
            if (hasFix) { val a = lat; val b = lng; Thread { Store.refresh(a, b) }.start() }
            h.postDelayed(this, 20000)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun nm() = getSystemService(NotificationManager::class.java)

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        val act = i?.action
        if (act == "mark" || act == "awake" || act == "ask") {
            if (Scout.running) {
                if (act == "mark") markHere(i?.getStringExtra("cat") ?: "gate") else if (act == "awake") imAwake() else askAwake()
            } else stopSelf()
            return START_STICKY
        }
        if (Scout.running) {
            if (i?.getBooleanExtra("test", false) == true) alert(150, "pothole")
            return START_STICKY
        }
        Settings.init(this); Store.init(this)
        nm().createNotificationChannel(NotificationChannel("hs", "Highway Scout", NotificationManager.IMPORTANCE_LOW))
        nm().createNotificationChannel(NotificationChannel("hs-ask", "Are you awake?", NotificationManager.IMPORTANCE_HIGH))
        Scout.sid = sid
        val n = Notification.Builder(this, "hs").setContentTitle("Highway Scout is running")
            .setContentText("Scanning the road for potholes and speed breakers")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(1, n)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hs:scout").apply { acquire(12 * 3600 * 1000L) }
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                say(Phrases.started(Settings.lang))
            }
        }
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this) } catch (e: SecurityException) { Scout.log("No location permission") }
        Scout.running = true; Scout.hits = 0; Scout.log("Started (" + (if (Store.online) "shared DB" else "local only") + ")")
        h.post(refresher); h.post(sleepTick)
        return START_STICKY
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        asking = false; Scout.awakeAskT = 0L; try { nm().cancel(2) } catch (e: Exception) {}
        try { sm.unregisterListener(this); lm.removeUpdates(this) } catch (e: Exception) {}
        tts?.shutdown(); try { wl?.release() } catch (e: Exception) {}
        Scout.running = false; Scout.log("Stopped")
        super.onDestroy()
    }

    // ---------------- geo helpers ----------------
    private fun dist(a: Double, b: Double, c: Double, d: Double): Double {
        val r = 6371000.0; val dl = Math.toRadians(c - a); val dn = Math.toRadians(d - b)
        val x = sin(dl / 2).pow(2) + cos(Math.toRadians(a)) * cos(Math.toRadians(c)) * sin(dn / 2).pow(2)
        return 2 * r * asin(sqrt(x))
    }
    private fun bearing(a: Double, b: Double, c: Double, d: Double): Double {
        val y = sin(Math.toRadians(d - b)) * cos(Math.toRadians(c))
        val x = cos(Math.toRadians(a)) * sin(Math.toRadians(c)) - sin(Math.toRadians(a)) * cos(Math.toRadians(c)) * cos(Math.toRadians(d - b))
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
    private fun angDiff(a: Double, b: Double) = abs(((a - b + 540) % 360) - 180)

    // ---------------- GPS ----------------
    override fun onLocationChanged(l: Location) {
        val now = System.currentTimeMillis()
        val sp = if (l.hasSpeed()) l.speed.toDouble() else if (hasFix && l.time > fixT) dist(lat, lng, l.latitude, l.longitude) / ((l.time - fixT) / 1000.0) else 0.0
        speed = sp
        if (l.hasBearing() && sp > 1) heading = l.bearing.toDouble()
        else if (hasPrev && dist(pLat, pLng, l.latitude, l.longitude) > 5) heading = bearing(pLat, pLng, l.latitude, l.longitude)
        if (!hasPrev || dist(pLat, pLng, l.latitude, l.longitude) > 5) { pLat = l.latitude; pLng = l.longitude; hasPrev = true }
        val firstFix = !hasFix
        lat = l.latitude; lng = l.longitude; fixT = l.time; acc = l.accuracy; hasFix = true
        if (firstFix) { val la0 = lat; val ln0 = lng; Thread { Store.refresh(la0, ln0) }.start() }
        Scout.speed = sp * 3.6; Scout.acc = acc

        trackEvents(now)
        for (p in Store.snapshot()) {
            if (p.hits.size < need(p)) continue
            val d = dist(lat, lng, p.lat, p.lng)
            if (p.cat == "road") {
                if (d < 60) {                         // self-cleaning: pass without a spike
                    val st = appr.getOrPut(p.id) { Appr(d) }
                    st.min = min(st.min, d)
                    if (now - spikeT < 8000 && dist(spikeLat, spikeLng, p.lat, p.lng) <= 20) st.spike = true
                    if (!st.done && d > st.min + 15) {
                        st.done = true
                        if (st.min <= 12 && !st.spike && !p.clean.contains(sid)) {
                            p.clean.add(sid); Scout.log("Clean pass ${p.clean.size}/${cleanNeed(p)}")
                            if (p.clean.size >= cleanNeed(p)) { Scout.log("Removed (looks repaired)"); Store.remove(p) } else Store.save(p)
                        }
                    }
                } else if (d > 120) appr.remove(p.id)
            }
            val am = max(150.0, speed * 7)        // faster vehicle = earlier warning
            if (d > am + 100) alerted.remove(p.id)
            val late = p.cat != "road" && d < 25  // too close: it is probably the spot we just learned
            if (d <= am && !late && alerted[p.id] != true && now - lastAlert > 3000 && heading >= 0 && angDiff(heading, bearing(lat, lng, p.lat, p.lng)) < 50) {
                alerted[p.id] = true
                alert(max(10, (d / 10).roundToInt() * 10), p.kind, p.id)
            }
        }
    }

    // ---------------- learning from many vehicles ----------------
    private fun trackEvents(now: Long) {
        fixes.add(Fix(now, lat, lng, speed, heading))
        while (fixes.isNotEmpty() && now - fixes[0].t > 10000) fixes.removeAt(0)
        if (acc > 20f || fixes.size < 3) return
        val kmh = speed * 3.6
        // 1) hard braking: lost 3.5 m/s or more per second, starting from 30 km/h or more
        val ref = fixes.lastOrNull { val a = now - it.t; a >= 800 && a <= 2500 }
        if (ref != null) {
            val dt = (now - ref.t) / 1000.0
            if (ref.sp * 3.6 >= 30 && (ref.sp - speed) / dt >= 3.5 && now - lastBrake > 20000) { lastBrake = now; learn("brake") }
        }
        // 2) slow-down zone: speed halves (and drops 20+ km/h) within 8 seconds but the vehicle keeps moving
        val peak = (fixes.filter { now - it.t <= 8000 }.maxOfOrNull { it.sp } ?: 0.0) * 3.6
        if (peak >= 40 && kmh <= peak * 0.5 && peak - kmh >= 20 && kmh >= 5 && now - lastSlow > 30000) { lastSlow = now; learn("slow") }
        // 3) sharp turn: heading changes 35 degrees or more within about 4 seconds
        val old = fixes.firstOrNull { now - it.t <= 4500 && it.hd >= 0 }
        if (old != null && heading >= 0 && kmh >= 15 && now - old.t >= 2000 && angDiff(old.hd, heading) >= 35 && now - lastTurn > 15000) {
            lastTurn = now; learn("turn", old.lat, old.lng)
        }
    }

    private fun learn(cat: String, la: Double = lat, ln: Double = lng) {
        val radius = if (cat == "turn") 30.0 else 40.0
        var near: Pothole? = null; var nd = 1e9
        for (p in Store.snapshot()) {
            if (p.cat != cat) continue
            val d = dist(la, ln, p.lat, p.lng)
            if (d < nd) { nd = d; near = p }
        }
        val p = near
        if (p != null && nd <= radius) {
            if (!p.hits.contains(sid)) {
                val n = p.hits.size
                p.lat = (p.lat * n + la) / (n + 1); p.lng = (p.lng * n + ln) / (n + 1)
                p.hits.add(sid); p.clean.clear(); Store.save(p)
            }
            Scout.log("${Phrases.label(cat)} · ${p.hits.size}/${need(p)}")
        } else {
            Store.add(Pothole(UUID.randomUUID().toString(), la, ln, 0, 0, mutableListOf(sid), mutableListOf(), cat))
            Scout.log("New ${Phrases.label(cat)}")
        }
    }

    private fun markHere(cat: String) {                    // driver pressed a Mark button
        if (!hasFix || acc > 30f) { Scout.log("Cannot mark: GPS not ready"); return }
        val at = deadReckon(System.currentTimeMillis())
        learn(cat, at[0], at[1])
        say(Phrases.marked(Settings.lang, Phrases.name(cat, Settings.lang)))
    }

    // ---------------- sleepy check ----------------
    private fun limitMin(): Int {                          // ask more often at night and early morning
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (hr >= 22 || hr < 5) 30 else if (hr < 8) 60 else 120
    }
    private fun askAwake() {
        if (asking) return
        asking = true; Scout.awakeAskT = System.currentTimeMillis()
        val mins = (driveSec / 60).toInt()
        try { tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600) } catch (e: Exception) {}
        h.postDelayed({ say(Phrases.awake(Settings.lang, mins)) }, 650)
        val pi = PendingIntent.getService(this, 2, Intent(this, ScoutService::class.java).setAction("awake"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val action = Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_info_details), "I'M AWAKE", pi).build()
        val n = Notification.Builder(this, "hs-ask").setContentTitle("Are you awake?")
            .setContentText("Driving for $mins min. Tap I'M AWAKE. Feeling sleepy? Stop and rest.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert).addAction(action).build()
        nm().notify(2, n)
        Scout.log("Sleepy check asked ($mins min driving)")
        h.postDelayed({ escalate() }, 20000)
    }
    private fun escalate() {                               // no answer: keep sounding an alarm
        if (!asking) return
        try { tone.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1500) } catch (e: Exception) {}
        h.postDelayed({ if (asking) say(Phrases.wake(Settings.lang)) }, 1600)
        h.postDelayed({ escalate() }, 9000)
    }
    private fun imAwake() {
        asking = false; driveSec = 0; Scout.awakeAskT = 0L; nm().cancel(2)
        say(Phrases.thanks(Settings.lang)); Scout.log("Driver confirmed awake")
    }

    // ---------------- motion ----------------
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onSensorChanged(e: SensorEvent) {
        val ax = e.values[0].toDouble(); val ay = e.values[1].toDouble(); val az = e.values[2].toDouble()
        val now = System.currentTimeMillis()
        if (!gInit) { gx = ax; gy = ay; gz = az; gInit = true; gT = now; return }
        val k = 0.95
        gx = k * gx + (1 - k) * ax; gy = k * gy + (1 - k) * ay; gz = k * gz + (1 - k) * az
        val gm = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1e-6)
        val ux = gx / gm; val uy = gy / gm; val uz = gz / gm
        val lx = ax - gx; val ly = ay - gy; val lz = az - gz
        val v = lx * ux + ly * uy + lz * uz                    // + pushed up, - dropping
        val hx = lx - v * ux; val hy = ly - v * uy; val hz = lz - v * uz
        val hh = sqrt(hx * hx + hy * hy + hz * hz); val av = abs(v)
        buf.add(Smp(now, v)); while (buf.isNotEmpty() && now - buf[0].t > 1500) buf.removeAt(0)
        base = 0.98 * base + 0.02 * av                        // road roughness baseline
        Scout.vert = av
        if (now - gT < 1000 || !hasFix) return
        val kmh = speed * 3.6; val sf = 0.6 + 0.4 * min(1.0, kmh / 60)
        val floor = when (Settings.sens) { 0 -> 2.0; 1 -> 3.0; else -> 4.5 }
        val thr = max(floor * sf, 3 * base); Scout.thr = thr
        if (!pend && av > thr && av > 1.3 * hh && (Settings.walk || kmh >= 8) && now - lastSpike > 1500) {
            pend = true; lastSpike = now
            val at = deadReckon(now)
            h.postDelayed({ pend = false; classify(now, at) }, 400)
        }
    }

    private fun deadReckon(now: Long): DoubleArray {          // GPS fix is up to ~1 s old: move it forward
        var la = lat; var ln = lng
        if (heading >= 0 && speed > 1) {
            val dt = ((now - fixT) / 1000.0).coerceIn(0.0, 2.0); val d = speed * dt; val hr = Math.toRadians(heading)
            la += d * cos(hr) / 111320.0; ln += d * sin(hr) / (111320.0 * cos(Math.toRadians(la)))
        }
        return doubleArrayOf(la, ln, speed * 3.6, acc.toDouble())
    }

    private fun classify(t0: Long, at: DoubleArray) {
        val w = buf.filter { it.t >= t0 - 150 && it.t <= t0 + 400 }
        if (w.isEmpty()) return
        val pk = w.maxOf { abs(it.v) }
        val first = w.first { abs(it.v) >= 0.3 * pk }
        var peaks = 0; var last = -999L
        for (i in w.indices) {
            val x = abs(w[i].v)
            if (x >= 0.5 * pk && x >= abs(w[max(0, i - 1)].v) && x >= abs(w[min(w.size - 1, i + 1)].v) && w[i].t - last > 60) { peaks++; last = w[i].t }
        }
        val kind = if (first.v > 0) "bump" else "pothole"       // bump: pushed UP first; pothole: drops DOWN first
        if (kind == "pothole" && !Settings.walk && at[2] < 20) return
        if (at[3] > 20) { Scout.log("Skipped $kind: GPS weak ±${at[3].toInt()} m"); return }
        register(at[0], at[1], kind, "%.1f m/s² %s, %d peak".format(pk, if (first.v > 0) "↑" else "↓", peaks))
    }

    private fun register(la: Double, ln: Double, kind: String, info: String) {
        spikeLat = la; spikeLng = ln; spikeT = System.currentTimeMillis(); Scout.hits++
        var near: Pothole? = null; var nd = 1e9
        for (p in Store.snapshot()) { if (p.cat != "road") continue; val d = dist(la, ln, p.lat, p.lng); if (d < nd) { nd = d; near = p } }
        val p = near
        if (p != null && nd <= 10) {
            var ch = false
            if (!p.hits.contains(sid)) {
                val n = p.hits.size
                p.lat = (p.lat * n + la) / (n + 1); p.lng = (p.lng * n + ln) / (n + 1)
                p.hits.add(sid); if (kind == "bump") p.bump++ else p.pot++; ch = true
            }
            if (p.clean.isNotEmpty()) { p.clean.clear(); ch = true }
            if (ch) Store.save(p)
            Scout.log("$kind $info · ${p.hits.size}/${need(p)}")
        } else {
            val q = Pothole(UUID.randomUUID().toString(), la, ln, if (kind == "bump") 1 else 0, if (kind == "bump") 0 else 1, mutableListOf(sid), mutableListOf())
            Store.add(q); Scout.log("New $kind $info")
        }
    }

    // ---------------- voice ----------------
    private fun say(t: String) {
        val e = tts ?: return
        e.language = Locale(Phrases.codes[Settings.lang], "IN")
        e.speak(t, TextToSpeech.QUEUE_FLUSH, Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }, "hs")
    }
    private fun alert(d: Int, kind: String, id: String = "") {
        lastAlert = System.currentTimeMillis(); Scout.lastId = id
        val text = Phrases.alert(kind, d, Settings.lang)
        try { tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400) } catch (e: Exception) {}
        h.postDelayed({ say(text) }, 450)
        Scout.alertText = Phrases.label(kind) + " " + d + " m"; Scout.alertT = System.currentTimeMillis()
        Scout.log("ALERT ${Phrases.label(kind)} $d m ahead")
    }
}
