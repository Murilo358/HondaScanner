package net.murilo.scanner

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.util.Locale

/**
 * Registro AUTOMÁTICO do sistema: grava ~5 amostras/s com TODOS os sensores + GPS,
 * sem precisar "iniciar". Liga sozinho quando chegam dados do carro e encerra a sessão quando some.
 * Cada sessão é um CSV em filesDir/trips/. Sessões curtíssimas (ruído) são descartadas.
 */
object TripLogger {
    @Volatile var active = false
        private set
    @Volatile var samples = 0
        private set
    private var appCtx: Context? = null
    private var writer: BufferedWriter? = null
    private var lastWrite = 0L
    private var lastData = 0L
    var currentFile: File? = null
        private set

    const val HEADER = "time_ms,lat,lon,rpm,speed,gear,ect,iat,map,tps,battery,injDur,lambda,fuelFlow,fuelPct,economy"
    private const val GRACE_MS = 15000L   // 15s sem dados encerra a sessão
    private const val MIN_SAMPLES = 15    // sessão menor que isso é descartada (ruído)

    fun init(ctx: Context) { appCtx = ctx.applicationContext }
    fun dir(ctx: Context): File = File(ctx.filesDir, "trips").apply { mkdirs() }

    private fun hasData(d: Dash) = d.connected   // só dados REAIS: simulação não vira registro

    /** Chamado a cada ciclo do painel. Decide sozinho quando gravar. */
    fun tick(d: Dash) {
        val ctx = appCtx ?: return
        val now = System.currentTimeMillis()
        val data = hasData(d)
        if (data) lastData = now
        if (data && !active) startInternal(ctx)
        else if (active && !data && now - lastData > GRACE_MS) { stopInternal(); return }
        if (!active) return
        if (now - lastWrite < 200L) return   // ~5 Hz
        lastWrite = now
        writeSample(now, d)
    }

    private fun startInternal(ctx: Context) {
        try {
            val f = File(dir(ctx), "trip_${System.currentTimeMillis()}.csv")
            val w = f.bufferedWriter()
            w.write(HEADER); w.newLine()
            writer = w; currentFile = f; samples = 0; lastWrite = 0L; active = true
        } catch (_: Exception) {}
    }

    private fun writeSample(now: Long, d: Dash) {
        try {
            // Locale.US obrigatório: em pt-BR o decimal vira vírgula e quebra o CSV
            writer?.write(
                "%d,%.6f,%.6f,%.0f,%.0f,%d,%.0f,%.0f,%.0f,%.0f,%.1f,%.2f,%.2f,%.2f,%.0f,%.1f".format(
                    Locale.US, now, d.gpsLat, d.gpsLon, d.rpm, d.speed, d.gear, d.ect, d.iat, d.map, d.tps,
                    d.battery, d.injDur, d.lambda, d.fuelFlowLh, d.fuelPct, d.economyKmL
                )
            )
            writer?.newLine()
            samples++
            if (samples % 50 == 0) writer?.flush()
        } catch (_: Exception) {}
    }

    private fun stopInternal() {
        active = false
        try { writer?.flush(); writer?.close() } catch (_: Exception) {}
        writer = null
        val f = currentFile
        if (f != null && samples < MIN_SAMPLES) { try { f.delete() } catch (_: Exception) {} }  // descarta ruído
        currentFile = null
    }

    /** Encerra a sessão em andamento (ex.: ao fechar o app). */
    fun stop() { if (active) stopInternal() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.name.endsWith(".csv") }?.sortedByDescending { it.name } ?: emptyList()

    fun delete(f: File) { try { f.delete() } catch (_: Exception) {} }
}
