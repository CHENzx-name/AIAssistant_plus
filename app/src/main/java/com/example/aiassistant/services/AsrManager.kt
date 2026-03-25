package com.example.aiassistant.services

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.example.aiassistant.config.AppConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 封装讯飞实时语音转写大模型（WebSocket 接口）。
 * 使用方式：按住时调用 startRecording()，松开时调用 stopRecording()。
 * 识别结果通过 onResult 回调在主线程返回。
 */
class AsrManager(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val TAG = "AsrManager"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()

    private var webSocket: WebSocket? = null
    private var audioRecorder: AudioRecorderHelper? = null
    private val resultBuffer = StringBuilder()
    private val audioBufferStream = ByteArrayOutputStream()
    private var sessionId = ""

    // 无需 SDK 初始化，WebSocket 接口直接连接
    fun initSdk() = Unit

    fun startRecording() {
        sessionId = UUID.randomUUID().toString()
        resultBuffer.clear()
        audioBufferStream.reset()

        val url = buildUrl()
        Log.d(TAG, "Connecting: $url")
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, wsListener)
    }

    fun stopRecording() {
        audioRecorder?.stop()
        audioRecorder = null

        // 把缓冲区剩余音频发完
        synchronized(audioBufferStream) {
            val remaining = audioBufferStream.toByteArray()
            audioBufferStream.reset()
            if (remaining.isNotEmpty()) {
                webSocket?.send(remaining.toByteString())
            }
        }
        // 发送结束标识
        val endMsg = JSONObject()
            .put("end", true)
            .put("sessionId", sessionId)
            .toString()
        webSocket?.send(endMsg)
        Log.d(TAG, "ASR stop requested")
    }

    fun release() {
        stopRecording()
        webSocket?.close(1000, null)
        webSocket = null
    }

    // -------------------------------------------------------
    // 构造鉴权 URL
    // -------------------------------------------------------
    private fun buildUrl(): String {
        val appId        = AppConfig.XUNFEI_APP_ID
        val accessKeyId  = AppConfig.XUNFEI_API_KEY
        val accessKeySecret = AppConfig.XUNFEI_API_SECRET
        val utc  = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXX"))
        val uuid = UUID.randomUUID().toString()

        // 按 key 升序排列（签名要求）
        val params = sortedMapOf(
            "accessKeyId" to accessKeyId,
            "appId"       to appId,
            "audio_encode" to "pcm_s16le",
            "lang"        to "autodialect",
            "samplerate"  to "16000",
            "utc"         to utc,
            "uuid"        to uuid
        )

        val baseString = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val signature = hmacSha1Base64(accessKeySecret, baseString)

        val query = baseString + "&signature=${URLEncoder.encode(signature, "UTF-8")}"
        return "wss://office-api-ast-dx.iflyaisol.com/ast/communicate/v1?$query"
    }

    private fun hmacSha1Base64(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return Base64.encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    // -------------------------------------------------------
    // WebSocket 监听
    // -------------------------------------------------------
    private val wsListener = object : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket opened")
            audioRecorder = AudioRecorderHelper { data ->
                synchronized(audioBufferStream) {
                    audioBufferStream.write(data)
                    // 每 1280 字节发一帧（约 40ms）
                    while (audioBufferStream.size() >= CHUNK_SIZE) {
                        val all   = audioBufferStream.toByteArray()
                        val chunk = all.copyOf(CHUNK_SIZE)
                        ws.send(chunk.toByteString())
                        audioBufferStream.reset()
                        audioBufferStream.write(all, CHUNK_SIZE, all.size - CHUNK_SIZE)
                    }
                }
            }
            audioRecorder?.start()
        }

        override fun onMessage(ws: WebSocket, text: String) {
            Log.d(TAG, "WS msg: $text")
            try {
                val json = JSONObject(text)
                if (json.optString("msg_type") != "result") return
                if (json.optString("res_type") != "asr")    return

                val data = json.optJSONObject("data") ?: return
                val ls   = data.optBoolean("ls", false)
                val st   = data.optJSONObject("cn")?.optJSONObject("st") ?: return
                val type = st.optString("type")
                val rt   = st.optJSONArray("rt")    ?: return

                val sb = StringBuilder()
                for (i in 0 until rt.length()) {
                    val wsList = rt.getJSONObject(i).optJSONArray("ws") ?: continue
                    for (j in 0 until wsList.length()) {
                        val cwList = wsList.getJSONObject(j).optJSONArray("cw") ?: continue
                        for (k in 0 until cwList.length()) {
                            val cw = cwList.getJSONObject(k)
                            if (cw.optString("wp") != "s") {  // 跳过顺滑词
                                sb.append(cw.optString("w"))
                            }
                        }
                    }
                }

                // type=0 确定性结果，追加到缓冲区
                if (type == "0") {
                    resultBuffer.append(sb)
                }

                // ls=true 时为最后一帧，上报最终结果
                if (ls) {
                    val final = resultBuffer.toString().trim()
                    if (final.isNotEmpty()) {
                        mainHandler.post { onResult(final) }
                    }
                    resultBuffer.clear()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Parse error: $e")
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WS failure: $t response=${response?.code}")
            mainHandler.post { onError("连接失败: ${t.message}") }
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WS closing: $code $reason")
            ws.close(1000, null)
        }
    }

    companion object {
        private const val CHUNK_SIZE = 1280
    }
}
