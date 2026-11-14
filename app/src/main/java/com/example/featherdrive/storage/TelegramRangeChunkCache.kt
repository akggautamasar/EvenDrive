package com.example.featherdrive.storage

import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * RAM-only LRU chunk cache + background read-ahead prefetcher.
 * Keeps 0 bytes on phone flash storage and caps concurrent Telegram chunk fetches to avoid FLOOD_WAIT.
 */
class TelegramRangeChunkCache(
    private val fetcher: MultiPartTelegramFetcher
) {
    companion object {
        const val CHUNK_SIZE = MultiPartTelegramFetcher.CHUNK_SIZE // 1 MiB
        private const val MAX_RAM_BYTES = 64 * 1024 * 1024         // 64 MiB RAM ceiling
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val prefetchDispatcher = Dispatchers.IO.limitedParallelism(3)
    private val prefetchScope = CoroutineScope(SupervisorJob() + prefetchDispatcher)

    private val memoryCache = object : LruCache<String, ByteArray>(MAX_RAM_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    private val inFlight = ConcurrentHashMap<String, CompletableFuture<ByteArray>>()

    @Throws(IOException::class)
    fun readRange(
        nodeId: String,
        fileSize: Long,
        offset: Long,
        length: Int,
        dest: ByteArray,
        destOffset: Int = 0
    ): Int {
        if (offset >= fileSize || length <= 0) return 0

        val bytesToRead = minOf(length.toLong(), fileSize - offset).toInt()
        var totalCopied = 0
        var currentOffset = offset

        while (totalCopied < bytesToRead) {
            val chunkIndex = currentOffset / CHUNK_SIZE
            val offsetInChunk = (currentOffset % CHUNK_SIZE).toInt()

            val chunkData = getOrFetchChunk(nodeId, chunkIndex)

            // Speculatively prefetch the next 1 MiB chunk in the background
            val nextChunkStart = (chunkIndex + 1) * CHUNK_SIZE
            if (nextChunkStart < fileSize) {
                prefetchChunkAsync(nodeId, chunkIndex + 1)
            }

            if (offsetInChunk >= chunkData.size) break

            val available = chunkData.size - offsetInChunk
            val toCopy = minOf(available, bytesToRead - totalCopied)

            System.arraycopy(chunkData, offsetInChunk, dest, destOffset + totalCopied, toCopy)
            totalCopied += toCopy
            currentOffset += toCopy
        }

        return totalCopied
    }

    private fun prefetchChunkAsync(nodeId: String, chunkIndex: Long) {
        val key = "$nodeId:$chunkIndex"
        if (memoryCache.get(key) != null || inFlight.containsKey(key)) return

        prefetchScope.launch {
            try {
                getOrFetchChunk(nodeId, chunkIndex)
            } catch (_: Exception) {
                // Foreground read will retry on demand if needed
            }
        }
    }

    @Throws(IOException::class)
    private fun getOrFetchChunk(nodeId: String, chunkIndex: Long): ByteArray {
        val key = "$nodeId:$chunkIndex"
        memoryCache.get(key)?.let { return it }

        var isOwner = false
        val future = inFlight.computeIfAbsent(key) {
            isOwner = true
            CompletableFuture()
        }

        if (!isOwner) {
            return try {
                future.get()
            } catch (e: Exception) {
                throw IOException("Failed waiting for in-flight chunk $key", e)
            }
        }

        return try {
            val bytes = fetcher.fetchVirtualChunk(nodeId, chunkIndex)
            if (bytes.isNotEmpty()) {
                memoryCache.put(key, bytes)
            }
            future.complete(bytes)
            bytes
        } catch (t: Throwable) {
            future.completeExceptionally(t)
            throw if (t is IOException) t else IOException(t)
        } finally {
            inFlight.remove(key)
        }
    }
}
