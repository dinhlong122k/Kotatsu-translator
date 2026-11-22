package org.koitharu.kotatsu.settings.translator

import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.annotation.StringRes
import androidx.fragment.app.viewModels
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.settings.utils.EditTextBindListener
import org.koitharu.kotatsu.settings.utils.SliderPreference
import org.koitharu.kotatsu.translator.ai.AiHttp
import org.koitharu.kotatsu.translator.ai.ModelPresets
import org.koitharu.kotatsu.translator.data.TranslatorSettings
import org.koitharu.kotatsu.translator.domain.model.AiProvider
import org.koitharu.kotatsu.translator.domain.model.ModelIntegrityException
import org.koitharu.kotatsu.translator.domain.model.OcrMode
import org.koitharu.kotatsu.translator.ocr.OcrModels
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class TranslatorSettingsFragment : BasePreferenceFragment(R.string.ai_translator) {

	private val viewModel by viewModels<TranslatorSettingsViewModel>()

	@Inject
	lateinit var translatorSettings: TranslatorSettings

	private var testJob: Job? = null

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_translator)
		initLanguagePickers()
		initProviderPicker()
		initApiKey()
		initBaseUrl()
		initModelPickers()
		initOcrMode()
		initSliders()
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.provider.observe(viewLifecycleOwner) { onProviderChanged() }
		viewModel.hasKey.observe(viewLifecycleOwner) { updateApiKeyPreference() }
		viewModel.models.observe(viewLifecycleOwner) { updateModelPreferences() }
		viewModel.packs.observe(viewLifecycleOwner) { bindPacks(it) }
		viewModel.missingPackLanguage.observe(viewLifecycleOwner) { bindPackWarning(it) }
		viewModel.autoSources.observe(viewLifecycleOwner) { bindAutoSources(it) }
		viewModel.onKeySaved.observeEvent(viewLifecycleOwner) { isStored ->
			if (!isStored) {
				showMessage(getString(R.string.translator_settings_api_key_save_failed))
			}
		}
		viewModel.onContextCleared.observeEvent(viewLifecycleOwner) {
			showMessage(getString(R.string.translator_context_cleared))
		}
		viewModel.onError.observeEvent(viewLifecycleOwner) {
			showMessage(it.getDisplayMessage(resources))
		}
	}

	override fun onDestroyView() {
		testJob?.cancel()
		testJob = null
		super.onDestroyView()
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean {
		val key = preference.key.orEmpty()
		return when {
			key == KEY_TEST -> {
				testConnection()
				true
			}

			key == KEY_MODEL_REFRESH -> {
				viewModel.loadModels(useCache = false)
				true
			}

			key == KEY_AUTO_SOURCES -> {
				showAutoSourcesDialog()
				true
			}

			key == KEY_CONTEXT_CLEAR -> {
				confirmClearContext()
				true
			}

			key == KEY_PACK_WARNING -> {
				scrollToPreference(KEY_PACKS)
				true
			}

			key.startsWith(KEY_PACK_PREFIX) -> {
				onPackClick(key.removePrefix(KEY_PACK_PREFIX))
				true
			}

			else -> super.onPreferenceTreeClick(preference)
		}
	}

	private fun initLanguagePickers() {
		findPreference<ListPreference>(AppSettings.KEY_TRANSLATOR_SOURCE_LANG)?.apply {
			val codes = listOf(AppSettings.TRANSLATOR_SOURCE_LANG_AUTO) + OcrModels.sourceLangs
			entryValues = codes.toTypedArray()
			entries = codes.map {
				if (it == AppSettings.TRANSLATOR_SOURCE_LANG_AUTO) {
					getString(R.string.translator_source_language_auto)
				} else {
					languageName(it)
				}
			}.toTypedArray()
		}
		findPreference<ListPreference>(AppSettings.KEY_TRANSLATOR_TARGET_LANG)?.apply {
			val codes = TARGET_LANGUAGES.toMutableList()
			val current = translatorSettings.targetLanguage
			if (current !in codes) {
				codes.add(current)
			}
			entryValues = codes.toTypedArray()
			entries = codes.map { languageName(it) }.toTypedArray()
			value = current
		}
	}

	private fun initProviderPicker() {
		findPreference<ListPreference>(AppSettings.KEY_TRANSLATOR_PROVIDER)?.apply {
			entryValues = AiProvider.entries.map { it.name }.toTypedArray()
			entries = AiProvider.entries.map { getString(it.titleResId()) }.toTypedArray()
			value = translatorSettings.provider.name
		}
	}

	private fun initApiKey() {
		findPreference<EditTextPreference>(KEY_API_KEY)?.apply {
			@Suppress("UsePropertyAccessSyntax")
			setOnBindEditTextListener(
				EditTextBindListener(
					inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD,
					hint = null,
					validator = null,
				),
			)
			// The key is never kept in the preference: it goes straight to the encrypted storage
			onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
				viewModel.saveApiKey(translatorSettings.provider, newValue as? String)
				false
			}
		}
	}

	private fun initBaseUrl() {
		findPreference<EditTextPreference>(AppSettings.KEY_TRANSLATOR_OPENAI_BASE_URL)?.apply {
			@Suppress("UsePropertyAccessSyntax")
			setOnBindEditTextListener(
				EditTextBindListener(
					inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_URI,
					hint = TranslatorSettings.BASE_URL_OPENAI,
					validator = HttpUrlValidator(),
				),
			)
			summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
				pref.text?.trim()?.takeIf { it.isNotEmpty() }
					?: getString(R.string.translator_settings_base_url_default, TranslatorSettings.BASE_URL_OPENAI)
			}
			onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
				val value = (newValue as? String)?.trim().orEmpty()
				if (value.isNotEmpty() && !HttpUrlValidator.isValid(value)) {
					showMessage(getString(R.string.translator_settings_base_url_invalid))
					false
				} else {
					true
				}
			}
		}
	}

	private fun initModelPickers() {
		findPreference<ListPreference>(KEY_MODEL)?.onPreferenceChangeListener =
			Preference.OnPreferenceChangeListener { _, newValue ->
				translatorSettings.setModel(translatorSettings.provider, newValue as? String)
				updateModelPreferences()
				true
			}
		findPreference<EditTextPreference>(KEY_MODEL_MANUAL)?.apply {
			onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
				val name = (newValue as? String)?.trim()?.takeIf { it.isNotEmpty() }
				translatorSettings.setModel(translatorSettings.provider, name)
				updateModelPreferences()
				false
			}
		}
	}

	private fun initOcrMode() {
		findPreference<ListPreference>(AppSettings.KEY_TRANSLATOR_OCR_MODE)?.apply {
			entryValues = OcrMode.entries.map { it.name }.toTypedArray()
			entries = OcrMode.entries.map {
				getString(
					when (it) {
						OcrMode.FAST -> R.string.translator_ocr_mode_fast
						OcrMode.ACCURATE -> R.string.translator_ocr_mode_accurate
					},
				)
			}.toTypedArray()
			value = translatorSettings.ocrMode.name
		}
	}

	private fun initSliders() {
		findPreference<SliderPreference>(AppSettings.KEY_TRANSLATOR_RPM)?.apply {
			summaryProvider = Preference.SummaryProvider<SliderPreference> { pref ->
				val description = getString(R.string.translator_requests_per_minute_summary)
				if (pref.value == 0) {
					"${getString(R.string.translator_settings_unlimited)}. $description"
				} else {
					"${pref.value}. $description"
				}
			}
		}
		findPreference<SliderPreference>(AppSettings.KEY_TRANSLATOR_FONT_SCALE)?.apply {
			summaryProvider = Preference.SummaryProvider<SliderPreference> { pref ->
				"${pref.value}%. ${getString(R.string.translator_font_scale_summary)}"
			}
		}
		findPreference<SliderPreference>(AppSettings.KEY_TRANSLATOR_CONTEXT_DAYS)?.apply {
			summaryProvider = Preference.SummaryProvider<SliderPreference> { pref ->
				"${getString(R.string.translator_days_pattern, pref.value)}. " +
					getString(R.string.translator_context_days_summary)
			}
		}
	}

	private fun onProviderChanged() {
		val provider = translatorSettings.provider
		findPreference<Preference>(AppSettings.KEY_TRANSLATOR_OPENAI_BASE_URL)?.isVisible = provider == AiProvider.OPENAI
		updateApiKeyPreference()
		updateModelPreferences()
	}

	private fun updateApiKeyPreference() {
		val pref = findPreference<EditTextPreference>(KEY_API_KEY) ?: return
		val provider = translatorSettings.provider
		pref.title = getString(R.string.translator_api_key) + " · " + getString(provider.titleResId())
		// Only the fact that a key exists is shown, never the key itself
		pref.summary = when (viewModel.hasKey.value) {
			true -> getString(R.string.translator_settings_api_key_set)
			false -> getString(
				if (provider == AiProvider.OPENAI) {
					R.string.translator_settings_api_key_optional
				} else {
					R.string.translator_api_key_not_set
				},
			)

			null -> null
		}
	}

	private fun updateModelPreferences() {
		val provider = translatorSettings.provider
		val state = viewModel.models.value.takeIf { it.provider == provider }
		val current = translatorSettings.modelFor(provider)
		// loaded models first, then presets that were not loaded, then the selected one if it is unknown
		val labels = LinkedHashMap<String, String>()
		state?.models?.forEach { labels[it.id] = it.displayName?.takeIf { name -> name.isNotBlank() } ?: it.id }
		ModelPresets.presets(provider).forEach { labels.putIfAbsent(it.id, it.label) }
		labels.putIfAbsent(current, current)
		findPreference<ListPreference>(KEY_MODEL)?.apply {
			entryValues = labels.keys.toTypedArray()
			entries = labels.values.toTypedArray()
			value = current
			summary = labels[current] ?: current
		}
		findPreference<EditTextPreference>(KEY_MODEL_MANUAL)?.text = translatorSettings.modelOrNull(provider)
		findPreference<Preference>(KEY_MODEL_REFRESH)?.apply {
			isEnabled = state?.isLoading != true
			val error = state?.error
			val loaded = state?.models
			summary = when {
				state == null -> getString(R.string.translator_model_refresh_summary)
				state.isLoading -> getString(R.string.translator_settings_model_loading)
				error != null -> getString(
					R.string.translator_settings_model_load_failed,
					error.toTranslatorMessage(resources, model = null, hasKey = viewModel.hasKey.value == true),
				)

				state.needsKey -> getString(R.string.translator_settings_model_need_key)
				loaded != null -> getString(R.string.translator_settings_model_loaded, loaded.size)
				else -> getString(R.string.translator_model_refresh_summary)
			}
		}
	}

	private fun testConnection() {
		testJob?.cancel()
		testJob = viewLifecycleScope.launch {
			val pref = findPreference<Preference>(KEY_TEST)
			pref?.run {
				setSummary(R.string.loading_)
				isEnabled = false
			}
			try {
				val model = translatorSettings.modelFor(translatorSettings.provider)
				val error = viewModel.testConnection()
				val message = if (error == null) {
					getString(R.string.translator_settings_test_ok, model)
				} else {
					error.toTranslatorMessage(resources, model, hasKey = viewModel.hasKey.value == true)
				}
				MaterialAlertDialogBuilder(requireContext())
					.setTitle(R.string.translator_test_connection)
					.setMessage(message)
					.setPositiveButton(android.R.string.ok, null)
					.setCancelable(true)
					.show()
			} finally {
				pref?.run {
					isEnabled = true
					setSummary(R.string.translator_test_connection_summary)
				}
			}
		}
	}

	private fun bindPackWarning(language: String?) {
		findPreference<Preference>(KEY_PACK_WARNING)?.apply {
			isVisible = language != null
			if (language != null) {
				icon = getWarningIcon()
				summary = getString(R.string.translator_settings_pack_missing_summary, languageName(language))
			}
		}
	}

	private fun bindAutoSources(sources: Set<String>) {
		findPreference<Preference>(KEY_AUTO_SOURCES)?.apply {
			isEnabled = sources.isNotEmpty()
			summary = if (sources.isEmpty()) {
				getString(R.string.translator_settings_auto_sources_none)
			} else {
				getString(R.string.translator_settings_auto_sources_count, sources.size)
			}
		}
	}

	private fun showAutoSourcesDialog() {
		val names = viewModel.autoSources.value.sorted()
		if (names.isEmpty()) {
			return
		}
		val context = requireContext()
		val titles = names.map { MangaSource(it).getTitle(context) }.toTypedArray()
		val checked = BooleanArray(names.size) { true }
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.translator_auto_sources)
			.setMultiChoiceItems(titles, checked) { _, which, isChecked -> checked[which] = isChecked }
			.setPositiveButton(android.R.string.ok) { _, _ ->
				viewModel.removeAutoSources(names.filterIndexed { i, _ -> !checked[i] })
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun confirmClearContext() {
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.translator_context_clear)
			.setMessage(R.string.translator_settings_context_clear_confirm)
			.setPositiveButton(R.string.delete) { _, _ -> viewModel.clearContext() }
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun bindPacks(packs: List<PackUiState>) {
		val category = findPreference<PreferenceCategory>(KEY_PACKS) ?: return
		val context = requireContext()
		for (state in packs) {
			val key = KEY_PACK_PREFIX + state.pack.id
			val pref = category.findPreference<Preference>(key) ?: Preference(context).also {
				it.key = key
				it.isPersistent = false
				category.addPreference(it)
			}
			pref.title = state.pack.langs.joinToString { languageName(it) }
			pref.summary = packSummary(state)
		}
	}

	private fun packSummary(state: PackUiState): String {
		val context = requireContext()
		fun size(bytes: Long) = Formatter.formatFileSize(context, bytes)
		return when {
			state.error != null -> getString(R.string.translator_settings_pack_failed, packErrorMessage(state.error))
			state.isDownloading -> {
				val progress = state.progress
				if (progress == null) {
					getString(R.string.translator_settings_pack_queued)
				} else {
					getString(
						R.string.translator_settings_pack_downloading,
						(progress.fraction * 100f).toInt(),
						size(progress.loadedBytes),
						size(progress.totalBytes),
					)
				}
			}

			state.isReady -> getString(R.string.translator_settings_pack_installed, size(state.sizeOnDisk))
			state.isInstalled -> getString(
				R.string.translator_settings_pack_needs_extra,
				size(state.sizeOnDisk),
				size(state.downloadBytes),
			)

			else -> getString(R.string.translator_settings_pack_not_installed, size(state.downloadBytes))
		}
	}

	private fun packErrorMessage(error: Throwable): String = if (error is ModelIntegrityException) {
		getString(R.string.translator_error_model_corrupted)
	} else {
		AiHttp.clean(error.message.orEmpty(), emptyList(), 200).ifEmpty { getString(R.string.error_occurred) }
	}

	private fun onPackClick(packId: String) {
		val state = viewModel.packs.value.find { it.pack.id == packId } ?: return
		when {
			state.isDownloading -> viewModel.cancelDownload(state.pack)
			state.error != null -> viewModel.downloadPack(state.pack)
			state.isReady -> confirmDeletePack(state)
			state.isInstalled -> MaterialAlertDialogBuilder(requireContext())
				.setTitle(packTitle(state))
				.setItems(arrayOf(getString(R.string.download), getString(R.string.delete))) { _, which ->
					if (which == 0) {
						viewModel.downloadPack(state.pack)
					} else {
						viewModel.deletePack(state.pack)
					}
				}
				.show()

			else -> viewModel.downloadPack(state.pack)
		}
	}

	private fun confirmDeletePack(state: PackUiState) {
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.translator_settings_pack_delete_title)
			.setMessage(getString(R.string.translator_settings_pack_delete_message, packTitle(state)))
			.setPositiveButton(R.string.delete) { _, _ -> viewModel.deletePack(state.pack) }
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun packTitle(state: PackUiState): String = state.pack.langs.joinToString { languageName(it) }

	private fun showMessage(message: CharSequence) {
		val anchor = view ?: return
		Snackbar.make(anchor, message, Snackbar.LENGTH_SHORT).show()
	}

	private fun languageName(code: String): String {
		val locale = Locale.forLanguageTag(code)
		val displayLocale = Locale.getDefault()
		return locale.getDisplayLanguage(displayLocale).replaceFirstChar { it.titlecase(displayLocale) }
			.ifEmpty { code }
	}

	@StringRes
	private fun AiProvider.titleResId(): Int = when (this) {
		AiProvider.GEMINI -> R.string.translator_provider_gemini
		AiProvider.OPENAI -> R.string.translator_provider_openai
		AiProvider.CLAUDE -> R.string.translator_provider_claude
	}

	private companion object {

		const val KEY_API_KEY = "translator_api_key"
		const val KEY_MODEL = "translator_model"
		const val KEY_MODEL_REFRESH = "translator_model_refresh"
		const val KEY_MODEL_MANUAL = "translator_model_manual"
		const val KEY_TEST = "translator_test"
		const val KEY_AUTO_SOURCES = "translator_auto_sources"
		const val KEY_CONTEXT_CLEAR = "translator_context_clear"
		const val KEY_PACK_WARNING = "translator_pack_warning"
		const val KEY_PACKS = "translator_ocr_packs"
		const val KEY_PACK_PREFIX = "translator_pack_"

		val TARGET_LANGUAGES = listOf("vi", "en", "ja", "ko", "zh", "fr", "es", "de", "id", "th", "ru")
	}
}
