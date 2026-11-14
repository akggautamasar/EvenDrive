package com.example.featherdrive.provider

import android.os.HandlerThread
import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import com.example.featherdrive.storage.TelegramRangeChunkCache
import java.io.IOException

class DriveProxyFileCallback(
    private val nodeId: String,
    private val fileSize: Long,
    private val chunkCache: TelegramRangeChunkCache,
    private val handlerThread: HandlerThread
) : ProxyFileDescriptorCallback() {

    override fun onGetSize(): Long = fileSize

    @Throws(ErrnoException::class)
    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        return try {
            chunkCache.readRange(
                nodeId = nodeId,
                fileSize = fileSize,
                offset = offset,
                length = size,
                dest = data,
                destOffset = 0
            )
        } catch (e: IOException) {
            throw ErrnoException("onRead", OsConstants.EIO, e)
        }
    }

    @Throws(ErrnoException::class)
    override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
        throw ErrnoException("onWrite", OsConstants.EBADF)
    }

    @Throws(ErrnoException::class)
    override fun onFsync() {
        // Read-only virtual stream
    }

    override fun onRelease() {
        handlerThread.quitSafely()
    }
}
