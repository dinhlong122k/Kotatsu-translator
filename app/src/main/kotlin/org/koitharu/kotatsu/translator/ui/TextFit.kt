package org.koitharu.kotatsu.translator.ui

/**
 * Font size search of the overlay (`fitFontSize` of `overlay.ts`), separated from the text measuring so that it
 * can be tested without Android.
 */
object TextFit {

	/**
	 * The largest integer size in [min]..[max] for which [fits] is true (a binary search, [fits] must be monotonic:
	 * if a size fits then every smaller size does too). Returns [min] if even the smallest size does not fit, and
	 * [min] if [max] is below it.
	 */
	inline fun largestFitting(min: Int, max: Int, fits: (Int) -> Boolean): Int {
		if (max <= min) return min
		var lo = min
		var hi = max
		var best = min
		while (lo <= hi) {
			val mid = (lo + hi) ushr 1
			if (fits(mid)) {
				best = mid
				lo = mid + 1
			} else {
				hi = mid - 1
			}
		}
		return best
	}
}
