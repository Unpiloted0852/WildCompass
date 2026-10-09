package io.github.unpiloted0852.wildcompass

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Http {
    // Both iNaturalist and GBIF ask API users to identify their application.
    private const val USER_AGENT = "WildCompass/${BuildConfig.VERSION_NAME} (Android)"

    class StatusException(val code: Int, host: String) : IOException("HTTP $code from $host")

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun getJson(url: HttpUrl): JSONObject {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        val response = client.newCall(request).await()
        return withContext(Dispatchers.IO) {
            response.use {
                if (!it.isSuccessful) throw StatusException(it.code, url.host)
                JSONObject(it.body?.string() ?: throw IOException("Empty answer from ${url.host}"))
            }
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }
}

/** A JSON string field, or null when it is missing, JSON null, or blank. */
fun JSONObject.str(name: String): String? =
    if (isNull(name)) null else optString(name).trim().ifEmpty { null }
