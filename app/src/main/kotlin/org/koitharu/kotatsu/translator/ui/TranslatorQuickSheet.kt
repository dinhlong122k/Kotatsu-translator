package org.koitharu.kotatsu.translator.ui

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getLocale
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.SheetTranslatorBinding
import org.koitharu.kotatsu.reader.ui.ReaderControlDelegate
import org.koitharu.kotatsu.reader.ui.ReaderViewModel
import org.koitharu.kotatsu.translator.context.ProfileEditError
import org.koitharu.kotatsu.translator.context.ProfileEditResult
import org.koitharu.kotatsu.translator.context.ProfileLimits
import org.koitharu.kotatsu.translator.context.SeriesProfile
import org.koitharu.kotatsu.translator.context.SeriesProfileRepository
import org.koitharu.kotatsu.translator.context.addPinned
import org.koitharu.kotatsu.translator.context.removePinned
import org.koitharu.kotatsu.translator.context.setGenres
import org.koitharu.kotatsu.translator.data.TranslatorSettings
import org.koitharu.kotatsu.translator.domain.TranslationOrchestrator
import org.koitharu.kotatsu.translator.domain.baseProfileFor
import org.koitharu.kotatsu.translator.domain.TranslatorLanguages
import org.koitharu.kotatsu.translator.domain.model.AiProvider
import org.koitharu.kotatsu.translator.domain.model.LanguagePair
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

/**
 * FR-61: quick controls of the translator inside the reader.
 */
@AndroidEntryPoint
class TranslatorQuickSheet : BaseAdaptiveSheet<SheetTranslatorBinding>(), View.OnClickListener {

	private val viewModel by activityViewModels<ReaderViewModel>()

	@Inject
	lateinit var translator: TranslationOrchestrator

	@Inject
	lateinit var settings: TranslatorSettings

