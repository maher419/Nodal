package nodal.core.net

import io.libp2p.core.PeerId
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.pubsub.MessageApi
import io.libp2p.core.pubsub.PubsubSubscription
import io.libp2p.core.pubsub.Topic
import io.libp2p.discovery.MDnsDiscovery
import io.libp2p.protocol.Identify
import io.libp2p.protocol.Ping
import io.libp2p.pubsub.gossip.Gossip
import io.netty.buffer.Unpooled
import nodal.core.config.Bootstrap
import nodal.core.crypto.Identity
import nodal.core.model.MediaRef
import nodal.core.model.PostEnvelope
import nodal.core.model.PostTypes
import nodal.core.model.Topics
import nodal.core.multiformats.Cid
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * عقدة Nodal — عميل وخادم معًا:
 * - GossipSub: نشر/اشتراك (تايم لاين حي)
 * - /nodal/blocks: تبادل كتل معنونة بـ CID
 * - /nodal/sync: سحب تعويضي منذ مؤشر زمني
 * - bootstrap + mDNS: اكتشاف الأقران
 * كل منشور وارد يُتحقق من توقيعه ونافذته الزمنية قبل قبوله.
 */
class NodalNode(
    val identity: Identity,
    private val store: BlockStore,
    private val feed: Feed,
    private val port: Int = 0,
    private val bootstrapAddrs: List<String> = Bootstrap.DEFAULT_ADDRS,
    private val enableMdns: Boolean = true,
    private val onPost: ((PostEnvelope) -> Unit)? = null,
    private val onLog: ((String) -> Unit)? = null
) : AutoCloseable {

    private val gossip = Gossip()
    private val publisher: io.libp2p.core.pubsub.PubsubPublisherApi = gossip.createPublisher(identity.privKey)
    private val blockBinding = BlockBinding(store)
    private val syncBinding = SyncBinding(feed)
    private val follows: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val subscriptions = ConcurrentHashMap<String, PubsubSubscription>()
    private val seq = AtomicLong(System.currentTimeMillis())
    private var mdns: MDnsDiscovery? = null

    private val host = host {
        identity { factory = { identity.privKey } }
        protocols {
            +gossip
            +Ping()
            +Identify()
            +blockBinding
            +syncBinding
        }
        network {
            listen("/ip4/0.0.0.0/tcp/$port")
        }
    }

    val peerId: PeerId get() = identity.peerId
    val peerIdB58: String get() = identity.peerIdB58

    fun start(): NodalNode {
        host.start().get(Bootstrap.DEFAULT_OP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val subscriber = java.util.function.Consumer<MessageApi> { onPubsubMessage(it) }
        gossip.subscribe(subscriber, Topic(Topics.GLOBAL), Topic(Topics.user(identity.peerIdB58)))
        bootstrapAddrs.forEach { addr ->
            runCatching { host.network.connect(Multiaddr.fromString(addr)).get(Bootstrap.DEFAULT_OP_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                .onSuccess { log("اتصال بـ bootstrap: $addr") }
                .onFailure { log("فشل bootstrap $addr: ${it.message}") }
        }
        if (enableMdns) {
            runCatching {
                val md = MDnsDiscovery(host, "_p2p._udp", 5353, InetAddress.getByName("224.0.0.251"))
                md.start()
                mdns = md
            }.onFailure { log("mDNS غير متاح: ${it.message}") }
        }
        log("العقدة جاهزة: $peerIdB58 @ ${listenAddr()}")
        return this
    }

    /**
     * عنوان الاستماع — يُشارك مع الأقران للإضافة اليدوية.
     * الاستماع يتم على 0.0.0.0 (كل الواجهات) لكن إعلان 0.0.0.0 للقرناء
     * عنوان غير قابل للاتصال من أي جهاز آخر → نستبدله بعنوان LAN فعلي
     * (أول IPv4 site-local)، وإلا loopback كحد أدنى قابل للاتصال محليًا.
     */
    fun listenAddr(): String {
        val addr = host.listenAddresses().firstOrNull()?.toString() ?: return ""
        return if (addr.contains("/0.0.0.0/")) {
            addr.replace("/0.0.0.0/", "/${localIpv4() ?: "127.0.0.1"}/")
        } else addr
    }

    private fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    /** اتصال مباشر بعنوان قرين (دون معرف) — identify يحدد الهوية */
    fun dial(addr: String) {
        runCatching { host.network.connect(Multiaddr.fromString(addr)).get(Bootstrap.DEFAULT_OP_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            .onSuccess { log("اتصال ناجح: $addr") }
            .onFailure { log("فشل اتصال $addr: ${it.message}") }
    }

    // ═══════════ النشر ═══════════

    fun publishPost(text: String, media: List<MediaRef> = emptyList(), topic: String = ""): PostEnvelope {
        val post = PostEnvelope.signed(
            identity, PostTypes.POST, System.currentTimeMillis(), seq.incrementAndGet(),
            text = text, media = media, topic = topic
        )
        broadcast(post)
        return post
    }

    fun publishProfile(name: String, bio: String, avatarCid: String = ""): PostEnvelope {
        val post = PostEnvelope.signed(
            identity, PostTypes.PROFILE, System.currentTimeMillis(), seq.incrementAndGet(),
            text = bio, media = if (avatarCid.isNotEmpty()) listOf(MediaRef(avatarCid, "image/webp", name)) else emptyList()
        )
        broadcast(post)
        return post
    }

    /** نشر أي غلاف موقّع في الموضوع العام + موضوعك الشخصي */
    fun broadcast(post: PostEnvelope) {
        val encoded = post.encode().toByteArray(Charsets.UTF_8)
        // نفس معيار القبول عند الأقران: منشور أكبر من الحد لن يستقبله أحد
        require(encoded.size <= Bootstrap.MAX_POST_BYTES) {
            "منشور أكبر من الحد ${Bootstrap.MAX_POST_BYTES} بايت"
        }
        feed.add(post)
        val data = Unpooled.wrappedBuffer(encoded)
        publisher.publish(data, Topic(Topics.GLOBAL), Topic(Topics.user(identity.peerIdB58)))
    }

    // ═══════════ المتابعة ═══════════

    /** متابعة = اشتراك في موضوع المستخدم + إعلان follow موقّع */
    fun follow(peerIdB58: String) {
        if (follows.add(peerIdB58)) {
            val subscriber = java.util.function.Consumer<MessageApi> { onPubsubMessage(it) }
            subscriptions[peerIdB58] = gossip.subscribe(subscriber, Topic(Topics.user(peerIdB58)))
            val followPost = PostEnvelope.signed(
                identity, PostTypes.FOLLOW, System.currentTimeMillis(), seq.incrementAndGet(), ref = peerIdB58
            )
            broadcast(followPost)
        }
    }

    /** إلغاء متابعة — يلغي الاشتراك الفعلي في موضوع المستخدم (لا مجرد إزالة من مجموعة) */
    fun unfollow(peerIdB58: String) {
        follows.remove(peerIdB58)
        subscriptions.remove(peerIdB58)?.unsubscribe()
    }

    fun followed(): Set<String> = follows.toSet()

    /** التايم لاين: كل المنشورات المتحقق منها في الذاكرة، مرتبة تنازليًا */
    fun timeline(): List<PostEnvelope> = feed.all()

    // ═══════════ المزامنة والكتل ═══════════

    /** سحب تعويضي: كل ما فات منذ مؤشر زمني */
    fun syncFrom(peerAddr: String, since: Long = 0) {
        var ctrl: SyncController? = null
        try {
            ctrl = syncBinding.dial(host, Multiaddr.fromString(peerAddr)).controller.get(Bootstrap.DEFAULT_OP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val posts = ctrl.sync(
                topics = listOf(Topics.GLOBAL, Topics.user(identity.peerIdB58)), since = since
            ).get(Bootstrap.DEFAULT_OP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            var added = 0
            for (encoded in posts) {
                // حماية حجمية: قرين خبيث لا يغرقنا بمنشورات ضخمة عبر المزامنة
                if (encoded.length > Bootstrap.MAX_POST_BYTES) continue
                val post = PostEnvelope.decode(encoded) ?: continue
                if (!post.verifySignature()) continue
                if (feed.add(post)) added++
            }
            log("مزامنة من $peerAddr: +$added منشور")
        } catch (e: Exception) {
            log("فشلت المزامنة من $peerAddr: ${e.message}")
        } finally {
            ctrl?.close()
        }
    }

    /**
     * جلب كتلة (وسيط) بالـ CID من قرين — مع التحقق من المحتوى:
     * التجزئة الذاتية للكتلة المستلمة يجب أن تطابق الـ CID المطلوب،
     * وإلا فالقرين قد سلّمنا بيانات غير مطابقة (تلف أو تلاعب) فنرفضها.
     */
    fun fetchBlock(peerAddr: String, cid: String): ByteArray? {
        var ctrl: BlockController? = null
        return try {
            ctrl = blockBinding.dial(host, Multiaddr.fromString(peerAddr)).controller.get(Bootstrap.DEFAULT_OP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val block = ctrl.request(cid).get(Bootstrap.DEFAULT_OP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (block != null && Cid.cidV1Raw(block) != cid) {
                log("كتلة $cid من $peerAddr لا تطابق تجزئتها — رُفضت")
                null
            } else block
        } catch (e: Exception) {
            log("فشل جلب كتلة $cid من $peerAddr: ${e.message}")
            null
        } finally {
            ctrl?.close()
        }
    }

    /** تخزين كتلة محليًا وإرجاع CIDv1 — أول نسخة من المحتوى عندك أنت */
    fun putBlock(data: ByteArray): String {
        require(data.size <= Bootstrap.MAX_BLOCK_BYTES) {
            "كتلة أكبر من الحد ${Bootstrap.MAX_BLOCK_BYTES} بايت"
        }
        val cid = Cid.cidV1Raw(data)
        store.put(cid, data)
        return cid
    }

    fun blockCount(): Int = store.size()

    // ═══════════ الاستقبال والتحقق ═══════════

    private fun onPubsubMessage(msg: MessageApi) {
        // حماية حجمية قبل أي فك ترميز: قرين خبيث لا يغرقنا برسائل ضخمة
        if (msg.data.readableBytes() > Bootstrap.MAX_POST_BYTES) {
            log("رفض رسالة pubsub أكبر من الحد ${Bootstrap.MAX_POST_BYTES} بايت")
            return
        }
        val post = PostEnvelope.decode(msg.data.toString(Charsets.UTF_8))
            ?: run { log("رسالة pubsub غير صالحة"); return }
        if (!post.verifySignature()) {
            log("رفض منشور بتوقيع غير صالح من ${post.author}")
            return
        }
        if (abs(System.currentTimeMillis() - post.createdAt) > Bootstrap.MAX_POST_AGE_MS) {
            log("رفض منشور خارج النافذة الزمنية من ${post.author}")
            return
        }
        if (feed.add(post)) onPost?.invoke(post)
    }

    private fun log(msg: String) {
        onLog?.invoke(msg)
    }

    override fun close() {
        runCatching { mdns?.stop() }
        runCatching { host.stop().get(Bootstrap.DEFAULT_OP_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
    }
}
