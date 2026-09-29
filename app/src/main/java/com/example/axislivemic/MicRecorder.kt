package com.example.axislivemic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat

class MicRecorder(
    private val context: Context
) {

    private val channelConfig =
        AudioFormat.CHANNEL_IN_MONO

    private val audioFormat =
        AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null

    private var agc: AutomaticGainControl? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    @Volatile
    private var isRecording = false

    private var recordingThread: Thread? = null

    fun startRecording(
        sampleRate: Int,
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

        /*
         * VOICE_COMMUNICATION is tuned for speech (like a
         * phone call). Some devices can't open it, so fall
         * back to the plain MIC source.
         */
        val recorder =
            createRecorder(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                minBufferSize
            ) ?: createRecorder(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                minBufferSize
            ) ?: return false

        audioRecord = recorder

        enableVoiceEffects(recorder.audioSessionId)

        Log.d(
            "AXIS_LEVEL",
            "Mic source=" +
                    (if (recorder.audioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                        "VOICE_COMMUNICATION" else "MIC") +
                    " rate=$sampleRate" +
                    " agc=${agc?.enabled == true}" +
                    " noiseSuppressor=${noiseSuppressor?.enabled == true}"
        )

        try {
            recorder.startRecording()
        } catch (e: Exception) {
            releaseEffects()
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

        releaseEffects()

        audioRecord?.release()
        audioRecord = null
    }

    private fun createRecorder(
        source: Int,
        sampleRate: Int,
        minBufferSize: Int
    ): AudioRecord? {

        return try {

            val recorder = AudioRecord(
                source,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize * 2
            )

            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                recorder
            } else {
                recorder.release()
                null
            }

        } catch (e: Exception) {
            null
        }
    }

    private fun enableVoiceEffects(sessionId: Int) {

        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)
                    ?.apply { enabled = true }
            }
        } catch (_: Exception) {
        }

        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)
                    ?.apply { enabled = true }
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseEffects() {

        try {
            agc?.release()
        } catch (_: Exception) {
        }

        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }

        agc = null
        noiseSuppressor = null
    }
}
