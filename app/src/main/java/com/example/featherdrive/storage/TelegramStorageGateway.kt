package com.example.featherdrive.storage

import java.io.InputStream

data class UploadedTelegramDoc(
    val messageId: Long,
    val documentId: Int
)

interface TelegramStorageGateway {
    suspend fun uploadDocumentStream(
        channelId: Long,
        fileName: String,
        mimeType: String,
        partSize: Long,
        stream: InputStream,
        onPartBytesSent: (Long) -> Unit
    ): UploadedTelegramDoc

    suspend fun getFileBytes(
        channelId: Long,
        messageId: Long,
        offset: Long,
        limit: Int
    ): ByteArray

    suspend fun updatePinnedIndexJson(channelId: Long, jsonBytes: ByteArray)

    suspend fun downloadPinnedIndexJson(channelId: Long): ByteArray?
}