	@Inject
	lateinit var profiles: SeriesProfileRepository

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetTranslatorBinding {
		return SheetTranslatorBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetTranslatorBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.textSummary.text = getString(
			R.string.translator_quick_summary,
			providerName(settings.provider),
			settings.modelFor(settings.provider),
			settings.targetLanguage,
		)
		binding.switchActive.setOnCheckedChangeListener { button, isChecked ->
			if (button.isPressed && isChecked != translator.isActive.value) {
				(activity as? ReaderControlDelegate.OnInteractionListener)?.toggleTranslation()
			}
		}
		binding.switchOriginal.setOnCheckedChangeListener { button, isChecked ->
			if (button.isPressed) {
				translator.setShowOriginal(isChecked)
			}
		}
		translator.isActive.observe(viewLifecycleOwner) {
			binding.switchActive.isChecked = it
			binding.switchOriginal.isEnabled = it
		}
		translator.isShowingOriginal.observe(viewLifecycleOwner) {
			binding.switchOriginal.isChecked = it
		}
		binding.buttonNames.setOnClickListener(this)
		binding.buttonGenres.setOnClickListener(this)
		binding.buttonClearContext.setOnClickListener(this)
		binding.buttonSettings.setOnClickListener(this)
	}


	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		viewBinding?.scrollView?.updatePadding(bottom = insets.getInsets(typeMask).bottom)
		return insets.consume(v, typeMask, bottom = true)
	}

	override fun onClick(v: View) {
		val manga = viewModel.getMangaOrNull()
		when (v.id) {
			R.id.button_names -> manga?.let { showNames(it) }
			R.id.button_genres -> manga?.let { showGenres(it) }
			R.id.button_clear_context -> manga?.let { clearContext(it) }
			R.id.button_settings -> {
				router.openTranslatorSettings()
				dismiss()
			}
		}
	}

	private fun providerName(provider: AiProvider): String = getString(
		when (provider) {
			AiProvider.GEMINI -> R.string.translator_provider_gemini
			AiProvider.OPENAI -> R.string.translator_provider_openai
			AiProvider.CLAUDE -> R.string.translator_provider_claude
		},
	)

	private fun pairFor(manga: Manga): LanguagePair? {
		val target = settings.targetLanguage
		val source = TranslatorLanguages.resolveSource(
			override = settings.sourceLanguageOverride,
			sourceLanguage = manga.source.getLocale()?.language,
			target = target,
		) ?: return null
		return LanguagePair(source, target)
	}

	/** Loads the stored profile (or an empty one) and applies [edit]; saves it if the edit is accepted. */
	private fun editProfile(manga: Manga, edit: (SeriesProfile) -> ProfileEditResult) {
		val pair = pairFor(manga) ?: return
		viewLifecycleOwner.lifecycleScope.launch {
			var error: ProfileEditError? = null
			profiles.update(manga.id) { stored ->
				val base = baseProfileFor(stored, pair, System.currentTimeMillis())
				when (val result = edit(base)) {
					is ProfileEditResult.Ok -> result.profile
					is ProfileEditResult.Rejected -> {
						error = result.error
						stored
					}
				}
			}
			error?.let { Toast.makeText(requireContext(), errorMessage(it), Toast.LENGTH_LONG).show() }
		}
	}

	private fun errorMessage(error: ProfileEditError): String = when (error) {
		ProfileEditError.EMPTY -> getString(R.string.translator_profile_error_empty)
		ProfileEditError.TOO_LONG -> getString(R.string.translator_profile_error_too_long, ProfileLimits.PINNED_FIELD)
		ProfileEditError.TOO_MANY -> getString(R.string.translator_profile_error_too_many, ProfileLimits.PINNED)
		ProfileEditError.TOO_LARGE -> getString(R.string.translator_profile_error_too_large)
	}

	private fun showNames(manga: Manga) {
		viewLifecycleOwner.lifecycleScope.launch {
			val pinned = profiles.get(manga.id)?.glossary?.filter { it.pinned }.orEmpty()
			val context = requireContext()
			val builder = MaterialAlertDialogBuilder(context)
				.setTitle(R.string.translator_pinned_names)
				.setNeutralButton(R.string.translator_add_name) { _, _ -> showAddName(manga) }
				.setNegativeButton(android.R.string.cancel, null)
			if (pinned.isEmpty()) {
				builder.setMessage(R.string.translator_pinned_names_empty)
			} else {
				builder.setItems(pinned.map { "${it.src} → ${it.dst}" }.toTypedArray()) { _, which ->
					val entry = pinned[which]
					editProfile(manga) { ProfileEditResult.Ok(removePinned(it, entry.src)) }
				}
			}
			builder.show()
		}
	}

	private fun showAddName(manga: Manga) {
		val context = requireContext()
		val padding = resources.getDimensionPixelSize(R.dimen.margin_normal)
		val source = textInput(context, R.string.translator_name_original)
		val target = textInput(context, R.string.translator_name_translation)
		val layout = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(padding, padding / 2, padding, 0)
			addView(source)
			addView(target)
		}
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.translator_add_name)
			.setView(layout)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				val src = source.text.toString()
				val dst = target.text.toString()
				editProfile(manga) { addPinned(it, src, dst, "name") }
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun textInput(context: Context, hintRes: Int) = EditText(context).apply {
		setHint(hintRes)
		inputType = InputType.TYPE_CLASS_TEXT
		setSingleLine()
	}

	private fun showGenres(manga: Manga) {
		viewLifecycleOwner.lifecycleScope.launch {
			val current = profiles.get(manga.id)?.genres.orEmpty()
			val input = textInput(requireContext(), R.string.translator_genres_hint).apply {
				setText(current.joinToString(", "))
			}
			val padding = resources.getDimensionPixelSize(R.dimen.margin_normal)
			val layout = LinearLayout(requireContext()).apply {
				setPadding(padding, padding / 2, padding, 0)
				addView(input, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
			}
			MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.translator_genres)
				.setView(layout)
				.setPositiveButton(android.R.string.ok) { _, _ ->
					val genres = input.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }
					editProfile(manga) { setGenres(it, genres) }
				}
				.setNegativeButton(android.R.string.cancel, null)
				.show()
		}
	}

	private fun clearContext(manga: Manga) {
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.translator_clear_series_context)
			.setMessage(R.string.translator_clear_series_context_confirm)
			.setPositiveButton(R.string.clear) { _, _ ->
				viewLifecycleOwner.lifecycleScope.launch {
					profiles.clear(manga.id)
					Toast.makeText(requireContext(), R.string.translator_context_cleared, Toast.LENGTH_SHORT).show()
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}
}
