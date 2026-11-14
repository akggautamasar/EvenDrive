package com.example.featherdrive.telegram

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface TelegramAuthState {
    data object Initializing : TelegramAuthState
    data object WaitPhoneNumber : TelegramAuthState
    data object WaitCode : TelegramAuthState
    data class WaitPassword(val hint: String) : TelegramAuthState
    data object Ready : TelegramAuthState
    data class Error(val message: String) : TelegramAuthState
}

class TdlibClientManager(
    private val context: Context,
    private val apiId: Int,
    private val apiHash: String
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Initializing)
    val authState: StateFlow<TelegramAuthState> = _authState

    val activeUploadStreams = ConcurrentHashMap<String, InputStream>()
    private val pendingMessageSends = ConcurrentHashMap<Long, CompletableDeferred<TdApi.Message>>()

    val client: Client = Client.create(
        { obj -> handleUpdate(obj) },
        { err -> err?.printStackTrace() },
        { err -> err?.printStackTrace() }
    )

    suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            client.send(query) { result ->
                if (!cont.isActive) return@send
                if (result is TdApi.Error) {
                    cont.resumeWithException(IOException("TDLib [${result.code}]: ${result.message}"))
                } else {
                    @Suppress("UNCHECKED_CAST")
                    cont.resume(result as T)
                }
            }
        }

    fun sendFireAndForget(query: TdApi.Function<*>) {
        client.send(query) { }
    }

    suspend fun submitPhoneNumber(phone: String) {
        send(TdApi.SetAuthenticationPhoneNumber(phone, null))
    }

    suspend fun submitAuthCode(code: String) {
        send(TdApi.CheckAuthenticationCode(code))
    }

    suspend fun submitPassword(password: String) {
        send(TdApi.CheckAuthenticationPassword(password))
    }

    fun registerPendingSend(tempMessageId: Long): CompletableDeferred<TdApi.Message> {
        val deferred = CompletableDeferred<TdApi.Message>()
        pendingMessageSends[tempMessageId] = deferred
        return deferred
    }

    private fun handleUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> onAuthStateChanged(obj.authorizationState)

            is TdApi.UpdateFileGenerationStart -> {
                ioScope.launch {
                    val stream = activeUploadStreams[obj.originalPath]
                    if (stream == null) {
                        sendFireAndForget(
                            TdApi.FinishFileGeneration(
                                obj.generationId,
                                TdApi.Error(404, "Upload stream closed")
                            )
                        )
                        return@launch
                    }
                    try {
                        File(obj.destinationPath).outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val read = stream.read(buf)
                                if (read <= 0) break
                                out.write(buf, 0, read)
                            }
                        }
                        sendFireAndForget(TdApi.FinishFileGeneration(obj.generationId, null))
                    } catch (e: Exception) {
                        sendFireAndForget(
                            TdApi.FinishFileGeneration(
                                obj.generationId,
                                TdApi.Error(500, e.message ?: "Stream error")
                            )
                        )
                    }
                }
            }

            is TdApi.UpdateFileGenerationStop -> {
                activeUploadStreams.remove(obj.originalPath)
            }

            is TdApi.UpdateMessageSendSucceeded -> {
                pendingMessageSends.remove(obj.oldMessageId)?.complete(obj.message)
            }

            is TdApi.UpdateMessageSendFailed -> {
                pendingMessageSends.remove(obj.oldMessageId)?.completeExceptionally(
                    IOException("Send failed [${obj.error.code}]: ${obj.error.message}")
                )
            }
        }
    }

    private fun onAuthStateChanged(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val baseDir = File(context.filesDir, "tdlib").apply { mkdirs() }
                ioScope.launch {
                    send(
                        TdApi.SetTdlibParameters(
                            false,
                            File(baseDir, "db").absolutePath,
                            File(baseDir, "files").absolutePath,
                            ByteArray(0),
                            false, // useFileDatabase
                            false, // useChatInfoDatabase
                            false, // useMessageDatabase
                            false, // useSecretChats
                            apiId,
                            apiHash,
                            "en",
                            Build.MODEL,
                            "Android ${Build.VERSION.RELEASE}",
                            "1.0.0"
                        )
                    )
                }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber ->
                _authState.value = TelegramAuthState.WaitPhoneNumber
            is TdApi.AuthorizationStateWaitCode ->
                _authState.value = TelegramAuthState.WaitCode
            is TdApi.AuthorizationStateWaitPassword ->
                _authState.value = TelegramAuthState.WaitPassword(state.passwordHint)
            is TdApi.AuthorizationStateReady ->
                _authState.value = TelegramAuthState.Ready
            is TdApi.AuthorizationStateClosed ->
                _authState.value = TelegramAuthState.Error("TDLib session closed")
        }
    }
}
