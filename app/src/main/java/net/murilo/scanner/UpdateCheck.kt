package net.murilo.scanner

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualização pelo Release do GitHub: compara a última tag publicada com a versão instalada
 * e, se houver mais nova, baixa o APK do release e abre o instalador do sistema.
 */
object UpdateCheck {
    private const val LATEST = "https://api.github.com/repos/Murilo358/HondaScanner/releases/latest"
    private const val PLAY = "com.android.vending"
    private const val KING = "com.example.kinginstaller"
    private const val APK_MIME = "application/vnd.android.package-archive"

    /** [manual] = pedido pelo menu: também avisa "já atualizado"/falhas. A checagem de abertura é silenciosa. */
    fun check(act: Activity, manual: Boolean) {
        Thread {
            try {
                val conn = URL(LATEST).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val tag = json.optString("tag_name").removePrefix("v")
                var apkUrl: String? = null
                val assets = json.optJSONArray("assets")
                for (i in 0 until (assets?.length() ?: 0)) {
                    val a = assets!!.getJSONObject(i)
                    val name = a.optString("name")
                    // o release tem dois APKs; o do app é o que não é o Instalador
                    if (name.endsWith(".apk") && !name.contains("Instalador")) {
                        apkUrl = a.optString("browser_download_url"); break
                    }
                }
                if (tag.isBlank() || apkUrl == null) throw IllegalStateException("release sem APK")
                if (!newer(tag, BuildConfig.VERSION_NAME)) {
                    if (manual) act.runOnUiThread { toast(act, "Já está na última versão (v${BuildConfig.VERSION_NAME})") }
                    return@Thread
                }
                val url = apkUrl
                act.runOnUiThread {
                    if (act.isFinishing) return@runOnUiThread
                    AlertDialog.Builder(act).setTitle("Atualização v$tag disponível")
                        .setMessage("Você está na v${BuildConfig.VERSION_NAME}. Baixar e instalar agora?")
                        .setPositiveButton("Atualizar") { _, _ -> download(act, url, tag) }
                        .setNegativeButton("Depois", null).show()
                }
            } catch (_: Exception) {
                if (manual) act.runOnUiThread { toast(act, "Não consegui checar atualização (sem internet ou release indisponível)") }
            }
        }.start()
    }

    private fun newer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.split(".").map { p -> p.filter { it.isDigit() }.toIntOrNull() ?: 0 }
        val r = parts(remote); val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun download(act: Activity, url: String, tag: String) {
        toast(act, "Baixando atualização…")
        Thread {
            try {
                val f = File(File(act.cacheDir, "shared").apply { mkdirs() }, "update.apk")
                (URL(url).openConnection() as HttpURLConnection)
                    .apply { connectTimeout = 10000; readTimeout = 120000; instanceFollowRedirects = true }
                    .inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                act.runOnUiThread { install(act, f, tag) }
            } catch (_: Exception) {
                act.runOnUiThread { toast(act, "Falha ao baixar a atualização") }
            }
        }.start()
    }

    /** O app foi instalado "como Play"? (esquema do instalador/KingInstaller p/ Android Auto) */
    private fun installedViaPlay(act: Activity): Boolean = try {
        if (Build.VERSION.SDK_INT >= 30)
            act.packageManager.getInstallSourceInfo(act.packageName).installingPackageName == PLAY
        else
            @Suppress("DEPRECATION") act.packageManager.getInstallerPackageName(act.packageName) == PLAY
    } catch (_: Exception) { false }

    /** Instala SEM perder a origem Play de cada esquema (sem ela o app some do Android Auto). */
    private fun install(act: Activity, f: File, tag: String) {
        val uri = FileProvider.getUriForFile(act, "${act.packageName}.fileprovider", f)
        if (Build.VERSION.SDK_INT < 31) {
            // Android <= 11 (central/celulares antigos): o intent legado aceita declarar a Play
            // como origem — mesmo método do módulo installer
            @Suppress("DEPRECATION")
            act.startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, PLAY)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            })
            return
        }
        if (installedViaPlay(act)) {
            // 12+: instalar direto troca a origem (vira packageinstaller) e o app some do
            // Android Auto. Só o KingInstaller+Shizuku preserva — entrega o APK pra ele.
            AlertDialog.Builder(act).setTitle("Instalar pelo KingInstaller")
                .setMessage("Neste aparelho a atualização precisa ir pelo KingInstaller para o app continuar aparecendo no Android Auto (origem Play), como na primeira instalação.")
                .setPositiveButton("Abrir") { _, _ -> handToKing(act, f, uri, tag) }
                .setNegativeButton("Depois", null).show()
            return
        }
        // sem esquema de origem: instalador normal do sistema
        // shortcut: no 13+ com source=STORE a atualização direta perde o STORE; se algum
        // aparelho depender disso, copiar o installSession() do módulo installer
        act.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun handToKing(act: Activity, f: File, uri: android.net.Uri, tag: String) {
        // cópia em Downloads: é o fluxo que o KingInstaller já conhece, e serve de reserva
        val name = "Scanner-v$tag.apk"
        saveToDownloads(act, f, name)
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME); setPackage(KING)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        } catch (_: Exception) {}
        val launch = act.packageManager.getLaunchIntentForPackage(KING)
        if (launch != null) {
            toast(act, "Escolha Downloads/$name no KingInstaller")
            try { act.startActivity(launch); return } catch (_: Exception) {}
        }
        toast(act, "Instale o KingInstaller e abra Downloads/$name")
    }

    private fun saveToDownloads(act: Activity, f: File, name: String) {
        try {
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, APK_MIME)
            }
            val uri = act.contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return
            act.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        } catch (_: Exception) {}
    }

    private fun toast(act: Activity, s: String) =
        android.widget.Toast.makeText(act, s, android.widget.Toast.LENGTH_LONG).show()
}
