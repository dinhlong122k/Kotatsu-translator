package org.koitharu.kotatsu.translator.ai

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import org.koitharu.kotatsu.translator.domain.model.AiException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * Timeouts of a streaming request.
 *
 * [headersMs] and [idleMs] are implemented with the OkHttp read timeout (it limits both the wait for the response
 * headers and the gap between two chunks), so the larger of them is not distinguished: both default to 20 s.
 */
internal data class StreamTimeouts(
	val totalMs: Long = STREAM_TOTAL_MS,
	val headersMs: Long = STREAM_IDLE_MS,
	val idleMs: Long = STREAM_IDLE_MS,
) {

	companion object {

		const val STREAM_TOTAL_MS = 120_000L
		const val STREAM_IDLE_MS = 20_000L
	}
}

/**
 * HTTP layer shared by the adapters. Failures are mapped to [AiException]; coroutine cancellation cancels the running
 * call and is rethrown as [kotlinx.coroutines.CancellationException].
 *
 * [baseClient] is the `@TranslatorHttpClient`: its read timeout is 0, so every request derives a client with its own
 * timeouts (`newBuilder` shares the connection pool, proxy, DNS and certificates).
 */
internal class AiTransport(private val baseClient: OkHttpClient) {

	/**
	 * POSTs [body] as JSON and reads the response as server-sent events; [onEvent] gets the data of each event.
	 * It runs on an IO thread and may throw [AiException].
	 */
	suspend fun postStream(
		url: String,
		headers: Map<String, String>,
		body: JSONObject,
		onEvent: (String) -> Unit,
		timeouts: StreamTimeouts = StreamTimeouts(),
	) {
		val secrets = AiHttp.secretsIn(headers)
		val client = baseClient.newBuilder()
			.callTimeout(timeouts.totalMs, TimeUnit.MILLISECONDS)
			.readTimeout(maxOf(timeouts.idleMs, timeouts.headersMs), TimeUnit.MILLISECONDS)
			.build()
		val request = buildRequest(url, headers + ("Accept-Encoding" to "identity"), body)
		execute(client.newCall(request), secrets, timeouts.idleMs) { response ->
			if (!response.isSuccessful) throw httpError(response, secrets)
			try {
				SseReader.read(response.body.source(), onEvent)
			} catch (e: AiException) {
				// Error events raised by the adapter carry provider text: redact it like HTTP errors.
				throw AiHttp.cleaned(e, secrets)
			}
		}
	}

	/**
	 * GET [url]. The body is read and returned unless [readBody] is false.
	 */
	suspend fun get(
		url: String,
		headers: Map<String, String>,
		timeoutMs: Long = GET_TIMEOUT_MS,
		readBody: Boolean = true,
	): String {
		val secrets = AiHttp.secretsIn(headers)
		val client = baseClient.newBuilder()
			.callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
			.readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
			.build()
		val request = try {
			Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
		} catch (e: IllegalArgumentException) {
			throw AiException(AiException.Kind.REJECTED, "Invalid request URL or headers")
		}
		return execute(client.newCall(request), secrets, timeoutMs) { response ->
			if (!response.isSuccessful) throw httpError(response, secrets)
			if (readBody) response.body.string() else ""
		}
	}

	suspend fun getJson(
		url: String,
		headers: Map<String, String>,
		timeoutMs: Long = GET_TIMEOUT_MS,
	): JSONObject {
		val text = get(url, headers, timeoutMs)
		try {
			return JSONObject(text)
		} catch (e: JSONException) {
			throw AiException(AiException.Kind.RETRYABLE, "Failed to read response body")
		}
	}

	private fun buildRequest(url: String, headers: Map<String, String>, body: JSONObject): Request = try {
		Request.Builder()
			.url(url)
			.apply { headers.forEach { (k, v) -> header(k, v) } }
			.post(body.toString().toRequestBody(JSON))
			.build()
	} catch (e: IllegalArgumentException) {
		throw AiException(AiException.Kind.REJECTED, "Invalid request URL or headers")
	}

	private fun httpError(response: Response, secrets: List<String>): AiException {
		val raw = try {
			response.peekBody(MAX_ERROR_BODY).string()
		} catch (e: IOException) {
			""
		}
		return AiHttp.httpError(response.code, raw, response.header("Retry-After"), secrets)
	}

	/**
	 * Runs the call on an IO thread and calls [block] with the response. Cancelling the coroutine cancels the call,
	 * which unblocks the pending read.
	 */
	private suspend fun <T> execute(
		call: Call,
		secrets: List<String>,
		idleMs: Long,
		block: (Response) -> T,
	): T = coroutineScope {
		val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
			try {
				awaitCancellation()
			} finally {
				call.cancel()
			}
		}
		try {
			withContext(Dispatchers.IO) {
				try {
					call.execute().use(block)
				} catch (e: AiException) {
					throw e
				} catch (e: IOException) {
					throw mapIo(e, secrets, idleMs)
				}
			}
		} catch (e: AiException) {
			// If the call was cancelled together with the coroutine, report cancellation and not a failure.
			currentCoroutineContext().ensureActive()
			throw e
		} finally {
			watcher.cancel()
		}
	}

	private fun mapIo(e: IOException, secrets: List<String>, idleMs: Long): AiException {
		if (e is InterruptedIOException) {
			return AiException(AiException.Kind.RETRYABLE, "Stream timeout: no data for ${idleMs / 1000}s or the total limit was reached")
		}
		val detail = e.message ?: e.javaClass.simpleName
		return AiException(
			AiException.Kind.RETRYABLE,
			AiHttp.message("Network error or timeout: ", detail, secrets),
		)
	}

	private companion object {

		val JSON = "application/json; charset=utf-8".toMediaType()
		const val GET_TIMEOUT_MS = 120_000L
		const val MAX_ERROR_BODY = 64L * 1024L
	}
}
