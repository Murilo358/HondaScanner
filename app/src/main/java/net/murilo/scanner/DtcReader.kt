package net.murilo.scanner

/**
 * Códigos de erro (DTC) do Honda OBD-1, lidos da memória da ECU em 0x40–0x5F (o scanner já pola
 * esses blocos -> ficam em dash.rawBlocks). Para o código N: endereço = 0x40 + N/2, nibble = par→baixo,
 * ímpar→alto; se o nibble != 0, o código N está presente. Limpar = ScannerClient.clearDtcRequested.
 */
object DtcReader {
    data class Dtc(val code: Int, val desc: String)

    private val TABLE = mapOf(
        0 to "ECU interna", 1 to "Sonda O2 (dianteira)", 3 to "MAP (pressão)", 4 to "CKP (rotação)",
        5 to "MAP (circuito)", 6 to "ECT (temp. líquido)", 7 to "TPS (borboleta)", 8 to "TDC (ponto morto)",
        9 to "CYP (cilindro)", 10 to "IAT (temp. admissão)", 12 to "EGR", 13 to "BARO (pressão atm.)",
        14 to "IAC/EACV (marcha lenta)", 15 to "Saída de ignição", 16 to "Injetor", 17 to "VSS (velocidade)",
        19 to "Lock-up (câmbio aut.)", 20 to "Carga elétrica", 21 to "Solenoide VTEC", 22 to "Pressão VTEC",
        23 to "Sensor de detonação", 30 to "Sinal FI A (aut.)", 31 to "Sinal FI B (aut.)",
        41 to "Aquecedor da sonda O2", 43 to "Sistema de combustível", 45 to "Mistura (metering)",
        48 to "Sonda LAF/O2", 54 to "Detonação", 61 to "Sonda O2 (dianteira)", 63 to "Sonda O2 (traseira)",
        71 to "Falha de combustão cil. 1", 72 to "Falha cil. 2", 73 to "Falha cil. 3", 74 to "Falha cil. 4"
    )

    fun hasData(dash: Dash) = dash.rawBlocks[0x40] != null || dash.rawBlocks[0x50] != null

    fun read(dash: Dash): List<Dtc> {
        val b40 = dash.rawBlocks[0x40]; val b50 = dash.rawBlocks[0x50]
        if (b40 == null && b50 == null) return emptyList()
        val out = ArrayList<Dtc>()
        for (n in 0..63) {
            val addr = 0x40 + n / 2
            val byte = (if (addr < 0x50) b40?.getOrNull(addr - 0x40) else b50?.getOrNull(addr - 0x50))
                ?.toInt()?.and(0xFF) ?: continue
            val nib = if (n % 2 == 0) (byte and 0x0F) else ((byte shr 4) and 0x0F)
            if (nib != 0) out.add(Dtc(n, TABLE[n] ?: "Código $n (ver manual)"))
        }
        return out
    }
}
