package com.example.featherdrive.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.InputStream
import java.util.UUID
import kotlin.math.ceil

data class UploadProgress(
    val fileName: String,
    val currentPart: Int,
    val totalParts: Int,
    val bytesUploaded: Long,
    val totalBytes: Long
)

class TelegramUploadManager(
    private val context: Context,
    private val vfsDao: VfsDao,
    private val telegram: TelegramStorageGateway,
    private val storageChannelId: Long
) {
    companion object {
        // 1900 MiB = 1,992,294,400 bytes (< 2 GB Telegram limit & divisible by 1 MiB)
        const val MAX_PART_BYTES = 1900L * 1024L * 1024L
    }

    private val _progress = MutableStateFlow<UploadProgress?>(null)
    val progress: StateFlow<UploadProgress?> = _progress

    suspend fun createFolder(parentId: String, folderName: String): VfsNodeEntity =
        withContext(Dispatchers.IO) {
            val folder = VfsNodeEntity(
                nodeId = UUID.randomUUID().toString(),
                parentId = parentId,
                name = folderName.trim(),
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                isDir = true,
                totalSize = 0L,
                lastModified = System.currentTimeMillis()
            )
            vfsDao.insertNode(folder)
            syncIndexToChannel()
            folder
        }

    suspend fun uploadFile(
        sourceUri: Uri,
        parentId: String = "root"
    ): VfsNodeEntity = withContext(Dispatchers.IO) {
        val (fileName, totalSize) = queryFileMeta(sourceUri)
        val mimeType = context.contentResolver.getType(sourceUri) ?: "application/octet-stream"
        val nodeId = UUID.randomUUID().toString()

        val totalParts = maxOf(1, ceil(totalSize.toDouble() / MAX_PART_BYTES).toInt())
        val uploadedParts = mutableListOf<TelegramPartEntity>()
        var totalSentSoFar = 0L

        context.contentResolver.openInputStream(sourceUri)?.use { rawStream ->
            for (partIdx in 0 until totalParts) {
                val virtualStart = partIdx * MAX_PART_BYTES
                val thisPartSize = minOf(MAX_PART_BYTES, totalSize - virtualStart)

                val telegramFileName = if (totalParts == 1) {
                    fileName
                } else {
                    "$fileName.part${partIdx.toString().padStart(3, '0')}"
                }

                val boundedStream = BoundedInputStream(rawStream, thisPartSize)

                val uploaded = telegram.uploadDocumentStream(
                    channelId = storageChannelId,
                    fileName = telegramFileName,
                    mimeType = if (totalParts == 1) mimeType else "application/octet-stream",
                    partSize = thisPartSize,
                    stream = boundedStream,
                    onPartBytesSent = { sentInPart ->
                        _progress.value = UploadProgress(
                            fileName = fileName,
                            currentPart = partIdx + 1,
                            totalParts = totalParts,
                            bytesUploaded = totalSentSoFar + sentInPart,
                            totalBytes = totalSize
                        )
                    }
                )

                totalSentSoFar += thisPartSize
                uploadedParts.add(
                    TelegramPartEntity(
                        nodeId = nodeId,
                        partIndex = partIdx,
                        channelId = storageChannelId,
                        messageId = uploaded.messageId,
                        virtualStartOffset = virtualStart,
                        partSize = thisPartSize
                    )
                )
            }
        } ?: error("Cannot open URI: $sourceUri")

        val node = VfsNodeEntity(
            nodeId = nodeId,
            parentId = parentId,
            name = fileName,
            mimeType = mimeType,
            isDir = false,
            totalSize = totalSize,
            lastModified = System.currentTimeMillis()
        )

        vfsDao.saveFileWithParts(node, uploadedParts)
        _progress.value = null
        syncIndexToChannel()
        node
    }

    suspend fun syncIndexToChannel() {
        val jsonBytes = ChannelIndexSync.exportToJson(
            nodes = vfsDao.getAllNodes(),
            parts = vfsDao.getAllParts()
        )
        telegram.updatePinnedIndexJson(storageChannelId, jsonBytes)
    }

    suspend fun restoreIndexFromChannel(): Boolean = withContext(Dispatchers.IO) {
        val jsonBytes = telegram.downloadPinnedIndexJson(storageChannelId) ?: return@withContext false
        ChannelIndexSync.importFromJson(jsonBytes, vfsDao)
        true
    }

    private fun queryFileMeta(uri: Uri): Pair<String, Long> {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else "upload.bin"
                val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                return name to size
            }
        }
        return "upload.bin" to 0L
    }
}

private class BoundedInputStream(
    inStream: InputStream,
    private var remaining: Long
) : FilterInputStream(inStream) {

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = super.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val toRead = minOf(len.toLong(), remaining).toInt()
        val count = super.read(b, off, toRead)
        if (count > 0) remaining -= count
        return count
    }

    override fun close() {
        // Keep underlying source InputStream open across split parts
    }
}
