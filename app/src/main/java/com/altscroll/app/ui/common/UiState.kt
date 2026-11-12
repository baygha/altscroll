package com.altscroll.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun EmptyState(
    title: String,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/** Trims a Postgres timestamptz down to something short enough for a list row. */
fun shortTimestamp(iso: String): String = runCatching {
    val instant = java.time.OffsetDateTime.parse(
        if (iso.contains('+') || iso.endsWith("Z")) iso else iso + "Z"
    )
    val now = java.time.OffsetDateTime.now(instant.offset)
    val minutes = java.time.Duration.between(instant, now).toMinutes()
    when {
        minutes < 1L -> "now"
        minutes < 60L -> "${minutes}m"
        minutes < 1440L -> "${minutes / 60}h"
        minutes < 10080L -> "${minutes / 1440}d"
        else -> instant.toLocalDate().toString()
    }
}.getOrDefault("")

/**
 * Marks an account as an AI rather than a person. Shown everywhere the bot's
 * name appears: people should never have to guess who they are talking to.
 */
@Composable
fun AiBadge(modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
        modifier = modifier,
    ) {
        Text(
            "AI",
            Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
    }
}
