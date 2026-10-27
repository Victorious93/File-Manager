package ai.victorious.filemanager.core.listing

import ai.victorious.filemanager.core.fs.FileEntry
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Sorting and filtering a listing off the main thread. A 10k+ entry folder takes long enough to
 * sort that doing it during composition janks; typing in the filter re-ran it on every keystroke.
 */
object ListingWork {
    const val FILTER_DEBOUNCE_MS = 250L

    /** Debounce only when the user is typing in the filter; sort/hidden/folder changes apply at once. */
    fun debounceFor(previous: ListingOptions?, next: ListingOptions): Long =
        if (previous != null && previous.filter != next.filter && previous.copy(filter = next.filter) == next) FILTER_DEBOUNCE_MS else 0L

    /** Cancellable: a newer keystroke cancels this one during the debounce or before the result is used. */
    suspend fun compute(
        entries: List<FileEntry>,
        options: ListingOptions,
        previous: ListingOptions?,
        context: CoroutineContext = Dispatchers.Default,
    ): List<FileEntry> {
        val wait = debounceFor(previous, options)
        if (wait > 0) delay(wait)
        return withContext(context) { options.apply(entries) }
    }
}
