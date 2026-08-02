package nodal.core.crypto

/**
 * JSON قانوني (Canonical JSON) للتوقيع — إخراج حتمي مطابق دائمًا:
 * مفاتيح مرتبة أبجديًا، UTF-8، أرقام صحيحة كما هي، نصوص مُهرّبة بشكل صارم.
 *
 * لا يُستخدم للعرض — فقط للتوقيع والتحقق. التحليل يتم عبر kotlinx.serialization.
 */
object CanonicalJson {

    fun stringify(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> if (value) "true" else "false"
        is Long -> value.toString()
        is Int -> value.toString()
        is String -> "\"" + escape(value) + "\""
        is Map<*, *> -> value.entries
            .sortedBy { it.key.toString() }
            .joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
                "\"" + escape(k.toString()) + "\":" + stringify(v)
            }
        is List<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { stringify(it) }
        else -> throw IllegalArgumentException("نوع غير مدعوم في JSON القانوني: ${value::class}")
    }

    private fun escape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
                }
            }
        }
    }
}
