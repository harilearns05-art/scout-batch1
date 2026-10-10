package com.highwayscout

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ---- Fill these two to share potholes between phones (see README). Blank = local-only. ----
const val SUPABASE_URL = ""   // e.g. "https://abcd1234.supabase.co"
const val SUPABASE_KEY = ""   // anon public key

val MANUAL = setOf("gate", "junction", "slippery", "water")   // marked by drivers with a button

// cat: "road" (pothole / speed breaker, found by shock) or "slow", "brake", "turn" (learned from many
// vehicles) or "gate", "junction", "slippery", "water" (marked by a driver).
class Pothole(val id: String, var lat: Double, var lng: Double, var bump: Int, var pot: Int,
              val hits: MutableList<String>, val clean: MutableList<String>, val cat: String = "road") {
    val kind: String get() = if (cat != "road") cat else if (bump > pot) "bump" else "pothole"
    fun json(): JSONObject = JSONObject().put("id", id).put("lat", lat).put("lng", lng)
        .put("bump", bump).put("pot", pot).put("hits", JSONArray(hits)).put("clean", JSONArray(clean)).put("cat", cat)
    companion object {
        fun from(o: JSONObject): Pothole {
            fun arr(a: JSONArray?) = MutableList(a?.length() ?: 0) { a!!.getString(it) }
            return Pothole(o.getString("id"), o.getDouble("lat"), o.getDouble("lng"),
                o.optInt("bump"), o.optInt("pot"), arr(o.optJSONArray("hits")), arr(o.optJSONArray("clean")),
                o.optString("cat", "road"))
        }
    }
}

fun need(p: Pothole): Int = when {
    Settings.solo -> 1
    p.cat in MANUAL -> 1
    p.cat == "road" -> if (p.kind == "bump") 2 else 3
    else -> 3
}
fun cleanNeed(p: Pothole): Int = when {
    Settings.solo -> 1
    p.cat == "road" -> if (p.kind == "bump") 10 else 3
    else -> 3
}

object Settings {
    private lateinit var p: SharedPreferences
    fun init(c: Context) { if (!::p.isInitialized) p = c.getSharedPreferences("hs-set", 0) }
    var lang: Int
        get() { val v = p.getInt("lang", 0); return if (v in 0 until Phrases.names.size) v else 0 }
        set(v) { p.edit().putInt("lang", v).apply() }
    var sleep: Boolean get() = p.getBoolean("sleep", true); set(v) { p.edit().putBoolean("sleep", v).apply() }
    var sens: Int get() = p.getInt("sens", 1); set(v) { p.edit().putInt("sens", v).apply() }   // 0 high,1 med,2 low
    var solo: Boolean get() = p.getBoolean("solo", false); set(v) { p.edit().putBoolean("solo", v).apply() }
    var walk: Boolean get() = p.getBoolean("walk", false); set(v) { p.edit().putBoolean("walk", v).apply() }
}

object Scout {   // live state shared with the screen
    @Volatile var running = false
    @Volatile var speed = 0.0
    @Volatile var acc = -1f
    @Volatile var hits = 0
    @Volatile var vert = 0.0
    @Volatile var thr = 0.0
    @Volatile var alertText = ""
    @Volatile var alertT = 0L
    @Volatile var lastId = ""        // spot of the last warning (for the Yes / Not there buttons)
    @Volatile var sid = ""           // this trip's anonymous id
    @Volatile var awakeAskT = 0L     // > 0 while the "are you awake?" question is waiting
    val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
    fun log(s: String) {
        logs.add(0, java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  " + s)
        while (logs.size > 8) logs.removeAt(logs.size - 1)
    }
}

object Store {
    private val list = ArrayList<Pothole>()
    private var prefs: SharedPreferences? = null
    val online get() = SUPABASE_URL.isNotBlank() && SUPABASE_KEY.isNotBlank()

    fun init(c: Context) {
        if (prefs != null) return
        prefs = c.getSharedPreferences("hs-data", 0)
        if (!online) try {
            val a = JSONArray(prefs!!.getString("list", "[]"))
            for (i in 0 until a.length()) list.add(Pothole.from(a.getJSONObject(i)))
        } catch (e: Exception) {}
    }
    @Synchronized fun snapshot(): List<Pothole> = ArrayList(list)
    // driver feedback after a warning: yes = it was there, no = it was not there
    fun vote(id: String, sid: String, yes: Boolean) {
        val p = snapshot().firstOrNull { it.id == id } ?: return
        if (yes) { if (!p.hits.contains(sid)) p.hits.add(sid); p.clean.clear() }
        else {
            if (!p.clean.contains(sid)) p.clean.add(sid)
            if (p.clean.size >= cleanNeed(p)) { remove(p); return }
        }
        save(p)
    }
    fun add(p: Pothole) { synchronized(this) { list.add(p) }; save(p) }
    fun remove(p: Pothole) {
        synchronized(this) { list.removeAll { it.id == p.id } }
        if (online) Thread { http("DELETE", "potholes?id=eq.${p.id}") }.start() else saveLocal()
    }
    fun save(p: Pothole) {
        if (online) { val b = p.json().toString(); Thread { http("POST", "potholes?on_conflict=id", b, "resolution=merge-duplicates,return=minimal") }.start() }
        else saveLocal()
    }
    fun refresh(lat: Double, lng: Double) {
        if (!online) return
        val r = http("GET", "potholes?select=*&lat=gte.${lat - 0.05}&lat=lte.${lat + 0.05}&lng=gte.${lng - 0.05}&lng=lte.${lng + 0.05}") ?: return
        try {
            val a = JSONArray(r); val n = ArrayList<Pothole>()
            for (i in 0 until a.length()) n.add(Pothole.from(a.getJSONObject(i)))
            synchronized(this) { list.clear(); list.addAll(n) }
        } catch (e: Exception) {}
    }
    @Synchronized private fun saveLocal() {
        val a = JSONArray(); list.forEach { a.put(it.json()) }
        prefs?.edit()?.putString("list", a.toString())?.apply()
    }
    private fun http(method: String, path: String, body: String? = null, prefer: String? = null): String? = try {
        val c = URL("$SUPABASE_URL/rest/v1/$path").openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("apikey", SUPABASE_KEY); c.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
        c.setRequestProperty("Content-Type", "application/json")
        if (prefer != null) c.setRequestProperty("Prefer", prefer)
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val s = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText()
        if (code < 400) s ?: "" else null
    } catch (e: Exception) { null }
}
