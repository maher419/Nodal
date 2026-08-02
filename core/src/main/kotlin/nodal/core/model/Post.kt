package nodal.core.model

import io.libp2p.core.PeerId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nodal.core.crypto.CanonicalJson
import nodal.core.crypto.Identity
import nodal.core.crypto.Signatures
import nodal.core.multiformats.Cid
import java.util.Base64

val PostJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
data class MediaRef(
    val cid: String,
    val mime: String,
    val name: String = ""
)

/**
 * الغلاف الموحد لكل شيء في Nodal: post / profile / list / like / follow / block.
 * التوقيع فوق JSON القانوني للحقول (بدون sig) — والـ CID ذاتي = تجزئة نفس البايتات.
 */
@Serializable
data class PostEnvelope(
    val v: Int = 1,
    val type: String,
    val author: String,       // PeerId base58
    val createdAt: Long,      // unix ms
    val seq: Long,            // تسلسلي تصاعدي لكل مؤلف
    val text: String = "",
    val media: List<MediaRef> = emptyList(),
    val topic: String = "",   // قناة اختيارية
    val ref: String = "",     // like/reply/repost → CID الهدف أو PeerId للمتابعة
    val payload: String = "", // list/block → JSON نصي للمدخلات
    val sig: String = ""      // Base64(Ed25519(canonical(payload fields)))
) {
    fun payloadMap(): Map<String, Any?> = linkedMapOf(
        "v" to v.toLong(),
        "type" to type,
        "author" to author,
        "createdAt" to createdAt,
        "seq" to seq,
        "text" to text,
        "media" to media.map { linkedMapOf("cid" to it.cid, "mime" to it.mime, "name" to it.name) },
        "topic" to topic,
        "ref" to ref,
        "payload" to payload
    )

    fun canonicalBytes(): ByteArray = CanonicalJson.stringify(payloadMap()).toByteArray(Charsets.UTF_8)

    /** CID ذاتي: تجزئة التمثيل القانوني — ثابت عبر الزمن وكل العقد */
    val cid: String by lazy { Cid.cidV0(canonicalBytes()) }

    /**
     * تحقق من التوقيع — يلتقط كل الاستثناءات ويعيد false:
     * author مشوّه (PeerId.fromBase58 يرمي)، sig غير صالح (Base64 يرمي)،
     * multihash غير معروف — كلها لا يجب أن تسقط العقدة أو توقف معالجة الرسائل.
     */
    fun verifySignature(): Boolean = try {
        sig.isNotEmpty() &&
            Signatures.verifyEd25519(PeerId.fromBase58(author), canonicalBytes(), Base64.getDecoder().decode(sig))
    } catch (e: Exception) {
        false
    }

    fun encode(): String = PostJson.encodeToString(serializer(), this)

    companion object {
        fun decode(s: String): PostEnvelope? = try {
            PostJson.decodeFromString(serializer(), s)
        } catch (e: Exception) {
            null
        }

        fun signed(
            identity: Identity,
            type: String,
            createdAt: Long,
            seq: Long,
            text: String = "",
            media: List<MediaRef> = emptyList(),
            topic: String = "",
            ref: String = "",
            payload: String = ""
        ): PostEnvelope {
            val unsigned = PostEnvelope(
                v = 1, type = type, author = identity.peerIdB58,
                createdAt = createdAt, seq = seq, text = text, media = media,
                topic = topic, ref = ref, payload = payload
            )
            val sig = Base64.getEncoder().encodeToString(identity.sign(unsigned.canonicalBytes()))
            return unsigned.copy(sig = sig)
        }
    }
}

object PostTypes {
    const val POST = "post"
    const val PROFILE = "profile"
    const val LIST = "list"
    const val FOLLOW = "follow"
    const val LIKE = "like"
    const val BLOCK = "block"

    val ALL = setOf(POST, PROFILE, LIST, FOLLOW, LIKE, BLOCK)
}

object Topics {
    const val GLOBAL = "/nodal/feed/1.0.0"
    fun user(peerIdB58: String): String = "/nodal/u/$peerIdB58"
}
