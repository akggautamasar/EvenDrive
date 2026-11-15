package com.example.featherdrive.storage

import androidx.room.*

@Entity(
    tableName = "vfs_nodes",
    indices = [Index(value = ["parentId"])]
)
data class VfsNodeEntity(
    @PrimaryKey val nodeId: String,          // UUID (or "root")
    val parentId: String,                    // Parent folder nodeId
    val name: String,
    val mimeType: String,                    // DocumentsContract.Document.MIME_TYPE_DIR for folders
    val isDir: Boolean,
    val totalSize: Long,                     // Full virtual size (e.g. 8.5 GB)
    val lastModified: Long
)

@Entity(
    tableName = "telegram_parts",
    foreignKeys = [
        ForeignKey(
            entity = VfsNodeEntity::class,
            parentColumns = ["nodeId"],
            childColumns = ["nodeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["nodeId", "partIndex"], unique = true)]
)
data class TelegramPartEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nodeId: String,
    val partIndex: Int,                      // 0, 1, 2...
    val channelId: Long,
    val messageId: Long,                     // Full TDLib 64-bit message ID
    val virtualStartOffset: Long,            // Cumulative byte offset in the virtual file
    val partSize: Long                       // Actual byte length of this Telegram document
)

data class NodeWithParts(
    @Embedded val node: VfsNodeEntity,
    @Relation(parentColumn = "nodeId", entityColumn = "nodeId")
    val parts: List<TelegramPartEntity>
)

@Dao
interface VfsDao {
    @Query("SELECT * FROM vfs_nodes WHERE parentId = :parentId ORDER BY isDir DESC, name ASC")
    suspend fun getChildren(parentId: String): List<VfsNodeEntity>

    @Query("SELECT * FROM vfs_nodes WHERE nodeId = :nodeId LIMIT 1")
    suspend fun getNode(nodeId: String): VfsNodeEntity?

    @Transaction
    @Query("SELECT * FROM vfs_nodes WHERE nodeId = :nodeId LIMIT 1")
    suspend fun getNodeWithParts(nodeId: String): NodeWithParts?

    @Query("SELECT EXISTS(SELECT 1 FROM telegram_parts WHERE channelId = :channelId AND messageId = :messageId)")
    suspend fun hasMessage(channelId: Long, messageId: Long): Boolean

    @Query("UPDATE vfs_nodes SET parentId = :parentId WHERE nodeId = :nodeId")
    suspend fun moveNode(nodeId: String, parentId: String)

    @Query("SELECT * FROM vfs_nodes")
    suspend fun getAllNodes(): List<VfsNodeEntity>

    @Query("SELECT * FROM telegram_parts")
    suspend fun getAllParts(): List<TelegramPartEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: VfsNodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParts(parts: List<TelegramPartEntity>)

    @Transaction
    suspend fun saveFileWithParts(node: VfsNodeEntity, parts: List<TelegramPartEntity>) {
        insertNode(node)
        insertParts(parts)
    }

    @Query("DELETE FROM vfs_nodes WHERE nodeId = :nodeId")
    suspend fun deleteNode(nodeId: String)
}

@Database(entities = [VfsNodeEntity::class, TelegramPartEntity::class], version = 1, exportSchema = false)
abstract class VfsDatabase : RoomDatabase() {
    abstract fun vfsDao(): VfsDao
}
