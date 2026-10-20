package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.core.memory.MemoryRepository
import com.example.core.memory.database.ActionLogEntity
import com.example.ui.components.*
import com.example.ui.theme.AuraTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ActivityHistoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography
    val repository = remember { MemoryRepository(context) }

    var searchQuery by remember { mutableStateOf("") }
    var filterType by remember { mutableStateOf("all") } // "all", "success", "failed"
    var selectedLog by remember { mutableStateOf<ActionLogEntity?>(null) }

    val logsFlow = remember(searchQuery) {
        if (searchQuery.isBlank()) repository.getActionLogs(100) else repository.searchLogs(searchQuery)
    }
    val logs by logsFlow.collectAsState(initial = emptyList())

    val filteredLogs = logs.filter {
        when (filterType) {
            "success" -> it.success
            "failed" -> !it.success
            else -> true
        }
    }

    val dateFormat = remember { SimpleDateFormat("HH:mm:ss · dd MMM", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        AuraTopBar(
            title = "Activity History",
            onBack = onBack,
            actions = {
                AuraIconButton(
                    icon = AuraIcons.Close,
                    onClick = {
                        scope.launch { repository.clearActionLogs() }
                    },
                    contentDescription = "Clear All",
                    size = 36.dp
                )
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Search Bar
        AuraTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = "Search actions or output...",
            testTag = "history_search_field"
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Filter Chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuraChip(
                text = "All (${logs.size})",
                selected = filterType == "all",
                onClick = { filterType = "all" },
                testTag = "filter_all_chip"
            )
            AuraChip(
                text = "Success",
                selected = filterType == "success",
                onClick = { filterType = "success" },
                testTag = "filter_success_chip"
            )
            AuraChip(
                text = "Failed",
                selected = filterType == "failed",
                onClick = { filterType = "failed" },
                testTag = "filter_failed_chip"
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Timeline List
        if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.text.BasicText(
                    text = "No action history found.",
                    style = typography.bodyMedium.copy(color = colors.textTertiary)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredLogs, key = { it.id }) { log ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                            .clickable { selectedLog = log }
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AuraBadge(
                                    text = if (log.success) "SUCCESS" else "FAILED",
                                    isSuccess = log.success
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                androidx.compose.foundation.text.BasicText(
                                    text = log.toolName,
                                    style = typography.labelLarge.copy(color = colors.textPrimary)
                                )
                            }
                            androidx.compose.foundation.text.BasicText(
                                text = "${log.durationMs}ms",
                                style = typography.labelSmall.copy(color = colors.textTertiary)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        if (log.output.isNotBlank()) {
                            androidx.compose.foundation.text.BasicText(
                                text = log.output.trim(),
                                style = typography.bodySmall.copy(color = colors.textSecondary),
                                maxLines = 2
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        androidx.compose.foundation.text.BasicText(
                            text = dateFormat.format(Date(log.timestamp)),
                            style = typography.labelSmall.copy(color = colors.textTertiary)
                        )
                    }
                }
            }
        }
    }

    // Detail Dialog
    selectedLog?.let { log ->
        AuraDialog(
            title = "Tool: ${log.toolName}",
            message = "Status: ${if (log.success) "Success" else "Failed"}\nDuration: ${log.durationMs}ms\nArgs:\n${log.argsJson}\n\nOutput:\n${log.output.ifEmpty { "(None)" }}",
            confirmText = "Delete",
            cancelText = "Close",
            isDangerous = true,
            onConfirm = {
                scope.launch {
                    repository.deleteActionLog(log.id)
                    selectedLog = null
                }
            },
            onCancel = { selectedLog = null }
        )
    }
}
