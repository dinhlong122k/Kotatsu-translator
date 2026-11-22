package org.koitharu.kotatsu.translator.domain

import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.model.getLocale
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.util.ext.EventFlow
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.lifecycleScope
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import org.koitharu.kotatsu.translator.ai.AiClientFactory
import org.koitharu.kotatsu.translator.ai.ModelPresets
import org.koitharu.kotatsu.translator.ai.PromptBubble
import org.koitharu.kotatsu.translator.ai.PromptBuilder
import org.koitharu.kotatsu.translator.ai.SkillPacks
import org.koitharu.kotatsu.translator.context.SeriesProfileRepository
import org.koitharu.kotatsu.translator.context.mergeResult
import org.koitharu.kotatsu.translator.context.trimProfile
import org.koitharu.kotatsu.translator.data.TranslatorSettings
import org.koitharu.kotatsu.translator.domain.model.AiException
import org.koitharu.kotatsu.translator.domain.model.AiProvider
import org.koitharu.kotatsu.translator.domain.model.LanguagePair
import org.koitharu.kotatsu.translator.domain.model.OcrMode
import org.koitharu.kotatsu.translator.domain.model.OcrNotReadyException
import org.koitharu.kotatsu.translator.domain.model.PageTranslation
import org.koitharu.kotatsu.translator.domain.model.TextBlock
import org.koitharu.kotatsu.translator.domain.model.TranslateRequest
import org.koitharu.kotatsu.translator.domain.model.TranslateResult
import org.koitharu.kotatsu.translator.domain.model.TranslatedBlock
import org.koitharu.kotatsu.translator.ocr.OcrEngine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Translates the pages the reader is looking at and the ones it will look at next. One instance lives as long as the
 * reader activity (also across rotations). Port of `orchestrator.ts`: OCR with a priority slot, batches of several
 * pages per request with a slow start, at most two requests in flight, in-place promotion of background pages,
 * single flight per page, hedged requests, fallback models and the series context written only after a success.
 *
 * Everything is idle until [isActive] is true: no model is loaded and no request is sent.
 */
