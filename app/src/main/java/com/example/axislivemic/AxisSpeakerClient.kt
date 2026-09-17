package com.example.axislivemic

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

class AxisSpeakerClient {

    data class VolumeInfo(
        val deviceId: String,
        val outputId: String,
        val connectionTypeId: String,
        val signalingTypeId: String,
        val channelId: Int,
        val gainValues: List<Int>,
        val currentGain: Int,
        val minGain: Double,
        val maxGain: Double
    )

    companion object {

        /*
         * AXIS gain values are dB, so a straight
         * percent -> gain mapping squeezes every audible
         * level into the top of the slider.
         *
         * Percent is treated as amplitude instead:
         *
         *   dB = maxGain + 20 * log10(percent / 100)
         *
         * 100% = maxGain, 50% = -6 dB, 25% = -12 dB.
         */
        fun percentToGain(
            volumeInfo: VolumeInfo,
            percent: Int
        ): Int {

            if (volumeInfo.gainValues.isEmpty()) {
                return volumeInfo.currentGain
            }

            val safePercent =
                percent.coerceIn(0, 100)

            if (safePercent == 0) {
                return volumeInfo.gainValues.first()
            }

            val targetGain =
                (
                        volumeInfo.maxGain +
                                20.0 *
                                log10(
                                    safePercent / 100.0
                                )
                        )
                    .coerceIn(
                        volumeInfo.minGain,
                        volumeInfo.maxGain
                    )

            return volumeInfo
                .gainValues
                .minByOrNull {
                    abs(it - targetGain)
                }
                ?: volumeInfo.gainValues.last()
        }

        /*
         * Inverse of percentToGain, used to place the
         * slider at the level the speaker is already on.
         */
        fun gainToPercent(
            volumeInfo: VolumeInfo,
            gain: Int
        ): Float {

            if (gain <= volumeInfo.minGain) {
                return 0f
            }

            return (
                    100.0 *
                            10.0.pow(
                                (
                                        gain -
                                                volumeInfo.maxGain
                                        ) / 20.0
                            )
                    )
                .toFloat()
                .coerceIn(0f, 100f)
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType =
        "application/json".toMediaType()

    /*
     * We keep the exact devices structure returned by
     * getDevicesSettings().
     *
     * When changing volume we clone this JSON and modify
     * ONLY the selected output channel gain.
     */
    @Volatile
    private var savedDevicesJson: JSONArray? = null

    /* =========================================================
       CONNECTION TEST
       ========================================================= */

    fun testConnection(
        ip: String,
        username: String,
        password: String,
        callback: (Boolean, String) -> Unit
    ) {
        Thread {
            try {

                val path =
                    "/axis-cgi/param.cgi?action=list&group=Properties.Audio.Decoder"

                val result = digestGet(
                    ip = ip,
                    path = path,
                    username = username,
                    password = password
                )

                if (result.first) {
                    callback(
                        true,
                        "Connected to AXIS speaker"
                    )
                } else {
                    callback(
                        false,
                        result.second
                    )
                }

            } catch (e: Exception) {

                callback(
                    false,
                    "Connection error: ${e.message}"
                )
            }
        }.start()
    }

    /* =========================================================
       GET VOLUME INFO
       ========================================================= */

    fun getVolumeInfo(
        ip: String,
        username: String,
        password: String,
        callback: (
            Boolean,
            VolumeInfo?,
            String
        ) -> Unit
    ) {
        Thread {
            try {

                /* -----------------------------
                   GET CURRENT SETTINGS
                   ----------------------------- */

                val settingsRequest = JSONObject()
                    .put("apiVersion", "1.0")
                    .put("context", "AxisLiveMic")
                    .put(
                        "method",
                        "getDevicesSettings"
                    )

                val settingsResult = digestPostJson(
                    ip = ip,
                    username = username,
                    password = password,
                    json = settingsRequest
                )

                if (!settingsResult.first) {
                    callback(
                        false,
                        null,
                        settingsResult.second
                    )
                    return@Thread
                }

                val settingsJson =
                    JSONObject(settingsResult.second)

                Log.d(
                    "AXIS_VOLUME",
                    "Settings response = ${settingsJson}"
                )

                if (settingsJson.has("error")) {

                    val error =
                        settingsJson.getJSONObject("error")

                    callback(
                        false,
                        null,
                        error.optString(
                            "message",
                            "Unable to read audio settings"
                        )
                    )

                    return@Thread
                }

                val devices =
                    settingsJson
                        .optJSONObject("data")
                        ?.optJSONArray("devices")

                if (
                    devices == null ||
                    devices.length() == 0
                ) {
                    callback(
                        false,
                        null,
                        "No AXIS audio devices found"
                    )
                    return@Thread
                }

                /*
                 * IMPORTANT:
                 * Save an independent copy of the EXACT
                 * structure returned by AXIS.
                 */
                savedDevicesJson =
                    JSONArray(devices.toString())

                var deviceId: String? = null
                var outputId: String? = null
                var connectionTypeId: String? = null
                var signalingTypeId: String? = null
                var channelId: Int? = null
                var currentGain: Int? = null

                /* -----------------------------
                   FIND ACTIVE OUTPUT CHANNEL
                   ----------------------------- */

                outer@
                for (d in 0 until devices.length()) {

                    val device =
                        devices.getJSONObject(d)

                    val outputs =
                        device.optJSONArray("outputs")
                            ?: continue

                    for (o in 0 until outputs.length()) {

                        val output =
                            outputs.getJSONObject(o)

                        if (
                            output.has("enabled") &&
                            !output.optBoolean(
                                "enabled",
                                true
                            )
                        ) {
                            continue
                        }

                        val selectedConnection =
                            output.optString(
                                "connectionTypeSelected",
                                ""
                            )

                        val connectionTypes =
                            output.optJSONArray(
                                "connectionTypes"
                            ) ?: continue

                        for (
                        c in 0 until connectionTypes.length()
                        ) {

                            val connection =
                                connectionTypes
                                    .getJSONObject(c)

                            val connectionId =
                                connection.optString(
                                    "id",
                                    ""
                                )

                            if (
                                selectedConnection.isNotBlank() &&
                                connectionId !=
                                selectedConnection
                            ) {
                                continue
                            }

                            val selectedSignal =
                                connection.optString(
                                    "signalingTypeSelected",
                                    ""
                                )

                            val signalingTypes =
                                connection.optJSONArray(
                                    "signalingTypes"
                                ) ?: continue

                            for (
                            s in 0 until signalingTypes.length()
                            ) {

                                val signal =
                                    signalingTypes
                                        .getJSONObject(s)

                                val signalId =
                                    signal.optString(
                                        "id",
                                        ""
                                    )

                                if (
                                    selectedSignal.isNotBlank() &&
                                    signalId != selectedSignal
                                ) {
                                    continue
                                }

                                val channels =
                                    signal.optJSONArray(
                                        "channels"
                                    ) ?: continue

                                for (
                                ch in 0 until channels.length()
                                ) {

                                    val channel =
                                        channels
                                            .getJSONObject(ch)

                                    if (!channel.has("gain")) {
                                        continue
                                    }

                                    deviceId =
                                        device.optString("id")

                                    outputId =
                                        output.optString("id")

                                    connectionTypeId =
                                        connectionId

                                    signalingTypeId =
                                        signalId

                                    channelId =
                                        channel.optInt("id")

                                    currentGain =
                                        channel.optInt("gain")

                                    break@outer
                                }
                            }
                        }
                    }
                }

                if (
                    deviceId == null ||
                    outputId == null ||
                    connectionTypeId == null ||
                    signalingTypeId == null ||
                    channelId == null ||
                    currentGain == null
                ) {
                    callback(
                        false,
                        null,
                        "Speaker output gain not found"
                    )
                    return@Thread
                }

                Log.d(
                    "AXIS_VOLUME",
                    "Output found: " +
                            "device=$deviceId " +
                            "output=$outputId " +
                            "connection=$connectionTypeId " +
                            "signal=$signalingTypeId " +
                            "channel=$channelId " +
                            "gain=$currentGain"
                )

                /* -----------------------------
                   GET SUPPORTED GAIN VALUES
                   ----------------------------- */

                val capabilityRequest =
                    JSONObject()
                        .put(
                            "apiVersion",
                            "1.0"
                        )
                        .put(
                            "context",
                            "AxisLiveMicCapabilities"
                        )
                        .put(
                            "method",
                            "getDevicesCapabilities"
                        )

                val capabilityResult =
                    digestPostJson(
                        ip = ip,
                        username = username,
                        password = password,
                        json = capabilityRequest
                    )

                if (!capabilityResult.first) {
                    callback(
                        false,
                        null,
                        capabilityResult.second
                    )
                    return@Thread
                }

                val capabilityJson =
                    JSONObject(
                        capabilityResult.second
                    )

                Log.d(
                    "AXIS_VOLUME",
                    "Capabilities = $capabilityJson"
                )

                if (capabilityJson.has("error")) {

                    val error =
                        capabilityJson.getJSONObject(
                            "error"
                        )

                    callback(
                        false,
                        null,
                        error.optString(
                            "message",
                            "Unable to read gain capabilities"
                        )
                    )

                    return@Thread
                }

                val gainValues =
                    findGainValues(
                        json = capabilityJson,
                        deviceId = deviceId,
                        outputId = outputId,
                        connectionTypeId =
                            connectionTypeId,
                        signalingTypeId =
                            signalingTypeId
                    )
                        .distinct()
                        .sorted()

                if (gainValues.isEmpty()) {
                    callback(
                        false,
                        null,
                        "Speaker gain values not found"
                    )
                    return@Thread
                }

                Log.d(
                    "AXIS_VOLUME",
                    "Supported gains = $gainValues"
                )

                callback(
                    true,
                    VolumeInfo(
                        deviceId = deviceId,
                        outputId = outputId,
                        connectionTypeId =
                            connectionTypeId,
                        signalingTypeId =
                            signalingTypeId,
                        channelId = channelId,
                        gainValues = gainValues,
                        currentGain = currentGain,
                        minGain =
                            gainValues
                                .first()
                                .toDouble(),
                        maxGain =
                            gainValues
                                .last()
                                .toDouble()
                    ),
                    "Volume ready"
                )

            } catch (e: Exception) {

                Log.e(
                    "AXIS_VOLUME",
                    "getVolumeInfo error",
                    e
                )

                callback(
                    false,
                    null,
                    "Volume error: ${e.message}"
                )
            }
        }.start()
    }

    /* =========================================================
       SET VOLUME
       ========================================================= */

    fun setVolume(
        ip: String,
        username: String,
        password: String,
        volumeInfo: VolumeInfo,
        percent: Int,
        callback: (
            Boolean,
            String
        ) -> Unit
    ) {
        Thread {
            try {

                val saved =
                    savedDevicesJson

                if (saved == null) {
                    callback(
                        false,
                        "Volume settings not loaded. Reconnect speaker."
                    )
                    return@Thread
                }

                if (volumeInfo.gainValues.isEmpty()) {
                    callback(
                        false,
                        "Speaker gain values are empty"
                    )
                    return@Thread
                }

                val safePercent =
                    percent.coerceIn(
                        0,
                        100
                    )

                /*
                 * Map UI 0-100% to one of the gain values
                 * explicitly supported by this AXIS device.
                 */
                val requestedGain =
                    percentToGain(
                        volumeInfo = volumeInfo,
                        percent = safePercent
                    )

                /*
                 * Make a new deep copy.
                 * Never modify our saved master object.
                 */
                val devicesToSend =
                    JSONArray(saved.toString())

                val changed =
                    changeOutputGain(
                        devices =
                            devicesToSend,
                        volumeInfo =
                            volumeInfo,
                        newGain =
                            requestedGain
                    )

                if (!changed) {
                    callback(
                        false,
                        "Unable to find AXIS output channel"
                    )
                    return@Thread
                }

                /*
                 * EXACT structure:
                 *
                 * {
                 *   apiVersion,
                 *   context,
                 *   method,
                 *   params: {
                 *      devices: [...]
                 *   }
                 * }
                 */
                val params =
                    JSONObject()
                        .put(
                            "devices",
                            devicesToSend
                        )

                val requestJson =
                    JSONObject()
                        .put(
                            "apiVersion",
                            "1.0"
                        )
                        .put(
                            "context",
                            "AxisLiveMicSetVolume"
                        )
                        .put(
                            "method",
                            "setDevicesSettings"
                        )
                        .put(
                            "params",
                            params
                        )

                Log.e(
                    "AXIS_VOLUME",
                    "SET REQUEST = ${requestJson}"
                )

                val result =
                    digestPostJson(
                        ip = ip,
                        username = username,
                        password = password,
                        json = requestJson
                    )

                if (!result.first) {

                    callback(
                        false,
                        result.second
                    )

                    return@Thread
                }

                Log.e(
                    "AXIS_VOLUME",
                    "SET RESPONSE = ${result.second}"
                )

                val response =
                    JSONObject(
                        result.second
                    )

                if (response.has("error")) {

                    val error =
                        response.getJSONObject(
                            "error"
                        )

                    val code =
                        error.optInt(
                            "code",
                            -1
                        )

                    val message =
                        error.optString(
                            "message",
                            "Unknown AXIS error"
                        )

                    callback(
                        false,
                        "Volume error $code: $message"
                    )

                    return@Thread
                }

                /*
                 * Request was accepted.
                 * Now read it back from C1410.
                 */
                readCurrentGain(
                    ip = ip,
                    username = username,
                    password = password,
                    volumeInfo = volumeInfo
                ) { success, actualGain ->

                    if (
                        success &&
                        actualGain != null
                    ) {

                        /*
                         * Update saved copy too so the next
                         * volume operation starts from the
                         * latest settings.
                         */
                        val updatedSaved =
                            JSONArray(saved.toString())

                        changeOutputGain(
                            devices =
                                updatedSaved,
                            volumeInfo =
                                volumeInfo,
                            newGain =
                                actualGain
                        )

                        savedDevicesJson =
                            updatedSaved

                        callback(
                            true,
                            "Volume $safePercent% | Gain $actualGain"
                        )

                    } else {

                        callback(
                            true,
                            "Volume $safePercent% | Requested gain $requestedGain"
                        )
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    "AXIS_VOLUME",
                    "setVolume error",
                    e
                )

                callback(
                    false,
                    "Volume error: ${e.message}"
                )
            }
        }.start()
    }

    /* =========================================================
       MODIFY ONLY OUTPUT GAIN
       ========================================================= */

    private fun changeOutputGain(
        devices: JSONArray,
        volumeInfo: VolumeInfo,
        newGain: Int
    ): Boolean {

        for (d in 0 until devices.length()) {

            val device =
                devices.getJSONObject(d)

            if (
                device.optString("id") !=
                volumeInfo.deviceId
            ) {
                continue
            }

            val outputs =
                device.optJSONArray("outputs")
                    ?: continue

            for (o in 0 until outputs.length()) {

                val output =
                    outputs.getJSONObject(o)

                if (
                    output.optString("id") !=
                    volumeInfo.outputId
                ) {
                    continue
                }

                val connections =
                    output.optJSONArray(
                        "connectionTypes"
                    ) ?: continue

                for (
                c in 0 until connections.length()
                ) {

                    val connection =
                        connections.getJSONObject(c)

                    if (
                        connection.optString("id") !=
                        volumeInfo.connectionTypeId
                    ) {
                        continue
                    }

                    val signals =
                        connection.optJSONArray(
                            "signalingTypes"
                        ) ?: continue

                    for (
                    s in 0 until signals.length()
                    ) {

                        val signal =
                            signals.getJSONObject(s)

                        if (
                            signal.optString("id") !=
                            volumeInfo.signalingTypeId
                        ) {
                            continue
                        }

                        val channels =
                            signal.optJSONArray(
                                "channels"
                            ) ?: continue

                        for (
                        ch in 0 until channels.length()
                        ) {

                            val channel =
                                channels
                                    .getJSONObject(ch)

                            if (
                                channel.optInt("id") ==
                                volumeInfo.channelId
                            ) {

                                /*
                                 * THIS is the only audio
                                 * value we change.
                                 */
                                channel.put(
                                    "gain",
                                    newGain
                                )

                                /*
                                 * Keep output unmuted.
                                 */
                                channel.put(
                                    "mute",
                                    false
                                )

                                return true
                            }
                        }
                    }
                }
            }
        }

        return false
    }

    /* =========================================================
       READ BACK CURRENT GAIN
       ========================================================= */

    private fun readCurrentGain(
        ip: String,
        username: String,
        password: String,
        volumeInfo: VolumeInfo,
        callback: (
            Boolean,
            Int?
        ) -> Unit
    ) {
        try {

            val request =
                JSONObject()
                    .put(
                        "apiVersion",
                        "1.0"
                    )
                    .put(
                        "context",
                        "AxisLiveMicReadback"
                    )
                    .put(
                        "method",
                        "getDevicesSettings"
                    )

            val result =
                digestPostJson(
                    ip = ip,
                    username = username,
                    password = password,
                    json = request
                )

            if (!result.first) {
                callback(
                    false,
                    null
                )
                return
            }

            val response =
                JSONObject(result.second)

            if (response.has("error")) {
                callback(
                    false,
                    null
                )
                return
            }

            val devices =
                response
                    .optJSONObject("data")
                    ?.optJSONArray("devices")
                    ?: run {
                        callback(
                            false,
                            null
                        )
                        return
                    }

            /*
             * Keep latest exact settings.
             */
            savedDevicesJson =
                JSONArray(devices.toString())

            for (d in 0 until devices.length()) {

                val device =
                    devices.getJSONObject(d)

                if (
                    device.optString("id") !=
                    volumeInfo.deviceId
                ) {
                    continue
                }

                val outputs =
                    device.optJSONArray(
                        "outputs"
                    ) ?: continue

                for (
                o in 0 until outputs.length()
                ) {

                    val output =
                        outputs.getJSONObject(o)

                    if (
                        output.optString("id") !=
                        volumeInfo.outputId
                    ) {
                        continue
                    }

                    val connections =
                        output.optJSONArray(
                            "connectionTypes"
                        ) ?: continue

                    for (
                    c in 0 until connections.length()
                    ) {

                        val connection =
                            connections
                                .getJSONObject(c)

                        if (
                            connection.optString("id") !=
                            volumeInfo.connectionTypeId
                        ) {
                            continue
                        }

                        val signals =
                            connection.optJSONArray(
                                "signalingTypes"
                            ) ?: continue

                        for (
                        s in 0 until signals.length()
                        ) {

                            val signal =
                                signals
                                    .getJSONObject(s)

                            if (
                                signal.optString("id") !=
                                volumeInfo.signalingTypeId
                            ) {
                                continue
                            }

                            val channels =
                                signal.optJSONArray(
                                    "channels"
                                ) ?: continue

                            for (
                            ch in 0 until channels.length()
                            ) {

                                val channel =
                                    channels
                                        .getJSONObject(ch)

                                if (
                                    channel.optInt("id") ==
                                    volumeInfo.channelId
                                ) {

                                    val gain =
                                        channel.optInt(
                                            "gain"
                                        )

                                    Log.d(
                                        "AXIS_VOLUME",
                                        "Readback gain = $gain"
                                    )

                                    callback(
                                        true,
                                        gain
                                    )

                                    return
                                }
                            }
                        }
                    }
                }
            }

            callback(
                false,
                null
            )

        } catch (e: Exception) {

            Log.e(
                "AXIS_VOLUME",
                "Readback error",
                e
            )

            callback(
                false,
                null
            )
        }
    }

    /* =========================================================
       FIND SUPPORTED OUTPUT GAIN VALUES
       ========================================================= */

    private fun findGainValues(
        json: JSONObject,
        deviceId: String,
        outputId: String,
        connectionTypeId: String,
        signalingTypeId: String
    ): List<Int> {

        val result =
            mutableListOf<Int>()

        val devices =
            json
                .optJSONObject("data")
                ?.optJSONArray("devices")
                ?: return result

        for (d in 0 until devices.length()) {

            val device =
                devices.getJSONObject(d)

            if (
                device.optString("id") !=
                deviceId
            ) {
                continue
            }

            val outputs =
                device.optJSONArray("outputs")
                    ?: continue

            for (o in 0 until outputs.length()) {

                val output =
                    outputs.getJSONObject(o)

                if (
                    output.optString("id") !=
                    outputId
                ) {
                    continue
                }

                val connections =
                    output.optJSONArray(
                        "connectionTypes"
                    ) ?: continue

                for (
                c in 0 until connections.length()
                ) {

                    val connection =
                        connections
                            .getJSONObject(c)

                    if (
                        connection.optString("id") !=
                        connectionTypeId
                    ) {
                        continue
                    }

                    val signals =
                        connection.optJSONArray(
                            "signalingTypes"
                        ) ?: continue

                    for (
                    s in 0 until signals.length()
                    ) {

                        val signal =
                            signals.getJSONObject(s)

                        if (
                            signal.optString("id") !=
                            signalingTypeId
                        ) {
                            continue
                        }

                        val gainValues =
                            signal.optJSONArray(
                                "gainValues"
                            ) ?: continue

                        for (
                        i in 0 until gainValues.length()
                        ) {
                            result.add(
                                gainValues.optInt(i)
                            )
                        }
                    }
                }
            }
        }

        return result
    }

    /* =========================================================
       DIGEST GET
       ========================================================= */

    private fun digestGet(
        ip: String,
        path: String,
        username: String,
        password: String
    ): Pair<Boolean, String> {

        val url =
            "http://$ip$path"

        val firstRequest =
            Request.Builder()
                .url(url)
                .get()
                .build()

        client.newCall(firstRequest)
            .execute()
            .use { firstResponse ->

                if (firstResponse.isSuccessful) {

                    return Pair(
                        true,
                        firstResponse.body
                            ?.string()
                            ?: ""
                    )
                }

                val code =
                    firstResponse.code

                val authHeader =
                    firstResponse.header(
                        "WWW-Authenticate"
                    )

                if (
                    code != 401 ||
                    authHeader == null
                ) {

                    return Pair(
                        false,
                        "HTTP error: $code"
                    )
                }

                val authorization =
                    createDigestAuthorization(
                        header =
                            authHeader,
                        username =
                            username,
                        password =
                            password,
                        method =
                            "GET",
                        uri =
                            path
                    )
                        ?: return Pair(
                            false,
                            "Digest authentication failed"
                        )

                val request =
                    Request.Builder()
                        .url(url)
                        .header(
                            "Authorization",
                            authorization
                        )
                        .get()
                        .build()

                client.newCall(request)
                    .execute()
                    .use { response ->

                        val text =
                            response.body
                                ?.string()
                                ?: ""

                        return if (
                            response.isSuccessful
                        ) {
                            Pair(
                                true,
                                text
                            )
                        } else {
                            Pair(
                                false,
                                "Authentication failed: ${response.code}"
                            )
                        }
                    }
            }
    }

    /* =========================================================
       DIGEST POST JSON
       ========================================================= */

    private fun digestPostJson(
        ip: String,
        username: String,
        password: String,
        json: JSONObject
    ): Pair<Boolean, String> {

        val path =
            "/axis-cgi/audiodevicecontrol.cgi"

        val url =
            "http://$ip$path"

        /*
         * First request obtains Digest challenge.
         */
        val challengeBody =
            "{}"
                .toRequestBody(
                    jsonMediaType
                )

        val challengeRequest =
            Request.Builder()
                .url(url)
                .post(challengeBody)
                .build()

        client.newCall(challengeRequest)
            .execute()
            .use { challengeResponse ->

                /*
                 * If authentication isn't required,
                 * send the actual JSON directly.
                 */
                if (
                    challengeResponse.isSuccessful
                ) {

                    val directBody =
                        json
                            .toString()
                            .toRequestBody(
                                jsonMediaType
                            )

                    val directRequest =
                        Request.Builder()
                            .url(url)
                            .header(
                                "Content-Type",
                                "application/json"
                            )
                            .post(directBody)
                            .build()

                    client.newCall(directRequest)
                        .execute()
                        .use { response ->

                            val text =
                                response.body
                                    ?.string()
                                    ?: ""

                            return Pair(
                                response.isSuccessful,
                                text
                            )
                        }
                }

                val code =
                    challengeResponse.code

                val authHeader =
                    challengeResponse.header(
                        "WWW-Authenticate"
                    )

                if (
                    code != 401 ||
                    authHeader == null
                ) {

                    return Pair(
                        false,
                        "HTTP error: $code"
                    )
                }

                val authorization =
                    createDigestAuthorization(
                        header =
                            authHeader,
                        username =
                            username,
                        password =
                            password,
                        method =
                            "POST",
                        uri =
                            path
                    )
                        ?: return Pair(
                            false,
                            "Digest authentication failed"
                        )

                val body =
                    json
                        .toString()
                        .toRequestBody(
                            jsonMediaType
                        )

                val request =
                    Request.Builder()
                        .url(url)
                        .header(
                            "Authorization",
                            authorization
                        )
                        .header(
                            "Content-Type",
                            "application/json"
                        )
                        .post(body)
                        .build()

                client.newCall(request)
                    .execute()
                    .use { response ->

                        val text =
                            response.body
                                ?.string()
                                ?: ""

                        return Pair(
                            response.isSuccessful,
                            text
                        )
                    }
            }
    }

    /* =========================================================
       CREATE DIGEST AUTHORIZATION
       ========================================================= */

    private fun createDigestAuthorization(
        header: String,
        username: String,
        password: String,
        method: String,
        uri: String
    ): String? {

        if (
            !header.startsWith(
                "Digest",
                ignoreCase = true
            )
        ) {
            return null
        }

        val params =
            parseDigestHeader(header)

        val realm =
            params["realm"]
                ?: return null

        val nonce =
            params["nonce"]
                ?: return null

        val opaque =
            params["opaque"]

        val algorithm =
            params["algorithm"]

        val qop =
            params["qop"]
                ?.split(",")
                ?.map {
                    it.trim()
                }
                ?.firstOrNull {
                    it.equals(
                        "auth",
                        ignoreCase = true
                    )
                }

        val nc =
            "00000001"

        val cnonce =
            System.nanoTime()
                .toString(16)

        val ha1 =
            md5(
                "$username:$realm:$password"
            )

        val ha2 =
            md5(
                "$method:$uri"
            )

        val response =
            if (qop != null) {

                md5(
                    "$ha1:$nonce:$nc:$cnonce:$qop:$ha2"
                )

            } else {

                md5(
                    "$ha1:$nonce:$ha2"
                )
            }

        return buildString {

            append("Digest ")

            append(
                "username=\"$username\", "
            )

            append(
                "realm=\"$realm\", "
            )

            append(
                "nonce=\"$nonce\", "
            )

            append(
                "uri=\"$uri\", "
            )

            append(
                "response=\"$response\""
            )

            if (qop != null) {

                append(
                    ", qop=$qop"
                )

                append(
                    ", nc=$nc"
                )

                append(
                    ", cnonce=\"$cnonce\""
                )
            }

            if (!opaque.isNullOrBlank()) {
                append(
                    ", opaque=\"$opaque\""
                )
            }

            if (!algorithm.isNullOrBlank()) {
                append(
                    ", algorithm=$algorithm"
                )
            }
        }
    }

    /* =========================================================
       PARSE DIGEST HEADER
       ========================================================= */

    private fun parseDigestHeader(
        header: String
    ): Map<String, String> {

        val result =
            mutableMapOf<String, String>()

        val digest =
            header
                .substringAfter(
                    "Digest",
                    ""
                )
                .trim()

        val regex =
            Regex(
                """(\w+)=("([^"]*)"|([^,\s]+))"""
            )

        regex.findAll(digest)
            .forEach {

                val key =
                    it.groupValues[1]

                val quoted =
                    it.groupValues[3]

                val plain =
                    it.groupValues[4]

                result[key] =
                    quoted.ifEmpty {
                        plain
                    }
            }

        return result
    }

    /* =========================================================
       MD5
       ========================================================= */

    private fun md5(
        value: String
    ): String {

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