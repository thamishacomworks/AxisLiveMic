package com.example.axislivemic

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AxisAudioStreamer {

    private val queue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var streaming = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun start(
        ip: String,
        username: String,
        password: String,
        onStatus: (String) -> Unit
    ) {
        if (streaming) return

        Thread {
            try {
                val path = "/axis-cgi/audio/transmit.cgi"
                val url = "http://$ip$path"

                // Get Digest challenge first.
                val challengeRequest = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val authHeader =
                    client.newCall(challengeRequest)
                        .execute()
                        .use { response ->

                            if (response.code != 401) {
                                onStatus(
                                    "Authentication challenge failed: ${response.code}"
                                )
                                return@Thread
                            }

                            response.header("WWW-Authenticate")
                        }

                if (
                    authHeader == null ||
                    !authHeader.startsWith(
                        "Digest",
                        ignoreCase = true
                    )
                ) {
                    onStatus("Digest authentication not available")
                    return@Thread
                }

                val params = parseDigestHeader(authHeader)

                val realm = params["realm"]
                val nonce = params["nonce"]

                if (realm == null || nonce == null) {
                    onStatus("Invalid Digest challenge")
                    return@Thread
                }

                val qop = params["qop"]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.firstOrNull { it.equals("auth", true) }

                val opaque = params["opaque"]

                val nc = "00000001"
                val cnonce =
                    System.nanoTime().toString(16)

                val method = "POST"

                val ha1 =
                    md5("$username:$realm:$password")

                val ha2 =
                    md5("$method:$path")

                val digestResponse =
                    if (qop != null) {
                        md5(
                            "$ha1:$nonce:$nc:$cnonce:$qop:$ha2"
                        )
                    } else {
                        md5("$ha1:$nonce:$ha2")
                    }

                val authorization = buildString {

                    append("Digest ")
                    append("username=\"$username\", ")
                    append("realm=\"$realm\", ")
                    append("nonce=\"$nonce\", ")
                    append("uri=\"$path\", ")
                    append("response=\"$digestResponse\"")

                    if (qop != null) {
                        append(", qop=$qop")
                        append(", nc=$nc")
                        append(", cnonce=\"$cnonce\"")
                    }

                    opaque?.let {
                        append(", opaque=\"$it\"")
                    }

                    params["algorithm"]?.let {
                        append(", algorithm=$it")
                    }
                }

                streaming = true
                queue.clear()

                val body = object : RequestBody() {

                    override fun contentType() =
                        "audio/basic".toMediaType()

                    // Continuous stream.
                    override fun contentLength(): Long =
                        -1L

                    override fun writeTo(sink: BufferedSink) {

                        while (streaming) {

                            val data = queue.poll(
                                100,
                                TimeUnit.MILLISECONDS
                            )

                            if (data != null) {
                                sink.write(data)
                                sink.flush()
                            }
                        }
                    }
                }

                val request = Request.Builder()
                    .url(url)
                    .header(
                        "Authorization",
                        authorization
                    )
                    .header(
                        "Content-Type",
                        "audio/basic"
                    )
                    .post(body)
                    .build()

                onStatus("Audio stream starting...")

                client.newCall(request)
                    .execute()
                    .use { response ->

                        if (!response.isSuccessful) {
                            onStatus(
                                "Audio stream failed: ${response.code}"
                            )
                        } else {
                            onStatus("Audio stream stopped")
                        }
                    }

            } catch (e: Exception) {

                if (streaming) {
                    onStatus(
                        "Stream error: ${e.message}"
                    )
                }

            } finally {
                streaming = false
                queue.clear()
            }

        }.start()
    }

    fun sendPcm(pcm: ByteArray) {

        if (!streaming) return

        val mulaw =
            G711Codec.pcm16ToMuLaw(pcm)

        queue.offer(mulaw)
    }

    fun stop() {
        streaming = false
        queue.clear()
    }

    fun isStreaming(): Boolean =
        streaming

    private fun parseDigestHeader(
        header: String
    ): Map<String, String> {

        val result =
            mutableMapOf<String, String>()

        val digest =
            header.substringAfter(
                "Digest",
                ""
            ).trim()

        val regex =
            Regex("""(\w+)=("([^"]*)"|([^,\s]+))""")

        regex.findAll(digest).forEach {

            val key = it.groupValues[1]
            val quoted = it.groupValues[3]
            val plain = it.groupValues[4]

            result[key] =
                quoted.ifEmpty { plain }
        }

        return result
    }

    private fun md5(value: String): String {

        val bytes =
            MessageDigest
                .getInstance("MD5")
                .digest(
                    value.toByteArray(
                        Charsets.ISO_8859_1
                    )
                )

        return bytes.joinToString("") {
            "%02x".format(it)
        }
    }
}