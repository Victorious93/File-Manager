package ai.victorious.victorfiles.core.apk

import ai.victorious.victorfiles.core.apk.AxmlEditor.Companion.ATTR_AUTHORITIES
import ai.victorious.victorfiles.core.apk.AxmlEditor.Companion.ATTR_DEBUGGABLE
import ai.victorious.victorfiles.core.apk.AxmlEditor.Companion.ATTR_EXPORTED
import ai.victorious.victorfiles.core.apk.AxmlEditor.Companion.ATTR_MIN_SDK
import ai.victorious.victorfiles.core.apk.AxmlEditor.Companion.ATTR_NAME
import ai.victorious.victorfiles.core.apk.AxmlEditor.Companion.ATTR_PERMISSION
import ai.victorious.victorfiles.core.apk.AxmlEditor.Companion.ATTR_TARGET_SDK
import ai.victorious.victorfiles.core.apk.AxmlEditor.Companion.ATTR_VERSION_CODE
import ai.victorious.victorfiles.core.apk.AxmlEditor.Companion.ATTR_VERSION_NAME

/** A component declared in the manifest. [className] is fully qualified. */
data class ManifestComponent(val tag: String, val className: String, val exported: Boolean?)

/** Editable view of AndroidManifest.xml. */
data class ManifestSummary(
    val packageName: String,
    val versionCode: Int?,
    val versionName: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val debuggable: Boolean,
    val permissions: List<String>,
    val components: List<ManifestComponent>,
)

/**
 * Edits for [ManifestEditor.apply]. Null / empty = leave unchanged.
 * [exported] is keyed by fully-qualified component class name.
 */
data class ManifestEdit(
    val packageName: String? = null,
    /** With a package change: expand relative class names and move authorities/permissions owned by the old package. */
    val renameOwnedNames: Boolean = true,
    val versionCode: Int? = null,
    val versionName: String? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val debuggable: Boolean? = null,
    val addPermissions: List<String> = emptyList(),
    val removePermissions: List<String> = emptyList(),
    val exported: Map<String, Boolean> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = this == ManifestEdit(renameOwnedNames = renameOwnedNames)
}

object ManifestEditor {
    val COMPONENT_TAGS = setOf("activity", "activity-alias", "service", "receiver", "provider")
    private val CLASS_NAME_TAGS = COMPONENT_TAGS + setOf("application", "instrumentation")
    private const val ATTR_TARGET_ACTIVITY = 0x01010202
    private val PERMISSION_TAGS = setOf("permission", "permission-tree", "permission-group", "uses-permission", "uses-permission-sdk-23")
    private val PACKAGE_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun isValidPackageName(name: String) = PACKAGE_RE.matches(name)

    fun fullClassName(pkg: String, name: String): String = when {
        name.startsWith(".") -> pkg + name
        !name.contains('.') -> "$pkg.$name"
        else -> name
    }

    fun summary(bytes: ByteArray): ManifestSummary = summary(AxmlEditor.parse(bytes))

    fun summary(ed: AxmlEditor): ManifestSummary {
        val manifest = ed.elements("manifest").firstOrNull() ?: error("No <manifest> element")
        val pkg = ed.attr(manifest, 0, "package")?.let(ed::valueOf).orEmpty()
        fun intAttr(e: AxmlEditor.Element?, id: Int) = e?.let { ed.attr(it, id) }?.let { a ->
            if (a.type == AxmlEditor.TYPE_STRING) ed.valueOf(a).toIntOrNull() else a.data
        }
        val sdk = ed.elements("uses-sdk").firstOrNull()
        val app = ed.elements("application").firstOrNull()
        return ManifestSummary(
            packageName = pkg,
            versionCode = intAttr(manifest, ATTR_VERSION_CODE),
            versionName = ed.attr(manifest, ATTR_VERSION_NAME)?.let(ed::valueOf),
            minSdk = intAttr(sdk, ATTR_MIN_SDK),
            targetSdk = intAttr(sdk, ATTR_TARGET_SDK),
            debuggable = app?.let { ed.attr(it, ATTR_DEBUGGABLE) }?.let { it.type == AxmlEditor.TYPE_INT_BOOLEAN && it.data != 0 } ?: false,
            permissions = (ed.elements("uses-permission") + ed.elements("uses-permission-sdk-23"))
                .mapNotNull { e -> ed.attr(e, ATTR_NAME)?.let(ed::valueOf) },
            components = ed.elements.filter { ed.elementName(it) in COMPONENT_TAGS }.mapNotNull { e ->
                val n = ed.attr(e, ATTR_NAME)?.let(ed::valueOf) ?: return@mapNotNull null
                val ex = ed.attr(e, ATTR_EXPORTED)?.takeIf { it.type == AxmlEditor.TYPE_INT_BOOLEAN }?.let { it.data != 0 }
                ManifestComponent(ed.elementName(e), fullClassName(pkg, n), ex)
            },
        )
    }

