package ai.victorious.filemanager.core.apk

/**
 * String-value edits to a sealed resources.arsc. Only the global string pool is rewritten; the
 * resource tables are copied byte-for-byte, so this cannot add or remove resources — it can only
 * change the text of existing string values. Adding new string resources is handled by ArscStringAdder (see
 * docs/APK_DEEP_EDIT.md): it would need new entries in the type tables and the per-package key
 * pool, which shifts every downstream offset.
 */
object ArscEditor {
    data class StringRes(val resId: Int, val name: String, val locale: String, val value: String, val poolIndex: Int)

    /** A pool slot shared by several resources: editing it changes all of them. */
    data class Shared(val poolIndex: Int, val value: String, val resources: List<StringRes>)

    fun strings(arsc: Arsc): List<StringRes> =
        arsc.strings.map { StringRes(it.resId, it.name, it.locale, it.value, it.poolIndex) }

    /** Pool slots referenced by more than one string resource (an edit there is not isolated). */
    fun sharedSlots(arsc: Arsc): List<Shared> =
        arsc.strings.groupBy { it.poolIndex }.filter { it.value.size > 1 }
            .map { (idx, list) -> Shared(idx, list.first().value, strings(arsc).filter { it.poolIndex == idx }) }

    /**
     * Applies [edits] (resId+locale -> new text) and returns new resources.arsc bytes.
     * @throws IllegalArgumentException if a target is missing or an edit would also change a
     * different resource sharing the same pool slot (unless that resource is edited to the same value).
     */
    fun apply(arsc: Arsc, edits: Map<ResTarget, String>): ByteArray {
        val all = arsc.strings
        val poolEdits = HashMap<Int, String>()
        for ((target, value) in edits) {
            val match = all.firstOrNull { it.resId == target.resId && it.locale == target.locale }
                ?: throw IllegalArgumentException("No string ${Integer.toHexString(target.resId)} (${target.locale})")
            val prev = poolEdits.put(match.poolIndex, value)
            require(prev == null || prev == value) { "Conflicting edits for shared pool slot ${match.poolIndex}" }
        }
        // Guard: a slot shared with an un-edited resource would silently change that one too.
        for ((idx, value) in poolEdits) {
            val sharers = all.filter { it.poolIndex == idx }
            val editedResIds = edits.keys.filter { all.any { s -> s.poolIndex == idx && s.resId == it.resId && s.locale == it.locale } }
            if (sharers.size > 1) {
                val uncovered = sharers.filter { s -> editedResIds.none { it.resId == s.resId && it.locale == s.locale } && s.value != value }
                require(uncovered.isEmpty()) {
                    "Pool slot $idx is shared; editing it would also change ${uncovered.joinToString { it.name + "/" + it.locale }}. " +
                        "Edit all of them, or none."
                }
            }
        }
        return arsc.withEdits(poolEdits)
    }

    data class ResTarget(val resId: Int, val locale: String = "default")
}
