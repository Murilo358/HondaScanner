package net.murilo.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import androidx.appcompat.content.res.AppCompatResources
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Cluster estilo painel de carro: 3 mostradores do grifo + faixa de dados + elementos extras
 * (relogio, luzes de aviso, estatisticas, alerta, boost, glow no anel, sweep de abertura, sombra).
 * Cores do [Theme]; elementos ligados/desligados no [Feat]. Tudo no Canvas (celular + Android Auto).
 */
class ClusterRenderer(private val context: Context, numFont: Typeface? = null) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val txtL = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val txtR = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 110 }
    private val bgTexPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // limites de tamanho em px escalados pela densidade (senão ficam minúsculos em telas de alta resolução)
    private val dens = context.resources.displayMetrics.density.coerceAtLeast(1f)
    private val font = numFont ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private var griffinBmp: Bitmap? = null
    private var griffinArt = -1
    private var bgTexBmp: Bitmap? = null
    private var bgTexIdx = -1

    /** Textura de fundo selecionada (bg01..bg12), recarregada quando muda. */
    private fun bgTex(): Bitmap? {
        val sel = BgTex.selected
        if (sel == bgTexIdx) return bgTexBmp
        bgTexIdx = sel
        bgTexBmp = null
        if (sel > 0) {
            val id = BgTex.resIdFor(context, sel)
            if (id != 0) bgTexBmp = try { BitmapFactory.decodeResource(context.resources, id) } catch (_: Exception) { null }
        }
        return bgTexBmp
    }

    /** ColorMatrix: textura escura -> cor do fundo; textura clara -> fundo clareado. Segue o [col]
     *  e aparece ate em preto (modulacao, nao multiplicacao). */
    private fun tintMatrix(col: Int): ColorMatrix {
        val dr = Color.red(col).toFloat(); val dg = Color.green(col).toFloat(); val db = Color.blue(col).toFloat()
        val light = Theme.lighten(col, 0.40f)   // quanto mais alto, mais forte a textura
        val kr = (Color.red(light) - dr) / 255f
        val kg = (Color.green(light) - dg) / 255f
        val kb = (Color.blue(light) - db) / 255f
        return ColorMatrix(floatArrayOf(
            kr*0.3f, kr*0.59f, kr*0.11f, 0f, dr,
            kg*0.3f, kg*0.59f, kg*0.11f, 0f, dg,
            kb*0.3f, kb*0.59f, kb*0.11f, 0f, db,
            0f, 0f, 0f, 1f, 0f))
    }

    private var cBg = 0; private var cFace = 0; private var cFaceEdge = 0
    private var cRing = 0; private var cProgress = 0; private var cNeedle = 0; private var cRedline = 0
    private var cText = 0; private var cUnit = 0; private var cDim = 0
    private var cTick = 0; private var cTickM = 0; private var cLine = 0; private var cShift = 0
    private var lastGriffin = 0

    // estatisticas (maximos da sessao) + sweep de abertura
    private var maxRpm = 0f; private var maxSpeed = 0f; private var maxEct = 0f
    private val sweepStart = SystemClock.uptimeMillis()

    init { txt.typeface = font; txtL.typeface = font; txtR.typeface = font }

    /** Arte central dos mostradores (grifo/gato/fogo), cinza; a cor vem do tint em bmpPaint. */
    private fun griffin(): Bitmap? {
        if (griffinArt == Theme.gaugeArt) griffinBmp?.let { return it }
        val id = when (Theme.gaugeArt) {
            1 -> R.drawable.gauge_gato_gray; 2 -> R.drawable.gauge_fogo_gray
            else -> R.drawable.gauge_griffin_gray
        }
        val dr = AppCompatResources.getDrawable(context, id) ?: return null
        val b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        dr.setBounds(0, 0, 512, 512); dr.draw(Canvas(b))
        griffinBmp = b; griffinArt = Theme.gaugeArt; return b
    }

    private fun resolveTheme() {
        cBg = Theme.bg; cRing = Theme.ring; cProgress = Theme.progress; cNeedle = Theme.needle
        cRedline = Theme.redline; cText = Theme.text
        cUnit = Theme.withAlpha(cText, 175); cDim = Theme.withAlpha(cText, 155)
        cTick = Theme.lighten(cRing, 0.30f); cTickM = Theme.lighten(cRing, 0.65f)
        cShift = Theme.shiftColor
        cFace = Theme.darken(cBg, 0.45f); cFaceEdge = Theme.lighten(cBg, 0.22f)
        cLine = Theme.withAlpha(cText, 42)
        // elemento em edição no customizador PISCA (cor escolhida ⇄ contraste) pra mostrar onde aplica
        val ek = Theme.editKey
        val blink = ek != null && (SystemClock.uptimeMillis() / 450L) % 2L == 0L
        if (blink) when (ek) {
            "bg" -> cBg = flash(cBg)
            "ring" -> cRing = flash(cRing)
            "progress" -> cProgress = flash(cProgress)
            "needle" -> cNeedle = flash(cNeedle)
            "redline" -> cRedline = flash(cRedline)
            "text" -> cText = flash(cText)
        }
        val gcol = if (blink && ek == "griffin") flash(Theme.griffin) else Theme.griffin
        if (gcol != lastGriffin) {
            // branco = arte cinza original (tint neutro); qualquer outra cor tinge por multiplicação
            bmpPaint.colorFilter = if (gcol == 0xFFFFFFFF.toInt()) null
                else PorterDuffColorFilter(gcol, PorterDuff.Mode.MULTIPLY)
            lastGriffin = gcol
        }
    }

    /** Zoom do customizador: o elemento em edição desenha ~30% maior (além de piscar). */
    private fun emph(key: String) = if (Theme.editKey == key) 1.3f else 1f

    /** Fase "acesa" do pisca: escurece cor clara, clareia cor escura — contraste garantido. */
    private fun flash(c: Int): Int {
        val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        return if (lum > 140) Theme.darken(c, 0.5f) else Theme.lighten(c, 0.6f)
    }

    fun draw(c: Canvas, w: Int, h: Int, d: Dash) {
        if (w <= 0 || h <= 0) return
        resolveTheme()
        c.drawColor(cBg)
        // fundo SVG (segue a cor de fundo via tint)
        if (BgTex.selected > 0) bgTex()?.let { bmp ->
            bgTexPaint.colorFilter = ColorMatrixColorFilter(tintMatrix(cBg))
            val scl = kotlin.math.max(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
            val dw = bmp.width * scl; val dh = bmp.height * scl
            c.drawBitmap(bmp, null, RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), bgTexPaint)
        }
        if (Feat.on("texture")) {
            fill.color = Theme.withAlpha(cText, 12); fill.style = Paint.Style.FILL
            val step = (h * 0.045f).coerceAtLeast(16f)
            var yy = step / 2f
            while (yy < h) { var xx = step / 2f; while (xx < w) { c.drawCircle(xx, yy, 1.4f, fill); xx += step }; yy += step }
        }

        // estatisticas (sempre acompanha os valores reais)
        if (d.rpm > maxRpm) maxRpm = d.rpm
        if (d.speed > maxSpeed) maxSpeed = d.speed
        if (d.ect > maxEct) maxEct = d.ect

        // sweep de abertura
        val el = SystemClock.uptimeMillis() - sweepStart
        val sweeping = Feat.on("sweep") && el < 1500L
        val sf = if (sweeping) { val p = el / 1500f; if (p < 0.5f) p * 2f else (1f - p) * 2f } else 0f

        val rpmV = if (sweeping) sf * 8000f else d.rpm
        val spdV = if (sweeping) sf * 220f else d.speed
        val tmpV = if (sweeping) 40f + sf * 80f else d.ect

        val sel = Layout.selected
        when {
            // Cada layout embutido tem arranjo próprio deitado E em pé; os personalizados
            // usam coordenadas normalizadas e seguem o aparelho.
            sel == 1 -> drawAdapt(c, w, h, d, rpmV, spdV, tmpV)
            sel == 2 -> drawCartoes(c, w, h, d, rpmV, spdV, tmpV)
            sel == 3 -> drawDigital(c, w, h, d, rpmV, spdV, tmpV)
            sel == 4 -> drawParede(c, w, h, d, rpmV, spdV, tmpV)
            sel == 5 -> drawUnderground(c, w, h, d, rpmV, spdV, tmpV)
            sel == 6 -> threeBig(c, w, h, d, rpmV, spdV, tmpV)
            Layout.isCustom(sel) -> drawCustom(c, w, h, d, CustomStore.get(Layout.customIndex(sel))?.widgets, rpmV, spdV, tmpV)
            else -> drawAtual(c, w, h, d, rpmV, spdV, tmpV)
        }
    }

    /** Layout 7+: personalizado (widgets posicionados a mao). Reusa os mesmos mostradores do grifo. */
    private fun drawCustom(c: Canvas, w: Int, h: Int, d: Dash, widgets: List<Widget>?, rpmV: Float, spdV: Float, tmpV: Float) {
        if (widgets.isNullOrEmpty()) {
            txt.color = cDim; txt.textAlign = Paint.Align.CENTER; txt.textSize = (h * 0.045f).coerceIn(16f * dens, 34f * dens)
            c.drawText("Layout vazio — ☰ Personalizar ▸ Layout ▸ Editar", w / 2f, h / 2f, txt)
            return
        }
        for (wg in widgets) drawWidget(c, wg.type, wg.x * w, wg.y * h, wg.w * w, wg.h * h, d, rpmV, spdV, tmpV)
    }

    private fun drawWidget(c: Canvas, type: String, x: Float, y: Float, ww: Float, hh: Float,
                           d: Dash, rpmV: Float, spdV: Float, tmpV: Float) {
        val cx = x + ww / 2f; val cy = y + hh / 2f; val dia = min(ww, hh)
        when (type) {
            "g_rpm" -> dial(c, cx, cy, dia, 0f, 8000f, RPM_MAJ, RPM_LBL, "RPM", rpmV.toInt().toString(), rpmV, 7000f, null, shiftOrNull())
            "g_speed" -> dial(c, cx, cy, dia, 0f, 220f, SPD_MAJ, SPD_LBL, "KM/H", spdV.toInt().toString(), spdV, null, gearStr(d))
            "g_temp" -> dial(c, cx, cy, dia, 40f, 120f, TMP_MAJ, TMP_LBL, "°C", tmpV.toInt().toString(), tmpV, 108f, null)
            "bar_rpm" -> hbar(c, x, cy - hh * 0.25f, ww, hh * 0.5f, rpmV / 8000f, 7000f / 8000f)
            "bar_temp" -> tempBar(c, cx, y + hh * 0.11f, min(ww, hh * 0.3f), hh * 0.78f, tmpV)
            "t_gear" -> tileDraw(c, x, y, ww, hh, "MARCHA", gearStr(d), "")
            "t_bat" -> tileDraw(c, x, y, ww, hh, "BATERIA", fmt1(d.battery), "V")
            "t_map" -> tileDraw(c, x, y, ww, hh, "MAP", d.map.toInt().toString(), "mBar")
            "t_lambda" -> tileDraw(c, x, y, ww, hh, "LAMBDA", fmt2(d.lambda), "")
            "t_inj" -> tileDraw(c, x, y, ww, hh, "INJEÇÃO", fmt1(d.injDur), "ms")
            "t_adv" -> tileDraw(c, x, y, ww, hh, "AVANÇO", d.ignAdv.toInt().toString(), "°")
            "t_iat" -> tileDraw(c, x, y, ww, hh, "TEMP. AR", d.iat.toInt().toString(), "°C")
            "t_rpmn" -> tileDraw(c, x, y, ww, hh, "RPM", rpmV.toInt().toString(), "")
            "t_spdn" -> tileDraw(c, x, y, ww, hh, "VELOCIDADE", spdV.toInt().toString(), "km/h")
            "clock" -> tileDraw(c, x, y, ww, hh, "", clockStr(), "")
            "grif" -> blitGriffin(c, cx, cy, dia / 2f)
        }
    }

    private fun tileDraw(c: Canvas, x: Float, y: Float, w: Float, h: Float, label: String, value: String, unit: String) {
        stroke.color = cLine; stroke.strokeWidth = 1.5f
        c.drawRoundRect(RectF(x, y, x + w, y + h), h * 0.14f, h * 0.14f, stroke)
        txt.textAlign = Paint.Align.CENTER
        val hasLabel = label.isNotEmpty()
        if (hasLabel) {
            txt.color = cDim; txt.textSize = (h * 0.24f).coerceIn(10f * dens, 26f * dens)
            c.drawText(label, x + w / 2f, y + h * 0.34f, txt)
        }
        txt.color = cText
        txt.textSize = (h * (if (hasLabel) 0.42f else 0.6f)).coerceIn(14f * dens, 120f * dens)
        val t = if (unit.isEmpty()) value else "$value $unit"
        c.drawText(t, x + w / 2f, if (hasLabel) y + h * 0.82f else y + h * 0.66f, txt)
    }

    // Arrays/constantes de eixo reutilizados pelos layouts
    private val RPM_MAJ = floatArrayOf(0f,1000f,2000f,3000f,4000f,5000f,6000f,7000f,8000f)
    private val RPM_LBL = arrayOf("0","1","2","3","4","5","6","7","8")
    private val SPD_MAJ = floatArrayOf(0f,40f,80f,120f,160f,200f)
    private val SPD_LBL = arrayOf("0","40","80","120","160","200")
    private val TMP_MAJ = floatArrayOf(40f,120f)
    private val TMP_LBL = arrayOf("C","H")
    private fun gearStr(d: Dash) = if (d.gear > 0) d.gear.toString() else if (d.gear == 0) "N" else "-"
    private fun shiftOrNull() = if (Theme.shiftEnabled) Theme.shiftRpm.toFloat() else null

    /** Layout 0: como sempre foi (3 redondos, central maior, faixa embaixo).
     *  Em pé: velocímetro (o maior) em cima, RPM e temperatura lado a lado, faixa 3x2. */
    private fun drawAtual(c: Canvas, w: Int, h: Int, d: Dash, rpmV: Float, spdV: Float, tmpV: Float) {
        if (h > w) { drawAtualPe(c, w, h, d, rpmV, spdV, tmpV); return }
        val topPad = h * 0.02f
        val stripH = (h * 0.16f).coerceIn(58f * dens, 160f * dens)
        val clusterH = h - stripH - topPad
        val cy = topPad + clusterH / 2f
        val sidePad = w * 0.012f
        val gap = w * 0.006f
        val centerH = clusterH * 1.0f
        val sideH = clusterH * 0.84f
        val scale = min(1f, (w - 2 * sidePad) / (centerH + 2 * sideH + 2 * gap))
        val centerDia = centerH * scale
        val sideDia = sideH * scale
        val cxC = w / 2f
        val cxL = cxC - centerDia / 2f - gap - sideDia / 2f
        val cxR = cxC + centerDia / 2f + gap + sideDia / 2f
        dial(c, cxL, cy, sideDia, 0f, 8000f, RPM_MAJ, RPM_LBL, "RPM", rpmV.toInt().toString(), rpmV, 7000f, null, shiftOrNull())
        dial(c, cxC, cy, centerDia, 0f, 220f, SPD_MAJ, SPD_LBL, "KM/H", spdV.toInt().toString(), spdV, null, gearStr(d))
        dial(c, cxR, cy, sideDia, 40f, 120f, TMP_MAJ, TMP_LBL, "°C", tmpV.toInt().toString(), tmpV, 108f, null)
        strip(c, w, h, stripH, d)
        drawOverlays(c, w, h, d, stripH)
    }

    /** Layout 0 em pé: mesmos mostradores e extras, rearranjados para a tela em retrato. */
    private fun drawAtualPe(c: Canvas, w: Int, h: Int, d: Dash, rpmV: Float, spdV: Float, tmpV: Float) {
        val stripH = (h * 0.17f).coerceIn(96f * dens, 220f * dens)   // faixa de dados em 2 linhas
        val topPad = h * 0.075f                         // espaço das luzes + relógio
        val statsH = h * 0.13f                          // MÁX / combustível / status empilhados acima da faixa
        val areaH = h - stripH - topPad - statsH
        val centerDia = minOf(w * 0.90f, areaH * 0.58f)
        val sideDia = minOf(w * 0.47f, areaH * 0.44f)
        val cy1 = topPad + centerDia / 2f
        // relógios pequenos ancorados embaixo da área: um só respiro entre as fileiras, sem faixa morta
        val cy2 = maxOf(cy1 + centerDia / 2f + sideDia / 2f - h * 0.005f,
                        h - stripH - statsH - sideDia / 2f)
        dial(c, w / 2f, cy1, centerDia, 0f, 220f, SPD_MAJ, SPD_LBL, "KM/H", spdV.toInt().toString(), spdV, null, gearStr(d))
        dial(c, w * 0.26f, cy2, sideDia, 0f, 8000f, RPM_MAJ, RPM_LBL, "RPM", rpmV.toInt().toString(), rpmV, 7000f, null, shiftOrNull())
        dial(c, w * 0.74f, cy2, sideDia, 40f, 120f, TMP_MAJ, TMP_LBL, "°C", tmpV.toInt().toString(), tmpV, 108f, null)
        strip(c, w, h, stripH, d, cols = 3)
        drawOverlays(c, w, h, d, stripH)
    }

    /** Altura da faixa de dados: em pé ela vira 2 linhas de 3 colunas (mais alta). */
    private fun stripHOf(w: Int, h: Int) = if (h > w) (h * 0.17f).coerceIn(96f * dens, 220f * dens)
        else (h * 0.16f).coerceIn(58f * dens, 160f * dens)

    /** Layout 1: adaptativo. Decide pela proporção da tela (split do AA, deitado, em pé). */
    private fun drawAdapt(c: Canvas, w: Int, h: Int, d: Dash, rpmV: Float, spdV: Float, tmpV: Float) {
        when {
            w.toFloat() / h.toFloat() >= 2.1f -> threeBig(c, w, h, d, rpmV, spdV, tmpV)
            h > w -> twoStack(c, w, h, d, rpmV, spdV, tmpV)
            else -> twoPlusBar(c, w, h, d, rpmV, spdV, tmpV)
        }
    }

    /** Adaptativo em pé: velocidade e RPM empilhados + barra de temperatura na direita. */
    private fun twoStack(c: Canvas, w: Int, h: Int, d: Dash, rpmV: Float, spdV: Float, tmpV: Float) {
        val stripH = stripHOf(w, h)
        val topPad = h * 0.075f
        val areaH = h - stripH - topPad - h * 0.11f   // reserva do rodapé (MÁX/combustível empilhados)
        val tbw = (w * 0.07f).coerceIn(22f * dens, 48f * dens); val tgap = w * 0.03f
        val availW = w - tbw - tgap * 2f
        val dia = min(availW * 0.92f, areaH / 2.08f)
        val cx = tgap + availW / 2f
        dial(c, cx, topPad + areaH * 0.25f, dia, 0f, 220f, SPD_MAJ, SPD_LBL, "KM/H", spdV.toInt().toString(), spdV, null, gearStr(d))
        dial(c, cx, topPad + areaH * 0.76f, dia, 0f, 8000f, RPM_MAJ, RPM_LBL, "RPM", rpmV.toInt().toString(), rpmV, 7000f, null, shiftOrNull())
        val barH = areaH * 0.72f
        tempBar(c, w - tgap - tbw / 2f, topPad + (areaH - barH) / 2f, tbw, barH, tmpV)
        strip(c, w, h, stripH, d, cols = 3)
        drawOverlays(c, w, h, d, stripH)
    }

    private fun threeBig(c: Canvas, w: Int, h: Int, d: Dash, rpmV: Float, spdV: Float, tmpV: Float) {
        val stripH = stripHOf(w, h)
        if (h > w) {   // em pé: os 3 empilhados, do tamanho que couber
            val topPad = h * 0.055f
            val areaH = h - stripH - topPad - h * 0.10f   // rodapé
            val dia = min(w * 0.95f, areaH / 3.05f)
            val cx = w / 2f
            dial(c, cx, topPad + areaH * (0.5f / 3f), dia, 0f, 8000f, RPM_MAJ, RPM_LBL, "RPM", rpmV.toInt().toString(), rpmV, 7000f, null, shiftOrNull())
            dial(c, cx, topPad + areaH * (1.5f / 3f), dia, 0f, 220f, SPD_MAJ, SPD_LBL, "KM/H", spdV.toInt().toString(), spdV, null, gearStr(d))
            dial(c, cx, topPad + areaH * (2.5f / 3f), dia, 40f, 120f, TMP_MAJ, TMP_LBL, "°C", tmpV.toInt().toString(), tmpV, 108f, null)
            strip(c, w, h, stripH, d, cols = 3)
            drawOverlays(c, w, h, d, stripH)
            return
        }
        val areaH = h - stripH
        val cy = areaH / 2f + h * 0.01f
        val dia = min((w - 16f) / 3.02f, areaH * 1.10f)
        val gap = (w - dia * 3f) / 4f
        val cx0 = gap + dia / 2f; val cx1 = 2 * gap + dia * 1.5f; val cx2 = 3 * gap + dia * 2.5f
        dial(c, cx0, cy, dia, 0f, 8000f, RPM_MAJ, RPM_LBL, "RPM", rpmV.toInt().toString(), rpmV, 7000f, null, shiftOrNull())
        dial(c, cx1, cy, dia, 0f, 220f, SPD_MAJ, SPD_LBL, "KM/H", spdV.toInt().toString(), spdV, null, gearStr(d))
        dial(c, cx2, cy, dia, 40f, 120f, TMP_MAJ, TMP_LBL, "°C", tmpV.toInt().toString(), tmpV, 108f, null)
        strip(c, w, h, stripH, d)
        drawOverlays(c, w, h, d, stripH)
    }

    private fun twoPlusBar(c: Canvas, w: Int, h: Int, d: Dash, rpmV: Float, spdV: Float, tmpV: Float) {
        val stripH = (h * 0.16f).coerceIn(58f * dens, 160f * dens)
        val areaH = h - stripH
        val cy = areaH / 2f + h * 0.01f
        val tbw = (w * 0.035f).coerceIn(22f * dens, 48f * dens); val tgap = w * 0.02f
        val avail = w - tbw - tgap - 16f
        val dia = min(avail / 2.05f, areaH * 1.06f)
        val gap = (avail - dia * 2f) / 3f
        val cx1 = 8f + gap + dia / 2f; val cx2 = 8f + 2 * gap + dia * 1.5f
        dial(c, cx1, cy, dia, 0f, 8000f, RPM_MAJ, RPM_LBL, "RPM", rpmV.toInt().toString(), rpmV, 7000f, null, shiftOrNull())
        dial(c, cx2, cy, dia, 0f, 220f, SPD_MAJ, SPD_LBL, "KM/H", spdV.toInt().toString(), spdV, null, gearStr(d))
        val barH = areaH * 0.84f; val barTop = cy - barH / 2f
        tempBar(c, w - tbw / 2f - 10f, barTop, tbw, barH, tmpV)
        strip(c, w, h, stripH, d)
        drawOverlays(c, w, h, d, stripH)
    }

    /** Layout 2: cartoes quadrados (cada um com um mostrador + grifo). Em pé: 3 linhas. */
    private fun drawCartoes(c: Canvas, w: Int, h: Int, d: Dash, rpmV: Float, spdV: Float, tmpV: Float) {
        val portrait = h > w
        val stripH = stripHOf(w, h)
        val topPad = h * (if (portrait) 0.065f else 0.03f)
        val gapC = min(w, h) * 0.012f
        val cw: Float; val chH: Float
        if (portrait) { cw = w - 2 * gapC; chH = (h - stripH - topPad - h * 0.11f - 2 * gapC) / 3f }
        else { cw = (w - 4 * gapC) / 3f; chH = h - stripH - topPad * 2f }
        val labels = arrayOf("RPM", "VELOCIDADE", "MOTOR")
        for (i in 0..2) {
            val x0 = if (portrait) gapC else gapC + i * (cw + gapC)
            val top = if (portrait) topPad + i * (chH + gapC) else topPad
            cardBox(c, x0, top, x0 + cw, top + chH, 18f)
            txt.color = cDim; txt.textSize = (chH * 0.07f).coerceIn(12f * dens, 22f * dens); txt.textAlign = Paint.Align.CENTER
            c.drawText(labels[i], x0 + cw / 2f, top + chH * 0.11f, txt)
            val dia = min(cw * 0.94f, chH * 0.80f)
            val gcx = x0 + cw / 2f; val gcy = top + chH * 0.56f
            when (i) {
                0 -> dial(c, gcx, gcy, dia, 0f, 8000f, RPM_MAJ, RPM_LBL, "RPM", rpmV.toInt().toString(), rpmV, 7000f, null, shiftOrNull())
                1 -> dial(c, gcx, gcy, dia, 0f, 220f, SPD_MAJ, SPD_LBL, "KM/H", spdV.toInt().toString(), spdV, null, gearStr(d))
                else -> dial(c, gcx, gcy, dia, 40f, 120f, TMP_MAJ, TMP_LBL, "°C", tmpV.toInt().toString(), tmpV, 108f, null)
            }
        }
        strip(c, w, h, stripH, d, if (portrait) 3 else 6)
        drawOverlays(c, w, h, d, stripH)
    }

    /** Layout 3: digital minimalista (velocidade gigante + barra de RPM + marcha + temp). */
    private fun drawDigital(c: Canvas, w: Int, h: Int, d: Dash, rpmV: Float, spdV: Float, tmpV: Float) {
        val portrait = h > w
        val stripH = stripHOf(w, h)
        val areaH = h - stripH
        val barH = (h * 0.03f).coerceIn(10f * dens, 22f * dens)
        val barY = h * (if (portrait) 0.115f else 0.085f)   // em pé desce: luzes de aviso em cima
        hbar(c, w * 0.06f, barY, w * 0.88f, barH, rpmV / 8000f, 7000f / 8000f)
        txtL.color = cDim; txtL.textSize = (h * 0.03f).coerceIn(11f * dens, 18f * dens)
        c.drawText("RPM ${rpmV.toInt()}", w * 0.06f, barY - barH * 0.4f, txtL)
        txtR.color = cDim; txtR.textSize = (h * 0.03f).coerceIn(11f * dens, 18f * dens)
        c.drawText("8000", w * 0.94f, barY - barH * 0.4f, txtR)
        val cxBig = w * (if (portrait) 0.5f else 0.40f)
        val cyC = areaH * (if (portrait) 0.42f else 0.52f)
        blitGriffin(c, cxBig, cyC, min(w, h) * 0.33f)
        val bigSize = if (portrait) min(w * 0.42f, h * 0.22f) else (h * 0.46f).coerceAtMost(240f * dens)
        txt.color = cText; txt.textAlign = Paint.Align.CENTER; txt.textSize = bigSize
        c.drawText(spdV.toInt().toString(), cxBig, cyC + bigSize * 0.34f, txt)
        txt.color = cUnit; txt.textSize = (h * 0.055f).coerceIn(16f * dens, 34f * dens)
        c.drawText("KM/H", cxBig, cyC + bigSize * 0.34f + (h * 0.075f).coerceIn(20f * dens, 44f * dens), txt)
        // marcha chip (em pé: abaixo da velocidade, centralizado)
        val chipS = ((if (portrait) w * 0.30f else h * 0.26f)).coerceIn(80f * dens, 170f * dens)
        val chx = w * (if (portrait) 0.5f else 0.74f); val chy = if (portrait) areaH * 0.72f else cyC
        roundRect(c, chx - chipS / 2f, chy - chipS / 2f, chx + chipS / 2f, chy + chipS / 2f, chipS * 0.18f, Theme.lighten(cBg, 0.04f))
        stroke.color = cFaceEdge; stroke.strokeWidth = 2f
        c.drawRoundRect(RectF(chx - chipS / 2f, chy - chipS / 2f, chx + chipS / 2f, chy + chipS / 2f), chipS * 0.18f, chipS * 0.18f, stroke)
        txt.color = cText; txt.textSize = chipS * 0.6f; c.drawText(gearStr(d), chx, chy + chipS * 0.22f, txt)
        txt.color = cDim; txt.textSize = chipS * 0.14f; c.drawText("MARCHA", chx, chy - chipS * 0.28f, txt)
        // temp barra na esquerda
        val tbw = (w * (if (portrait) 0.06f else 0.03f)).coerceIn(20f * dens, 42f * dens)
        val tbH = areaH * (if (portrait) 0.42f else 0.6f)
        tempBar(c, w * 0.08f, cyC - tbH / 2f, tbw, tbH, tmpV)
        strip(c, w, h, stripH, d, if (portrait) 3 else 6)
        drawOverlays(c, w, h, d, stripH)
    }

    /** Layout 4: um mostrador grande (anel=RPM, digital=velocidade) + parede de dados.
     *  Em pé: mostrador em cima, parede embaixo em 2 colunas. */
    private fun drawParede(c: Canvas, w: Int, h: Int, d: Dash, rpmV: Float, spdV: Float, tmpV: Float) {
        if (h > w) {
            val topPad = h * 0.075f
            val dia = min(w * 0.84f, h * 0.40f)
            val cx = w / 2f; val cy = topPad + dia / 2f
            dial(c, cx, cy, dia, 0f, 8000f, RPM_MAJ, RPM_LBL, "KM/H", spdV.toInt().toString(), rpmV, 7000f, gearStr(d), shiftOrNull())
            val wallTop = cy + dia / 2f + h * 0.05f
            txt.color = cDim; txt.textAlign = Paint.Align.CENTER; txt.textSize = (h * 0.022f).coerceIn(12f * dens, 20f * dens)
            c.drawText("RPM ${rpmV.toInt()}", cx, wallTop - h * 0.022f, txt)
            val mp = w * 0.03f
            dataWall(c, mp, wallTop, w - 2 * mp, h * 0.84f - wallTop, d, cols = 2)
            drawOverlays(c, w, h, d, 0f)
            return
        }
        val m = w * 0.015f
        val dia = min(h * 0.9f, w * 0.44f)
        val cxL = m + dia / 2f; val cyL = h * 0.52f
        dial(c, cxL, cyL, dia, 0f, 8000f, RPM_MAJ, RPM_LBL, "KM/H", spdV.toInt().toString(), rpmV, 7000f, gearStr(d), shiftOrNull())
        txt.color = cDim; txt.textAlign = Paint.Align.CENTER; txt.textSize = (h * 0.035f).coerceIn(12f * dens, 20f * dens)
        c.drawText("RPM ${rpmV.toInt()}", cxL, h * 0.10f, txt)
        val wallX = cxL + dia / 2f + m; val wallTop = h * 0.17f; val wallW = w - wallX - m; val wallH = h * 0.78f
        dataWall(c, wallX, wallTop, wallW, wallH, d)
        drawOverlays(c, w, h, d, 0f)
    }

    /** Layout 5: Underground — tacometro central grande + cards (laterais; em pé, 2×2 embaixo). */
    private fun drawUnderground(c: Canvas, w: Int, h: Int, d: Dash, rpmV: Float, spdV: Float, tmpV: Float) {
        val portrait = h > w
        val stripH = stripHOf(w, h)
        val areaH = h - stripH
        val boost = (d.map - 1013f) / 1000f
        if (portrait) {
            val topPad = h * 0.075f
            val dia = min(w * 0.88f, areaH * 0.46f)
            val cx = w / 2f; val cy = topPad + dia / 2f
            dial(c, cx, cy, dia, 0f, 8000f, RPM_MAJ, RPM_LBL, "KM/H", spdV.toInt().toString(), rpmV, 7000f, gearStr(d), shiftOrNull())
            val gap = w * 0.03f
            val cardW = (w - 3 * gap) / 2f; val cardH = areaH * 0.095f
            val y0 = cy + dia / 2f + h * 0.02f
            sideCard(c, gap, y0, cardW, cardH, if (boost >= 0f) "BOOST" else "VÁCUO", "${fmt2(abs(boost))} bar")
            sideCard(c, gap * 2 + cardW, y0, cardW, cardH, "TEMP", "${d.ect.toInt()}°C")
            sideCard(c, gap, y0 + cardH + gap, cardW, cardH, "LAMBDA", fmt2(d.lambda))
            sideCard(c, gap * 2 + cardW, y0 + cardH + gap, cardW, cardH, "BATERIA", "${fmt1(d.battery)}v")
        } else {
            val cx = w / 2f; val cy = areaH * 0.56f
            val dia = min(areaH * 0.84f, w * 0.5f)
            dial(c, cx, cy, dia, 0f, 8000f, RPM_MAJ, RPM_LBL, "KM/H", spdV.toInt().toString(), rpmV, 7000f, gearStr(d), shiftOrNull())
            val cardW = (cx - dia / 2f - w * 0.03f).coerceIn(80f * dens, 260f * dens)
            sideCard(c, w * 0.02f, areaH * 0.26f, cardW, areaH * 0.20f, if (boost >= 0f) "BOOST" else "VÁCUO", "${fmt2(abs(boost))} bar")
            sideCard(c, w * 0.02f, areaH * 0.52f, cardW, areaH * 0.20f, "TEMP", "${d.ect.toInt()}°C")
            sideCard(c, w - w * 0.02f - cardW, areaH * 0.26f, cardW, areaH * 0.20f, "LAMBDA", fmt2(d.lambda))
            sideCard(c, w - w * 0.02f - cardW, areaH * 0.52f, cardW, areaH * 0.20f, "BATERIA", "${fmt1(d.battery)}v")
        }
        strip(c, w, h, stripH, d, if (portrait) 3 else 6)
        drawOverlays(c, w, h, d, stripH)
    }

    // --- helpers dos layouts ---
    private fun blitGriffin(c: Canvas, cx: Float, cy: Float, half: Float) {
        val hz = half * emph("griffin")
        griffin()?.let { c.drawBitmap(it, null, RectF(cx - hz, cy - hz, cx + hz, cy + hz), bmpPaint) }
    }

    private fun tempBar(c: Canvas, cx: Float, top: Float, barW: Float, barH: Float, value: Float) {
        val frac = ((value - 40f) / 80f).coerceIn(0f, 1f)
        val l = cx - barW / 2f; val r = cx + barW / 2f
        fill.style = Paint.Style.FILL
        fill.color = Theme.withAlpha(cRing, 150); c.drawRoundRect(RectF(l, top, r, top + barH), barW / 2f, barW / 2f, fill)
        val fh = barH * frac
        fill.color = if (value >= 108f) cRedline else cProgress
        c.drawRoundRect(RectF(l, top + barH - fh, r, top + barH), barW / 2f, barW / 2f, fill)
        txt.textAlign = Paint.Align.CENTER
        txt.color = cRedline; txt.textSize = barW * 0.75f; c.drawText("H", cx, top - barW * 0.35f, txt)
        txt.color = cUnit; c.drawText("C", cx, top + barH + barW * 0.95f, txt)
        txt.color = cText; txt.textSize = barW * 1.05f; c.drawText("${value.toInt()}°", cx, top + barH / 2f + barW * 0.35f, txt)
    }

    private fun hbar(c: Canvas, l: Float, top: Float, w: Float, barH: Float, frac: Float, redFrac: Float) {
        fill.style = Paint.Style.FILL
        fill.color = Theme.withAlpha(cRing, 150); c.drawRoundRect(RectF(l, top, l + w, top + barH), barH / 2f, barH / 2f, fill)
        if (redFrac in 0f..1f) {
            fill.color = Theme.withAlpha(cRedline, 90)
            c.drawRoundRect(RectF(l + w * redFrac, top, l + w, top + barH), barH / 2f, barH / 2f, fill)
        }
        val fr = frac.coerceIn(0f, 1f)
        fill.color = if (fr >= redFrac) cShift else cProgress
        if (fr > 0f) c.drawRoundRect(RectF(l, top, l + w * fr, top + barH), barH / 2f, barH / 2f, fill)
    }

    private fun cardBox(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float) {
        fill.style = Paint.Style.FILL; fill.color = Theme.lighten(cBg, 0.03f)
        c.drawRoundRect(RectF(l, t, r, b), rad, rad, fill)
        stroke.color = cFaceEdge; stroke.strokeWidth = 2f; c.drawRoundRect(RectF(l, t, r, b), rad, rad, stroke)
    }

    private fun sideCard(c: Canvas, x: Float, y: Float, w: Float, h: Float, label: String, value: String) {
        fill.style = Paint.Style.FILL; fill.color = Theme.lighten(cBg, 0.04f)
        c.drawRoundRect(RectF(x, y, x + w, y + h), 12f, 12f, fill)
        stroke.color = cFaceEdge; stroke.strokeWidth = 1.5f; c.drawRoundRect(RectF(x, y, x + w, y + h), 12f, 12f, stroke)
        txt.textAlign = Paint.Align.CENTER
        txt.color = cDim; txt.textSize = (h * 0.26f).coerceIn(11f * dens, 20f * dens); c.drawText(label, x + w / 2f, y + h * 0.38f, txt)
        txt.color = cText; txt.textSize = (h * 0.40f).coerceIn(15f * dens, 32f * dens); c.drawText(value, x + w / 2f, y + h * 0.80f, txt)
    }

    private fun dataWall(c: Canvas, x: Float, y: Float, w: Float, h: Float, d: Dash, cols: Int = 3) {
        val cells = arrayOf(
            Triple("TEMP", "${d.ect.toInt()}°C", if (d.ect >= Feat.TEMP_WARN) cRedline else cText),
            Triple("BATERIA", "${fmt1(d.battery)}V", cText),
            Triple("MAP", "${d.map.toInt()}", cText),
            Triple("LAMBDA", fmt2(d.lambda), cText),
            Triple("INJEÇÃO", "${fmt1(d.injDur)}ms", cText),
            Triple("AVANÇO", "${d.ignAdv.toInt()}°", cText))
        val rows = (cells.size + cols - 1) / cols; val g = w * 0.02f
        val cw = (w - g * (cols - 1)) / cols; val ch = (h - g * (rows - 1)) / rows
        for (i in cells.indices) {
            val cx0 = x + (i % cols) * (cw + g); val cy0 = y + (i / cols) * (ch + g)
            fill.style = Paint.Style.FILL; fill.color = Theme.lighten(cBg, 0.04f)
            c.drawRoundRect(RectF(cx0, cy0, cx0 + cw, cy0 + ch), 12f, 12f, fill)
            stroke.color = cFaceEdge; stroke.strokeWidth = 1.5f; c.drawRoundRect(RectF(cx0, cy0, cx0 + cw, cy0 + ch), 12f, 12f, stroke)
            txt.textAlign = Paint.Align.CENTER
            txt.color = cDim; txt.textSize = (ch * 0.18f).coerceIn(11f * dens, 22f * dens); c.drawText(cells[i].first, cx0 + cw / 2f, cy0 + ch * 0.32f, txt)
            txt.color = cells[i].third; txt.textSize = (ch * 0.34f).coerceIn(16f * dens, 40f * dens); c.drawText(cells[i].second, cx0 + cw / 2f, cy0 + ch * 0.72f, txt)
        }
    }

    private fun aOf(v: Float, vmin: Float, vmax: Float) =
        -135f + 270f * ((v - vmin) / (vmax - vmin)).coerceIn(0f, 1f)

    private fun dial(c: Canvas, cx: Float, cy: Float, dia: Float, vmin: Float, vmax: Float,
                     majors: FloatArray, labels: Array<String>, unit: String, bigText: String,
                     value: Float, redFrom: Float?, gearText: String?, shiftRpm: Float? = null) {
        val s = dia / 480f
        fun x(g: Float, r: Float) = cx + (r * sin(Math.toRadians(g.toDouble())).toFloat()) * s
        fun y(g: Float, r: Float) = cy - (r * cos(Math.toRadians(g.toDouble())).toFloat()) * s
        fun oval(r: Float) = RectF(cx - r * s, cy - r * s, cx + r * s, cy + r * s)

        // face (com relevo se ligado)
        if (Feat.on("depth")) {
            // sombra externa (ajuda em fundo claro)
            shade.shader = RadialGradient(cx, cy + 14f * s, 256f * s,
                intArrayOf(0x66000000, 0x00000000), floatArrayOf(0.84f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy + 14f * s, 256f * s, shade)
            // face: gradiente que destaca do fundo mesmo no PRETO (piso claro no centro-topo)
            shade.shader = RadialGradient(cx, cy - 60f * s, 300f * s,
                intArrayOf(Theme.lighten(cBg, 0.17f), Theme.lighten(cBg, 0.05f), Theme.darken(cBg, 0.1f)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, 236f * s, shade)
            shade.shader = null
            // bisel: rim CLARO em cima, ESCURO embaixo -> relevo (funciona em qualquer fundo)
            stroke.strokeWidth = 4.5f * s
            stroke.color = Theme.withAlpha(Color.WHITE, 60); c.drawArc(oval(233f), -150f, 120f, false, stroke)
            stroke.color = Theme.withAlpha(Color.BLACK, 130); c.drawArc(oval(233f), 30f, 120f, false, stroke)
        } else {
            fill.color = cFace; c.drawCircle(cx, cy, 236f * s, fill)
        }
        stroke.color = cFaceEdge; stroke.strokeWidth = 3f * s; c.drawCircle(cx, cy, 236f * s, stroke)

        // anel base
        arc.color = cRing; arc.strokeWidth = 11f * s * emph("ring")
        c.drawArc(oval(210f), -225f, 270f, false, arc)
        // preenchimento que segue o ponteiro (+ glow); editando a cor dele, aparece mesmo parado
        var frac = ((value - vmin) / (vmax - vmin)).coerceIn(0f, 1f)
        if (Theme.editKey == "progress") frac = frac.coerceAtLeast(0.55f)
        val nearShift = shiftRpm != null && value >= shiftRpm - 600f
        val progColor = if (nearShift) cShift else cProgress
        if (frac > 0f) {
            val pe = emph("progress")
            if (Feat.on("glow")) {
                arc.color = Theme.withAlpha(progColor, 55); arc.strokeWidth = 36f * s * pe
                c.drawArc(oval(210f), -225f, 270f * frac, false, arc)
                arc.color = Theme.withAlpha(progColor, 115); arc.strokeWidth = 20f * s * pe
                c.drawArc(oval(210f), -225f, 270f * frac, false, arc)
            }
            arc.color = progColor; arc.strokeWidth = 11f * s * pe
            c.drawArc(oval(210f), -225f, 270f * frac, false, arc)
        }
        // zona vermelha (limite) por cima
        if (redFrom != null) {
            val a0 = aOf(redFrom, vmin, vmax)
            arc.color = cRedline; arc.strokeWidth = 11f * s * emph("redline")
            c.drawArc(oval(210f), a0 - 90f, aOf(vmax, vmin, vmax) - a0, false, arc)
        }
        // arte central: as três preenchem o disco (r246 do canvas 512); 204 encaixa a arte
        // dentro do anel de RPM, sem lavar ring/ticks
        val gz = 204f * s * emph("griffin")
        griffin()?.let { c.drawBitmap(it, null, RectF(cx - gz, cy - gz, cx + gz, cy + gz), bmpPaint) }
        // ticks + numeros
        stroke.color = cTick; stroke.strokeWidth = 2f * s
        val nminor = (majors.size - 1) * 2
        for (i in 0..nminor) {
            val g = -135f + 270f * i / nminor
            c.drawLine(x(g,204f), y(g,204f), x(g,212f), y(g,212f), stroke)
        }
        stroke.color = cTickM; stroke.strokeWidth = 4f * s
        txt.color = cText; txt.textSize = 26f * s
        for (i in majors.indices) {
            val g = aOf(majors[i], vmin, vmax)
            c.drawLine(x(g,196f), y(g,196f), x(g,214f), y(g,214f), stroke)
            c.drawText(labels[i], x(g,174f), y(g,174f) + 9f * s, txt)
        }
        // leitura digital
        txt.color = cText; txt.textSize = 60f * s * emph("text")
        c.drawText(bigText, cx, cy + (374f - 256f) * s, txt)
        txt.color = cUnit; txt.textSize = 22f * s
        c.drawText(unit, cx, cy + (408f - 256f) * s, txt)
        if (gearText != null) {
            txt.color = cText; txt.textSize = 66f * s
            c.drawText(gearText, cx + (348f - 256f) * s, cy + (332f - 256f) * s, txt)
        }
        // ponteiro
        val g = aOf(value, vmin, vmax)
        val ns = s * emph("needle")
        c.save(); c.rotate(g, cx, cy)
        val p = Path()
        p.moveTo(cx, cy + (70f - 256f) * ns); p.lineTo(cx + (264f - 256f) * ns, cy)
        p.lineTo(cx, cy + (300f - 256f) * ns); p.lineTo(cx + (248f - 256f) * ns, cy); p.close()
        fill.color = cNeedle; c.drawPath(p, fill)
        c.restore()
        fill.color = Theme.darken(cBg, 0.2f); c.drawCircle(cx, cy, 20f * s, fill)
        stroke.color = cFaceEdge; stroke.strokeWidth = 3f * s; c.drawCircle(cx, cy, 20f * s, stroke)
        fill.color = cNeedle; c.drawCircle(cx, cy, 7f * s, fill)

        // shift light (previewShift = editando no customizador, acende pra ser visível)
        if (shiftRpm != null && (value >= shiftRpm || Theme.previewShift) &&
            (System.currentTimeMillis() / 60L) % 2L == 0L) {
            arc.color = cShift; arc.strokeWidth = 11f * s
            c.drawArc(oval(210f), -225f, 270f, false, arc)
            stroke.color = cShift; stroke.strokeWidth = 10f * s
            c.drawCircle(cx, cy, 236f * s, stroke)
        }
    }

    private fun fmt1(v: Float) = String.format("%.1f", v)
    private fun fmt2(v: Float) = String.format("%.2f", v)

    private fun roundRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int) {
        fill.color = color; fill.style = Paint.Style.FILL
        c.drawRoundRect(RectF(l, t, r, b), rad, rad, fill)
    }

    private fun drawOverlays(c: Canvas, w: Int, h: Int, d: Dash, stripH: Float) {
        val m = h * 0.03f
        val portrait = h > w
        // MÁX / combustivel / status / ECO: desenhados no fim por drawBottomInfo (em pe, empilhados)
        // relogio (canto superior direito) + boost embaixo
        if (Feat.on("clock")) {
            val cs = (h * 0.045f).coerceIn(14f * dens, 30f * dens)
            txtR.textSize = cs; txtR.color = cText
            c.drawText(clockStr(), w - m, m + cs, txtR)
        }
        if (Feat.on("boost")) {
            val bs = (h * 0.03f).coerceIn(11f * dens, 20f * dens)
            val boost = (d.map - 1013f) / 1000f
            val lbl = if (boost >= 0f) "BOOST" else "VÁCUO"
            txtR.textSize = bs; txtR.color = cUnit
            val cs = if (Feat.on("clock")) (h * 0.045f).coerceIn(14f * dens, 30f * dens) else 0f
            c.drawText("$lbl ${fmt2(abs(boost))} bar", w - m, m + cs + bs * 1.5f, txtR)
        }
        // luzes de aviso (centro superior; em pe, espremidas entre o hamburger e o relogio)
        if (Feat.on("tell")) {
            txt.textAlign = Paint.Align.CENTER
            val lbls = arrayOf("TEMP", "BAT", "INJ")
            val lit = booleanArrayOf(d.ect >= Feat.TEMP_WARN, d.battery in 5f..Feat.BAT_WARN, d.mil)
            val bgs = intArrayOf(0xFFD44E63.toInt(), 0xFFFFD600.toInt(), 0xFFFF9100.toInt())
            val fgs = intArrayOf(Color.WHITE, 0xFF06222E.toInt(), 0xFF06222E.toInt())
            var bs = (h * (if (portrait) 0.030f else 0.042f)).coerceIn(11f * dens, 28f * dens)
            val leftZone = if (portrait) 62f * dens else 0f   // botao hamburger (54dp + margem)
            val rightZone = if (portrait && Feat.on("clock")) {
                txtR.textSize = (h * 0.045f).coerceIn(14f * dens, 30f * dens); txtR.measureText(clockStr()) + m * 2f
            } else 0f
            val availW = w - leftZone - rightZone
            txt.textSize = bs
            var total = 0f; for (l in lbls) total += txt.measureText(l) + bs * 1.4f; total += bs * 0.5f * 2
            if (total > availW) { bs *= availW / total; txt.textSize = bs }   // encolhe pra caber
            val padX = bs * 0.7f; val gap = bs * 0.5f; val bh = bs * 1.7f
            val ws = FloatArray(3) { txt.measureText(lbls[it]) + 2 * padX }
            total = ws.sum() + gap * 2
            val by = h * (if (portrait) 0.018f else 0.02f)
            var bx = leftZone + (availW - total) / 2f
            for (i in 0..2) {
                val bw = ws[i]
                roundRect(c, bx, by, bx + bw, by + bh, bh * 0.3f, if (lit[i]) bgs[i] else Theme.withAlpha(cText, 22))
                txt.color = if (lit[i]) fgs[i] else Theme.withAlpha(cText, 95)
                c.drawText(lbls[i], bx + bw / 2f, by + bh * 0.68f, txt)
                bx += bw + gap
            }
        }
        // alerta (pisca)
        if (Feat.on("alert")) {
            val overheat = d.ect >= Feat.TEMP_ALERT
            val lowbat = d.battery in 5f..Feat.BAT_ALERT
            if ((overheat || lowbat) && (System.currentTimeMillis() / 400L) % 2L == 0L) {
                val msg = if (overheat) "⚠ TEMPERATURA ALTA" else "⚠ BATERIA BAIXA"
                val fs = (h * 0.055f).coerceIn(16f * dens, 38f * dens)
                txt.textSize = fs; txt.textAlign = Paint.Align.CENTER
                val bw = txt.measureText(msg) + fs * 1.6f; val bh = fs * 1.7f
                val bx = (w - bw) / 2f; val byy = h * 0.12f
                roundRect(c, bx, byy, bx + bw, byy + bh, bh * 0.3f, 0xFFD44E63.toInt())
                txt.color = Color.WHITE
                c.drawText(msg, w / 2f, byy + bh * 0.66f, txt)
            }
        }
        // ---- MÁX / combustivel / status de temperatura / ECO (acima da faixa) ----
        drawBottomInfo(c, w, h, d, stripH, m, portrait)
        // ---- aviso de RADAR (centro, abaixo das luzes) ----
        if (Feat.on("radar") && d.radarDistM in 0f..700f) {
            val near = d.radarDistM < 300f
            val fs = (h * 0.052f).coerceIn(15f * dens, 34f * dens)
            txt.textSize = fs; txt.textAlign = Paint.Align.CENTER
            val sp = if (d.radarMaxSpeed > 0) "  ${d.radarMaxSpeed}" else ""
            val msg = "◉ RADAR ${d.radarDistM.toInt()}m$sp"
            val bw = txt.measureText(msg) + fs * 1.4f; val bh = fs * 1.7f
            val bx = (w - bw) / 2f; val by = h * 0.11f
            roundRect(c, bx, by, bx + bw, by + bh, bh * 0.3f, if (near) 0xFFD44E63.toInt() else 0xFFFFB300.toInt())
            txt.color = if (near) Color.WHITE else 0xFF06222E.toInt()
            c.drawText(msg, w / 2f, by + bh * 0.68f, txt)
        }
    }

    /** MÁX, combustivel, status de temperatura e ECO — logo acima da faixa.
     *  Deitado: MÁX na esquerda, o resto na direita (cabe de sobra). Em pe a largura e curta e os dois
     *  lados se sobrepunham, entao fica tudo centralizado e empilhado. */
    private fun drawBottomInfo(c: Canvas, w: Int, h: Int, d: Dash, stripH: Float, m: Float, portrait: Boolean) {
        val lineH = (h * 0.045f).coerceIn(14f * dens, 26f * dens)
        val fs = (h * 0.03f).coerceIn(12f * dens, 22f * dens)
        val econ = if (d.economyKmL > 0f) String.format("%.1f km/L", d.economyKmL) else "-- km/L"
        // antes do primeiro "tanque cheio" o nivel e inventado: mostra -- em vez de um numero
        val fuelStr = if (FuelCalc.everFilled)
            "COMB ${d.fuelPct.toInt()}%  ·  $econ  ·  ${d.autonomyKm.toInt()} km"
        else "COMB --  ·  $econ"
        val (tempStr, tempCol) = tempStatus(d)
        val ecoOn = Feat.on("eco") && d.tps in 1f..35f && d.speed > 15f

        if (portrait) {
            txt.textAlign = Paint.Align.CENTER
            var ry = h - stripH - m
            if (ecoOn) { txt.textSize = lineH * 0.82f; txt.color = 0xFF2DC06E.toInt(); c.drawText("ECO", w / 2f, ry, txt); ry -= lineH }
            if (Feat.on("fuel")) { txt.textSize = lineH * 0.82f; txt.color = cText; c.drawText(fuelStr, w / 2f, ry, txt); ry -= lineH }
            if (Feat.on("tempind")) { txt.textSize = lineH * 0.82f; txt.color = tempCol; c.drawText("● $tempStr", w / 2f, ry, txt); ry -= lineH }
            if (Feat.on("stats")) {
                txt.textSize = fs * 0.95f; txt.color = cDim
                c.drawText("MÁX  RPM ${maxRpm.toInt()}  ·  VEL ${maxSpeed.toInt()}  ·  TEMP ${maxEct.toInt()}°", w / 2f, ry, txt)
            }
            return
        }

        if (Feat.on("stats")) {
            val base = h - stripH - fs * 1.5f   // folga acima da faixa (nao encostar)
            txtL.textSize = fs; txtL.color = cText
            c.drawText("RPM ${maxRpm.toInt()}  ·  VEL ${maxSpeed.toInt()}  ·  TEMP ${maxEct.toInt()}°", m, base, txtL)
            txtL.textSize = fs * 0.78f; txtL.color = cDim
            c.drawText("MÁX", m, base - fs * 1.2f, txtL)
        }
        var ry = h - stripH - m
        if (Feat.on("fuel")) { txtR.textSize = lineH * 0.82f; txtR.color = cText; c.drawText(fuelStr, w - m, ry, txtR); ry -= lineH }
        if (Feat.on("tempind")) { txtR.textSize = lineH * 0.82f; txtR.color = tempCol; c.drawText("● $tempStr", w - m, ry, txtR); ry -= lineH }
        if (ecoOn) { txtR.textSize = lineH * 0.82f; txtR.color = 0xFF2DC06E.toInt(); c.drawText("ECO", w - m, ry, txtR) }
    }

    private fun tempStatus(d: Dash): Pair<String, Int> = when {
        d.ect < Feat.WARMUP_TEMP -> "FRIO — não forçar" to 0xFF49C0E0.toInt()
        d.ect < Feat.IDEAL_TEMP  -> "AQUECENDO" to 0xFFFFB300.toInt()
        d.ect < Feat.TEMP_WARN   -> "PRONTO" to 0xFF2DC06E.toInt()
        else                     -> "QUENTE" to 0xFFD44E63.toInt()
    }

    private fun clockStr(): String {
        val cal = Calendar.getInstance()
        return String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }

    private fun strip(c: Canvas, w: Int, h: Int, stripH: Float, d: Dash, cols: Int = 6) {
        val top = h - stripH
        val labels = arrayOf("BATERIA","TEMP. AR","MAP","LAMBDA","INJEÇÃO","AVANÇO")
        val vals = arrayOf(fmt1(d.battery), d.iat.toInt().toString(), d.map.toInt().toString(),
            fmt2(d.lambda), fmt1(d.injDur), d.ignAdv.toInt().toString())
        val units = arrayOf("V","°C","mBar","","ms","°")
        val rows = (labels.size + cols - 1) / cols
        val cw = w.toFloat() / cols
        val ch = stripH / rows
        stroke.color = cLine; stroke.strokeWidth = 1.5f
        for (r in 0 until rows) c.drawLine(0f, top + r * ch, w.toFloat(), top + r * ch, stroke)
        val labelSize = (ch * 0.26f).coerceAtMost(18f * dens)
        val valueSize = (ch * 0.44f).coerceAtMost(30f * dens)
        for (i in labels.indices) {
            val col = i % cols
            val cellTop = top + (i / cols) * ch
            val cxCell = cw * (col + 0.5f)
            val cy = cellTop + ch * 0.5f
            if (col > 0) { stroke.color = cLine; stroke.strokeWidth = 1f
                c.drawLine(cw * col, cellTop + ch * 0.18f, cw * col, cellTop + ch * 0.82f, stroke) }
            txt.color = cDim; txt.textSize = labelSize; txt.textAlign = Paint.Align.CENTER
            c.drawText(labels[i], cxCell, cy - ch * 0.14f, txt)
            txtL.textSize = valueSize
            val vw = txtL.measureText(vals[i])
            val uSize = valueSize * 0.5f
            txtL.textSize = uSize
            val uw = txtL.measureText(" " + units[i])
            val startX = cxCell - (vw + uw) / 2f
            val baseY = cy + ch * 0.26f
            txtL.textSize = valueSize; txtL.color = cText
            c.drawText(vals[i], startX, baseY, txtL)
            txtL.textSize = uSize; txtL.color = cUnit
            c.drawText(" " + units[i], startX + vw, baseY, txtL)
        }
    }
}
