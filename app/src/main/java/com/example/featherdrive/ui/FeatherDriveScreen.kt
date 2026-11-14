package com.example.featherdrive.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.featherdrive.storage.UploadProgress
import com.example.featherdrive.storage.VfsNodeEntity
import com.example.featherdrive.telegram.TelegramAuthState

@Composable
fun FeatherDriveScreen(vm: FeatherDriveViewModel = viewModel()) {
    val authState by vm.authState.collectAsState()

    when (val state = authState) {
        is TelegramAuthState.Ready -> VaultExplorerScreen(vm)
        else -> TelegramLoginScreen(
            state = state,
            onSubmitPhone = vm::submitPhone,
            onSubmitCode = vm::submitCode,
            onSubmitPassword = vm::submitPassword
        )
    }
}

@Composable
private fun TelegramLoginScreen(
    state: TelegramAuthState,
    onSubmitPhone: (String) -> Unit,
    onSubmitCode: (String) -> Unit,
    onSubmitPassword: (String) -> Unit
) {
    var input by remember(state) { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.CloudQueue,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Feather Vault",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Unlimited Telegram Storage • 0 Bytes on Disk",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))

            when (state) {
                TelegramAuthState.Initializing -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Connecting to Telegram MTProto...")
                }

                TelegramAuthState.WaitPhoneNumber -> {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("Phone Number (e.g. +919876543210)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { onSubmitPhone(input) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Send Verification Code")
                    }
                }

                TelegramAuthState.WaitCode -> {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("Telegram Login Code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { onSubmitCode(input) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Verify Code")
                    }
                }

                is TelegramAuthState.WaitPassword -> {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = {
                            Text(
                                if (state.hint.isNotBlank()) "2FA Password (Hint: ${state.hint})"
                                else "2FA Cloud Password"
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { onSubmitPassword(input) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Unlock Vault")
                    }
                }

                is TelegramAuthState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                TelegramAuthState.Ready -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultExplorerScreen(vm: FeatherDriveViewModel) {
    val context = LocalContext.current
    val folderStack by vm.folderStack.collectAsState()
    val items by vm.items.collectAsState()
    val uploadProgress by vm.uploadProgress.collectAsState()
    val statusMessage by vm.statusMessage.collectAsState()

    var showNewFolderDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(enabled = folderStack.size > 1) {
        vm.navigateUp()
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearStatusMessage()
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.uploadFile(uri)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = folderStack.last().title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "0 bytes used on S24",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    if (folderStack.size > 1) {
                        IconButton(onClick = { vm.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.restoreFromCloudIndex() }) {
                        Icon(Icons.Default.CloudSync, contentDescription = "Restore Index")
                    }
                    IconButton(onClick = { showNewFolderDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Default.CloudUpload, contentDescription = null) },
                text = { Text("Upload") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            uploadProgress?.let { progress ->
                UploadProgressBanner(progress)
            }

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Folder is empty. Tap Upload to stream files to Telegram.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(items, key = { it.nodeId }) { node ->
                        VfsNodeRow(
                            node = node,
                            formattedSize = if (node.isDir) "Folder" else Formatter.formatFileSize(context, node.totalSize),
                            onClick = {
                                if (node.isDir) vm.openFolder(node)
                                else vm.streamFileInExternalPlayer(node)
                            },
                            onDelete = { vm.deleteNode(node) }
                        )
                    }
                }
            }
        }
    }

    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("New Virtual Folder") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.createFolder(folderName)
                        showNewFolderDialog = false
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun UploadProgressBanner(progress: UploadProgress) {
    val context = LocalContext.current
    val fraction = if (progress.totalBytes > 0) {
        (progress.bytesUploaded.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Uploading ${progress.fileName}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (progress.totalParts > 1) {
                    Text(
                        text = "Part ${progress.currentPart}/${progress.totalParts}",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${Formatter.formatFileSize(context, progress.bytesUploaded)} / ${Formatter.formatFileSize(context, progress.totalBytes)}",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun VfsNodeRow(
    node: VfsNodeEntity,
    formattedSize: String,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(
                imageVector = when {
                    node.isDir -> Icons.Default.Folder
                    node.mimeType.startsWith("video/") -> Icons.Default.Movie
                    node.mimeType.startsWith("audio/") -> Icons.Default.AudioFile
                    node.mimeType.startsWith("image/") -> Icons.Default.Image
                    else -> Icons.Default.InsertDriveFile
                },
                contentDescription = null,
                tint = if (node.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
        },
        headlineContent = {
            Text(
                text = node.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = if (node.isDir) formattedSize else "$formattedSize • Cloud Stream",
                style = MaterialTheme.typography.bodySmall
            )
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Remove from index"
                )
            }
        }
    )
}
