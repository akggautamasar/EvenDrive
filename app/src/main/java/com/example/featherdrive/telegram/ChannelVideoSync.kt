package com.example.featherdrive.telegram

import android.content.SharedPreferences
import android.webkit.MimeTypeMap
import androidx.room.withTransaction
import com.example.featherdrive.storage.TelegramPartEntity
import com.example.featherdrive.storage.VfsDatabase
import com.example.featherdrive.storage.VfsNodeEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.util.Locale

class ChannelVideoSync(
    private val td: TdlibClientManager,
    private val db: VfsDatabase,
    private val prefs: SharedPreferences
) {
    private val syncLock = Mutex()

    suspend fun sync(channelId: Long): Int = syncLock.withLock {
        val checkpointKey = "channel_video_checkpoint_$channelId"
        val lastSeen = prefs.getLong(checkpointKey, 0L)
        var newest = lastSeen
        var cursor = 0L
        var imported = 0
        val dao = db.vfsDao()

        while (true) {
            val page = td.send(TdApi.GetChatHistory(channelId, cursor, 0, 100, false))
                .messages.filterNotNull()
            val older = page.filter { it.id > 0 && (cursor == 0L || it.id < cursor) }
            if (older.isEmpty()) break

            val unseen = older.filter { it.id > lastSeen && it.sendingState == null }
            db.withTransaction {
                for (message in unseen) {
                    val node = videoNode(message) ?: continue
                    if (dao.hasMessage(channelId, message.id)) continue
                    dao.saveFileWithParts(
                        node,
                        listOf(TelegramPartEntity(
                            nodeId = node.nodeId,
                            partIndex = 0,
                            channelId = channelId,
                            messageId = message.id,
                            virtualStartOffset = 0,
                            partSize = node.totalSize
                        ))
                    )
                    imported++
                }
            }
            newest = maxOf(newest, unseen.maxOfOrNull { it.id } ?: lastSeen)
            cursor = older.minOf { it.id }
            if (cursor <= lastSeen) break
        }

        if (newest > lastSeen) prefs.edit().putLong(checkpointKey, newest).apply()
        imported
    }

    private fun videoNode(message: TdApi.Message): VfsNodeEntity? {
        val (file, name, mime) = when (val content = message.content) {
            is TdApi.MessageVideo -> with(content.video) {
                Triple(video, fileName, mimeType.takeIf { it.startsWith("video/") } ?: "video/mp4")
            }
            is TdApi.MessageDocument -> with(content.document) {
                if (content.caption?.text == EVENDRIVE_UPLOAD_CAPTION) return null
                val inferred = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase(Locale.ROOT))
                val videoMime = mimeType.takeIf { it.startsWith("video/") }
                    ?: inferred?.takeIf { it.startsWith("video/") }
                    ?: return null
                Triple(document, fileName, videoMime)
            }
            else -> return null
        }
        val size = file.size.takeIf { it > 0 } ?: file.expectedSize
        if (size <= 0) return null
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "mp4"
        return VfsNodeEntity(
            nodeId = "video:${message.chatId}:${message.id}",
            parentId = "root",
            name = name.ifBlank { "Video-${message.id}.$extension" },
            mimeType = mime,
            isDir = false,
            totalSize = size,
            lastModified = message.date * 1000L
        )
    }
}
