package net.murilo.scanner

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Radares (pardais) p/ aviso de proximidade. Fonte: OpenStreetMap via Overpass (grátis, sem chave),
 * baixando os radares da região atual e guardando local (funciona offline depois). Também dá pra
 * IMPORTAR um CSV (lat,lon[,velocidade]) de qualquer base mais completa.
 */
object RadarDb {
    class Radar(val lat: Double, val lon: Double, val maxSpeed: Int)

    private val radars = CopyOnWriteArrayList<Radar>()
    @Volatile private var centerLat = 999.0
    @Volatile private var centerLon = 999.0
    @Volatile var loading = false
        private set
    private var ctx: Context? = null
    private const val PREF = "radar_prefs"
    // "osm" = baixa a região ao vivo (OpenStreetMap). "pardal" = base completa de um estado (projeto Pardal).
    @Volatile var sourceMode = "osm"; private set

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        try {
            val p = ctx!!.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            sourceMode = p.getString("source", "pardal") ?: "pardal"
        } catch (_: Exception) {}
        loadCache()
        if (radars.isEmpty()) loadAsset()   // base nacional embutida no app (offline, na hora)
    }

    /** Carrega a base nacional que vem junto no APK (assets/radares_brasil.csv). */
    private fun loadAsset() {
        val c = ctx ?: return
        try {
            val list = parsePardal(c.assets.open("radares_brasil.csv").bufferedReader())
            if (list.isNotEmpty()) { radars.clear(); radars.addAll(list); sourceMode = "pardal"; saveMode(); saveCache() }
            android.util.Log.d("RadarDb", "asset embutido: ${list.size} radares")
        } catch (e: Exception) { android.util.Log.w("RadarDb", "asset falhou: ${e.message}") }
    }

    /** Parse do CSV do Pardal (lat,lng,kind,limit_kmh,source,active,...) — só ativos. */
    private fun parsePardal(br: java.io.BufferedReader): ArrayList<Radar> {
        val list = ArrayList<Radar>(20000)
        br.useLines { lines ->
            lines.drop(1).forEach { ln ->
                val p = ln.split(",")
                val la = p.getOrNull(0)?.toDoubleOrNull()
                val lo = p.getOrNull(1)?.toDoubleOrNull()
                val active = p.getOrNull(5)?.trim()
                if (la != null && lo != null && active != "0") {
                    val sp = p.getOrNull(3)?.trim()?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                    list.add(Radar(la, lo, sp))
                }
            }
        }
        return list
    }

    private fun saveMode() {
        try { ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()?.putString("source", sourceMode)?.apply() } catch (_: Exception) {}
    }

    fun sourceLabel(): String = if (sourceMode == "pardal") "Pardal (Brasil inteiro)" else "OpenStreetMap (região)"

    /** Base NACIONAL completa do projeto Pardal (Brasil inteiro, ~19 mil radares, offline depois). */
    fun refreshPardal() {
        if (loading || ctx == null) return
        loading = true
        thread(name = "RadarPardal") {
            try {
                val url = URL("https://raw.githubusercontent.com/Murkiriel/pardal/main/brasil/radares.csv")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 60000
                    setRequestProperty("User-Agent", "AplicativoScanner/1.0 (net.murilo.scanner)")
                }
                val code = conn.responseCode
                if (code != 200) { android.util.Log.w("RadarDb", "Pardal HTTP $code"); conn.disconnect(); loading = false; return@thread }
                val list = parsePardal(conn.inputStream.bufferedReader())
                if (list.isNotEmpty()) {
                    radars.clear(); radars.addAll(list)
                    sourceMode = "pardal"; saveMode()
                    saveCache()
                    android.util.Log.d("RadarDb", "Pardal OK: ${list.size} radares (Brasil)")
                }
            } catch (e: Exception) {
                android.util.Log.w("RadarDb", "Pardal falhou: ${e.javaClass.simpleName} ${e.message}")
            } finally { loading = false }
        }
    }
    fun size() = radars.size
    fun all(): List<Radar> = radars.toList()
    /** Centro da última base baixada (p/ o mapa abrir onde os radares estão, mesmo sem GPS). */
    fun center(): Pair<Double, Double>? = if (centerLat < 900.0 && centerLon < 900.0) centerLat to centerLon else null

    /** Chamado a cada posição: só rebaixa a região no modo OSM (a base Pardal do estado já é completa/offline). */
    fun onLocation(lat: Double, lon: Double) {
        if (sourceMode == "pardal") return
        if (centerLat > 900.0 || haversine(lat, lon, centerLat, centerLon) > 25000.0) refresh(lat, lon)
    }

    fun refresh(lat: Double, lon: Double) {
        if (loading || ctx == null) { android.util.Log.d("RadarDb", "refresh pulado (loading=$loading ctx=${ctx!=null})"); return }
        loading = true
        sourceMode = "osm"; saveMode()
        thread(name = "RadarDownload") {
            // overpass-api.de é o mais confiável do celular (mail.ru dá erro de certificado, kumi dá timeout);
            // ele às vezes volta 502 quando lotado, então tentamos ele mais de uma vez + private.coffee de reserva
            val servers = listOf(
                "https://overpass-api.de/api/interpreter",
                "https://overpass.private.coffee/api/interpreter",
                "https://overpass-api.de/api/interpreter"
            )
            val d = 0.35   // ~35-40 km de raio
            val q = "[out:json][timeout:25];node[\"highway\"=\"speed_camera\"](${lat - d},${lon - d},${lat + d},${lon + d});out;"
            var ok = false
            for (base in servers) {
                try {
                    android.util.Log.d("RadarDb", "baixando de $base  bbox=${lat-d},${lon-d},${lat+d},${lon+d}")
                    val url = URL(base + "?data=" + URLEncoder.encode(q, "UTF-8"))
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000; readTimeout = 22000
                        setRequestProperty("User-Agent", "AplicativoScanner/1.0 (net.murilo.scanner)")
                    }
                    val code = conn.responseCode
                    if (code != 200) { android.util.Log.w("RadarDb", "HTTP $code em $base"); conn.disconnect(); continue }
                    val txt = conn.inputStream.bufferedReader().use { it.readText() }
                    val arr = JSONObject(txt).getJSONArray("elements")
                    val list = ArrayList<Radar>(arr.length())
                    for (i in 0 until arr.length()) {
                        val e = arr.getJSONObject(i)
                        val sp = e.optJSONObject("tags")?.optString("maxspeed", "")?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                        list.add(Radar(e.getDouble("lat"), e.getDouble("lon"), sp))
                    }
                    radars.clear(); radars.addAll(list)
                    centerLat = lat; centerLon = lon
                    saveCache()
                    android.util.Log.d("RadarDb", "OK: ${list.size} radares de $base")
                    ok = true
                    break
                } catch (e: Exception) {
                    android.util.Log.w("RadarDb", "falha em $base: ${e.javaClass.simpleName} ${e.message}")
                }
            }
            if (!ok) android.util.Log.e("RadarDb", "todos os servidores falharam")
            loading = false
        }
    }

    fun nearest(lat: Double, lon: Double): Pair<Radar, Double>? {
        var best: Radar? = null; var bd = Double.MAX_VALUE
        for (r in radars) { val d = haversine(lat, lon, r.lat, r.lon); if (d < bd) { bd = d; best = r } }
        return best?.let { it to bd }
    }

    fun importCsv(f: File): Int {
        return try {
            val list = ArrayList<Radar>()
            f.bufferedReader().useLines { lines ->
                lines.forEach { ln ->
                    val p = ln.split(",", ";")
                    val la = p.getOrNull(0)?.trim()?.toDoubleOrNull()
                    val lo = p.getOrNull(1)?.trim()?.toDoubleOrNull()
                    if (la != null && lo != null) list.add(Radar(la, lo, p.getOrNull(2)?.trim()?.filter { it.isDigit() }?.toIntOrNull() ?: 0))
                }
            }
            if (list.isNotEmpty()) { radars.clear(); radars.addAll(list); centerLat = list[0].lat; centerLon = list[0].lon; saveCache() }
            list.size
        } catch (_: Exception) { 0 }
    }

    private fun cacheFile() = ctx?.let { File(it.filesDir, "radars.csv") }
    private fun saveCache() {
        try {
            cacheFile()?.bufferedWriter()?.use { w ->
                w.write("#center,$centerLat,$centerLon"); w.newLine()
                for (r in radars) { w.write("${r.lat},${r.lon},${r.maxSpeed}"); w.newLine() }
            }
        } catch (_: Exception) {}
    }
    private fun loadCache() {
        try {
            val f = cacheFile() ?: return; if (!f.exists()) return
            val list = ArrayList<Radar>()
            f.bufferedReader().useLines { lines ->
                lines.forEach { ln ->
                    if (ln.startsWith("#center")) { val p = ln.split(","); centerLat = p.getOrNull(1)?.toDoubleOrNull() ?: 999.0; centerLon = p.getOrNull(2)?.toDoubleOrNull() ?: 999.0 }
                    else { val p = ln.split(","); val la = p.getOrNull(0)?.toDoubleOrNull(); val lo = p.getOrNull(1)?.toDoubleOrNull(); if (la != null && lo != null) list.add(Radar(la, lo, p.getOrNull(2)?.toIntOrNull() ?: 0)) }
                }
            }
            radars.clear(); radars.addAll(list)
        } catch (_: Exception) {}
    }

    private fun haversine(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        if (la2 > 900 || la1 > 900) return Double.MAX_VALUE
        val R = 6371000.0
        val dLa = Math.toRadians(la2 - la1); val dLo = Math.toRadians(lo2 - lo1)
        val a = sin(dLa / 2) * sin(dLa / 2) + cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLo / 2) * sin(dLo / 2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
