package de.wanderlogplus.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Euros per Yen for [withEuro] on screens; null until the app knows a rate (then texts stay as they are). */
val LocalYenRate = compositionLocalOf<Double?> { null }

private const val MARK = """(?:¥|￥|JPY(?![A-Za-z]))"""
private const val SUFFIX = """(?:円|Yen(?![A-Za-z])|JPY(?![A-Za-z]))"""
private const val AMOUNT = """(?:\d{1,3}(?:[.,]\d{3})+|\d+)(?:[.,]\d+)?万?"""
private val YEN = Regex(
    """(?<![\d.,])($MARK\s?)?($AMOUNT)(\s?$SUFFIX)?(?:\s?[-–~～〜]\s?($MARK\s?)?($AMOUNT)(\s?$SUFFIX)?)?""",
)
private val GROUPED = Regex("""\d{1,3}(?:[.,]\d{3})+""")
private val ALREADY = Regex("""^\s*\(≈""")

/** "1,200" / "3.000" / "1,5万" in Yen; null when it is no number. */
private fun yenValue(amount: String): Double? {
    if (amount.endsWith("万")) return amount.dropLast(1).replace(',', '.').toDoubleOrNull()?.times(10_000)
    return if (GROUPED.matches(amount)) amount.replace(Regex("[.,]"), "").toDoubleOrNull() else amount.replace(',', '.').toDoubleOrNull()
}

/** "0,9", "7", "1.500" – one decimal below 1 €, whole euros above. */
private fun euroText(v: Double): String =
    if (v < 0.95) String.format(java.util.Locale.GERMAN, "%.1f", v).removeSuffix(",0")
    else String.format(java.util.Locale.GERMAN, "%,d", Math.round(v))

/**
 * [text] with "(≈ X €)" after every Yen amount ("¥1,200", "￥1,000～￥1,999", "1500円", "3.000 Yen", "JPY 10000",
 * "2万円") at [eurPerYen]; a range gets one range ("≈ 6–12 €"). Numbers without a Yen sign stay alone, an amount that
 * already has "(≈ …)" is not converted twice, and without a rate the text is returned unchanged.
 */
fun withEuro(text: String, eurPerYen: Double?): String {
    if (eurPerYen == null || eurPerYen <= 0 || text.isEmpty()) return text
    return YEN.replace(text) { m ->
        val g = m.groupValues
        val marked = listOf(g[1], g[3], g[4], g[6]).any { it.isNotBlank() }
        val after = text.substring(m.range.last + 1)
        if (!marked || ALREADY.containsMatchIn(after)) return@replace m.value
        val low = yenValue(g[2]) ?: return@replace m.value
        val high = g[5].takeIf { it.isNotEmpty() }?.let(::yenValue)
        val a = euroText(low * eurPerYen)
        val euro = if (high == null) a else euroText(high * eurPerYen).let { b -> if (a == b) a else "$a–$b" }
        m.value + " (≈ $euro €)"
    }
}

/**
 * The Yen rate for [LocalYenRate]: the last one stored on the device right away, fetched with [load] (Euros per Yen)
 * at most once a day; null until any is known.
 */
@Composable
fun rememberYenRate(load: (suspend () -> Double?)?): Double? {
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("yen_rate", android.content.Context.MODE_PRIVATE)
    var rate by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(prefs.getString("rate", null)?.toDoubleOrNull()) }
    androidx.compose.runtime.LaunchedEffect(load != null) {
        val today = java.time.LocalDate.now().toString()
        if (load == null || (rate != null && prefs.getString("day", null) == today)) return@LaunchedEffect
        val fresh = try { load() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        if (fresh != null && fresh > 0) {
            rate = fresh
            prefs.edit().putString("rate", fresh.toString()).putString("day", today).apply()
        }
    }
    return rate
}

/** [text] with Euro amounts after Yen prices, at the rate the app knows ([LocalYenRate]). */
@Composable
fun euroed(text: String): String = withEuro(text, LocalYenRate.current)
