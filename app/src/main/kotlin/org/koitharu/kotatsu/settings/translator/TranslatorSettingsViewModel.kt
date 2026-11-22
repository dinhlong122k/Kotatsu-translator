package org.koitharu.kotatsu.settings.translator

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.EventFlow
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.translator.ai.AiClientFactory
import org.koitharu.kotatsu.translator.ai.AiModelInfo
import org.koitharu.kotatsu.translator.context.SeriesProfileRepository
import org.koitharu.kotatsu.translator.data.TranslatorSecrets
import org.koitharu.kotatsu.translator.data.TranslatorSettings
import org.koitharu.kotatsu.translator.domain.model.AiProvider
import org.koitharu.kotatsu.translator.domain.model.OcrMode
import org.koitharu.kotatsu.translator.ocr.DownloadProgress
import org.koitharu.kotatsu.translator.ocr.OcrEngine
import org.koitharu.kotatsu.translator.ocr.OcrModelStore
import org.koitharu.kotatsu.translator.ocr.OcrModels
import org.koitharu.kotatsu.translator.ocr.OcrPack
import javax.inject.Inject

/**
 * State of the model list of [provider]: [models] is null while nothing was loaded for it yet.
 */
data class ModelsState(
	val provider: AiProvider,
	val isLoading: Boolean = false,
	val models: List<AiModelInfo>? = null,
	val error: Throwable? = null,
	val needsKey: Boolean = false,
)

data class PackUiState(
	val pack: OcrPack,
	/** Recognizer and dictionary are on disk */
	val isInstalled: Boolean,
	/** Everything needed by the current OCR mode is on disk */
	val isReady: Boolean,
	val sizeOnDisk: Long,
	/** What a download would fetch right now (missing detector, bubble detector for ACCURATE, recognizer, dictionary) */
	val downloadBytes: Long,
	/** Download is running or waits for its turn */
	val isDownloading: Boolean,
	val progress: DownloadProgress?,
	val error: Throwable?,
)

internal class PackTransient(
	val progress: DownloadProgress?,
	val error: Throwable?,
)

