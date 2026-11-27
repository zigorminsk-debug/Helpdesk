package com.helpdesk.jiraagent.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.helpdesk.jiraagent.domain.PhoneNumberNormalizer
import com.helpdesk.jiraagent.model.PhoneRegion

/**
 * Renders text where every phone number is automatically recognized, displayed with its
 * international E.164 format if needed, and made clickable to launch the default system dialer.
 */
@Composable
fun ClickablePhoneText(
    text: String,
    region: PhoneRegion,
    customCountryCode: String,
    onPhoneClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium
) {
    val linkColor = MaterialTheme.colorScheme.primary

    val spans = remember(text, region, customCountryCode) {
        PhoneNumberNormalizer.findPhoneSpans(
            text = text,
            region = region,
            customCountryCode = customCountryCode
        )
    }

    if (spans.isEmpty()) {
        Text(
            text = text,
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier
        )
        return
    }

    val annotated = remember(text, spans, linkColor) {
        buildAnnotatedString {
            var cursor = 0
            for (span in spans) {
                if (span.startIndex > cursor) {
                    append(text.substring(cursor, span.startIndex))
                }
                val link = LinkAnnotation.Clickable(
                    tag = span.match.e164,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = linkColor,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = TextDecoration.Underline
                        )
                    ),
                    linkInteractionListener = {
                        onPhoneClick(span.match.e164)
                    }
                )
                pushLink(link)
                append(span.match.formattedDisplay)
                pop()
                cursor = span.endIndex
            }
            if (cursor < text.length) {
                append(text.substring(cursor))
            }
        }
    }

    Text(
        text = annotated,
        style = style.copy(color = MaterialTheme.colorScheme.onSurface),
        modifier = modifier
    )
}
