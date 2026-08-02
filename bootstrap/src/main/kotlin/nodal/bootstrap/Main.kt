package nodal.bootstrap

/**
 * M1: نقطة تشغيل عقدة bootstrap — عنصر نائب.
 * البناء الكامل (تجميع العناوين، الذاكرة الحلقية للمواضيع، الـ relay)
 * هو مخطط M2. كانت الوحدة تُعلن mainClass هنا بدون وجود هذا الملف أصلًا
 * (كان :bootstrap:run يفشل بـ ClassNotFoundException).
 */
fun main(args: Array<String>) {
    println("Nodal bootstrap node - placeholder (M2). Protocol ${nodal.core.NodalVersion.PROTOCOL_VERSION}")
}
