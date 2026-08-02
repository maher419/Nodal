package nodal.core.multiformats

/**
 * Base58btc (Bitcoin alphabet) — ترميز/فك ترميز من الصفر.
 * يستخدم لـ CIDv0 ومعرفات PeerId (base58btc في libp2p).
 */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEX = IntArray(128) { -1 }.also { arr ->
        ALPHABET.forEachIndexed { i, c -> arr[c.code] = i }
    }

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        var zeros = 0
        while (zeros < bytes.size && bytes[zeros].toInt() == 0) zeros++
        val data = bytes.copyOfRange(zeros, bytes.size)
        val size = data.size * 138 / 100 + 1
        val buf = IntArray(size)
        var length = 0
        for (b in data) {
            var carry = b.toInt() and 0xFF
            var i = 0
            for (j in size - 1 downTo 0) {
                if (carry == 0 && i >= length) break
                carry += 256 * buf[j]
                buf[j] = carry % 58
                carry /= 58
                i++
            }
            length = i
        }
        var j = 0
        while (j < size && buf[j] == 0) j++
        val sb = StringBuilder()
        repeat(zeros) { sb.append('1') }
        while (j < size) sb.append(ALPHABET[buf[j++]])
        return sb.toString()
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        var zeros = 0
        while (zeros < input.length && input[zeros] == '1') zeros++
        val data = input.substring(zeros)
        val size = data.length * 733 / 1000 + 1
        val buf = IntArray(size)
        var length = 0
        for (c in data) {
            val v = INDEX.getOrNull(c.code) ?: throw IllegalArgumentException("حرف base58 غير صالح: $c")
            var carry = v
            var i = 0
            for (j in size - 1 downTo 0) {
                if (carry == 0 && i >= length) break
                carry += 58 * buf[j]
                buf[j] = carry % 256
                carry /= 256
                i++
            }
            length = i
        }
        var j = 0
        while (j < size && buf[j] == 0) j++
        val out = ByteArray(zeros + (size - j))
        var k = 0
        while (j < size) out[zeros + k++] = buf[j++].toByte()
        return out
    }
}
