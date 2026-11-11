package de.wanderlogplus.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

data class MdSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false, val link: String? = null)

sealed interface MdBlock {
    val spans: List<MdSpan>
    data class Paragraph(override val spans: List<MdSpan>) : MdBlock
    data class Bullet(override val spans: List<MdSpan>) : MdBlock
    data class Numbered(val n: Int, override val spans: List<MdSpan>) : MdBlock
}

private val BULLET = Regex("""^\s*[-*]\s+(.*)$""")
private val NUMBERED = Regex("""^\s*(\d+)\.\s+(.*)$""")

/** Small Markdown subset: paragraphs, "- " bullets, "1. " lists, **bold**, *italic*, [text](url). Single newlines are kept. */
fun parseMarkdown(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val para = mutableListOf<String>()
    fun flush() {
        if (para.isNotEmpty()) blocks += MdBlock.Paragraph(parseSpans(para.joinToString("\n")))
        para.clear()
    }
    for (line in text.replace("\r\n", "\n").split('\n')) {
        val bullet = BULLET.matchEntire(line)
        val numbered = NUMBERED.matchEntire(line)
        when {
            line.isBlank() -> flush()
            bullet != null -> { flush(); blocks += MdBlock.Bullet(parseSpans(bullet.groupValues[1])) }
            numbered != null -> {
                flush()
                blocks += MdBlock.Numbered(numbered.groupValues[1].toIntOrNull() ?: 1, parseSpans(numbered.groupValues[2]))
            }
            else -> para += line.trimEnd()
        }
    }
    flush()
    return blocks
}

private fun parseSpans(s: String): List<MdSpan> {
    val out = mutableListOf<MdSpan>()
    val plain = StringBuilder()
    fun flushPlain() {
        if (plain.isNotEmpty()) out += MdSpan(plain.toString())
        plain.clear()
    }
    var i = 0
    while (i < s.length) {
        if (s.startsWith("**", i)) {
            val end = s.indexOf("**", i + 2)
            if (end > i + 2) {
                flushPlain(); out += MdSpan(s.substring(i + 2, end), bold = true); i = end + 2; continue
            }
        } else if (s[i] == '*') {
            val end = s.indexOf('*', i + 1)
            if (end > i + 1) {
                flushPlain(); out += MdSpan(s.substring(i + 1, end), italic = true); i = end + 1; continue
            }
        } else if (s[i] == '[') {
            val mid = s.indexOf("](", i + 1)
            val end = if (mid > 0) s.indexOf(')', mid + 2) else -1
            if (end > mid && mid > i + 1) {
                flushPlain()
                out += MdSpan(s.substring(i + 1, mid), link = s.substring(mid + 2, end)); i = end + 1; continue
            }
        }
        plain.append(s[i]); i++
    }
    flushPlain()
    return out
}

/** Only web links are made tappable; anything else (javascript:, intent:, file: ...) stays plain text. */
fun isSafeUrl(url: String): Boolean {
    val u = url.trim().lowercase()
    return u.startsWith("http://") || u.startsWith("https://")
}

private fun annotated(prefix: String, spans: List<MdSpan>, linkStyle: TextLinkStyles): AnnotatedString = buildAnnotatedString {
    append(prefix)
    for (sp in spans) {
        when {
            sp.link != null && isSafeUrl(sp.link) -> withLink(LinkAnnotation.Url(sp.link, linkStyle)) { append(sp.text) }
            sp.bold || sp.italic -> withStyle(
                SpanStyle(
                    fontWeight = if (sp.bold) FontWeight.Bold else null,
                    fontStyle = if (sp.italic) FontStyle.Italic else null,
                ),
            ) { append(sp.text) }
            else -> append(sp.text)
        }
    }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val linkStyle = TextLinkStyles(
        SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        parseMarkdown(euroed(text)).forEach { block ->
            val prefix = when (block) {
                is MdBlock.Paragraph -> ""
                is MdBlock.Bullet -> "• "
                is MdBlock.Numbered -> "${block.n}. "
            }
            Text(annotated(prefix, block.spans, linkStyle), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
