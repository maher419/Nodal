package nodal.core.net

import io.libp2p.core.Stream
import io.libp2p.core.multistream.ProtocolId
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.etc.types.toByteBuf
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.netty.buffer.ByteBuf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nodal.core.config.Bootstrap
import nodal.core.model.Topics
import java.util.Base64
import java.util.concurrent.CompletableFuture

private val ProtocolJson = Json { ignoreUnknownKeys = true }

const val BLOCK_PROTOCOL_ID: ProtocolId = "/nodal/blocks/1.0.0"
const val SYNC_PROTOCOL_ID: ProtocolId = "/nodal/sync/1.0.0"

private fun ByteBuf.readAll(): ByteArray {
    val bytes = ByteArray(readableBytes())
    getBytes(readerIndex(), bytes)
    return bytes
}

private fun ByteBuf.toText(): String = String(readAll(), Charsets.UTF_8)

// ═══════════════ بروتوكول الكتل: /nodal/blocks/1.0.0 ═══════════════
// طلب: {"get":"<cid>"}  →  رد: {"ok":true,"cid":"...","data":"<base64>"} أو {"ok":false}

@Serializable
private data class BlockRequest(val get: String)

@Serializable
private data class BlockResponse(val ok: Boolean, val cid: String, val data: String = "")

interface BlockController {
    /** يطلب كتلة بالـ CID — null إذا لم توجد عند الطرف الآخر */
    fun request(cid: String): CompletableFuture<ByteArray?>
    fun close()
}

class BlockBinding(store: BlockStore) :
    StrictProtocolBinding<BlockController>(BLOCK_PROTOCOL_ID, BlockProtocol(store))

class BlockProtocol(private val store: BlockStore) :
    ProtocolHandler<BlockController>(
        Bootstrap.MAX_BLOCK_BYTES.toLong() * 2,
        Bootstrap.MAX_BLOCK_BYTES.toLong() * 2
    ) {

    override fun onStartInitiator(stream: Stream) = onStart(stream, initiator = true)
    override fun onStartResponder(stream: Stream) = onStart(stream, initiator = false)

    private fun onStart(stream: Stream, initiator: Boolean): CompletableFuture<BlockController> {
        val ready = CompletableFuture<Void>()
        val handler = Handler(initiator, ready)
        stream.pushHandler(handler)
        return ready.thenApply { handler }
    }

    inner class Handler(
        private val initiator: Boolean,
        private val ready: CompletableFuture<Void>
    ) : ProtocolMessageHandler<ByteBuf>, BlockController {

        lateinit var stream: Stream
        /**
         * مستقبل الطلب الجاري. يُستبدل بآخر عند كل request() —
         * بدون ذلك كانت الاستجابة الأولى تُسلَّم بصمت للطلب الثاني على نفس الدفق.
         * البروتوكول واحد-طلب-واحد-معلّق لكل دفق: لا ترسل طلبين متداخلين.
         */
        private var pending = CompletableFuture<ByteArray?>()

        override fun onActivated(stream: Stream) {
            this.stream = stream
            ready.complete(null)
        }

        override fun onMessage(stream: Stream, msg: ByteBuf) {
            val text = msg.toText()
            if (initiator) {
                val resp = runCatching { ProtocolJson.decodeFromString(BlockResponse.serializer(), text) }.getOrNull()
                pending.complete(
                    if (resp?.ok == true && resp.data.isNotEmpty()) Base64.getDecoder().decode(resp.data) else null
                )
            } else {
                val req = runCatching { ProtocolJson.decodeFromString(BlockRequest.serializer(), text) }.getOrNull()
                val data = req?.let { store.get(it.get) }
                val resp = BlockResponse(
                    ok = data != null,
                    cid = req?.get ?: "",
                    data = data?.let { Base64.getEncoder().encodeToString(it) } ?: ""
                )
                stream.writeAndFlush(ProtocolJson.encodeToString(BlockResponse.serializer(), resp).toByteArray().toByteBuf())
            }
        }

        override fun onClosed(stream: Stream) {
            // إيقاظ المنتظر بدل تركه معلقًا حتى مهلة المتصل
            pending.completeExceptionally(IllegalStateException("أُغلق دفق /nodal/blocks قبل الرد"))
        }

        override fun request(cid: String): CompletableFuture<ByteArray?> {
            val f = CompletableFuture<ByteArray?>()
            pending = f
            stream.writeAndFlush(ProtocolJson.encodeToString(BlockRequest.serializer(), BlockRequest(cid)).toByteArray().toByteBuf())
            return f
        }

        override fun close() {
            runCatching { stream.close() }
        }
    }
}

