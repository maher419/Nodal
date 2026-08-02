package nodal.core.crypto

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.PubKey
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.crypto.keys.unmarshalEd25519PrivateKey
import io.libp2p.crypto.keys.unmarshalEd25519PublicKey
import java.util.Base64

/**
 * الهوية اللامركزية: زوج مفاتيح Ed25519.
 * المفتاح الخاص = الحساب؛ المفتاح العام = العنوان (PeerId) — بلا أي سلطة.
 */
class Identity(val privKey: PrivKey) {
    val pubKey: PubKey get() = privKey.publicKey()
    val peerId: PeerId get() = PeerId.fromPubKey(pubKey)
    val peerIdB58: String get() = peerId.toBase58()

    fun sign(data: ByteArray): ByteArray = privKey.sign(data)

    /** تصدير المفتاح الخاص (Base64) — للنسخ الاحتياطي المشفر فقط.
     *  bytes() يُرجع protobuf؛ unmarshal ينتظر الخام 32 بايت → نستخرج حقل Data. */
    fun exportSecret(): String =
        Base64.getEncoder().encodeToString(extractProtobufDataField(privKey.bytes()))

    companion object {
        fun generate(): Identity = Identity(generateEd25519KeyPair().first)
        fun fromSecret(b64: String): Identity = Identity(unmarshalEd25519PrivateKey(Base64.getDecoder().decode(b64)))
    }
}

/**
 * تحليل protobuf يدوي: استخراج حقل Data (رقم 2) — يعمل للمفتاح الخاص والعام.
 * libp2p crypto.proto: message { required KeyType Type = 1; required bytes Data = 2; }
 */
fun extractProtobufDataField(proto: ByteArray): ByteArray {
    var i = 0
    while (i < proto.size) {
        val tag = proto[i].toInt() and 0xFF
        i++
        val wireType = tag and 0x07
        val field = tag shr 3
        if (wireType == 2) { // length-delimited
            var len = 0
            var shift = 0
            while (true) {
                val b = proto[i].toInt() and 0xFF
                i++
                len = len or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            if (field == 2) return proto.copyOfRange(i, i + len)
            i += len
        } else { // varint
            while (true) {
                val b = proto[i].toInt() and 0xFF
                i++
                if (b and 0x80 == 0) break
            }
        }
    }
    throw IllegalArgumentException("لا يوجد حقل Data في protobuf")
}

/**
 * استخراج المفتاح العام من PeerId — تحليل يدوي:
 * نمط identity-multihash: إما 0x00 0x20 + مفتاح خام (32) أو 0x00 0x24 + protobuf (36)
 */
object PeerKeyUtils {
    fun pubKeyBytesFromPeerId(peerId: PeerId): ByteArray {
        val bytes = peerId.bytes
        require(bytes.size >= 2 && bytes[0].toInt() == 0x00) { "ليس PeerId بنمط identity-multihash" }
        return when (bytes[1].toInt()) {
            0x20 -> bytes.copyOfRange(2, bytes.size)                 // مفتاح خام مباشرة
            0x24 -> extractProtobufDataField(bytes.copyOfRange(2, bytes.size)) // protobuf → حقل Data
            else -> throw IllegalArgumentException("طول multihash غير معروف: ${bytes[1]}")
        }
    }
}

object Signatures {
    /** التحقق من توقيع Ed25519 بمعرفة PeerId الكاتب فقط — بلا استدعاء أي خدمة */
    fun verifyEd25519(authorPeerId: PeerId, data: ByteArray, sig: ByteArray): Boolean = try {
        val pub = unmarshalEd25519PublicKey(PeerKeyUtils.pubKeyBytesFromPeerId(authorPeerId))
        pub.verify(data, sig)
    } catch (e: Exception) {
        false
    }
}
