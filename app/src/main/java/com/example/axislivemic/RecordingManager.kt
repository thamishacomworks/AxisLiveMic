package com.example.axislivemic

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingManager(
    private val context: Context
) {

    private var tempFile: File? = null
    private var output: RandomAccessFile? = null
    private var sampleRate = 8000

    fun startRecording(
        sampleRate: Int = 8000,
        recordingName: String = ""
    ): Boolean {

        return try {

            this.sampleRate = sampleRate

            val folder = File(
                context.filesDir,
                "recordings"
            )

            if (!folder.exists()) {
                folder.mkdirs()
            }

            // User entered name
            val cleanName = recordingName
                .trim()
                .replace(Regex("""[\\/:*?"<>|]"""), "_")

            // If name is empty, use automatic name
            val baseName =
                if (cleanName.isNotBlank()) {
                    cleanName
                } else {
                    "Recording_" +
                            SimpleDateFormat(
                                "yyyy-MM-dd_HH-mm-ss",
                                Locale.getDefault()
                            ).format(Date())
                }

            // Do not overwrite an existing recording
            var file = File(
                folder,
                "$baseName.wav"
            )

            var number = 2

            while (file.exists()) {

                file = File(
                    folder,
                    "${baseName}_$number.wav"
                )

                number++
            }

            val raf =
                RandomAccessFile(
                    file,
                    "rw"
                )

            // Reserve 44 bytes for WAV header
            raf.setLength(0)
            raf.write(ByteArray(44))

            tempFile = file
            output = raf

            true

        } catch (e: Exception) {
            false
        }
    }

    fun writePcm(data: ByteArray) {
        try {
            output?.write(data)
        } catch (_: Exception) {
        }
    }

    fun stopRecording(): File? {

        val file = tempFile
        val raf = output

        try {
            if (file != null && raf != null) {

                val dataSize =
                    raf.length() - 44

                raf.seek(0)

                writeWavHeader(
                    raf = raf,
                    dataSize = dataSize,
                    sampleRate = sampleRate,
                    channels = 1,
                    bitsPerSample = 16
                )
            }
        } catch (_: Exception) {
        }

        try {
            raf?.close()
        } catch (_: Exception) {
        }

        output = null
        tempFile = null

        return file
    }

    fun getRecordings(): List<File> {

        val folder =
            File(
                context.filesDir,
                "recordings"
            )

        if (!folder.exists()) {
            return emptyList()
        }

        return folder
            .listFiles()
            ?.filter {
                it.isFile &&
                        it.extension.equals(
                            "wav",
                            ignoreCase = true
                        )
            }
            ?.sortedByDescending {
                it.lastModified()
            }
            ?: emptyList()
    }

    fun deleteRecording(file: File): Boolean {
        return file.delete()
    }

    private fun writeWavHeader(
        raf: RandomAccessFile,
        dataSize: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {

        val byteRate =
            sampleRate *
                    channels *
                    bitsPerSample / 8

        val totalDataLen =
            dataSize + 36

        raf.writeBytes("RIFF")
        writeIntLE(raf, totalDataLen.toInt())

        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")

        writeIntLE(raf, 16)
        writeShortLE(raf, 1)
        writeShortLE(raf, channels)
        writeIntLE(raf, sampleRate)
        writeIntLE(raf, byteRate)

        writeShortLE(
            raf,
            channels * bitsPerSample / 8
        )

        writeShortLE(
            raf,
            bitsPerSample
        )

        raf.writeBytes("data")
        writeIntLE(raf, dataSize.toInt())
    }

    private fun writeIntLE(
        raf: RandomAccessFile,
        value: Int
    ) {
        raf.write(value and 0xFF)
        raf.write((value shr 8) and 0xFF)
        raf.write((value shr 16) and 0xFF)
        raf.write((value shr 24) and 0xFF)
    }

    private fun writeShortLE(
        raf: RandomAccessFile,
        value: Int
    ) {
        raf.write(value and 0xFF)
        raf.write((value shr 8) and 0xFF)
    }
}
