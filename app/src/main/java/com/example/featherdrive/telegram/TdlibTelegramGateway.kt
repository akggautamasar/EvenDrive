package com.example.featherdrive.telegram

import com.example.featherdrive.storage.TelegramStorageGateway
import com.example.featherdrive.storage.UploadedTelegramDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID

internal const val EVENDRIVE_UPLOAD_CAPTION = "Uploaded by EvenDrive"

class TdlibTelegramGateway(
    private val td: TdlibClientManager
) : TelegramStorageGateway {

    private val rangeReadLock = Mutex()

    override suspend fun getFileBytes(
        channelId: Long,
        messageId: Long,
        offset: Long,
        limit: Int
    ): ByteArray = withContext(Dispatchers.IO) {
        rangeReadLock.withLock {
            val msg = td.send(TdApi.GetMessage(channelId, messageId))
            val file = when (val content = msg.content) {
                is TdApi.MessageDocument -> content.document.document
                is TdApi.MessageVideo -> content.video.video
                else -> throw IOException("Message $messageId does not contain a playable file")
            }
            val fileId = file.id
            val totalFileSize = file.size.takeIf { it > 0 } ?: file.expectedSize
            if (offset >= totalFileSize) return@withLock ByteArray(0)

            val expectedBytes = minOf(limit.toLong(), totalFileSize - offset).toInt()

            try {
                val downloadedFile = td.send(
                    TdApi.DownloadFile(
                        fileId,
                        32,
                        offset,
                        expectedBytes.toLong(),
                        true
                    )
                )

                val localPath = downloadedFile.local.path
                if (localPath.isNullOrEmpty()) {
                    throw IOException("TDLib returned empty local path for fileId=$fileId")
                }

                val buffer = ByteArray(expectedBytes)
                RandomAccessFile(localPath, "r").use { raf ->
                    raf.seek(offset)
                    raf.readFully(buffer)
                }
                buffer
            } finally {
                // Immediately wipe transient disk chunk; RAM LruCache now holds it
                withContext(NonCancellable) {
                    try {
                        td.send(TdApi.DeleteFile(fileId))
                    } catch (_: IOException) {
                        // Preserve the result of the range read if TDLib cannot clear its cache.
                    }
                }
            }
        }
    }

    override suspend fun uploadDocumentStream(
        channelId: Long,
        fileName: String,
        mimeType: String,
        partSize: Long,
        stream: InputStream,
        onPartBytesSent: (Long) -> Unit
    ): UploadedTelegramDoc = withContext(Dispatchers.IO) {
        val virtualKey = "stream://${UUID.randomUUID()}/$fileName"

        val progressStream = object : InputStream() {
            private var sent = 0L
            override fun read(): Int = stream.read().also {
                if (it >= 0) {
                    sent++
                    onPartBytesSent(sent)
                }
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = stream.read(b, off, len)
                if (n > 0) {
                    sent += n
                    onPartBytesSent(sent)
                }
                return n
            }
        }

        val inputFile = TdApi.InputFileGenerated(
            virtualKey,
            "raw_copy",
            partSize
        )

        val content = TdApi.InputMessageDocument(
            TdApi.InputDocument(inputFile, null, true),
            TdApi.FormattedText(EVENDRIVE_UPLOAD_CAPTION, emptyArray())
        )

        td.activeUploadStreams[virtualKey] = progressStream
        try {
            val finalMsg = td.sendMessage(
                TdApi.SendMessage(
                    channelId,
                    null,
                    null,
                    null,
                    null,
                    content
                )
            )

            val uploadedDoc = (finalMsg.content as TdApi.MessageDocument).document.document

            td.sendFireAndForget(TdApi.DeleteFile(uploadedDoc.id))

            UploadedTelegramDoc(
                messageId = finalMsg.id,
                documentId = uploadedDoc.id
            )
        } finally {
            td.activeUploadStreams.remove(virtualKey)
        }
    }

    override suspend fun updatePinnedIndexJson(channelId: Long, jsonBytes: ByteArray) {
        val uploaded = uploadDocumentStream(
            channelId = channelId,
            fileName = "even_index.json",
            mimeType = "application/json",
            partSize = jsonBytes.size.toLong(),
            stream = jsonBytes.inputStream(),
            onPartBytesSent = {}
        )
        td.send(
            TdApi.PinChatMessage(
                channelId,
                uploaded.messageId,
                true,
                false
            )
        )
    }

    override suspend fun downloadPinnedIndexJson(channelId: Long): ByteArray? =
        withContext(Dispatchers.IO) {
            val msg = runCatching { td.send(TdApi.GetChatPinnedMessage(channelId)) }.getOrNull()
                ?: return@withContext null
            val doc = (msg.content as? TdApi.MessageDocument)?.document?.document ?: return@withContext null

            try {
                val downloaded = td.send(TdApi.DownloadFile(doc.id, 32, 0L, doc.size, true))
                val path = downloaded.local.path
                if (path.isNullOrEmpty()) null else File(path).readBytes()
            } finally {
                td.sendFireAndForget(TdApi.DeleteFile(doc.id))
            }
        }
}
