package com.example.featherdrive.storage

import kotlinx.coroutines.runBlocking
import java.io.IOException

class MultiPartTelegramFetcher(
    private val vfsDao: VfsDao,
    private val telegram: TelegramStorageGateway
) {
    companion object {
        // 1 MiB (1,048,576 bytes) satisfies Telegram's strict offset & limit alignment rules
        const val CHUNK_SIZE = 1 * 1024 * 1024
    }

    @Throws(IOException::class)
    fun fetchVirtualChunk(nodeId: String, chunkIndex: Long): ByteArray = runBlocking {
        val record = vfsDao.getNodeWithParts(nodeId)
            ?: throw IOException("Virtual file not found: $nodeId")

        val virtualOffset = chunkIndex * CHUNK_SIZE
        if (virtualOffset >= record.node.totalSize) {
            return@runBlocking ByteArray(0)
        }

        // Locate which 1900 MiB part owns this virtual byte offset
        val part = record.parts
            .sortedBy { it.partIndex }
            .firstOrNull {
                virtualOffset >= it.virtualStartOffset &&
                    virtualOffset < (it.virtualStartOffset + it.partSize)
            } ?: throw IOException("No Telegram part covers offset=$virtualOffset for $nodeId")

        // Always divisible by 1,048,576 because MAX_PART_BYTES is a multiple of 1 MiB
        val partLocalOffset = virtualOffset - part.virtualStartOffset

        telegram.getFileBytes(
            channelId = part.channelId,
            messageId = part.messageId,
            offset = partLocalOffset,
            limit = CHUNK_SIZE
        )
    }
}
