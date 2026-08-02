package nodal.core.net

import nodal.core.model.PostEnvelope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * الموجز — تايم لاين حي في الذاكرة (بلا قاعدة بيانات في M1–M3).
 * يُجمَّع من GossipSub (push) ومن /nodal/sync (pull) — إزالة تكرار بالـ CID.
 */
class Feed(private val maxSize: Int = 500) {
    private val byCid = ConcurrentHashMap<String, PostEnvelope>()
    private val byTopic = ConcurrentHashMap<String, CopyOnWriteArrayList<PostEnvelope>>()
    private val listeners = CopyOnWriteArrayList<(PostEnvelope) -> Unit>()

    /** @return false إذا كان المنشور مكررًا (CID موجود) */
    fun add(post: PostEnvelope): Boolean {
        val existed = byCid.putIfAbsent(post.cid, post) != null
        if (existed) return false
        val topic = post.topic.ifEmpty { nodal.core.model.Topics.GLOBAL }
        byTopic.computeIfAbsent(topic) { CopyOnWriteArrayList() }.add(post)
        evictIfNeeded()
        // عزل استثناءات المستمعين: مستمع معطوب لا يوقف تسليم الرسائل للبقية
        listeners.forEach { runCatching { it(post) } }
        return true
    }

    /**
     * تنفيذ حد الذاكرة الحلقية فعليًا: عند تجاوز maxSize تُحذف الأقدم
     * من byCid وقوائم مواضيعها — وإلا لكان maxSize مجرد رقم زخرفي
     * وتنمو الخريطة بلا حدود.
     */
    private fun evictIfNeeded() {
        val excess = byCid.size - maxSize
        if (excess <= 0) return
        val victims = byCid.values
            .sortedBy { it.createdAt } // الأقدم أولًا
            .take(excess)
            .map { it.cid }
        for (cid in victims) {
            val evicted = byCid.remove(cid) ?: continue
            val t = evicted.topic.ifEmpty { nodal.core.model.Topics.GLOBAL }
            byTopic[t]?.remove(evicted)
            byTopic[t]?.takeIf { it.isEmpty() }?.let { byTopic.remove(t, it) }
        }
    }

    fun contains(cid: String): Boolean = byCid.containsKey(cid)

    fun get(cid: String): PostEnvelope? = byCid[cid]

    fun all(): List<PostEnvelope> =
        byCid.values.sortedByDescending { it.createdAt }.take(maxSize)

    fun forTopic(topic: String): List<PostEnvelope> =
        byTopic[topic]?.sortedByDescending { it.createdAt } ?: emptyList()

    /**
     * المنشورات الأحدث من مؤشر زمني — لبروتوكول المزامنة.
     * `>=` وليس `>`: منشوران في نفس المللي ثانية لا يُفقد أحدهما
     * عندما يقدّم العميل مؤشره إلى آخر وقت استلمه (التكرار يُزال بالـ CID).
     */
    fun after(since: Long): List<PostEnvelope> =
        byCid.values.filter { it.createdAt >= since }.sortedByDescending { it.createdAt }.take(maxSize)

    fun addListener(listener: (PostEnvelope) -> Unit) {
        listeners += listener
    }

    fun size(): Int = byCid.size
}
