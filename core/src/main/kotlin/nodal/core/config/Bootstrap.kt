package nodal.core.config

/**
 * عقد البنية التحتية (bootstrap) — محايدة: تصل الأقران وتُمرر المحتوى ولا تملك شيئًا دائمًا.
 * القائمة الافتراضية تُستبدل بعناوين عامة حقيقية عند تشغيل عقد bootstrap عامة.
 */
object Bootstrap {
    const val DEFAULT_PORT = 4001

    /** مسارات مدمجة — M1: محلية للاختبار */
    val DEFAULT_ADDRS: List<String> = listOf("/ip4/127.0.0.1/tcp/4001")

    /** الحد الأقصى لمنشورات "الذاكرة الحلقية" لكل موضوع عند bootstrap */
    const val RING_BUFFER_PER_TOPIC = 500

    /** نافذة الثقة الزمنية للمنشورات (ms) — ما عداها يُرفض كمحاولة تلاعب */
    const val MAX_POST_AGE_MS = 5 * 60 * 1000L

    /** أقصى حجم لمنشور (بايت) */
    const val MAX_POST_BYTES = 64 * 1024

    /** أقصى حجم كتلة وسائط (بايت) */
    const val MAX_BLOCK_BYTES = 10 * 1024 * 1024

    /** مهلة العمليات الشبكية (ثوانٍ) — اتصال، طلب كتلة، مزامنة */
    const val DEFAULT_OP_TIMEOUT_SECONDS = 30L
}
