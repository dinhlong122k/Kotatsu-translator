package org.koitharu.kotatsu.translator.ai

import okio.BufferedSource

/**
 * Reads a server-sent-events body line by line. Blocking: call from an IO thread. Timeouts (no data for too long)
 * are enforced by the OkHttp client that owns the source (see [AiTransport]), a timeout surfaces as an IOException.
 */
internal object SseReader {

	/**
	 * Calls [onData] with the joined `data:` lines of each event (`[DONE]` is skipped). Comments and the
	 * `event:`, `id:`, `retry:` fields are ignored. Handles LF and CRLF line ends.
	 */
	fun read(source: BufferedSource, onData: (String) -> Unit) {
		val data = ArrayList<String>()
		while (true) {
			val line = source.readUtf8Line() ?: break
			if (line.isEmpty()) {
				emit(data, onData)
			} else if (line.startsWith("data:")) {
				val value = line.substring(5)
				data.add(if (value.startsWith(' ')) value.substring(1) else value)
			}
		}
		emit(data, onData)
	}

	private fun emit(data: MutableList<String>, onData: (String) -> Unit) {
		if (data.isEmpty()) return
		val joined = data.joinToString("\n")
		data.clear()
		if (joined.trim() != "[DONE]") onData(joined)
	}
}
