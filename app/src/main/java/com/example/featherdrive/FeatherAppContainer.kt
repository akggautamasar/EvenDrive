package com.example.featherdrive

import android.content.Context
import androidx.room.Room
import com.example.featherdrive.storage.MultiPartTelegramFetcher
import com.example.featherdrive.storage.TelegramRangeChunkCache
import com.example.featherdrive.storage.TelegramUploadManager
import com.example.featherdrive.storage.VfsDatabase
import com.example.featherdrive.telegram.ChannelVideoSync
import com.example.featherdrive.telegram.TdlibClientManager
import com.example.featherdrive.telegram.TdlibTelegramGateway
import org.drinkless.tdlib.TdApi

/**
 * Process-wide singleton container shared between the Compose UI and DriveDocumentsProvider.
 * Ensures only one TDLib instance opens the local TDLib directory at any time.
 */
class FeatherAppContainer private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("feather_tg_prefs", Context.MODE_PRIVATE)

    val db: VfsDatabase = Room.databaseBuilder(
        appContext,
        VfsDatabase::class.java,
        "feather_vfs.db"
    ).build()

    val tdManager = TdlibClientManager(
        context = appContext,
        apiId = BuildConfig.TG_API_ID,
        apiHash = BuildConfig.TG_API_HASH
    )

    val channelVideoSync = ChannelVideoSync(tdManager, db, prefs)
    val gateway = TdlibTelegramGateway(tdManager)
    val fetcher = MultiPartTelegramFetcher(db.vfsDao(), gateway)
    val chunkCache = TelegramRangeChunkCache(fetcher)

    suspend fun getOrCreateVaultChannelId(): Long {
        val savedId = prefs.getLong("vault_channel_id", 0L)
        if (savedId != 0L) return savedId

        // Look for an existing private channel named "Feather Vault"
        val chats = tdManager.send(TdApi.GetChats(TdApi.ChatListMain(), 100))
        for (chatId in chats.chatIds) {
            val chat = runCatching { tdManager.send(TdApi.GetChat(chatId)) }.getOrNull()
            if (chat?.title == "Feather Vault" && (chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == true) {
                prefs.edit().putLong("vault_channel_id", chat.id).apply()
                return chat.id
            }
        }

        // Create a dedicated private channel if none exists yet
        val created = tdManager.send(
            TdApi.CreateNewSupergroupChat(
                "Feather Vault",
                false, // isForum
                true,  // isChannel
                "Personal zero-disk cloud storage",
                null,  // location
                0,     // messageAutoDeleteTime
                false  // forImport
            )
        )
        prefs.edit().putLong("vault_channel_id", created.id).apply()
        return created.id
    }

    suspend fun createUploadManager(): TelegramUploadManager {
        val channelId = getOrCreateVaultChannelId()
        return TelegramUploadManager(
            context = appContext,
            db = db,
            telegram = gateway,
            storageChannelId = channelId
        )
    }

    companion object {
        @Volatile
        private var instance: FeatherAppContainer? = null

        fun get(context: Context): FeatherAppContainer =
            instance ?: synchronized(this) {
                instance ?: FeatherAppContainer(context).also { instance = it }
            }
    }
}
