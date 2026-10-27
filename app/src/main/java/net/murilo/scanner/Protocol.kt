package net.murilo.scanner

/**
 * Protocolo do scanner (Honda OBD-I), decodificado de captura HCI real.
 *
 * Requisicao (app -> scanner), 5 bytes:  20 05 <addr> <len> <ck>
 *   ck = (0x100 - (0x20 + 0x05 + addr + len)) & 0xFF
 * Resposta (scanner -> app): vem o ECO dos 5 bytes do comando, depois:
 *   00 <L> <data[len]> <ck>   onde L = len + 3 ; ck = (0x100 - (0x00 + L + Sum(data))) & 0xFF
 *
 * Linha meia-duplex: o scanner ecoa o comando antes de responder.
 */
object Protocol {

    /** UUID padrao de Serial Port Profile (SPP). */
    const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

    /** Monta o comando de leitura de [len] bytes a partir de [addr]. */
    fun readCommand(addr: Int, len: Int): ByteArray {
        val a = addr and 0xFF
        val l = len and 0xFF
        val ck = (0x100 - (0x20 + 0x05 + a + l)) and 0xFF
        return byteArrayOf(0x20, 0x05, a.toByte(), l.toByte(), ck.toByte())
    }

    /** Tamanho total que chega na leitura: eco (5) + resposta (len + 3). */
    fun totalReplyLen(len: Int): Int = 5 + len + 3

    /** Comando de LIMPAR códigos de erro (OBD-1 Honda): 21 04 01 ck. */
    fun clearDtcCommand(): ByteArray {
        val ck = (0x100 - (0x21 + 0x04 + 0x01)) and 0xFF
        return byteArrayOf(0x21, 0x04, 0x01, ck.toByte())
    }

    /**
     * Valida e extrai os [len] bytes de dados de uma resposta completa [reply]
     * (eco + frame). Retorna null se o formato/checksum nao bater.
     */
    fun parseReply(cmd: ByteArray, reply: ByteArray, len: Int): ByteArray? {
        if (reply.size < totalReplyLen(len)) return null
        // eco
        for (i in 0 until 5) if (reply[i] != cmd[i]) return null
        val frame = reply.copyOfRange(5, 5 + len + 3)
        if (frame[0].toInt() and 0xFF != 0x00) return null
        if (frame[1].toInt() and 0xFF != len + 3) return null
        var sum = 0
        for (i in 0 until frame.size - 1) sum += frame[i].toInt() and 0xFF
        val ck = (0x100 - (sum and 0xFF)) and 0xFF
        if (ck != frame[frame.size - 1].toInt() and 0xFF) return null
        return frame.copyOfRange(2, 2 + len)
    }

    /** Blocos lidos no polling (endereco, tamanho), na ordem observada. */
    val BLOCKS = listOf(
        0x78 to 5,
        0x10 to 12,
        0x20 to 16,
        0x08 to 8,
        0x3c to 1,
        0x9a to 3,
        0x76 to 2,
    )

    /** Keep-alive lido entre cada bloco. */
    val KEEPALIVE = 0x00 to 3
}