@ActivityRetainedScoped
class TranslationOrchestrator @Inject constructor(
	lifecycle: ActivityRetainedLifecycle,
	private val settings: TranslatorSettings,
	private val pageLoader: PageLoader,
	private val ocrEngine: OcrEngine,
	private val aiClientFactory: AiClientFactory,
	private val repository: SeriesProfileRepository,
	private val skillPacks: SkillPacks,
) {

	private val scope = lifecycle.lifecycleScope + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
		e.printStackTraceDebug()
	}

	private val lock = Any()
	private val states = ConcurrentHashMap<Long, MutableStateFlow<PageTranslation>>()
	private val tasks = HashMap<Long, PageTask>()
	private val batchers = HashMap<Long, SeriesBatcher>()
	private val idleBursts = HashMap<Long, IdleBurst>()
	private val planners = HashMap<Long, PagePlanner>()
	private val prefetchedChapters = HashSet<Long>()
	private val notifiedPacks = HashSet<String>()

	private val caches = SessionCaches()
	private val ocrSlots = PrioritySemaphore(OCR_SLOTS)
	private val rateLimiter = RateLimiter(limit = { settings.requestsPerMinute })
	private val health = ModelHealth()
	private val retryPolicy = RetryPolicy()

	private val _isActive = MutableStateFlow(false)
	private val _showOriginal = MutableStateFlow(false)
	private val _events = MutableEventFlow<TranslatorEvent>()
	private val _fontScale = MutableStateFlow(settings.fontScale)

	@Volatile
	private var lastPosition: Position? = null

	@Volatile
	private var currentMangaId = 0L

	@Volatile
	private var authError: AiException? = null

	@Volatile
	private var authNotified = false

	@Volatile
	private var quotaNotifiedAt = 0L

	@Volatile
	private var settingsSignature = ""

	/** The translator works for the current reading session */
	val isActive: StateFlow<Boolean> = _isActive

	/** The original text is shown instead of the translation (FR-42) */
	val isShowingOriginal: StateFlow<Boolean> = _showOriginal

	val events: EventFlow<TranslatorEvent> = _events

	/** Scale of the translated text, 0.8..1.5 */
	val fontScale: StateFlow<Float> = _fontScale

	init {
		if (CLEANUP_STARTED.compareAndSet(false, true)) {
			scope.launch {
				runCatchingCancellable { repository.deleteExpired(settings.contextRetentionDays) }
					.onFailure { it.printStackTraceDebug() }
			}
		}
		settingsSignature = computeSignature()
		scope.launch {
			settings.observeAny().drop(1).collect { onSettingsChanged() }
		}
	}

	fun setActive(value: Boolean) {
		if (_isActive.value == value) return
		_isActive.value = value
		if (value) {
			clearBlocks()
			lastPosition?.let { pos ->
				warmUp(pos.manga)
				scope.launch { plan(pos) }
			}
		} else {
			_showOriginal.value = false
			cancelAll()
		}
	}

	fun toggle() = setActive(!_isActive.value)

	fun setShowOriginal(value: Boolean) {
		_showOriginal.value = value
	}

	/** Called once when a manga is opened: decides whether the translator starts on. */
	fun onMangaOpened(manga: Manga) {
		if (currentMangaId == manga.id) return
		currentMangaId = manga.id
		cancelAll()
		_showOriginal.value = false
		val initial = settings.isEnabled && (settings.isAutoOnOpen || settings.isAutoSource(manga.source.unwrap().name))
		_isActive.value = initial
		if (initial) {
			warmUp(manga)
		}
	}

	fun observe(pageId: Long): StateFlow<PageTranslation> = stateOf(pageId)

	/**
	 * Called at every scroll or page change, so it only does cheap work: planning and starting of jobs.
	 * @param chapterId chapter of the page the reader is on; [lowerPos]..[upperPos] are positions in [pages]
	 */
	fun onPositionChanged(
		manga: Manga,
		chapterId: Long,
		pages: List<ReaderPage>,
		lowerPos: Int,
		upperPos: Int,
		isWebtoon: Boolean,
	) {
		val position = Position(manga, chapterId, pages, lowerPos, upperPos, isWebtoon)
		lastPosition = position
		if (_isActive.value) {
			plan(position)
		}
	}

	/** Translates the page again after a failure (FR-53) */
	fun retry(pageId: Long) {
		val position = lastPosition ?: return
		if (!_isActive.value) return
		clearBlocks()
		val page = position.pages.firstOrNull { it.id == pageId } ?: return
		val session = createSession(position.manga, position.isWebtoon) ?: return
		val planner = synchronized(lock) {
			if (tasks.containsKey(pageId)) return
			planners[page.chapterId]?.also { it.reset(page.index) }
		}
		begin(session, page, background = false, planner = planner)
	}

	/** FR-46: the first pages of the next chapter, in the background */
	fun prefetchChapter(manga: Manga, chapterId: Long, pages: List<ReaderPage>) {
		if (!_isActive.value || !settings.isPrefetchNextChapter) return
		val isNew = synchronized(lock) { prefetchedChapters.add(chapterId) }
		if (!isNew) return
		val session = createSession(manga, lastPosition?.isWebtoon ?: false) ?: return
		pages.asSequence()
			.filter { it.chapterId == chapterId }
			.sortedBy { it.index }
			.take(PREFETCH_PAGES)
			.forEach { page ->
				val planner = synchronized(lock) { planners[page.chapterId] }
				begin(session, page, background = true, planner = planner)
			}
	}

	// ---- planning ----

	private fun plan(position: Position) {
		val session = createSession(position.manga, position.isWebtoon) ?: return
		val chapterId = position.chapterId
		val byIndex = HashMap<Int, ReaderPage>()
		for (p in position.pages) {
			if (p.chapterId == chapterId) byIndex[p.index] = p
		}
		if (byIndex.isEmpty()) return
		var first = Int.MAX_VALUE
		var last = Int.MIN_VALUE
		for (pos in position.lowerPos..position.upperPos) {
			val p = position.pages.getOrNull(pos) ?: continue
			if (p.chapterId != chapterId) continue
			if (p.index < first) first = p.index
			if (p.index > last) last = p.index
		}
		if (first > last) return
		val planner = synchronized(lock) { planners.getOrPut(chapterId) { PagePlanner() } }
		val plan = planner.update(first, byIndex.keys.max() + 1)
		for (index in plan.cancel) {
			val page = byIndex[index] ?: continue
			val task = synchronized(lock) { tasks[page.id] }
			if (task != null && !task.isBackground) continue // somebody needs it now
			task?.job?.cancel()
			planner.markFailed(index)
		}
		for (index in plan.start) {
			val page = byIndex[index] ?: continue
			begin(session, page, background = index !in first..last, planner = planner)
		}
		// pages the reader has reached while they were queued as background
		for (index in first..last) {
			val page = byIndex[index] ?: continue
			val task = synchronized(lock) { tasks[page.id] } ?: continue
			promote(task)
		}
	}

	/** Starts the job of a page unless the result is already known or the page is already being worked on */
	private fun begin(session: Session, page: ReaderPage, background: Boolean, planner: PagePlanner?) {
		val key = SessionCaches.translationKey(session.pair.source, session.pair.target, session.mode, page.id)
		val cached = caches.translations[key]
		if (cached != null) {
			stateOf(page.id).value = if (cached.isEmpty()) {
				PageTranslation.Empty
			} else {
				PageTranslation.Ready(cached, isPartial = false)
			}
			planner?.markDone(page.index)
			return
		}
		synchronized(lock) {
			val existing = tasks[page.id]
			if (existing != null) {
				if (!background) promote(existing)
				return
			}
			val task = PageTask(page, session, background, planner)
			tasks[page.id] = task
			task.job = scope.launch { runPage(task) }
		}
	}

	private fun promote(task: PageTask) {
		if (!task.isBackground) return
		task.isBackground = false
		flush(task.session.mangaId)
	}

	private fun createSession(manga: Manga, isWebtoon: Boolean): Session? {
		val target = settings.targetLanguage
		val lang = TranslatorLanguages.resolveSource(
			override = settings.sourceLanguageOverride,
			sourceLanguage = manga.source.getLocale()?.language,
			target = target,
		) ?: return null
		return Session(manga.id, LanguagePair(lang, target), settings.ocrMode, isWebtoon)
	}

	private fun warmUp(manga: Manga) {
		val session = createSession(manga, false) ?: return
		scope.launch { ocrEngine.warmUp(session.pair.source, session.mode) }
		scope.launch {
			val provider = settings.provider
			runCatchingCancellable { aiClientFactory.create(provider).warmUp(settings.modelFor(provider)) }
				.onFailure { it.printStackTraceDebug() }
		}
	}

	private fun stateOf(pageId: Long): MutableStateFlow<PageTranslation> =
		states.getOrPut(pageId) { MutableStateFlow(PageTranslation.Idle) }

	/** Cancels everything, every page goes back to idle. Cached results stay. */
	private fun cancelAll() {
		val jobs = ArrayList<Job>()
		synchronized(lock) {
			tasks.values.mapNotNullTo(jobs) { it.job }
			for (b in batchers.values) b.running.mapNotNullTo(jobs) { it.job }
			tasks.clear()
			batchers.clear()
			idleBursts.clear()
			planners.clear()
			prefetchedChapters.clear()
		}
		jobs.forEach { it.cancel() }
		for (flow in states.values) {
			flow.value = PageTranslation.Idle
		}
	}

	private fun clearBlocks() {
		authError = null
		authNotified = false
		synchronized(lock) { notifiedPacks.clear() }
	}

	private fun computeSignature(): String =
		"${settings.targetLanguage}|${settings.sourceLanguageOverride}|${settings.ocrMode}"

	private fun onSettingsChanged() {
		_fontScale.value = settings.fontScale
		clearBlocks()
		if (!settings.isEnabled && _isActive.value) {
			setActive(false)
			return
		}
		val signature = computeSignature()
		val isChanged = signature != settingsSignature
		settingsSignature = signature
		if (!_isActive.value) return
		if (isChanged) {
			cancelAll()
		} else {
			synchronized(lock) { planners.values.forEach { it.clear() } }
		}
		lastPosition?.let { plan(it) }
	}

	// ---- one page ----

	private suspend fun runPage(task: PageTask) {
		val page = task.page
		val session = task.session
		val flow = stateOf(page.id)
		var isFinished = false
		try {
			authError?.let { throw it }
			flow.value = PageTranslation.Pending
			val blocks = recognize(task)
			val key = SessionCaches.translationKey(session.pair.source, session.pair.target, session.mode, page.id)
			if (blocks.isEmpty()) {
				caches.translations[key] = emptyList()
				flow.value = PageTranslation.Empty
				isFinished = true
				return
			}
			when (val outcome = awaitTranslation(Entry(task, blocks))) {
				is PageOutcome.Done -> if (outcome.blocks.isEmpty()) {
					flow.value = PageTranslation.Failed(AiException(AiException.Kind.BAD_OUTPUT, "No translation received"))
				} else {
					if (outcome.isComplete) {
						caches.translations[key] = outcome.blocks
					}
					flow.value = PageTranslation.Ready(outcome.blocks, isPartial = !outcome.isComplete)
				}

				is PageOutcome.Error -> flow.value = PageTranslation.Failed(outcome.error)
			}
			isFinished = true
		} catch (e: CancellationException) {
			throw e
		} catch (e: OcrNotReadyException) {
			notifyOcrMissing(e)
			flow.value = PageTranslation.Failed(e)
			isFinished = true
		} catch (e: Throwable) {
			e.printStackTraceDebug()
			flow.value = PageTranslation.Failed(e)
			isFinished = true
		} finally {
			val isCurrent = synchronized(lock) {
				val current = tasks[page.id]
				if (current === task) tasks.remove(page.id)
				current === task || current == null
			}
			if (isFinished) {
				task.planner?.markDone(page.index)
			} else {
				task.planner?.markFailed(page.index)
				if (isCurrent) flow.compareAndSet(PageTranslation.Pending, PageTranslation.Idle)
			}
		}
	}

	/** Cache of the OCR, then the image, then a slot of the (single) OCR engine; foreground goes first */
	private suspend fun recognize(task: PageTask): List<TextBlock> {
		val page = task.page
		val session = task.session
		val lang = session.pair.source
		val ocrKey = SessionCaches.ocrKey(session.mode, lang, page.id)
		caches.ocr[ocrKey]?.let { return it }
		val uri = pageLoader.loadPage(page.toMangaPage(), force = false)
		ocrSlots.acquire(session.mangaId, page.index) { task.isBackground }
		val blocks = try {
			ocrEngine.recognize(uri, lang, session.mode, rtl = lang == "ja" && !session.isWebtoon)
		} finally {
			ocrSlots.release()
		}
		caches.ocr[ocrKey] = blocks
		return blocks
	}

	// ---- batching ----

	private suspend fun awaitTranslation(entry: Entry): PageOutcome {
		val seriesKey = entry.task.session.mangaId
		synchronized(lock) {
			val batcher = batchers.getOrPut(seriesKey) {
				// a reader who keeps reading must not drop back to 2-page batches after every page
				val prev = idleBursts.remove(seriesKey)
				val started = if (prev != null && now() - prev.at < BatchPlanner.IDLE_RESTART_MS) prev.started else 0
				SeriesBatcher(started)
			}
			batcher.pending.add(entry)
			entry.task.entry = entry
		}
		flush(seriesKey)
		try {
			return entry.result.await()
		} catch (e: CancellationException) {
			onEntryAborted(entry)
			throw e
		}
	}

	private fun onEntryAborted(entry: Entry) {
		val seriesKey = entry.task.session.mangaId
		var toCancel: Job? = null
		synchronized(lock) {
			if (entry.result.isCompleted) return
			entry.isAborted = true
			batchers[seriesKey]?.pending?.remove(entry)
			// the request is only cancelled when every page riding on it was aborted; pages that finished by
			// streaming let it complete so the context is still merged
			val batch = entry.batch
			if (batch != null && batch.entries.all { it.isAborted }) {
				toCancel = batch.job
			}
		}
		toCancel?.cancel()
		flush(seriesKey)
	}

	/**
	 * Starts requests while allowed: the first page never waits; a second request may overlap during the slow
	 * start burst or once a full batch is pending. Otherwise pages wait for the request in flight.
	 */
	private fun flush(seriesKey: Long) {
		val started = ArrayList<Batch>()
		var needsRateSlot = false
		synchronized(lock) {
			val b = batchers[seriesKey] ?: return
			while (true) {
				if (b.pending.isEmpty()) {
					if (b.inFlight == 0) {
						batchers.remove(seriesKey)
						idleBursts[seriesKey] = IdleBurst(b.started, now())
					}
					break
				}
				if (b.inFlight >= BatchPlanner.MAX_IN_FLIGHT) break
				val t = now()
				// a stuck request must not hold the queue back
				val hasStale = b.running.any { t - it.startedAt >= BatchPlanner.STALE_MS }
				val isBackgroundOnly = b.running.all { r -> r.entries.all { it.task.isBackground } }
				val hasForeground = b.pending.any { !it.task.isBackground }
				if (BatchPlanner.shouldWait(
						inFlight = b.inFlight,
						started = b.started,
						hasStale = hasStale,
						foregroundBlockedByBackground = hasForeground && isBackgroundOnly,
						nextChapterPages = BatchPlanner.nextChapterPages(b.pending),
					)
				) {
					break
				}
				// the batch is cut only once a request slot is free, so pages that finish meanwhile ride along
				if (settings.requestsPerMinute > 0 && b.slot != Slot.READY) {
					if (b.slot != Slot.WAITING) {
						b.slot = Slot.WAITING
						needsRateSlot = true
					}
					break
				}
				b.slot = Slot.NONE
				val entries = BatchPlanner.take(b.pending, BatchPlanner.capsFor(b.started))
				b.pending.removeAll(entries.toSet())
				val batch = Batch(entries, t, isFirst = b.started == 0)
				for (e in entries) e.batch = batch
				b.inFlight++
				b.started++
				b.running.add(batch)
				batch.job = scope.launch(start = CoroutineStart.LAZY) { runBatch(seriesKey, batch) }
				started.add(batch)
			}
		}
		if (needsRateSlot) {
			scope.launch {
				runCatchingCancellable { rateLimiter.ready() }
				synchronized(lock) { batchers[seriesKey]?.slot = Slot.READY }
				flush(seriesKey)
			}
		}
		for (batch in started) {
			val job = batch.job ?: continue
			job.invokeOnCompletion { onBatchFinished(seriesKey, batch) }
			job.start()
		}
	}

	private fun onBatchFinished(seriesKey: Long, batch: Batch) {
		synchronized(lock) {
			val b = batchers[seriesKey]
			if (b != null && b.running.remove(batch)) {
				b.inFlight--
			}
		}
		for (e in batch.entries) {
			e.result.complete(PageOutcome.Error(AiException(AiException.Kind.ABORTED, "Aborted")))
		}
		flush(seriesKey)
	}

	private suspend fun runBatch(seriesKey: Long, batch: Batch) = coroutineScope {
		// a request older than STALE_MS lets the next one start
		val timer = launch {
			delay(BatchPlanner.STALE_MS)
			flush(seriesKey)
		}
		try {
			translateBatch(batch)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			e.printStackTraceDebug()
			for (entry in batch.entries) {
				entry.result.complete(PageOutcome.Error(e))
			}
		} finally {
			timer.cancel()
		}
	}

	private suspend fun translateBatch(batch: Batch) {
		val entries = batch.entries
		val head = entries[0]
		val session = head.task.session
		val pair = session.pair
		val mangaId = session.mangaId
		authError?.let { throw it }

		// the context is taken as a snapshot: no lock is held during the network call
		val profile = baseProfileFor(repository.get(mangaId), pair, System.currentTimeMillis())
		val packs = withContext(Dispatchers.IO) { skillPacks.select(pair, profile.genres.toSet()) }
		val bubbles = ArrayList<PromptBubble>()
		val owners = HashMap<String, Entry>()
		for (e in entries) {
			for (b in e.blocks) {
				val id = "${e.pageIndex}:${b.id}"
				owners[id] = e
				bubbles.add(PromptBubble(id, b.text))
			}
		}
		val request = PromptBuilder.build(pair, profile.toPromptContext(), packs, bubbles)

		// a page is finished as soon as all of its blocks arrived in the stream
		val got = HashMap<Entry, HashMap<String, String>>()
		val onBlock: (String, String) -> Unit = { id, text ->
			val entry = owners[id]
			if (entry != null && !entry.result.isCompleted) {
				val ready: Map<String, String>? = synchronized(got) {
					val m = got.getOrPut(entry) { HashMap() }
					val blockId = id.substringAfter(':')
					if (!m.containsKey(blockId)) m[blockId] = text
					if (entry.blocks.all { m.containsKey(it.id) }) HashMap(m) else null
				}
				if (ready != null) {
					entry.result.complete(entry.outcomeOf(ready))
				}
			}
		}

		val result: TranslateResult = try {
			retryPolicy.run { attempt(request, onBlock, batch.isFirst) }
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			if (e !is AiException || e.kind != AiException.Kind.ABORTED) {
				notifyFailure(e)
			}
			val partial = (e as? AiException)?.takeIf { it.kind == AiException.Kind.BAD_OUTPUT }?.partial?.translations
			for (entry in entries) {
				if (entry.result.isCompleted) continue
				var outcome: PageOutcome = PageOutcome.Error(e)
				if (partial != null) {
					val m = HashMap<String, String>()
					synchronized(got) { got[entry]?.let { m.putAll(it) } }
					for ((id, text) in partial) {
						if (owners[id] === entry) m.putIfAbsent(id.substringAfter(':'), text)
					}
					val placed = entry.outcomeOf(m)
					if (placed.blocks.isNotEmpty()) outcome = placed
				}
				entry.result.complete(outcome)
			}
			return
		}
		currentCoroutineContext().ensureActive()

		// merge onto whatever is stored now: another batch may have written meanwhile
		runCatchingCancellable {
			repository.update(mangaId) { stored ->
				val now = System.currentTimeMillis()
				trimProfile(mergeResult(baseProfileFor(stored, pair, now), result, head.chapterId, now, entries.size))
			}
		}.onFailure { it.printStackTraceDebug() }

		for (entry in entries) {
			if (entry.result.isCompleted) continue
			val m = HashMap<String, String>()
			for ((id, text) in result.translations) {
				if (owners[id] === entry) m[id.substringAfter(':')] = text
			}
			entry.result.complete(entry.outcomeOf(m))
		}
	}

	/** One attempt: waits for a request slot, picks a healthy model, hedges if the service is slow */
	private suspend fun attempt(
		request: TranslateRequest,
		onBlock: (String, String) -> Unit,
		isFirst: Boolean,
	): TranslateResult {
		rateLimiter.acquire()
		val provider = settings.provider
		val primary = settings.modelFor(provider)
		val chain = if (settings.isFallbackEnabled) {
			listOf(primary) + ModelPresets.fallbackModels(provider, primary)
		} else {
			listOf(primary)
		}
		val model = health.pick(provider, chain)
		try {
			val (result, used) = hedged(provider, model, chain, request, onBlock, isFirst)
			health.markOk(provider, used)
			return result
		} catch (e: AiException) {
			if (e.kind != AiException.Kind.RETRYABLE) throw e
			health.markFailed(provider, model, e.retryAfterMs)
			// another healthy model: try it at once instead of backing off on the overloaded one
			if (health.alternative(provider, chain, model) != null) {
				throw AiException(AiException.Kind.RETRYABLE, e.message.orEmpty(), retryAfterMs = 0L)
			}
			throw e
		}
	}

	private class Leg(val model: String) {

		@Volatile
		var job: Job? = null

		@Volatile
		var isDone = false
	}

	private class LegOutcome(val leg: Leg, val value: TranslateResult?, val error: Throwable?)

	/**
	 * Hedged request: when nothing has been streamed after [BatchPlanner.HEDGE_MS], a duplicate goes to the next
	 * healthy model (or the same one) and the first one that streams wins, the other is cancelled. The first batch of
	 * a burst goes to two models at once when a second healthy model exists. Only with hedging enabled in settings.
	 */
	private suspend fun hedged(
		provider: AiProvider,
		model: String,
		chain: List<String>,
		request: TranslateRequest,
		onBlock: (String, String) -> Unit,
		immediate: Boolean,
	): Pair<TranslateResult, String> = coroutineScope {
		val client = aiClientFactory.create(provider)
		val outcomes = Channel<LegOutcome>(Channel.UNLIMITED)
		val legLock = Any()
		val legs = ArrayList<Leg>()
		var winner: Leg? = null

		fun launchLeg(m: String) {
			val leg = Leg(m)
			synchronized(legLock) { legs.add(leg) }
			leg.job = launch {
				var value: TranslateResult? = null
				var error: Throwable? = null
				try {
					value = client.translate(m, request) { id, text ->
						val accepted = synchronized(legLock) {
							if (winner == null) {
								winner = leg
								for (other in legs) {
									if (other !== leg && !other.isDone) {
										other.job?.cancel()
										if (other.model != leg.model) {
											health.markFailed(provider, other.model, BatchPlanner.SLOW_COOLDOWN_MS)
										}
									}
								}
							}
							winner === leg
						}
						if (accepted) onBlock(id, text)
					}
				} catch (e: CancellationException) {
					throw e
				} catch (e: Throwable) {
					error = e
				}
				leg.isDone = true
				outcomes.trySend(LegOutcome(leg, value, error))
			}
		}

		launchLeg(model)
		if (settings.isHedgingEnabled) {
			val hasAlternative = health.alternative(provider, chain, model) != null
			launch {
				delay(if (immediate && hasAlternative) 0L else BatchPlanner.HEDGE_MS)
				val canHedge = { synchronized(legLock) { winner == null && legs.size == 1 } }
				if (canHedge()) {
					rateLimiter.acquire()
					if (canHedge()) {
						launchLeg(health.alternative(provider, chain, model) ?: model)
					}
				}
			}
		}

		var result: Pair<TranslateResult, String>? = null
		var firstError: Throwable? = null
		try {
			while (result == null) {
				val o = outcomes.receive()
				val w = synchronized(legLock) { winner }
				if (w != null && w !== o.leg) continue // the beaten leg
				val error = o.error
				if (error == null) {
					synchronized(legLock) { if (winner == null) winner = o.leg }
					result = checkNotNull(o.value) to o.leg.model
				} else {
					if (w === o.leg) throw error
					if (firstError == null) firstError = error
					// wait for a leg that is still running, else report the first failure
					val allDone = synchronized(legLock) { legs.all { it.isDone } }
					if (allDone) throw checkNotNull(firstError)
				}
			}
		} finally {
			coroutineContext.cancelChildren()
		}
		checkNotNull(result)
	}

	// ---- results and events ----

	private fun Entry.outcomeOf(texts: Map<String, String>): PageOutcome.Done {
		val placed = ArrayList<TranslatedBlock>(blocks.size)
		for (b in blocks) {
			val text = texts[b.id] ?: continue
			placed.add(TranslatedBlock(b.id, b.box, b.text, text))
		}
		return PageOutcome.Done(placed, isComplete = placed.size == blocks.size)
	}

	private fun notifyFailure(e: Throwable) {
		if (e !is AiException) return
		when {
			e.kind == AiException.Kind.AUTH -> {
				authError = e
				if (!authNotified) {
					authNotified = true
					_events.call(TranslatorEvent.AuthRequired)
				}
			}

			e.isQuotaExceeded() -> {
				val t = now()
				if (t - quotaNotifiedAt > QUOTA_NOTIFY_INTERVAL_MS) {
					quotaNotifiedAt = t
					_events.call(TranslatorEvent.QuotaExceeded)
				}
			}
		}
	}

	private fun notifyOcrMissing(e: OcrNotReadyException) {
		val isNew = synchronized(lock) { notifiedPacks.add(e.missingPack) }
		if (isNew) {
			_events.call(TranslatorEvent.OcrPackMissing(e.missingPack))
		}
	}

	private fun now(): Long = RateLimiter.DEFAULT_CLOCK()

	// ---- types ----

	private class Session(
		val mangaId: Long,
		val pair: LanguagePair,
		val mode: OcrMode,
		val isWebtoon: Boolean,
	)

	private class Position(
		val manga: Manga,
		val chapterId: Long,
		val pages: List<ReaderPage>,
		val lowerPos: Int,
		val upperPos: Int,
		val isWebtoon: Boolean,
	)

	private class PageTask(
		val page: ReaderPage,
		val session: Session,
		@Volatile var isBackground: Boolean,
		val planner: PagePlanner?,
	) {

		@Volatile
		var job: Job? = null

		@Volatile
		var entry: Entry? = null
	}

	private sealed interface PageOutcome {

		class Done(val blocks: List<TranslatedBlock>, val isComplete: Boolean) : PageOutcome

		class Error(val error: Throwable) : PageOutcome
	}

	private class Entry(
		val task: PageTask,
		val blocks: List<TextBlock>,
	) : BatchItem {

		override val chapterId: Long get() = task.page.chapterId
		override val pageIndex: Int get() = task.page.index
		override val blockCount: Int = blocks.size
		override val charCount: Int = blocks.sumOf { it.text.length }
		override val isBackground: Boolean get() = task.isBackground

		val result = CompletableDeferred<PageOutcome>()

		@Volatile
		var batch: Batch? = null

		@Volatile
		var isAborted = false
	}

	private class Batch(
		val entries: List<Entry>,
		val startedAt: Long,
		val isFirst: Boolean,
	) {

		@Volatile
		var job: Job? = null
	}

	private enum class Slot { NONE, WAITING, READY }

	private class SeriesBatcher(var started: Int) {

		val pending = ArrayList<Entry>()
		val running = HashSet<Batch>()
		var inFlight = 0
		var slot = Slot.NONE
	}

	private class IdleBurst(val started: Int, val at: Long)

	companion object {

		/** OCR is CPU bound: parallel runs only delay the page the reader is looking at */
		private const val OCR_SLOTS = 1
		private const val PREFETCH_PAGES = 6
		private const val QUOTA_NOTIFY_INTERVAL_MS = 30_000L

		private val CLEANUP_STARTED = AtomicBoolean(false)
	}
}
