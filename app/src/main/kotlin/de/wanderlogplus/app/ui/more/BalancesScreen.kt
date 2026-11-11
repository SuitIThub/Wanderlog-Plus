package de.wanderlogplus.app.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.design.Badge
import de.wanderlogplus.app.ui.design.BadgeSpec
import de.wanderlogplus.app.ui.design.BadgeTone
import de.wanderlogplus.app.ui.design.SurfaceCard
import de.wanderlogplus.shared.sync.BalanceReport
import de.wanderlogplus.shared.sync.BalanceStatus
import de.wanderlogplus.shared.sync.ServiceBalance
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

const val BALANCES_ROUTE = "balances"

/** Share used from which a card is marked. */
private const val WARN_SHARE = 0.8

/** Days before a key or plan runs out from which a card is marked. */
private const val WARN_DAYS = 14L

private fun dateOf(iso: String?): LocalDate? = iso?.let { runCatching { Instant.parse(it).atZone(ZoneId.of("Europe/Berlin")).toLocalDate() }.getOrNull() }

/** Why a service needs attention ("84 % verbraucht", "Key läuft in 10 Tagen ab"); null when all is fine. */
fun balanceWarning(b: ServiceBalance, today: LocalDate): String? {
    dateOf(b.expiresAt)?.let { end ->
        val days = ChronoUnit.DAYS.between(today, end)
        if (days < 0) return "Key ist abgelaufen"
        if (days <= WARN_DAYS) return "Key läuft in $days " + (if (days == 1L) "Tag" else "Tagen") + " ab"
    }
    val used = b.used
    val limit = b.limit
    if (b.status == BalanceStatus.OK && used != null && limit != null && limit > 0 && used / limit >= WARN_SHARE) {
        return "${(used / limit * 100).toInt()} % verbraucht"
    }
    return null
}

private fun usd(v: Double) = String.format(Locale.GERMAN, "%.2f $", v)
private fun count(v: Double) = String.format(Locale.GERMAN, "%,d", v.toLong())

/** The main figure of a card; null when there is none. */
fun amountText(b: ServiceBalance): String? = when {
    b.unit == "USD" && b.remaining != null && b.limit != null -> "${usd(b.remaining!!)} übrig von ${usd(b.limit!!)}"
    b.unit == "CALLS" && b.remaining != null && b.limit != null -> "${count(b.remaining!!)} von ${count(b.limit!!)} Abfragen übrig"
    b.unit == "CALLS" && b.used != null -> "${count(b.used!!)} Aufrufe diesen Monat"
    else -> null
}

private val DATE = DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMAN)

/**
 * Mehr → "Guthaben & Limits": credit and quotas of the services the server pays for. [load] asks the server
 * (fresh = past its five-minute cache) and gives null when there is no server or it did not answer.
 */
@Composable
fun BalancesScreen(load: suspend (fresh: Boolean) -> BalanceReport?, onBack: () -> Unit, modifier: Modifier = Modifier, today: LocalDate = LocalDate.now()) {
    var report by remember { mutableStateOf<BalanceReport?>(null) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        loading = true
        val got = try { load(round > 0) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (got != null) report = got
        failed = got == null
        loading = false
    }
    Column(modifier.fillMaxSize().testTag("balances")) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
            Text("Guthaben & Limits", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { round++ }, enabled = !loading, modifier = Modifier.testTag("balances-refresh")) { Icon(Icons.Filled.Refresh, contentDescription = "Aktualisieren") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (failed && report == null) Text("Server nicht erreichbar – die Guthaben fragt der Server ab.", color = MaterialTheme.colorScheme.error)
            report?.services?.forEach { BalanceCard(it, today) }
            report?.let { Text("Stand " + java.text.SimpleDateFormat("HH:mm", Locale.GERMAN).format(java.util.Date(it.fetchedAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun BalanceCard(b: ServiceBalance, today: LocalDate) {
    val uri = LocalUriHandler.current
    val warning = balanceWarning(b, today)
    SurfaceCard(Modifier.fillMaxWidth().testTag("balance-${b.service}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                when (b.status) {
                    BalanceStatus.NOT_CONFIGURED -> Badge(BadgeSpec("nicht eingerichtet", BadgeTone.Neutral))
                    BalanceStatus.UNAVAILABLE -> Badge(BadgeSpec("nicht erreichbar", BadgeTone.Warning))
                    else -> warning?.let { Badge(BadgeSpec(it, BadgeTone.Warning)) }
                }
            }
            amountText(b)?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            val used = b.used
            val limit = b.limit
            if (b.status == BalanceStatus.OK && used != null && limit != null && limit > 0) {
                LinearProgressIndicator(
                    progress = { (used / limit).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (warning != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            b.details.forEach { d ->
                Row {
                    Text(d.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(d.value, style = MaterialTheme.typography.bodySmall)
                }
            }
            dateOf(b.periodEnd)?.let { Text("Zeitraum bis " + DATE.format(it), style = MaterialTheme.typography.bodySmall) }
            dateOf(b.expiresAt)?.let { Text("Key gültig bis " + DATE.format(it), style = MaterialTheme.typography.bodySmall) }
            if (b.note.isNotBlank() && b.status != BalanceStatus.NOT_CONFIGURED) {
                Text(b.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            b.link?.let { link -> TextButton(onClick = { runCatching { uri.openUri(link) } }) { Text("Im Dashboard ansehen") } }
        }
    }
}
