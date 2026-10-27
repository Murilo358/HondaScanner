package net.murilo.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Guarda o ultimo scanner escolhido e reconecta sozinho (auto-conectar).
 * Usado pelo app do celular e pela tela do Android Auto, compartilhando o mesmo [Shared.client].
 */
object ScannerConnector {
    private const val PREF = "scanner_prefs"
    private const val KEY_ADDR = "last_device"

    fun saveLast(ctx: Context, address: String) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_ADDR, address).apply()
        } catch (_: Exception) {}
    }

    fun lastAddress(ctx: Context): String? =
        try { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_ADDR, null) }
        catch (_: Exception) { null }

    fun hasPerm(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * Tenta reconectar ao ultimo scanner. Retorna o client (novo ou o ja ativo), ou null se
     * nao der (sem permissao, bluetooth off, nenhum scanner salvo/pareado). Nao duplica conexao.
     */
    @SuppressLint("MissingPermission")
    fun autoConnect(ctx: Context, dash: Dash, onUpdate: () -> Unit, onStatus: (String) -> Unit): ScannerClient? {
        if (Shared.client?.isRunning == true) return Shared.client
        if (!hasPerm(ctx)) return null
        val addr = lastAddress(ctx) ?: return null
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return null
        if (!adapter.isEnabled) return null
        val dev = adapter.bondedDevices?.firstOrNull { it.address == addr } ?: return null
        val c = ScannerClient(dev, dash, onUpdate, onStatus)
        Shared.client = c
        c.start()
        return c
    }
}