@HiltViewModel
class TranslatorSettingsViewModel @Inject constructor(
	private val appSettings: AppSettings,
	private val settings: TranslatorSettings,
	private val secrets: TranslatorSecrets,
	private val clientFactory: AiClientFactory,
	private val ocrStore: OcrModelStore,
	private val ocrEngine: OcrEngine,
	private val profiles: SeriesProfileRepository,
) : BaseViewModel() {

	private val keyRevision = MutableStateFlow(0)
	private val diskRevision = MutableStateFlow(0)
	private val transient = MutableStateFlow<Map<String, PackTransient>>(emptyMap())
	private val downloads = HashMap<String, Job>()
	private val downloadMutex = Mutex()
	private val modelCache = HashMap<AiProvider, List<AiModelInfo>>()
	private var modelsJob: Job? = null
	private val keySaveEvent = MutableEventFlow<Boolean>()
	private val contextClearedEvent = MutableEventFlow<Unit>()

	val provider: StateFlow<AiProvider> = settings.observeProvider()
		.stateIn(viewModelScope, SharingStarted.Eagerly, settings.provider)

	/** Whether the key of the current provider is set; null until it is known. Never carries the key itself. */
	val hasKey: StateFlow<Boolean?> = combine(provider, keyRevision) { p, _ -> p }
		.map { p -> withContext(Dispatchers.IO) { secrets.has(p) } }
		.stateIn<Boolean?>(viewModelScope, SharingStarted.Eagerly, null)

	private val _models = MutableStateFlow(ModelsState(settings.provider))
	val models: StateFlow<ModelsState>
		get() = _models

	val autoSources: StateFlow<Set<String>> = settings.observeAutoSources()
		.stateIn(viewModelScope, SharingStarted.Eagerly, settings.autoSources)

	val packs: StateFlow<List<PackUiState>> = combine(
		settings.observeOcrMode(),
		diskRevision,
		transient,
	) { mode, _, tr -> mode to tr }
		.map { (mode, tr) -> withContext(Dispatchers.IO) { buildPacks(mode, tr) } }
		.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

	/** Source language chosen by the user whose OCR data is not installed, or null */
	val missingPackLanguage: StateFlow<String?> = combine(
		settings.observeSourceLanguageOverride(),
		packs,
	) { lang, list ->
		if (lang == null || list.isEmpty()) {
			return@combine null
		}
		val candidates = OcrModels.packCandidates(lang)
		if (candidates.isEmpty() || candidates.any { c -> list.any { it.pack.id == c.id && it.isReady } }) {
			null
		} else {
			lang
		}
	}.stateIn(viewModelScope, SharingStarted.Eagerly, null)

	/** true if the key was stored, false if it could not be encrypted */
	val onKeySaved: EventFlow<Boolean>
		get() = keySaveEvent

	val onContextCleared: EventFlow<Unit>
		get() = contextClearedEvent

	init {
		appSettings.observe(AppSettings.KEY_TRANSLATOR_PROVIDER, AppSettings.KEY_TRANSLATOR_OPENAI_BASE_URL)
			.onEach { key ->
				if (key == AppSettings.KEY_TRANSLATOR_OPENAI_BASE_URL) {
					modelCache.remove(AiProvider.OPENAI)
					loadModels(useCache = false)
				} else {
					loadModels(useCache = true)
				}
			}.launchIn(viewModelScope)
	}

	/**
	 * Loads the model list of the current provider. If [useCache] and the list was already loaded, it is reused.
	 */
	fun loadModels(useCache: Boolean) {
		modelsJob?.cancel()
		val provider = settings.provider
		if (useCache) {
			modelCache[provider]?.let {
				_models.value = ModelsState(provider, models = it)
				return
			}
		}
		modelsJob = viewModelScope.launch {
			_models.value = ModelsState(provider, isLoading = true, models = modelCache[provider])
			val canList = withContext(Dispatchers.IO) { secrets.has(provider) } || hasCustomEndpoint(provider)
			if (!canList) {
				_models.value = ModelsState(provider, needsKey = true)
				return@launch
			}
			runCatchingCancellable {
				withContext(Dispatchers.Default) { clientFactory.create(provider).listModels() }
			}.onSuccess {
				modelCache[provider] = it
				_models.value = ModelsState(provider, models = it)
			}.onFailure {
				it.printStackTraceDebug()
				_models.value = ModelsState(provider, models = modelCache[provider], error = it)
			}
		}
	}

	/**
	 * Stores (or removes if [key] is blank) the key of [provider]. The result arrives in [onKeySaved].
	 */
	fun saveApiKey(provider: AiProvider, key: String?) {
		viewModelScope.launch {
			val isStored = withContext(Dispatchers.IO) { secrets.set(provider, key) }
			keyRevision.update { it + 1 }
			keySaveEvent.call(isStored)
			if (isStored && provider == settings.provider) {
				modelCache.remove(provider)
				loadModels(useCache = false)
			}
		}
	}

	/**
	 * Cheap authenticated request, no tokens are spent.
	 * @return null on success or the error
	 */
	suspend fun testConnection(): Throwable? = withContext(Dispatchers.Default) {
		val provider = settings.provider
		val model = settings.modelFor(provider)
		runCatchingCancellable { clientFactory.create(provider).testModel(model) }.exceptionOrNull()
	}

	fun downloadPack(pack: OcrPack) {
		if (downloads[pack.id]?.isActive == true) {
			return
		}
		val mode = settings.ocrMode
		downloads[pack.id] = launchJob(Dispatchers.Default) {
			transient.update { it + (pack.id to PackTransient(null, null)) }
			try {
				// One download at a time: packs share the detector files
				downloadMutex.withLock {
					ocrStore.download(pack, mode).collect { progress ->
						transient.update { it + (pack.id to PackTransient(progress, null)) }
					}
				}
				transient.update { it - pack.id }
			} catch (e: CancellationException) {
				transient.update { it - pack.id }
				throw e
			} catch (e: Throwable) {
				e.printStackTraceDebug()
				transient.update { it + (pack.id to PackTransient(null, e)) }
			} finally {
				diskRevision.update { it + 1 }
			}
		}
	}

	fun cancelDownload(pack: OcrPack) {
		downloads[pack.id]?.cancel()
	}

	fun deletePack(pack: OcrPack) {
		launchJob(Dispatchers.IO) {
			downloads[pack.id]?.cancelAndJoin()
			transient.update { it - pack.id }
			ocrStore.delete(pack)
			ocrEngine.release()
			diskRevision.update { it + 1 }
		}
	}

	fun removeAutoSources(names: Collection<String>) {
		for (name in names) {
			settings.setAutoSource(name, false)
		}
	}

	fun clearContext() {
		launchJob(Dispatchers.Default) {
			profiles.clearAll()
			contextClearedEvent.call(Unit)
		}
	}

	private fun hasCustomEndpoint(provider: AiProvider): Boolean {
		if (provider != AiProvider.OPENAI) {
			return false
		}
		val custom = settings.openAiBaseUrl?.trim()?.trimEnd('/')
		return !custom.isNullOrEmpty() && custom != TranslatorSettings.BASE_URL_OPENAI
	}

	private fun hasModel(key: String): Boolean {
		val file = ocrStore.fileOf(key)
		return file.isFile && file.length() == OcrModels.spec(key).bytes
	}

	private fun buildPacks(mode: OcrMode, tr: Map<String, PackTransient>): List<PackUiState> {
		return ocrStore.packs.map { pack ->
			val keys = buildList {
				add(OcrModels.KEY_DET)
				if (mode == OcrMode.ACCURATE) {
					add(OcrModels.KEY_BUBBLE)
				}
				add(pack.recKey)
				add(pack.dictKey)
			}
			val state = tr[pack.id]
			PackUiState(
				pack = pack,
				isInstalled = ocrStore.isPackInstalled(pack),
				isReady = ocrStore.isInstalled(pack, mode),
				sizeOnDisk = ocrStore.sizeOnDisk(pack),
				downloadBytes = keys.filterNot { hasModel(it) }.sumOf { OcrModels.spec(it).bytes },
				isDownloading = state != null && state.error == null,
				progress = state?.progress,
				error = state?.error,
			)
		}
	}
}
