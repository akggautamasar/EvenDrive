package com.example.featherdrive.storage

import org.json.JSONArray
import org.json.JSONObject

object ChannelIndexSync {

    fun exportToJson(nodes: List<VfsNodeEntity>, parts: List<TelegramPartEntity>): ByteArray {
        val root = JSONObject().apply {
            put("version", 1)
            put("updatedAt", System.currentTimeMillis())
            put("nodes", JSONArray().apply {
                nodes.forEach { n ->
                    put(JSONObject().apply {
                        put("nodeId", n.nodeId)
                        put("parentId", n.parentId)
                        put("name", n.name)
                        put("mimeType", n.mimeType)
                        put("isDir", n.isDir)
                        put("totalSize", n.totalSize)
                        put("lastModified", n.lastModified)
                    })
                }
            })
            put("parts", JSONArray().apply {
                parts.forEach { p ->
                    put(JSONObject().apply {
                        put("nodeId", p.nodeId)
                        put("partIndex", p.partIndex)
                        put("channelId", p.channelId)
                        put("messageId", p.messageId)
                        put("virtualStartOffset", p.virtualStartOffset)
                        put("partSize", p.partSize)
                    })
                }
            })
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    suspend fun importFromJson(jsonBytes: ByteArray, vfsDao: VfsDao) {
        val root = JSONObject(String(jsonBytes, Charsets.UTF_8))
        val nodesArr = root.getJSONArray("nodes")
        val partsArr = root.getJSONArray("parts")

        val nodes = buildList {
            for (i in 0 until nodesArr.length()) {
                val o = nodesArr.getJSONObject(i)
                add(
                    VfsNodeEntity(
                        nodeId = o.getString("nodeId"),
                        parentId = o.getString("parentId"),
                        name = o.getString("name"),
                        mimeType = o.getString("mimeType"),
                        isDir = o.getBoolean("isDir"),
                        totalSize = o.getLong("totalSize"),
                        lastModified = o.getLong("lastModified")
                    )
                )
            }
        }

        val parts = buildList {
            for (i in 0 until partsArr.length()) {
                val o = partsArr.getJSONObject(i)
                add(
                    TelegramPartEntity(
                        nodeId = o.getString("nodeId"),
                        partIndex = o.getInt("partIndex"),
                        channelId = o.getLong("channelId"),
                        messageId = o.getLong("messageId"),
                        virtualStartOffset = o.getLong("virtualStartOffset"),
                        partSize = o.getLong("partSize")
                    )
                )
            }
        }

        nodes.forEach { vfsDao.insertNode(it) }
        vfsDao.insertParts(parts)
    }
}
