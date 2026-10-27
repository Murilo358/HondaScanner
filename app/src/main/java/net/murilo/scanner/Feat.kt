package net.murilo.scanner

import android.content.Context

/** Liga/desliga dos elementos do painel (relogio, luzes, estatisticas, alerta, boost, glow,
 *  sweep, sombra, textura). Salvo em SharedPreferences; lido ao vivo pelo ClusterRenderer. */
object Feat {
    private const val PREF = "feat_prefs"

    val labels = linkedMapOf(
        "clock" to "Relógio", "tell" to "Luzes de aviso", "stats" to "Estatísticas (máx)",
        "alert" to "Alerta temp/bateria", "boost" to "Boost", "glow" to "Glow no anel",
        "sweep" to "Sweep de abertura", "depth" to "Sombra nos gauges", "texture" to "Textura de fundo",
        "fuel" to "Combustível (nível/autonomia)", "tempind" to "Indicador de aquecimento", "eco" to "Ícone ECO",
        "radar" to "Aviso de radar"
    )
    private val defaults = mapOf(
        "clock" to true, "tell" to true, "stats" to true, "alert" to true, "boost" to true,
        "glow" to true, "sweep" to true, "depth" to true, "texture" to false,
        "fuel" to true, "tempind" to true, "eco" to true, "radar" to true
    )
    private val state = HashMap(defaults)

    // limiares CONFIGURAVEIS (graus C / volts / rpm). Salvos em prefs.
    @JvmField var TEMP_WARN = 106f; @JvmField var TEMP_ALERT = 112f
    @JvmField var BAT_WARN = 12.0f; @JvmField var BAT_ALERT = 11.5f
    @JvmField var WARMUP_TEMP = 60f    // abaixo = motor FRIO (nao forçar)
    @JvmField var IDEAL_TEMP = 80f     // acima = motor PRONTO p/ acelerar
    @JvmField var REDLINE_RPM = 7000f  // aviso de redline
    // chaves dos limiares (p/ a tela de config)
    val thresholds = linkedMapOf(
        "TEMP_WARN" to "Temp. aviso (°C)", "TEMP_ALERT" to "Temp. alerta (°C)",
        "BAT_WARN" to "Bateria aviso (V)", "BAT_ALERT" to "Bateria alerta (V)",
        "WARMUP_TEMP" to "Motor frio abaixo de (°C)", "IDEAL_TEMP" to "Motor pronto acima de (°C)",
        "REDLINE_RPM" to "Redline (RPM)"
    )

    private var ctx: Context? = null
    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        try {
            val p = ctx!!.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            for (k in defaults.keys) state[k] = p.getBoolean(k, defaults[k]!!)
            TEMP_WARN = p.getFloat("TEMP_WARN", TEMP_WARN); TEMP_ALERT = p.getFloat("TEMP_ALERT", TEMP_ALERT)
            BAT_WARN = p.getFloat("BAT_WARN", BAT_WARN); BAT_ALERT = p.getFloat("BAT_ALERT", BAT_ALERT)
            WARMUP_TEMP = p.getFloat("WARMUP_TEMP", WARMUP_TEMP); IDEAL_TEMP = p.getFloat("IDEAL_TEMP", IDEAL_TEMP)
            REDLINE_RPM = p.getFloat("REDLINE_RPM", REDLINE_RPM)
        } catch (_: Exception) {}
    }

    fun on(key: String) = state[key] ?: true

    fun toggle(key: String, v: Boolean) {
        state[key] = v
        try { ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()?.putBoolean(key, v)?.apply() } catch (_: Exception) {}
    }

    fun threshold(key: String): Float = when (key) {
        "TEMP_WARN" -> TEMP_WARN; "TEMP_ALERT" -> TEMP_ALERT; "BAT_WARN" -> BAT_WARN; "BAT_ALERT" -> BAT_ALERT
        "WARMUP_TEMP" -> WARMUP_TEMP; "IDEAL_TEMP" -> IDEAL_TEMP; "REDLINE_RPM" -> REDLINE_RPM; else -> 0f
    }

    fun setThreshold(key: String, v: Float) {
        when (key) {
            "TEMP_WARN" -> TEMP_WARN = v; "TEMP_ALERT" -> TEMP_ALERT = v; "BAT_WARN" -> BAT_WARN = v
            "BAT_ALERT" -> BAT_ALERT = v; "WARMUP_TEMP" -> WARMUP_TEMP = v; "IDEAL_TEMP" -> IDEAL_TEMP = v
            "REDLINE_RPM" -> REDLINE_RPM = v
        }
        try { ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()?.putFloat(key, v)?.apply() } catch (_: Exception) {}
    }
}
