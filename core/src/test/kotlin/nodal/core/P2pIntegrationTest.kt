package nodal.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import nodal.core.crypto.Identity
import nodal.core.model.MediaRef
import nodal.core.model.PostEnvelope
import nodal.core.model.PostTypes
import nodal.core.multiformats.Cid
import nodal.core.net.BlockStore
import nodal.core.net.Feed
import nodal.core.net.NodalNode
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * اختبار الشبكة التكاملي — إثبات المفهوم (M1):
 * عقدتان حقيقيتان على libp2p (TCP + Noise + mplex + GossipSub) تتبادلان:
 * 1) منشورًا موقّعًا يصل عبر GossipSub ويُتحقق
 * 2) كتلة وسائط عبر /nodal/blocks
 * 3) مزامنة تعويضية عبر /nodal/sync
 */
class P2pIntegrationTest {

    @Test
    fun `عقدتان تتبادلان منشورات موقعة وكتلا`() = runBlocking {
        val idA = Identity.generate()
        val idB = Identity.generate()
        val feedA = Feed()
        val feedB = Feed()
        val storeA = BlockStore()
        val storeB = BlockStore()
        val receivedB = CompletableFuture<PostEnvelope>()

        val nodeA = NodalNode(
            idA, storeA, feedA, port = 0,
            bootstrapAddrs = emptyList(), enableMdns = false
        )
        val nodeB = NodalNode(
            idB, storeB, feedB, port = 0,
            bootstrapAddrs = emptyList(), enableMdns = false,
            onPost = { receivedB.complete(it) }
        )

        try {
            nodeA.start()
            nodeB.start()
            val addrA = nodeA.listenAddr()
            assertTrue(addrA.isNotEmpty(), "يجب أن تستمع العقدة على عنوان")

            // B يتصل بـ A مباشرة ثم تنتظر الشبكة أن تتشكل (نبض gossipsub)
            nodeB.dial(addrA)
            delay(3000)

            // 1) منشور حي عبر GossipSub
            val post = nodeA.publishPost(
                "أول منشور P2P حي!",
                media = listOf(MediaRef(cid = "QmRefMedia", mime = "image/png", name = "x.png"))
            )
            val got = receivedB.get(15, TimeUnit.SECONDS)
            assertEquals(post.cid, got.cid, "المنشور المستلم يجب أن يطابق المنشور المنشور")
            assertTrue(got.verifySignature(), "التوقيع يجب أن يتحقق عند المستلم")
            assertEquals(idA.peerIdB58, got.author, "المؤلف = هوية العقدة A")

            // 2) تبادل الكتل عبر /nodal/blocks
            val blockData = "بيانات صورة تجريبية (كتلة IPFS-style)".toByteArray()
            val blockCid = Cid.cidV1Raw(blockData)
            storeA.put(blockCid, blockData)
            val fetched = nodeB.fetchBlock(addrA, blockCid)
            assertNotNull(fetched, "يجب جلب الكتلة من العقدة A")
            assertContentEquals(blockData, fetched)

            // 2b) كتلة بمحتوى لا يطابق CID المطلوب (تلف/تلاعب) → رفض
            val wrongData = "بيانات مختلفة تمامًا".toByteArray()
            storeA.put(blockCid, wrongData) // عنوان بـ CID لكن بيانات مغايرة
            val rejected = nodeB.fetchBlock(addrA, blockCid)
            assertEquals(null, rejected, "كتلة لا تطابق تجزئتها يجب رفضها")

            // 3) المزامنة التعويضية عبر /nodal/sync
            nodeA.publishPost("منشور ثانٍ بعد المزامنة")
            nodeB.syncFrom(addrA, since = 0)
            assertTrue(feedB.size() >= 2, "موجز B يجب أن يحتوي منشورَي A بعد المزامنة")
        } finally {
            nodeA.close()
            nodeB.close()
        }
    }

    @Test
    fun `المزامنة تتخطى المنشورات الأكبر من الحد`() = runBlocking {
        val idA = Identity.generate()
        val idB = Identity.generate()
        val feedA = Feed()
        val feedB = Feed()
        val storeA = BlockStore()
        val storeB = BlockStore()

        val nodeA = NodalNode(
            idA, storeA, feedA, port = 0,
            bootstrapAddrs = emptyList(), enableMdns = false
        )
        val nodeB = NodalNode(
            idB, storeB, feedB, port = 0,
            bootstrapAddrs = emptyList(), enableMdns = false
        )

        try {
            nodeA.start()
            nodeB.start()
            val addrA = nodeA.listenAddr()

            // منشور موقّع ضخم يُحقن مباشرة في موجز A (broadcast يرفضه محليًا)
            val oversized = PostEnvelope.signed(
                idA, PostTypes.POST, System.currentTimeMillis(), 1,
                text = "x".repeat(70 * 1024)
            )
            assertTrue(feedA.add(oversized))

            nodeB.syncFrom(addrA, since = 0)
            assertFalse(feedB.contains(oversized.cid), "المنشور الضخم يجب ألا يعبر المزامنة")
        } finally {
            nodeA.close()
            nodeB.close()
        }
    }
}
