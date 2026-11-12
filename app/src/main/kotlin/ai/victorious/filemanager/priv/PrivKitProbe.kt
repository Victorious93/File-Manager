package ai.victorious.filemanager.priv

/**
 * SPIKE ONLY, not for merge. Referencing a priv-kit class makes the compiler resolve the library, so
 * CI shows whether it builds with this project's AGP/Kotlin/compileSdk. It changes no behaviour.
 */
internal object PrivKitProbe {
    val className: String = priv.kit.core.Privilege::class.java.name
}
