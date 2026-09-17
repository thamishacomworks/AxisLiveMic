package com.example.axislivemic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat

class MicRecorder(
    private val context: Context
) {

    private val sampleRate = 8000

    private val channelConfig =
        AudioFormat.CHANNEL_IN_MONO

    private val audioFormat =
        AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null

    @Volatile
    private var isRecording = false

    private var recordingThread: Thread? = null

    fun startRecording(
        onAudioData: (ByteArray) -> Unit
    ): Boolean {

        if (isRecording) {
            return true
        }

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val minBufferSize =
            AudioRecord.getMinBufferSize(
                sampleRate,
                channelConfig,
                audioFormat
            )

        if (minBufferSize <= 0) {
            return false
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            minBufferSize * 2
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }

        audioRecord = recorder

        try {
            recorder.startRecording()
        } catch (e: Exception) {
            recorder.release()
            audioRecord = null
            return false
        }

        isRecording = true

        recordingThread = Thread {

            val buffer = ByteArray(minBufferSize)

            while (isRecording) {

                val bytesRead = recorder.read(
                    buffer,
                    0,
                    buffer.size
                )

                if (bytesRead > 0) {

                    val audioData =
                        buffer.copyOf(bytesRead)

                    onAudioData(audioData)
                }
            }

        }.apply {
            name = "MicRecorderThread"
            start()
        }

        return true
    }

    fun stopRecording() {

        isRecording = false

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            recordingThread?.join(500)
        } catch (_: InterruptedException) {
        }

        recordingThread = null

        audioRecord?.release()
        audioRecord = null
    }
}

