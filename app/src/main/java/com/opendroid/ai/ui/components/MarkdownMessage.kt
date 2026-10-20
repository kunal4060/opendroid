package com.opendroid.ai.ui.components

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.coil2.Coil2ImageTransformerImpl
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.opendroid.ai.ui.theme.AppTheme
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes

/**
 * Renders an assistant chat reply as Markdown — headings, lists, links, tables
 * and fenced code blocks with syntax highlighting — themed from the app's own
 * palette ([AppTheme.colors]) and the chat bubble's 14sp/20sp body metrics,
 * not the markdown library's Material defaults.
 *
 * Streaming safety: agent replies stream into Room chunk by chunk, so this
 * composable re-parses constantly while text is still arriving. Two defences
 * keep that invisible — [rememberMarkdownState] with `retainState = true`
 * keeps the previous render on screen while the new text parses, and
 * [closeOpenCodeFence] closes a still-open code fence for the parser only, so
 * a half-streamed code block doesn't swallow the rest of the reply as "code"
 * until its closing fence lands. The stored message text is never mutated.
 *
 * Malformed markdown degrades to plain text: the parser is fault-tolerant,
 * and the renderer's error slot falls back to the raw [Text] rendering, so a
 * reply can never turn into a blank bubble.
 */
@Composable
fun MarkdownMessage(
    text: String,
    modifier: Modifier = Modifier
) {
    val palette = AppTheme.colors

    // Slightly lifted inset for code blocks — the same colours ChatScreen
    // uses for the user bubble, so code reads as "inset" against the agent
    // card in both palettes.
    val codeBackground = if (palette.isDark) Color(0xFF1E1E22) else Color(0xFFF1F3F5)

    val colors = markdownColor(
        text = palette.textPrimary,
        codeBackground = codeBackground,
        inlineCodeBackground = codeBackground,
        dividerColor = palette.borderColor,
        tableBackground = palette.surface.copy(alpha = 0.5f)
    )

    val body = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = palette.textPrimary
    )
    val typography = markdownTypography(
        h1 = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary),
        h2 = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary),
        h3 = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary),
        h4 = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary),
        h5 = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary),
        h6 = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, color = palette.textSecondary),
        text = body,
        code = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, color = palette.textPrimary),
        inlineCode = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = palette.textPrimary),
        quote = body.copy(fontStyle = FontStyle.Italic, color = palette.textSecondary),
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        textLink = TextLinkStyles(
            style = SpanStyle(
                color = palette.accentCyan,
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline
            )
        ),
        table = body.copy(fontSize = 13.sp, lineHeight = 18.sp)
    )

    val components = remember {
        markdownComponents(
            checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
            codeFence = { model -> CopyableCodeFence(model) },
            codeBlock = { model -> CopyableCodeBlock(model) }
        )
    }

    val prepared = remember(text) { closeOpenCodeFence(text) }
    val state = rememberMarkdownState(content = prepared, retainState = true)

    Markdown(
        markdownState = state,
        modifier = modifier.fillMaxWidth(),
        colors = colors,
        typography = typography,
        components = components,
        imageTransformer = Coil2ImageTransformerImpl,
        error = { errorModifier ->
            // Plain-text fallback: a reply that fails to render shows its raw
            // text instead of an empty bubble.
            Text(
                text = text,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = palette.textPrimary,
                modifier = errorModifier
            )
        }
    )
}

/** Fenced (```) code block with the copy-header treatment. */
@Composable
private fun CopyableCodeFence(model: MarkdownComponentModel) {
    MarkdownCodeFence(
        content = model.content,
        node = model.node,
        style = model.typography.code
    ) { code, language, style ->
        CodeBlockWithCopyHeader(code = code, language = language, style = style)
    }
}

/** Indented code block — same treatment, no language tag. */
@Composable
private fun CopyableCodeBlock(model: MarkdownComponentModel) {
    MarkdownCodeBlock(
        content = model.content,
        node = model.node,
        style = model.typography.code
    ) { code, language, style ->
        CodeBlockWithCopyHeader(code = code, language = language, style = style)
    }
}

/**
 * One code block: a header row (language label + copy button writing the
 * block's exact raw code to the clipboard) over the syntax-highlighted code.
 * The highlight theme follows the app palette's dark/light, not the system
 * flag, because the app theme can differ from the device theme.
 */
@Composable
private fun CodeBlockWithCopyHeader(
    code: String,
    language: String?,
    style: TextStyle
) {
    val palette = AppTheme.colors
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val codeBackground = LocalMarkdownColors.current.codeBackground
    val cornerSize = LocalMarkdownDimens.current.codeBackgroundCornerSize
    val isDark = palette.isDark
    val highlightsBuilder = remember(isDark) {
        Highlights.Builder().theme(SyntaxThemes.default(darkMode = isDark))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(cornerSize))
            .background(codeBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language?.takeIf { it.isNotBlank() } ?: "code",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = palette.textSecondary,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(code))
                    // Android 13+ shows its own copy confirmation; a Toast
                    // there would double up (same rule as CrashLogScreen).
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy code",
                    tint = palette.textSecondary,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        MarkdownHighlightedCode(
            code = code,
            language = language,
            style = style,
            highlightsBuilder = highlightsBuilder
        )
    }
}

/**
 * Parse-only streaming guard: if [text] currently ends inside an unclosed
 * code fence (odd number of fence lines), appends the matching closing fence
 * to the string handed to the parser. Without this, a half-streamed reply's
 * tail would render as one giant code block until the real closing fence
 * arrives. Balanced input is returned unchanged. The stored message is never
 * modified — this only shapes what the parser sees.
 */
internal fun closeOpenCodeFence(text: String): String {
    var openMarker: String? = null
    for (line in text.lineSequence()) {
        val trimmed = line.trimStart()
        val marker = when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (marker != null) {
            openMarker = if (openMarker == marker) null else marker
        }
    }
    return if (openMarker != null) {
        text + (if (text.endsWith("\n")) "" else "\n") + openMarker
    } else {
        text
    }
}
