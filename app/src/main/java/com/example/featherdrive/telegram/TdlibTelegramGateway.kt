package com.example.featherdrive.telegram

import com.example.featherdrive.storage.TelegramStorageGateway
import com.example.featherdrive.storage.UploadedTelegramDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID

class TdlibTelegramGateway(
    private val td: TdlibClientManager
) : TelegramStorageGateway {

    override suspend fun getFileBytes(
        channelId: Long,
        messageId: Long,
        offset: Long,
        limit: Int
    ): ByteArray = withContext(Dispatchers.IO) {
        val msg = td.send(TdApi.GetMessage(channelId, messageId))
        val docContent = msg.content as? TdApi.MessageDocument
            ?: throw IOException("Message $messageId does not contain a document")

        val fileId = docContent.document.document.id
        val totalFileSize = docContent.document.document.size
        if (offset >= totalFileSize) return@withContext ByteArray(0)

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
            td.sendFireAndForget(TdApi.DeleteFile(fileId))
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
            null
        )

        td.activeUploadStreams[virtualKey] = progressStream
        try {
            val tempMsg = td.send(
                TdApi.SendMessage(
                    channelId,
                    null,
                    null,
                    null,
                    null,
                    content
                )
            )

            val finalMsg = td.registerPendingSend(tempMsg.id).await()
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
