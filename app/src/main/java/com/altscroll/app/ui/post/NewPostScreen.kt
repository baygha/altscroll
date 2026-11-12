package com.altscroll.app.ui.post

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun NewPostScreen(
    onPublished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NewPostViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("New post", style = MaterialTheme.typography.titleLarge)
        Text(
            "Every submission is screened. Posts that teach something factual get " +
                "published; personal updates, promotion, and opinion pieces do not.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = state.title,
            onValueChange = viewModel::onTitle,
            label = { Text("Title") },
            singleLine = true,
            isError = state.title.isNotEmpty() && !state.titleValid,
            supportingText = { Text("${state.title.length}/200") },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.body,
            onValueChange = viewModel::onBody,
            label = { Text("What are you explaining?") },
            isError = state.body.isNotEmpty() && !state.bodyValid,
            supportingText = {
                Text(
                    if (state.wordCount < 12)
                        "${state.wordCount} words - aim for at least 12 so there is something to assess"
                    else
                        "${state.wordCount} words"
                )
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
        )

        OutlinedTextField(
            value = state.sourceUrl,
            onValueChange = viewModel::onSourceUrl,
            label = { Text("Source link (optional)") },
            singleLine = true,
            supportingText = { Text("A paper, article, or dataset backing the claim") },
            modifier = Modifier.fillMaxWidth(),
        )

        state.error?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        message,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(onClick = viewModel::dismissError) { Text("Dismiss") }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = viewModel::submit,
            enabled = state.canSubmit,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (state.submitting) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text("Checking your post...")
                }
            } else {
                Text("Submit for review")
            }
        }
    }

    val verdict = state.verdict
    if (verdict != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissVerdict,
            title = {
                Text(
                    if (verdict.approved) "Published" else "Not a fit for the feed",
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(verdict.reason)
                    if (verdict.approved && verdict.topic != null) {
                        Text(
                            "Filed under ${verdict.topic}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!verdict.approved) {
                        Text(
                            "Edit your draft and submit again - it stays in the box.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissVerdict()
                    if (verdict.approved) {
                        viewModel.reset()
                        onPublished()
                    }
                }) {
                    Text(if (verdict.approved) "See it in the feed" else "Revise")
                }
            },
        )
    }
}
