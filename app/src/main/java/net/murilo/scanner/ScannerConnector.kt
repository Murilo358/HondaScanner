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
    private const val KEY_SPP = "spp_mac"
    private const val KEY_AUTO = "auto_connect"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun saveLast(ctx: Context, address: String) {
        try { prefs(ctx).edit().putString(KEY_ADDR, address).apply() } catch (_: Exception) {}
    }

    fun lastAddress(ctx: Context): String? =
        try { prefs(ctx).getString(KEY_ADDR, null) } catch (_: Exception) { null }

    /** MAC do scanner OBD já conectado pela central (reconecta direto sem procurar de novo). */
    fun saveSppMac(ctx: Context, mac: String) {
        try { prefs(ctx).edit().putString(KEY_SPP, mac).apply() } catch (_: Exception) {}
    }

    fun sppMac(ctx: Context): String? =
        try { prefs(ctx).getString(KEY_SPP, null) } catch (_: Exception) { null }

    /** Conectar automaticamente ao abrir / ficar tentando. Vem LIGADO por padrão. */
    fun isAutoConnect(ctx: Context): Boolean =
        try { prefs(ctx).getBoolean(KEY_AUTO, true) } catch (_: Exception) { true }

    fun setAutoConnect(ctx: Context, on: Boolean) {
        try { prefs(ctx).edit().putBoolean(KEY_AUTO, on).apply() } catch (_: Exception) {}
    }

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
        val transport = chooseTransport(ctx) ?: return null
        val c = ScannerClient(transport, dash, onUpdate, onStatus)
        Shared.client = c
        c.start()
        return c
    }

    /** Central NWD → usa o Bluetooth do fabricante (BTFeature); celular → RFCOMM no aparelho pareado. */
    @SuppressLint("MissingPermission")
    fun chooseTransport(ctx: Context): ScannerTransport? {
        // Central NWD: só reconecta sozinho num scanner já escolhido no seletor; senão espera a escolha.
        if (NwdSppTransport.present(ctx)) return if (sppMac(ctx) != null) NwdSppTransport(ctx) else null
        if (!hasPerm(ctx)) return null
        val addr = lastAddress(ctx) ?: return null
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return null
        if (!adapter.isEnabled) return null
        val dev = adapter.bondedDevices?.firstOrNull { it.address == addr } ?: return null
        return SocketTransport(dev)
    }
}
