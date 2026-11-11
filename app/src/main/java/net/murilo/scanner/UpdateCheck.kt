package net.murilo.scanner

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
                        .setPositiveButton("Atualizar") { _, _ -> download(act, url) }
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

    private fun download(act: Activity, url: String) {
        toast(act, "Baixando atualização…")
        Thread {
            try {
                val f = File(File(act.cacheDir, "shared").apply { mkdirs() }, "update.apk")
                (URL(url).openConnection() as HttpURLConnection)
                    .apply { connectTimeout = 10000; readTimeout = 120000; instanceFollowRedirects = true }
                    .inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                val uri = FileProvider.getUriForFile(act, "${act.packageName}.fileprovider", f)
                act.runOnUiThread {
                    act.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            } catch (_: Exception) {
                act.runOnUiThread { toast(act, "Falha ao baixar a atualização") }
            }
        }.start()
    }

    private fun toast(act: Activity, s: String) =
        android.widget.Toast.makeText(act, s, android.widget.Toast.LENGTH_LONG).show()
}