    /** Applies [edit] and returns a new binary manifest. Throws IllegalArgumentException on invalid input. */
    fun apply(bytes: ByteArray, edit: ManifestEdit): ByteArray {
        val ed = AxmlEditor.parse(bytes)
        val manifest = ed.elements("manifest").firstOrNull() ?: error("No <manifest> element")
        val before = summary(ed)
        val oldPkg = before.packageName

        // Component names are resolved against the *old* package, before any rename.
        for ((cls, value) in edit.exported) {
            val targets = ed.elements.filter { e ->
                ed.elementName(e) in COMPONENT_TAGS &&
                    ed.attr(e, ATTR_NAME)?.let { fullClassName(oldPkg, ed.valueOf(it)) } == cls
            }
            require(targets.isNotEmpty()) { "No component $cls in the manifest" }
            targets.forEach { ed.setBool(it, ATTR_EXPORTED, value) }
        }

        edit.packageName?.takeIf { it != oldPkg }?.let { newPkg ->
            require(isValidPackageName(newPkg)) { "Invalid package name: $newPkg" }
            if (edit.renameOwnedNames) renameOwned(ed, oldPkg, newPkg)
            ed.setString(manifest, 0, newPkg, plainName = "package")
        }
        edit.versionCode?.let { require(it > 0) { "versionCode must be > 0" }; ed.setInt(manifest, ATTR_VERSION_CODE, it) }
        edit.versionName?.let { ed.setString(manifest, ATTR_VERSION_NAME, it) }
        if (edit.minSdk != null || edit.targetSdk != null) {
            val sdk = ed.elements("uses-sdk").firstOrNull() ?: ed.addManifestChild("uses-sdk", null)
            edit.minSdk?.let { require(it in 1..99) { "minSdk out of range" }; ed.setInt(sdk, ATTR_MIN_SDK, it) }
            edit.targetSdk?.let { require(it in 1..99) { "targetSdk out of range" }; ed.setInt(sdk, ATTR_TARGET_SDK, it) }
            val min = edit.minSdk ?: before.minSdk
            val target = edit.targetSdk ?: before.targetSdk
            if (min != null && target != null) require(target >= min) { "targetSdk ($target) must be ≥ minSdk ($min)" }
        }
        edit.debuggable?.let { on ->
            val app = ed.elements("application").firstOrNull() ?: error("No <application> element")
            if (on) ed.setBool(app, ATTR_DEBUGGABLE, true) else ed.removeAttr(app, ATTR_DEBUGGABLE)
        }
        for (perm in edit.removePermissions) {
            (ed.elements("uses-permission") + ed.elements("uses-permission-sdk-23"))
                .filter { e -> ed.attr(e, ATTR_NAME)?.let(ed::valueOf) == perm }
                .forEach(ed::removeElement)
        }
        val existing = summary(ed).permissions.toSet()
        for (perm in edit.addPermissions.distinct()) {
            require(perm.isNotBlank() && !perm.contains(' ')) { "Invalid permission: '$perm'" }
            if (perm !in existing) ed.addManifestChild("uses-permission", perm)
        }
        return ed.encode()
    }

    /**
     * Clone-style rename: relative class names become absolute (so classes in dex still
     * resolve), and authorities / permissions that start with the old package move to the new
     * one so the clone can be installed next to the original.
     */
    private fun renameOwned(ed: AxmlEditor, oldPkg: String, newPkg: String) {
        fun moved(v: String) = if (v == oldPkg || v.startsWith("$oldPkg.")) newPkg + v.removePrefix(oldPkg) else v
        for (e in ed.elements) {
            val tag = ed.elementName(e)
            if (tag in CLASS_NAME_TAGS) {
                ed.attr(e, ATTR_NAME)?.let { a -> ed.valueOf(a).let { n -> if (n.startsWith(".") || !n.contains('.')) ed.setString(e, ATTR_NAME, fullClassName(oldPkg, n)) } }
                ed.attr(e, ATTR_TARGET_ACTIVITY)?.let { a ->
                    val n = ed.valueOf(a)
                    if (n.startsWith(".") || !n.contains('.')) ed.setString(e, ATTR_TARGET_ACTIVITY, fullClassName(oldPkg, n))
                }
            }
            if (tag == "provider") ed.attr(e, ATTR_AUTHORITIES)?.let { a ->
                val v = ed.valueOf(a)
                val nv = v.split(';').joinToString(";") { moved(it) }
                if (nv != v) ed.setString(e, ATTR_AUTHORITIES, nv)
            }
            if (tag in PERMISSION_TAGS) ed.attr(e, ATTR_NAME)?.let { a ->
                val v = ed.valueOf(a); val nv = moved(v)
                if (nv != v) ed.setString(e, ATTR_NAME, nv)
            }
            ed.attr(e, ATTR_PERMISSION)?.let { a ->
                val v = ed.valueOf(a); val nv = moved(v)
                if (a.type == AxmlEditor.TYPE_STRING && nv != v) ed.setString(e, ATTR_PERMISSION, nv)
            }
        }
    }
}
