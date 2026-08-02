package nodal.core.multiformats

import java.security.MessageDigest

/**
 * عنونة المحتوى (CID) — من الصفر، بدون مكتبات خارجية.
 *
 * CIDv0   : base58btc(0x12 0x20 sha256(data))   — معرف المنشورات
 * CIDv1   : base32(0x01 0x55 0x12 0x20 sha256)  — معرف الوسائط والكتل (raw)
 */
object Cid {
    const val SHA2_256 = 0x12
    private const val SHA2_256_LEN = 0x20
    private const val RAW_CODEC = 0x55
    private const val CID_V1 = 0x01

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun multihash(data: ByteArray): ByteArray =
        byteArrayOf(SHA2_256.toByte(), SHA2_256_LEN.toByte()) + sha256(data)

    /** CIDv0 للمنشورات: base58btc(multihash) */
    fun cidV0(data: ByteArray): String = Base58.encode(multihash(data))

    /** CIDv1 raw للوسائط: base32(0x01 0x55 multihash) */
    fun cidV1Raw(data: ByteArray): String =
        Base32.encode(byteArrayOf(CID_V1.toByte(), RAW_CODEC.toByte(), SHA2_256.toByte(), SHA2_256_LEN.toByte()) + sha256(data))
}
