package ai.victorious.filemanager.core.cloud

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Shared JSON + small helpers for the OAuth cloud backends. */
internal val cloudJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

internal fun Request.Builder.bearer(token: String): Request.Builder = header("Authorization", "Bearer $token")

internal fun OkHttpClient.execOrThrow(req: Request, what: String): Response {
    val resp = newCall(req).execute()
    if (!resp.isSuccessful) {
        val code = resp.code
        resp.close()
        throw CloudException("$what failed: HTTP $code")
    }
    return resp
}
