package nodal.core

import io.libp2p.core.PeerId
import kotlinx.coroutines.test.runTest
import nodal.core.crypto.CanonicalJson
import nodal.core.crypto.Identity
import nodal.core.crypto.PeerKeyUtils
import nodal.core.crypto.Signatures
import nodal.core.crypto.extractProtobufDataField
import nodal.core.model.MediaRef
import nodal.core.model.PostEnvelope
import nodal.core.model.PostTypes
import nodal.core.multiformats.Base58
import nodal.core.multiformats.Cid
import nodal.core.net.Feed
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiformatsTest {

    @Test
    fun `base58 round trip`() {
        val random = Random(42)
        repeat(200) { size ->
            val data = ByteArray(size) { random.nextInt(256).toByte() }
            val encoded = Base58.encode(data)
            val decoded = Base58.decode(encoded)
            assertContentEquals(data, decoded, "حلقة base58 فشلت لحجم $size")
        }
    }

    @Test
    fun `base58 known vector`() {
        // ناقل اختبار معروف من مواصفات Bitcoin
        assertEquals("StV1DL6CwTryKyV", Base58.encode("hello world".toByteArray()))
    }

    @Test
    fun `base58 leading zeros preserved`() {
        val data = byteArrayOf(0, 0, 0, 1, 2, 3)
        assertEquals("111Ldp", Base58.encode(data)) // 3 أصفار → 3 أحرف '1'
        assertContentEquals(data, Base58.decode("111Ldp"))
    }

    @Test
    fun `cidv0 of empty data matches computed reference`() {
        // قيمة محسوبة ومتحقق منها خارجيًا: base58btc(0x12 0x20 sha256(فارغ))
        assertEquals("QmdfTbBqBPQ7VNxZEYEj14VmRuZBkqFbiwReogJgS1zR1n", Cid.cidV0(ByteArray(0)))
    }

    @Test
    fun `cid stable and unique`() {
        val a = Cid.cidV0("نodal".toByteArray())
        val b = Cid.cidV0("نodal".toByteArray())
        val c = Cid.cidV0("نodal!".toByteArray())
        assertEquals(a, b)
        assertFalse(a == c)
        assertEquals(46, a.length) // CIDv0 = 46 حرفًا دائمًا
    }
}

class CanonicalJsonTest {

    @Test
    fun `key order does not matter`() {
        val m1 = linkedMapOf("b" to 1L, "a" to "x", "c" to listOf(1L, 2L))
        val m2 = linkedMapOf("c" to listOf(1L, 2L), "a" to "x", "b" to 1L)
        assertEquals(CanonicalJson.stringify(m1), CanonicalJson.stringify(m2))
        assertEquals("""{"a":"x","b":1,"c":[1,2]}""", CanonicalJson.stringify(m1))
    }

    @Test
    fun `unicode and escaping`() {
        val s = CanonicalJson.stringify(linkedMapOf("text" to "مرحبا \"Nodal\"\n\t"))
        assertEquals("""{"text":"مرحبا \"Nodal\"\n\t"}""", s)
    }
}

class IdentityTest {

    @Test
    fun `identity generates valid peer id`() {
        val id = Identity.generate()
        val peerId = PeerId.fromBase58(id.peerIdB58)
        assertEquals(id.peerId, peerId)
    }

    @Test
    fun `pubkey extraction from peer id matches raw key`() {
        val id = Identity.generate()
        val extracted = PeerKeyUtils.pubKeyBytesFromPeerId(id.peerId)
        // PeerId يحمل المفتاح الخام (32 بايت) داخل protobuf — المقارنة مع الخام المستخرج من bytes()
        val rawFromBytes = extractProtobufDataField(id.pubKey.bytes())
        assertContentEquals(rawFromBytes, extracted)
        assertEquals(32, extracted.size)
    }

    @Test
    fun `sign and verify`() {
        val id = Identity.generate()
        val data = "رسالة موقعة".toByteArray()
        val sig = id.sign(data)
        assertTrue(Signatures.verifyEd25519(id.peerId, data, sig))
        assertFalse(Signatures.verifyEd25519(id.peerId, "معدلة".toByteArray(), sig))
    }

    @Test
    fun `secret export and import round trip`() {
        val original = Identity.generate()
        val restored = Identity.fromSecret(original.exportSecret())
        assertEquals(original.peerIdB58, restored.peerIdB58)
    }
}

class PostTest {

