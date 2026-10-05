package com.rusty.museecho.gadget

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** Just enough Markdown for Muse's replies: bold, italics, code, headings, lists, links. */
object Markdown {
    private val IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val LINK = Regex("""\[([^\]]+)]\((https?://[^)\s]+)\)""")
    private val BARE_URL = Regex("""https?://\S+""")
    private val INLINE = Regex("""\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|\*(\S(?:.*?\S)?)\*|_(\S(?:.*?\S)?)_""")

    /** For speech: no symbols, no URLs, list markers read as pauses. */
    fun toSpeech(text: String): String = text.lines().joinToString("\n") { raw ->
        var line = raw.replace(IMAGE, "").replace(LINK) { it.groupValues[1] }.replace(BARE_URL, "a link")
        line = line.trimStart().removePrefix("#").trimStart('#', ' ')
        line = line.replace(Regex("""^\s*([-*+]|\d+[.)])\s+"""), "")
        line.replace(INLINE) { m -> m.groupValues.drop(1).first { it.isNotEmpty() } }
            .replace("|", ", ")
    }.replace(Regex("\n{2,}"), "\n").trim()

    /** For the caption card. */
    fun toAnnotated(text: String): AnnotatedString = buildAnnotatedString {
        text.replace(IMAGE, "").trimEnd().lines().forEachIndexed { i, raw ->
            if (i > 0) append('\n')
            var line = raw.replace(LINK) { it.groupValues[1] }
            val heading = line.trimStart().startsWith("#")
            line = if (heading) line.trimStart().trimStart('#').trim() else line
            line = line.replace(Regex("""^(\s*)[-*+]\s+"""), "$1• ")
            if (heading) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inline(line) } else inline(line)
        }
    }

    private fun AnnotatedString.Builder.inline(line: String) {
        var pos = 0
        for (m in INLINE.findAll(line)) {
            append(line.substring(pos, m.range.first))
            val (bold, bold2, code, italic, italic2) = m.destructured
            when {
                bold.isNotEmpty() || bold2.isNotEmpty() ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold.ifEmpty { bold2 }) }
                code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic.ifEmpty { italic2 }) }
            }
            pos = m.range.last + 1
        }
        append(line.substring(pos))
    }
}