// ═══════════════ بروتوكول المزامنة: /nodal/sync/1.0.0 ═══════════════
// طلب: {"topics":[...],"since":<ts>}  →  رد: {"posts":["<json منشور>",...]}

@Serializable
private data class SyncRequest(val topics: List<String> = emptyList(), val since: Long = 0)

@Serializable
private data class SyncResponse(val posts: List<String> = emptyList())

interface SyncController {
    fun sync(topics: List<String>, since: Long): CompletableFuture<List<String>>
    fun close()
}

class SyncBinding(feed: Feed) :
    StrictProtocolBinding<SyncController>(SYNC_PROTOCOL_ID, SyncProtocol(feed))

class SyncProtocol(private val feed: Feed) :
    ProtocolHandler<SyncController>(64L * 1024 * 1024, 64L * 1024 * 1024) {

    override fun onStartInitiator(stream: Stream) = onStart(stream, initiator = true)
    override fun onStartResponder(stream: Stream) = onStart(stream, initiator = false)

    private fun onStart(stream: Stream, initiator: Boolean): CompletableFuture<SyncController> {
        val ready = CompletableFuture<Void>()
        val handler = Handler(initiator, ready)
        stream.pushHandler(handler)
        return ready.thenApply { handler }
    }

    inner class Handler(
        private val initiator: Boolean,
        private val ready: CompletableFuture<Void>
    ) : ProtocolMessageHandler<ByteBuf>, SyncController {

        lateinit var stream: Stream
        /** نفس نمط BlockController: طلب واحد معلّق لكل دفق */
        private var pending = CompletableFuture<List<String>>()

        override fun onActivated(stream: Stream) {
            this.stream = stream
            ready.complete(null)
        }

        override fun onMessage(stream: Stream, msg: ByteBuf) {
            val text = msg.toText()
            if (initiator) {
                val resp = runCatching { ProtocolJson.decodeFromString(SyncResponse.serializer(), text) }.getOrNull()
                pending.complete(resp?.posts ?: emptyList())
            } else {
                val req = runCatching { ProtocolJson.decodeFromString(SyncRequest.serializer(), text) }.getOrNull()
                val posts = req?.let { r ->
                    feed.after(r.since)
                        .filter { p -> r.topics.isEmpty() || r.topics.contains(p.topic.ifEmpty { Topics.GLOBAL }) }
                        .map { it.encode() }
                } ?: emptyList()
                stream.writeAndFlush(ProtocolJson.encodeToString(SyncResponse.serializer(), SyncResponse(posts)).toByteArray().toByteBuf())
            }
        }

        override fun onClosed(stream: Stream) {
            // إيقاظ المنتظر بدل تركه معلقًا حتى مهلة المتصل
            pending.completeExceptionally(IllegalStateException("أُغلق دفق /nodal/sync قبل الرد"))
        }

        override fun sync(topics: List<String>, since: Long): CompletableFuture<List<String>> {
            val f = CompletableFuture<List<String>>()
            pending = f
            stream.writeAndFlush(ProtocolJson.encodeToString(SyncRequest.serializer(), SyncRequest(topics, since)).toByteArray().toByteBuf())
            return f
        }

        override fun close() {
            runCatching { stream.close() }
        }
    }
}
