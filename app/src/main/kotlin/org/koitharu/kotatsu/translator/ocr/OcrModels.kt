package org.koitharu.kotatsu.translator.ocr

enum class OcrModelKind(val extension: String) {

	/** ONNX network */
	ONNX("onnx"),

	/** One character per line */
	DICT_TXT("txt"),

	/** JSON array of strings */
	DICT_JSON("json"),
}

/**
 * A downloadable model file. [url] is pinned to a commit, [sha256] is lowercase hex.
 */
data class OcrModelSpec(
	val key: String,
	val kind: OcrModelKind,
	val url: String,
	val sha256: String,
	val bytes: Long,
)

/**
 * A language pack: a recognition model plus its dictionary. [langs] are ISO 639-1 source languages.
 * [vertical]: the pack may contain vertical text (ja, zh), tall line crops are rotated before recognition.
 */
data class OcrPack(
	val id: String,
	val label: String,
	val langs: List<String>,
	val recKey: String,
	val dictKey: String,
	val vertical: Boolean,
)

/**
 * Model and language pack tables. Port of `src/ocr/models.ts`: values are copied verbatim.
 */
object OcrModels {

	/** Text-line detector, shared by all languages. */
	const val KEY_DET = "det"

	/** Bubble detector, only needed by [org.koitharu.kotatsu.translator.domain.model.OcrMode.ACCURATE]. */
	const val KEY_BUBBLE = "bubble"

	const val DEFAULT_SOURCE_LANG = "ko"

	private const val HF_MONKT =
		"https://huggingface.co/monkt/paddleocr-onnx/resolve/7b02d0a30a07ba2b92ad1ff5a8941ae2c633de65/languages"
	private const val HF_V5 =
		"https://huggingface.co/nakamura196/ppocrv5-mobile-web-onnx/resolve/dc7e2271c00d961a4c0a38dcebe23beb735ba33e"

	private fun monkt(
		id: String,
		dir: String,
		recBytes: Long,
		recSha: String,
		dictBytes: Long,
		dictSha: String,
	) = listOf(
		OcrModelSpec("rec-$id", OcrModelKind.ONNX, "$HF_MONKT/$dir/rec.onnx", recSha, recBytes),
		OcrModelSpec("dict-$id", OcrModelKind.DICT_TXT, "$HF_MONKT/$dir/dict.txt", dictSha, dictBytes),
	)

	private val list: List<OcrModelSpec> = buildList {
		add(
			OcrModelSpec(
				key = KEY_BUBBLE,
				kind = OcrModelKind.ONNX,
				url = "https://huggingface.co/ogkalu/comic-text-and-bubble-detector/resolve/" +
					"16e8a622f91fabc6b5b65c96d32d1183f8843546/detector-v4-s_int8.onnx",
				sha256 = "5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79",
				bytes = 11120765L,
			),
		)
		add(
			OcrModelSpec(
				key = KEY_DET,
				kind = OcrModelKind.ONNX,
				url = "$HF_V5/PP-OCRv5_mobile_det.onnx",
				sha256 = "6bdf743af375b13fbbeeb47a91a40063af5bfb7a7f197f6df8cb990dc827e5d6",
				bytes = 4766440L,
			),
		)
		addAll(
			monkt(
				"ko", "korean",
				13401252L, "322f140154c820fcb83c3d24cfe42c9ec70dd1a1834163306a7338136e4f1eaa",
				47451L, "a88071c68c01707489baa79ebe0405b7beb5cca229f4fc94cc3ef992328802d7",
			),
		)
		add(
			OcrModelSpec(
				key = "rec-cjk",
				kind = OcrModelKind.ONNX,
				url = "$HF_V5/PP-OCRv5_mobile_rec.onnx",
				sha256 = "1205c2e14cc3ecaf7a57fe14736521ded32489603c40d98e36fe4212a70d8be5",
				bytes = 16537064L,
			),
		)
		add(
			OcrModelSpec(
				key = "dict-cjk",
				kind = OcrModelKind.DICT_JSON,
				url = "$HF_V5/PP-OCRv5_mobile_rec.dict.json",
				sha256 = "02c0a0121c577dfa211f11de21084e3abd23478a77ea4fbdf71ee7a3e990a717",
				bytes = 110781L,
			),
		)
		addAll(
			monkt(
				"en", "english",
				7830888L, "4e16deb22c4da6468bdca539b2cd3c8687825538b67109177c47d359ab994cd7",
				1416L, "e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6",
			),
		)
		addAll(
			monkt(
				"latin", "latin",
				7862832L, "614ffc2d6d3902d360fad7f1b0dd455ee45e877069d14c4e51a99dc4ef144409",
				1634L, "3c0a8a79b612653c25f765271714f71281e4e955962c153e272b7b8c1d2b13ff",
			),
		)
		addAll(
			monkt(
				"cyrillic", "eslav",
				7870092L, "dc6bf0e855247decce214ba6dae5bc135fa0ad725a5918a7fcfb59fad6c9cdee",
				1663L, "3e95f1581557162870cacdba5af91a4c6be2890710d395b0c3c7578e7ee5e6eb",
			),
		)
		addAll(
			monkt(
				"th", "thai",
				7873480L, "2b6e56b1872200349e227574c25aeb0e0f9af9b8356e9ff5f75ac543a535669a",
				1767L, "57f5406f94bb6688fb7077f7be65f08bbd71cecf48c01ea26c522cb5c4836b7a",
			),
		)
		addAll(
			monkt(
				"el", "greek",
				7791200L, "13373f736dbb229e96945fc41c2573403d91503b0775c7b7294839e0c5f3a7a3",
				1103L, "31defc62c0c3ad3674a82da6192226a2ba98ef4ff014a7045cb88d59f9c3de31",
			),
		)
	}

