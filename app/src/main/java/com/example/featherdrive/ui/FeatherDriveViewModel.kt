package com.example.featherdrive.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.featherdrive.FeatherAppContainer
import com.example.featherdrive.storage.TelegramUploadManager
import com.example.featherdrive.storage.UploadProgress
import com.example.featherdrive.storage.VfsNodeEntity
import com.example.featherdrive.telegram.TelegramAuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FolderCrumb(val nodeId: String, val title: String)

class FeatherDriveViewModel(application: Application) : AndroidViewModel(application) {

    private val container = FeatherAppContainer.get(application)
    val authState: StateFlow<TelegramAuthState> = container.tdManager.authState

    private val _folderStack = MutableStateFlow(listOf(FolderCrumb("root", "Feather Vault")))
    val folderStack: StateFlow<List<FolderCrumb>> = _folderStack.asStateFlow()

    private val _items = MutableStateFlow<List<VfsNodeEntity>>(emptyList())
    val items: StateFlow<List<VfsNodeEntity>> = _items.asStateFlow()

    private val _uploadProgress = MutableStateFlow<UploadProgress?>(null)
    val uploadProgress: StateFlow<UploadProgress?> = _uploadProgress.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private var uploadManager: TelegramUploadManager? = null

    init {
        viewModelScope.launch {
            authState.collect { state ->
                if (state is TelegramAuthState.Ready) {
                    if (uploadManager == null) {
                        uploadManager = container.createUploadManager().also { mgr ->
                            launch {
                                mgr.progress.collect { _uploadProgress.value = it }
                            }
                        }
                    }
                    refreshCurrentFolder()
                }
            }
        }
    }

    fun submitPhone(phone: String) = viewModelScope.launch {
        runCatching { container.tdManager.submitPhoneNumber(phone.trim()) }
            .onFailure { _statusMessage.value = it.message }
    }

    fun submitCode(code: String) = viewModelScope.launch {
        runCatching { container.tdManager.submitAuthCode(code.trim()) }
            .onFailure { _statusMessage.value = it.message }
    }

    fun submitPassword(password: String) = viewModelScope.launch {
        runCatching { container.tdManager.submitPassword(password) }
            .onFailure { _statusMessage.value = it.message }
    }

    fun openFolder(folder: VfsNodeEntity) {
        if (!folder.isDir) return
        _folderStack.value = _folderStack.value + FolderCrumb(folder.nodeId, folder.name)
        refreshCurrentFolder()
    }

    fun navigateUp(): Boolean {
        val stack = _folderStack.value
        if (stack.size <= 1) return false
        _folderStack.value = stack.dropLast(1)
        refreshCurrentFolder()
        return true
    }

    fun refreshCurrentFolder() = viewModelScope.launch {
        val currentId = _folderStack.value.last().nodeId
        _items.value = container.db.vfsDao().getChildren(currentId)
    }

    fun createFolder(name: String) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        val mgr = uploadManager ?: return@launch
        val parentId = _folderStack.value.last().nodeId
        runCatching {
            mgr.createFolder(parentId, name)
            refreshCurrentFolder()
        }.onFailure {
            _statusMessage.value = "Failed to create folder: ${it.message}"
        }
    }

    fun uploadFile(uri: Uri) = viewModelScope.launch {
        val mgr = uploadManager ?: return@launch
        val parentId = _folderStack.value.last().nodeId
        runCatching {
            mgr.uploadFile(uri, parentId)
            refreshCurrentFolder()
            _statusMessage.value = "Uploaded to Telegram (0 B kept on phone)"
        }.onFailure {
            _statusMessage.value = "Upload failed: ${it.message}"
        }
    }

    fun restoreFromCloudIndex() = viewModelScope.launch {
        val mgr = uploadManager ?: return@launch
        runCatching {
            val restored = mgr.restoreIndexFromChannel()
            refreshCurrentFolder()
            _statusMessage.value = if (restored) {
                "Restored folder index from pinned Telegram message"
            } else {
                "No pinned index found in Feather Vault channel"
            }
        }.onFailure {
            _statusMessage.value = "Restore failed: ${it.message}"
        }
    }

    fun deleteNode(node: VfsNodeEntity) = viewModelScope.launch {
        container.db.vfsDao().deleteNode(node.nodeId)
        uploadManager?.syncIndexToChannel()
        refreshCurrentFolder()
    }

    fun streamFileInExternalPlayer(node: VfsNodeEntity) {
        val ctx = getApplication<Application>()
        val authority = "${ctx.packageName}.documents"
        val docUri = DocumentsContract.buildDocumentUri(authority, node.nodeId)

        val playIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(docUri, node.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(playIntent, "Stream ${node.name}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(chooser)
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}
