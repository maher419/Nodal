package nodal.core.net

import java.util.concurrent.ConcurrentHashMap

/**
 * مخزن الكتل (BlockStore) — IPFS-style: محتوى معنون بـ CID.
 * M1–M3: ذاكرة فقط (بلا قاعدة بيانات) — كما في المفهوم النقي.
 * M4: يُضاف كاش محلي خلف هذه الواجهة.
 */
class BlockStore {
    private val blocks = ConcurrentHashMap<String, ByteArray>()

    fun put(cid: String, data: ByteArray) {
        blocks[cid] = data
    }

    fun get(cid: String): ByteArray? = blocks[cid]

    fun has(cid: String): Boolean = blocks.containsKey(cid)

    fun size(): Int = blocks.size
}
