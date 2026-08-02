package nodal.core.multiformats

/**
 * Base32 (RFC4648، أحرف صغيرة، بلا حشو) — يستخدم لـ CIDv1.
 */
object Base32 {
    private const val ALPHA = "abcdefghijklmnopqrstuvwxyz234567"

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder(bytes.size * 8 / 5 + 1)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHA[(buffer shr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHA[(buffer shl (5 - bits)) and 0x1F])
        return sb.toString()
    }
}