	val specs: Map<String, OcrModelSpec> = list.associateBy { it.key }

	val packs: List<OcrPack> = listOf(
		OcrPack("ko", "Tiếng Hàn", listOf("ko"), "rec-ko", "dict-ko", vertical = false),
		OcrPack(
			"cjk", "Tiếng Nhật / Trung (giản thể, phồn thể)", listOf("ja", "zh"),
			"rec-cjk", "dict-cjk", vertical = true,
		),
		OcrPack("en", "Tiếng Anh", listOf("en"), "rec-en", "dict-en", vertical = false),
		OcrPack(
			"latin", "Ngôn ngữ Latin (Pháp, Tây Ban Nha, Đức, Ý, Bồ Đào Nha, Indonesia…)",
			listOf("fr", "es", "de", "it", "pt", "id"), "rec-latin", "dict-latin", vertical = false,
		),
		OcrPack(
			"cyrillic", "Tiếng Nga / Ukraina / Bulgaria", listOf("ru", "uk", "bg"),
			"rec-cyrillic", "dict-cyrillic", vertical = false,
		),
		OcrPack("th", "Tiếng Thái", listOf("th"), "rec-th", "dict-th", vertical = false),
		OcrPack("el", "Tiếng Hy Lạp", listOf("el"), "rec-el", "dict-el", vertical = false),
	)

	/** Supported source languages, in menu order. */
	val sourceLangs: List<String> = packs.flatMap { it.langs }

	fun spec(key: String): OcrModelSpec = specs[key] ?: throw IllegalArgumentException("Unknown OCR model $key")

	fun isSourceLang(lang: String?): Boolean = lang != null && lang in sourceLangs

	fun packById(id: String): OcrPack? = packs.find { it.id == id }

	/** The pack dedicated to a source language. */
	fun packFor(lang: String): OcrPack? = packs.find { lang in it.langs }

	/** Packs able to read [lang], best first (the CJK pack also reads English, at a larger download). */
	fun packCandidates(lang: String): List<OcrPack> {
		val result = ArrayList<OcrPack>(2)
		packFor(lang)?.let { result += it }
		if (lang == "en") {
			packById("cjk")?.let { result += it }
		}
		return result
	}

	/** Sum of the rec + dict sizes of the pack. */
	fun packBytes(pack: OcrPack): Long = spec(pack.recKey).bytes + spec(pack.dictKey).bytes
}
