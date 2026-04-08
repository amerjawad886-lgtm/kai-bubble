package com.example.reply.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun KaiHistoryScreen(
    onBack: () -> Unit,
    onOpenSession: (Long) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var sessions by remember { mutableStateOf(KaiChatHistoryStore.listSessions(context)) }
    var pendingDeleteId by remember { mutableLongStateOf(-1L) }
    var pendingRenameId by remember { mutableLongStateOf(-1L) }
    var renameText by remember { mutableStateOf("") }
    var pendingDeleteAll by remember { mutableStateOf(false) }

    val bgTop = Color(0xFF05060B)
    val bgBottom = Color(0xFF091222)
    val auroraA = Color(0xFF56F0A6)
    val auroraB = Color(0xFF28D7C7)

    fun refresh() {
        sessions = KaiChatHistoryStore.listSessions(context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(bgTop, bgBottom)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .navigationBarsPadding()
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .combinedClickable(onClick = onBack, onLongClick = onBack)
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = "Kai History",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Refresh",
                        color = Color.White.copy(alpha = 0.70f),
                        fontSize = 12.sp,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable {
                                KaiChatHistoryStore.compactAll(context)
                                refresh()
                            }
                    )

                    Text(
                        text = "Hold = rename",
                        color = Color.White.copy(alpha = 0.52f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Spacer(modifier = Modifier.padding(top = 8.dp))

            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No saved chats yet.",
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }
            } else {
                TextButton(
                    onClick = { pendingDeleteAll = true },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Delete all", color = Color(0xFFFF8E8E))
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(sessions, key = { it.id }) { session ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Color(0xFF0B1624).copy(alpha = 0.96f),
                                    RoundedCornerShape(18.dp)
                                )
                                .border(
                                    1.dp,
                                    auroraB.copy(alpha = 0.30f),
                                    RoundedCornerShape(18.dp)
                                )
                                .combinedClickable(
                                    onClick = { onOpenSession(session.id) },
                                    onLongClick = {
                                        pendingRenameId = session.id
                                        renameText = session.title
                                    }
                                )
                                .padding(14.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = session.title,
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Edit",
                                            color = Color.White.copy(alpha = 0.55f),
                                            fontSize = 12.sp,
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clickable {
                                                    pendingRenameId = session.id
                                                    renameText = session.title
                                                }
                                        )
                                        Text(
                                            text = "Delete",
                                            color = Color.White.copy(alpha = 0.50f),
                                            fontSize = 12.sp,
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clickable {
                                                    pendingDeleteId = session.id
                                                }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.padding(top = 6.dp))

                                Text(
                                    text = session.messages.lastOrNull()?.text.orEmpty().take(120),
                                    color = Color.White.copy(alpha = 0.68f),
                                    style = MaterialTheme.typography.bodySmall
                                )

                                Spacer(modifier = Modifier.padding(top = 6.dp))

                                Text(
                                    text = "Messages: ${session.messages.size}",
                                    color = auroraA.copy(alpha = 0.9f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }

        if (pendingDeleteId > 0L) {
            AlertDialog(
                onDismissRequest = { pendingDeleteId = -1L },
                title = { Text("Delete chat?", color = Color.White) },
                text = {
                    Text(
                        "This conversation will be removed from Kai History.",
                        color = Color.White.copy(alpha = 0.78f)
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            KaiChatHistoryStore.deleteSession(context, pendingDeleteId)
                            pendingDeleteId = -1L
                            refresh()
                        }
                    ) {
                        Text("Delete", color = Color(0xFFFF8E8E))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteId = -1L }) {
                        Text("Cancel", color = Color.White)
                    }
                },
                containerColor = Color(0xFF0B1624)
            )
        }

        if (pendingDeleteAll) {
            AlertDialog(
                onDismissRequest = { pendingDeleteAll = false },
                title = { Text("Delete all chats?", color = Color.White) },
                text = {
                    Text(
                        "This will remove all local Kai History sessions.",
                        color = Color.White.copy(alpha = 0.78f)
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            KaiChatHistoryStore.deleteAll(context)
                            pendingDeleteAll = false
                            refresh()
                        }
                    ) {
                        Text("Delete all", color = Color(0xFFFF8E8E))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteAll = false }) {
                        Text("Cancel", color = Color.White)
                    }
                },
                containerColor = Color(0xFF0B1624)
            )
        }

        if (pendingRenameId > 0L) {
            AlertDialog(
                onDismissRequest = { pendingRenameId = -1L },
                title = { Text("Edit title", color = Color.White) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = renameText,
                            onValueChange = { renameText = it },
                            singleLine = true,
                            label = { Text("Title") }
                        )

                        Spacer(modifier = Modifier.padding(top = 10.dp))

                        TextButton(onClick = {
                            pendingDeleteId = pendingRenameId
                            pendingRenameId = -1L
                        }) {
                            Text("Delete this chat", color = Color(0xFFFF8E8E))
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            KaiChatHistoryStore.renameSession(
                                context = context,
                                id = pendingRenameId,
                                newTitle = renameText
                            )
                            pendingRenameId = -1L
                            refresh()
                        }
                    ) {
                        Text("Save", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRenameId = -1L }) {
                        Text("Cancel", color = Color.White)
                    }
                },
                containerColor = Color(0xFF0B1624)
            )
        }
    }
}