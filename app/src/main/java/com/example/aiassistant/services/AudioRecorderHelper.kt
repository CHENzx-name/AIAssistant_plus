package com.example.aiassistant.services

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 简单的麦克风 PCM 采集器，16 kHz / 16-bit / 单声道。
 * 每读到一帧数据就通过 onData 回调传出。
 */
class AudioRecorderHelper(private val onData: (ByteArray) -> Unit) {
    private val TAG = "AudioRecorderHelper"
    private val SAMPLE_RATE = 16000
    private val isRunning = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (isRunning.getAndSet(true)) return
        thread = Thread({
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, 4096)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
            recorder.startRecording()
            val buf = ByteArray(bufSize)
            try {
                while (isRunning.get()) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        onData(buf.copyOf(read))
                    }
                }
            } finally {
                recorder.stop()
                recorder.release()
                Log.d(TAG, "AudioRecord released")
            }
        }, "asr-mic-thread")
        thread!!.start()
    }

    fun stop() {
        isRunning.set(false)
        thread?.join(1000)
        thread = null
    }
}