    @Test
    fun `signed post verifies`() {
        val id = Identity.generate()
        val post = PostEnvelope.signed(
            identity = id,
            type = PostTypes.POST,
            createdAt = System.currentTimeMillis(),
            seq = 1,
            text = "أول منشور لامركزي!",
            media = listOf(MediaRef(cid = "QmTest123", mime = "image/webp", name = "pic.webp"))
        )
        assertTrue(post.verifySignature())
        assertEquals(46, post.cid.length)
    }

    @Test
    fun `tampered post fails verification`() {
        val id = Identity.generate()
        val post = PostEnvelope.signed(id, PostTypes.POST, System.currentTimeMillis(), 1, text = "أصلي")
        val tampered = post.copy(text = "معدل!")
        assertFalse(tampered.verifySignature())
        assertFalse(post.cid == tampered.cid)
    }

    @Test
    fun `encode decode round trip keeps cid and signature`() {
        val id = Identity.generate()
        val post = PostEnvelope.signed(id, PostTypes.POST, System.currentTimeMillis(), 7, text = "دورة كاملة")
        val decoded = PostEnvelope.decode(post.encode())!!
        assertEquals(post.cid, decoded.cid)
        assertTrue(decoded.verifySignature())
    }

    @Test
    fun `wrong author fails verification`() {
        val id = Identity.generate()
        val other = Identity.generate()
        val post = PostEnvelope.signed(id, PostTypes.POST, System.currentTimeMillis(), 1)
        val forged = post.copy(author = other.peerIdB58)
        assertFalse(forged.verifySignature())
    }

    @Test
    fun `malformed author does not crash verification`() {
        val id = Identity.generate()
        val post = PostEnvelope.signed(id, PostTypes.POST, System.currentTimeMillis(), 1)
        // author ليس PeerId صالحًا وsig ترميز غير صالح — يجب أن يعيد false ولا يرمي
        val evil = post.copy(author = "@@@ليس-معرفا@@@", sig = "!!!ليس-base64!!!")
        assertFalse(evil.verifySignature())
        val emptyAuthor = post.copy(author = "")
        assertFalse(emptyAuthor.verifySignature())
    }
}

class FeedTest {

    @Test
    fun `maxSize is enforced by evicting oldest`() {
        val feed = Feed(maxSize = 5)
        val id = Identity.generate()
        var ts = 1_000L
        val posts = (1..10).map { i ->
            PostEnvelope.signed(id, PostTypes.POST, ts++, i.toLong(), text = "p$i")
        }
        posts.forEach { feed.add(it) }
        assertEquals(5, feed.size(), "الموجز يجب ألا يتجاوز maxSize")
        assertFalse(feed.contains(posts.first().cid), "الأقدم يجب أن يُحذف")
        assertTrue(feed.contains(posts.last().cid), "الأحدث يجب أن يبقى")
        assertEquals(5, feed.all().size)
    }

    @Test
    fun `eviction prunes topic lists too`() {
        val feed = Feed(maxSize = 3)
        val id = Identity.generate()
        var ts = 1_000L
        val posts = (1..6).map { i ->
            PostEnvelope.signed(id, PostTypes.POST, ts++, i.toLong(), text = "t$i", topic = "sports")
        }
        posts.forEach { feed.add(it) }
        assertEquals(3, feed.forTopic("sports").size)
        assertFalse(feed.forTopic("sports").any { it.cid == posts.first().cid })
    }

    @Test
    fun `sync boundary includes same-timestamp posts`() {
        val feed = Feed()
        val id = Identity.generate()
        val a = PostEnvelope.signed(id, PostTypes.POST, 100, 1)
        val b = PostEnvelope.signed(id, PostTypes.POST, 100, 2) // نفس المللي ثانية
        val c = PostEnvelope.signed(id, PostTypes.POST, 200, 3)
        feed.add(a); feed.add(b); feed.add(c)
        assertEquals(3, feed.after(100).size, "حدود المؤشر يجب ألا تفقد منشورات نفس المللي ثانية")
        assertEquals(1, feed.after(200).size)
        assertEquals(0, feed.after(201).size)
    }

    @Test
    fun `listener exception does not break feed or other listeners`() {
        val feed = Feed()
        val id = Identity.generate()
        var secondCalled = false
        feed.addListener { throw RuntimeException("مستمع معطوب") }
        feed.addListener { secondCalled = true }
        val post = PostEnvelope.signed(id, PostTypes.POST, 100, 1)
        feed.add(post)
        assertTrue(secondCalled, "المستمع الثاني يجب أن يُستدعى رغم فشل الأول")
        assertEquals(1, feed.size())
    }
}
