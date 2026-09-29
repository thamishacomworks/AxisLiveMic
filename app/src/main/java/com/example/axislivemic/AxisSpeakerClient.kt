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
import kotlin.math.roundToInt

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
        val maxGain: Double,
        val useAudioControl: Boolean = false,
        val currentPercent: Int? = null,
        val masterMinVolume: Int = 0,
        val masterMaxVolume: Int = 100
    )

    companion object {

        /*
         * Linear across the supported gain range, the same
         * way the speaker Web UI slider positions it:
         *
         *   gain = minGain + (maxGain - minGain) * percent / 100
         *
         * so app 40% / 90% shows as 40% / 90% in the Web UI.
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
                        volumeInfo.minGain +
                                (volumeInfo.maxGain - volumeInfo.minGain) *
                                safePercent / 100.0
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

            if (volumeInfo.currentPercent != null) {
                return volumeInfo.currentPercent
                    .toFloat()
                    .coerceIn(0f, 100f)
            }

            val range =
                volumeInfo.maxGain - volumeInfo.minGain

            if (range <= 0.0) {
                return 100f
            }

            return (
                    100.0 *
                            (gain - volumeInfo.minGain) /
                            range
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

    /*
     * True when the speaker decoder accepts 16 kHz
     * "audio/axis-mulaw-128" (read in testConnection).
     */
    @Volatile
    var supportsWideband: Boolean = false
        private set

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

                    supportsWideband =
                        result.second.contains(
                            "axis-mulaw-128",
                            ignoreCase = true
                        )

                    Log.d(
                        "AXIS_AUDIO",
                        "Decoder = ${result.second.trim()} wideband=$supportsWideband"
                    )

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

                /*
                 * Control the same Output Gain the speaker's
                 * own web UI uses (Audio Device Control).
                 * That is the device's original volume.
                 */

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
                var currentlyMuted = false

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

                                    currentlyMuted =
                                        channel.optBoolean(
                                            "mute",
                                            false
                                        )

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
                                .toDouble(),
                        currentPercent =
                            if (currentlyMuted) 0
                            else null
                    ),
                    "Volume ready (speaker output gain)"
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

                val safePercent =
                    percent.coerceIn(0, 100)

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
                            requestedGain,
                        mute = safePercent == 0
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
                                actualGain,
                            mute = safePercent == 0
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
       AXIS AUDIO CONTROL SERVICE (PREFERRED)
       ========================================================= */

    private fun tryGetAudioControlVolumeInfo(
        ip: String,
        username: String,
        password: String
    ): VolumeInfo? {
        return try {
            val capabilitiesResult =
                digestPostJsonToPath(
                    ip = ip,
                    path = "/vapix/audiocontrol",
                    username = username,
                    password = password,
                    json = JSONObject()
                        .put("axac:GetControlCapabilities", JSONObject())
                )

            if (!capabilitiesResult.first) return null

            val capabilitiesJson = JSONObject(capabilitiesResult.second)
            val ranges = capabilitiesJson
                .optJSONObject("Capabilities")
                ?.optJSONObject("VolumeRanges")
                ?: return null

            val minVolume = ranges.optInt("MinValue", Int.MIN_VALUE)
            val maxVolume = ranges.optInt("MaxValue", Int.MAX_VALUE)

            if (
                minVolume == Int.MIN_VALUE ||
                maxVolume == Int.MAX_VALUE ||
                maxVolume <= minVolume
            ) return null

            val volumeResult =
                digestPostJsonToPath(
                    ip = ip,
                    path = "/vapix/audiocontrol",
                    username = username,
                    password = password,
                    json = JSONObject()
                        .put("axac:GetVolume", JSONObject())
                )

            if (!volumeResult.first) return null

            val volume = JSONObject(volumeResult.second)
                .optJSONObject("Volume")
                ?: return null

            if (!volume.has("ForegroundVolume")) return null

            val currentVolume = volume.getInt("ForegroundVolume")
            val muted = volume.optBoolean("ForegroundVolumeMute", false)

            val currentPercent =
                if (muted) 0
                else nativeVolumeToPercent(
                    currentVolume,
                    minVolume,
                    maxVolume
                )

            Log.d(
                "AXIS_VOLUME",
                "Using Audio Control: foreground=$currentVolume " +
                        "range=$minVolume..$maxVolume percent=$currentPercent"
            )

            VolumeInfo(
                deviceId = "",
                outputId = "",
                connectionTypeId = "",
                signalingTypeId = "",
                channelId = -1,
                gainValues = emptyList(),
                currentGain = currentVolume,
                minGain = minVolume.toDouble(),
                maxGain = maxVolume.toDouble(),
                useAudioControl = true,
                currentPercent = currentPercent,
                masterMinVolume = minVolume,
                masterMaxVolume = maxVolume
            )
        } catch (e: Exception) {
            Log.d(
                "AXIS_VOLUME",
                "Audio Control detection failed: ${e.message}"
            )
            null
        }
    }

    private fun setAudioControlVolume(
        ip: String,
        username: String,
        password: String,
        volumeInfo: VolumeInfo,
        percent: Int,
        callback: (Boolean, String) -> Unit
    ) {
        val safePercent = percent.coerceIn(0, 100)

        val targetVolume =
            percentToNativeVolume(
                safePercent,
                volumeInfo.masterMinVolume,
                volumeInfo.masterMaxVolume
            )

        val request =
            JSONObject()
                .put(
                    "axac:SetVolume",
                    JSONObject()
                        .put(
                            "Volume",
                            JSONObject()
                                .put("ForegroundVolume", targetVolume)
                                .put(
                                    "ForegroundVolumeMute",
                                    safePercent == 0
                                )
                        )
                )

        val result =
            digestPostJsonToPath(
                ip = ip,
                path = "/vapix/audiocontrol",
                username = username,
                password = password,
                json = request
            )

        if (!result.first) {
            callback(false, "Volume error: ${result.second}")
            return
        }

        Log.d(
            "AXIS_VOLUME",
            "Set Audio Control volume percent=$safePercent -> ${targetVolume}dB"
        )

        callback(true, "Volume $safePercent% | ${targetVolume}dB")
    }

    private fun percentToNativeVolume(
        percent: Int,
        min: Int,
        max: Int
    ): Int {
        val safe = percent.coerceIn(0, 100)

        return (
                min +
                        (max - min) * safe / 100.0
                )
            .roundToInt()
            .coerceIn(min, max)
    }

    private fun nativeVolumeToPercent(
        value: Int,
        min: Int,
        max: Int
    ): Int {
        if (max <= min) return 0

        return (
                100.0 *
                        (value - min) /
                        (max - min)
                )
            .roundToInt()
            .coerceIn(0, 100)
    }

    /* =========================================================
       MODIFY ONLY OUTPUT GAIN
       ========================================================= */

    private fun changeOutputGain(
        devices: JSONArray,
        volumeInfo: VolumeInfo,
        newGain: Int,
        mute: Boolean = false
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

                                channel.put(
                                    "mute",
                                    mute
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
        return digestPostJsonToPath(
            ip = ip,
            path = "/axis-cgi/audiodevicecontrol.cgi",
            username = username,
            password = password,
            json = json
        )
    }

    private fun digestPostJsonToPath(
        ip: String,
        path: String,
        username: String,
        password: String,
        json: JSONObject
    ): Pair<Boolean, String> {

        val url = "http://$ip$path"

        val challengeBody =
            "{}".toRequestBody(jsonMediaType)

        val challengeRequest =
            Request.Builder()
                .url(url)
                .post(challengeBody)
                .build()

        client.newCall(challengeRequest)
            .execute()
            .use { challengeResponse ->

                if (challengeResponse.isSuccessful) {
                    val directBody =
                        json.toString().toRequestBody(jsonMediaType)

                    val directRequest =
                        Request.Builder()
                            .url(url)
                            .header("Content-Type", "application/json")
                            .post(directBody)
                            .build()

                    client.newCall(directRequest)
                        .execute()
                        .use { response ->
                            return Pair(
                                response.isSuccessful,
                                response.body?.string() ?: ""
                            )
                        }
                }

                val code = challengeResponse.code
                val authHeader =
                    challengeResponse.header("WWW-Authenticate")

                if (code != 401 || authHeader == null) {
                    return Pair(false, "HTTP error: $code")
                }

                val authorization =
                    createDigestAuthorization(
                        header = authHeader,
                        username = username,
                        password = password,
                        method = "POST",
                        uri = path
                    )
                        ?: return Pair(
                            false,
                            "Digest authentication failed"
                        )

                val body =
                    json.toString().toRequestBody(jsonMediaType)

                val request =
                    Request.Builder()
                        .url(url)
                        .header("Authorization", authorization)
                        .header("Content-Type", "application/json")
                        .post(body)
                        .build()

                client.newCall(request)
                    .execute()
                    .use { response ->
                        return Pair(
                            response.isSuccessful,
                            response.body?.string() ?: ""
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