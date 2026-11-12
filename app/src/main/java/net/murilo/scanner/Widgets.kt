package net.murilo.scanner

/** Um widget posicionado livremente. Coordenadas NORMALIZADAS (0..1) sobre a tela, pra funcionar
 *  igual no celular e na multimidia. O renderer multiplica por w/h reais. */
data class Widget(var type: String, var x: Float, var y: Float, var w: Float, var h: Float)

/** Catalogo de widgets disponiveis + tamanho padrao (fracao, referencia 1280x480). */
object Widgets {
    data class Spec(val type: String, val label: String, val w: Float, val h: Float)

    val catalog = listOf(
        Spec("g_rpm", "Mostrador RPM", 0.23f, 0.62f),
        Spec("g_speed", "Mostrador Velocidade", 0.25f, 0.66f),
        Spec("g_temp", "Mostrador Temp", 0.23f, 0.62f),
        Spec("bar_rpm", "Barra de RPM", 0.33f, 0.10f),
        Spec("bar_temp", "Termometro Temp", 0.05f, 0.46f),
        Spec("t_gear", "Marcha", 0.12f, 0.31f),
        Spec("t_bat", "Bateria", 0.16f, 0.19f),
        Spec("t_map", "MAP", 0.16f, 0.19f),
        Spec("t_lambda", "Lambda", 0.16f, 0.19f),
        Spec("t_inj", "Injecao", 0.16f, 0.19f),
        Spec("t_adv", "Avanco", 0.16f, 0.19f),
        Spec("t_iat", "Temp. Ar", 0.16f, 0.19f),
        Spec("t_rpmn", "RPM (numero)", 0.16f, 0.19f),
        Spec("t_spdn", "Velocidade (numero)", 0.18f, 0.19f),
        Spec("clock", "Relogio", 0.13f, 0.12f),
        Spec("grif", "Arte (grifo/gato/fogo)", 0.13f, 0.35f)
    )

    fun specOf(type: String) = catalog.firstOrNull { it.type == type }
    val labels get() = catalog.map { it.label }
}
