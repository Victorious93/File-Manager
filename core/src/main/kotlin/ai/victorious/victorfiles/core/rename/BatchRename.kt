package ai.victorious.victorfiles.core.rename

import ai.victorious.victorfiles.core.path.PathHelpers
import java.util.Locale
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

enum class CaseMode { KEEP, UPPER, LOWER, TITLE }

/**
 * Rules applied to each base name (extension handled separately), in order:
 * find/replace → case → template. Template tokens: `{name}` (result of the previous
 * steps), `{n}` (counter), `{ext}` (extension without the dot).
 */
data class RenameSpec(
    val find: String = "",
    val replace: String = "",
    val regex: Boolean = false,
    val caseSensitive: Boolean = true,
    val caseMode: CaseMode = CaseMode.KEEP,
    val template: String = "{name}",
    val counterStart: Int = 1,
    val counterStep: Int = 1,
    val counterPad: Int = 1,
    /** Null keeps each file's extension; empty string removes it; otherwise replaces it (no leading dot). */
    val newExtension: String? = null,
)

enum class PlanStatus { OK, UNCHANGED, INVALID, CONFLICT }

data class PlanItem(
    val path: String,
    val oldName: String,
    val newName: String,
    val status: PlanStatus,
    val reason: String? = null,
) {
    val targetPath: String? get() = PathHelpers.parentOf(path)?.let { PathHelpers.join(it, newName) }
}

data class RenamePlan(val items: List<PlanItem>, val error: String? = null) {
    /** Renames to perform: only OK items. Applicable only when nothing is invalid or conflicting. */
    val renames: List<Pair<String, String>>
        get() = items.filter { it.status == PlanStatus.OK }.map { it.path to it.targetPath!! }

    val isApplicable: Boolean
        get() = error == null &&
            items.none { it.status == PlanStatus.INVALID || it.status == PlanStatus.CONFLICT } &&
            items.any { it.status == PlanStatus.OK }
}

object BatchRename {
    /**
     * Builds a preview. [existingNames] returns the names currently in a directory; a
     * name occupied by another batch member that is itself being renamed is not a conflict.
     */
    fun plan(paths: List<String>, spec: RenameSpec, existingNames: (String) -> Set<String>): RenamePlan {
        val normalized = paths.map(PathHelpers::normalize).distinct()
        val pattern = try {
            compile(spec)
        } catch (e: PatternSyntaxException) {
            return RenamePlan(emptyList(), "Invalid pattern: ${e.description}")
        }

        val proposed = normalized.mapIndexed { index, path ->
            val oldName = PathHelpers.fileName(path)
            val newName = runCatching { transform(oldName, index, spec, pattern) }.getOrElse { oldName }
            Triple(path, oldName, newName)
        }

        // A directory entry will be vacated if its owner is renamed to something else.
        val vacated = proposed.filter { it.second != it.third }.map { it.first }.toSet()
        val byDir = proposed.groupBy { PathHelpers.parentOf(it.first) }
        val items = mutableListOf<PlanItem>()
        for ((path, oldName, newName) in proposed) {
            val parent = PathHelpers.parentOf(path)
            val status: PlanStatus
            var reason: String? = null
            if (parent == null || !PathHelpers.isValidFileName(newName)) {
                status = PlanStatus.INVALID
                reason = "Invalid name"
            } else if (newName == oldName) {
                status = PlanStatus.UNCHANGED
            } else {
                val siblings = byDir[parent].orEmpty()
                val dupes = siblings.count { it.third == newName }
                val occupant = PathHelpers.join(parent, newName)
                val occupiedByOutsider = newName in existingNames(parent) &&
                    !(occupant in vacated && siblings.any { it.first == occupant })
                when {
                    dupes > 1 -> { status = PlanStatus.CONFLICT; reason = "Duplicate target name" }
                    occupiedByOutsider -> { status = PlanStatus.CONFLICT; reason = "Already exists" }
                    else -> status = PlanStatus.OK
                }
            }
            items += PlanItem(path, oldName, newName, status, reason)
        }
        return RenamePlan(items)
    }

    private fun compile(spec: RenameSpec): Pattern? {
        if (spec.find.isEmpty()) return null
        var flags = 0
        if (!spec.caseSensitive) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        return if (spec.regex) Pattern.compile(spec.find, flags) else Pattern.compile(Pattern.quote(spec.find), flags)
    }

    private fun transform(oldName: String, index: Int, spec: RenameSpec, pattern: Pattern?): String {
        val (base, dotExt) = PathHelpers.splitBaseExt(oldName)
        val ext = dotExt.removePrefix(".")
        var name = base
        if (pattern != null) {
            val replacement = if (spec.regex) spec.replace else java.util.regex.Matcher.quoteReplacement(spec.replace)
            name = pattern.matcher(name).replaceAll(replacement)
        }
        name = applyCase(name, spec.caseMode)
        val counter = (spec.counterStart.toLong() + spec.counterStep.toLong() * index).toString()
            .padStart(spec.counterPad.coerceIn(1, 12), '0')
        val templated = spec.template
            .replace("{name}", name)
            .replace("{n}", counter)
            .replace("{ext}", ext)
        val finalExt = when (val e = spec.newExtension) {
            null -> dotExt
            "" -> ""
            else -> "." + e.removePrefix(".")
        }
        return templated + finalExt
    }

    private fun applyCase(s: String, mode: CaseMode): String = when (mode) {
        CaseMode.KEEP -> s
        CaseMode.UPPER -> s.uppercase(Locale.ROOT)
        CaseMode.LOWER -> s.lowercase(Locale.ROOT)
        CaseMode.TITLE -> {
            val sb = StringBuilder(s.length)
            var startOfWord = true
            for (c in s) {
                sb.append(if (startOfWord) c.titlecaseChar() else c.lowercaseChar())
                startOfWord = c == ' ' || c == '_' || c == '-' || c == '.'
            }
            sb.toString()
        }
    }
}
