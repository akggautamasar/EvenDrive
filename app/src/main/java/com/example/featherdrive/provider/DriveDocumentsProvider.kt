package com.example.featherdrive.provider

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import com.example.featherdrive.FeatherAppContainer
import com.example.featherdrive.storage.VfsNodeEntity
import com.example.featherdrive.telegram.TelegramAuthState
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException

class DriveDocumentsProvider : DocumentsProvider() {

    companion object {
        private const val ROOT_ID = "feather_telegram_root"
        private const val ROOT_DOC_ID = "root"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
        )
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val ctx = context ?: return result
        val container = FeatherAppContainer.get(ctx)

        if (container.tdManager.authState.value !is TelegramAuthState.Ready) {
            return result
        }

        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            add(Root.COLUMN_TITLE, "Feather Drive")
            add(Root.COLUMN_SUMMARY, "Telegram Cloud (0 B on disk)")
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_ICON, android.R.drawable.ic_menu_upload)
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (documentId == ROOT_DOC_ID) {
            result.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_DISPLAY_NAME, "Feather Vault")
                add(Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
                add(Document.COLUMN_FLAGS, 0)
                add(Document.COLUMN_SIZE, null)
            }
            return result
        }

        val ctx = context ?: throw FileNotFoundException("Provider context unavailable")
        val node = runBlocking { FeatherAppContainer.get(ctx).db.vfsDao().getNode(documentId) }
            ?: throw FileNotFoundException("Document not found: $documentId")

        appendNodeRow(result, node)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val ctx = context ?: return result
        val children = runBlocking {
            FeatherAppContainer.get(ctx).db.vfsDao().getChildren(parentDocumentId)
        }
        children.forEach { appendNodeRow(result, it) }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("Read-only virtual stream; requested mode=$mode")
        }

        val ctx = context ?: throw FileNotFoundException("Provider context unavailable")
        val container = FeatherAppContainer.get(ctx)
        val node = runBlocking { container.db.vfsDao().getNode(documentId) }
            ?: throw FileNotFoundException("Document not found: $documentId")

        if (node.isDir) {
            throw FileNotFoundException("Cannot open directory as stream: $documentId")
        }

        val storageManager = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val handlerThread = HandlerThread("TgStream-$documentId").apply { start() }

        val callback = DriveProxyFileCallback(
            nodeId = node.nodeId,
            fileSize = node.totalSize,
            chunkCache = container.chunkCache,
            handlerThread = handlerThread
        )

        return storageManager.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            callback,
            Handler(handlerThread.looper)
        )
    }

    private fun appendNodeRow(cursor: MatrixCursor, node: VfsNodeEntity) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, node.nodeId)
            add(Document.COLUMN_MIME_TYPE, node.mimeType)
            add(Document.COLUMN_DISPLAY_NAME, node.name)
            add(Document.COLUMN_LAST_MODIFIED, node.lastModified)
            add(Document.COLUMN_FLAGS, 0)
            add(Document.COLUMN_SIZE, if (node.isDir) null else node.totalSize)
        }
    }
}
