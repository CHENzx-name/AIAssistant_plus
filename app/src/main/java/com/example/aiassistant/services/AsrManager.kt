package com.example.aiassistant.services

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.aiassistant.config.AppConfig
import com.iflytek.sparkchain.core.SparkChain
import com.iflytek.sparkchain.core.SparkChainConfig
import com.iflytek.sparkchain.core.asr.ASR
import com.iflytek.sparkchain.core.asr.AsrCallbacks

/**
 * 封装讯飞 SparkChain ASR 语音听写。
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

    private var mAsr: ASR? = null
    private var audioRecorder: AudioRecorderHelper? = null
    private var sdkInited = false
    private val resultBuffer = StringBuilder()

    // -------------------------------------------------------
    // SparkChain 初始化（只需一次）
    // -------------------------------------------------------
    fun initSdk() {
        if (sdkInited) return
        val config = SparkChainConfig.builder()
            .appID(AppConfig.XUNFEI_APP_ID)
            .apiKey(AppConfig.XUNFEI_API_KEY)
            .apiSecret(AppConfig.XUNFEI_API_SECRET)
        val ret = SparkChain.getInst().init(context.applicationContext, config)
        if (ret == 0) {
            sdkInited = true
            Log.d(TAG, "SparkChain SDK init OK")
        } else {
            Log.e(TAG, "SparkChain SDK init failed: $ret")
            mainHandler.post { onError("SDK 初始化失败: $ret") }
        }
    }

    // -------------------------------------------------------
    // 开始录音识别
    // -------------------------------------------------------
    fun startRecording() {
        if (!sdkInited) {
            initSdk()
            if (!sdkInited) return
        }
        resultBuffer.clear()

        val asr = ASR()
        asr.registerCallbacks(asrCallbacks)
        asr.language("zh_cn")
        asr.domain("iat")
        asr.accent("mandarin")
        asr.vinfo(true)
        asr.dwa("wpgs")
        mAsr = asr

        val ret = asr.start(count++.toString())
        if (ret != 0) {
            Log.e(TAG, "ASR start failed: $ret")
            mainHandler.post { onError("启动识别失败: $ret") }
            mAsr = null
            return
        }

        audioRecorder = AudioRecorderHelper { data ->
            mAsr?.write(data)
        }
        audioRecorder?.start()
        Log.d(TAG, "ASR started")
    }

    // -------------------------------------------------------
    // 停止录音，等待最终结果回调
    // -------------------------------------------------------
    fun stopRecording() {
        audioRecorder?.stop()
        audioRecorder = null
        mAsr?.stop(true)
        Log.d(TAG, "ASR stop requested")
    }

    // -------------------------------------------------------
    // 释放资源
    // -------------------------------------------------------
    fun release() {
        stopRecording()
        mAsr = null
    }

    // -------------------------------------------------------
    // ASR 回调（AsrCallbacks 是 interface）
    // -------------------------------------------------------
    private val asrCallbacks = object : AsrCallbacks {
        override fun onResult(asrResult: ASR.ASRResult?, o: Any?) {
            val status = asrResult?.getStatus() ?: return
            val text = asrResult.getBestMatchText() ?: return
            Log.d(TAG, "onResult status=$status text=$text")
            when (status) {
                0 -> {
                    resultBuffer.clear()
                    resultBuffer.append(text)
                }
                1 -> {
                    resultBuffer.clear()
                    resultBuffer.append(text)
                }
                2 -> {
                    resultBuffer.clear()
                    resultBuffer.append(text)
                    val final = resultBuffer.toString().trim()
                    if (final.isNotEmpty()) {
                        mainHandler.post { onResult(final) }
                    }
                    mAsr = null
                }
            }
        }

        override fun onError(asrError: ASR.ASRError?, o: Any?) {
            val msg = asrError?.getErrMsg() ?: "未知错误"
            val code = asrError?.getCode() ?: -1
            Log.e(TAG, "onError code=$code msg=$msg")
            mainHandler.post { onError("识别出错($code): $msg") }
            mAsr = null
        }

        override fun onBeginOfSpeech() {}
        override fun onEndOfSpeech() {}
    }

    companion object {
        private var count = 0
    }
}
