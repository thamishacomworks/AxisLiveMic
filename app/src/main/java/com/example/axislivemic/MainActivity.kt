package com.example.axislivemic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val requestMicPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestMicPermission.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }

        setContent {
            AxisLiveMicApp()
        }
    }
}

/* =========================================================
   COLORS
   ========================================================= */

private val AppBackground =
    Color(0xFF0B1220)

private val CardBackground =
    Color(0xFF151F30)

private val InputBackground =
    Color(0xFF101827)

private val PrimaryBlue =
    Color(0xFF3B82F6)

private val ConnectedGreen =
    Color(0xFF22C55E)

private val LiveRed =
    Color(0xFFEF4444)

private val TextPrimary =
    Color(0xFFF8FAFC)

private val TextSecondary =
    Color(0xFF94A3B8)

private val BorderColor =
    Color(0xFF263449)

/* =========================================================
   APP
   ========================================================= */

@Composable
fun AxisLiveMicApp() {

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PrimaryBlue,
            background = AppBackground,
            surface = CardBackground
        )
    ) {
        AxisLiveMicScreen()
    }
}

/* =========================================================
   MAIN SCREEN
   ========================================================= */

@Composable
fun AxisLiveMicScreen() {

    val context =
        androidx.compose.ui.platform.LocalContext.current

    val preferences =
        remember {
            context.getSharedPreferences(
                "axis_live_mic_settings",
                android.content.Context.MODE_PRIVATE
            )
        }

    val scope =
        rememberCoroutineScope()

    val micRecorder =
        remember {
            MicRecorder(context)
        }

    val axisSpeakerClient =
        remember {
            AxisSpeakerClient()
        }

    val audioStreamer =
        remember {
            AxisAudioStreamer()
        }
    var speakerIp by remember {
        mutableStateOf(
            preferences.getString(
                "speaker_ip",
                ""
            ) ?: ""
        )
    }

    var username by remember {
        mutableStateOf(
            preferences.getString(
                "username",
                ""
            ) ?: ""
        )
    }

    var password by remember {
        mutableStateOf(
            preferences.getString(
                "password",
                ""
            ) ?: ""
        )
    }

    var connectionStatus by remember {
        mutableStateOf("Not connected")
    }

    var speakerConnected by remember {
        mutableStateOf(false)
    }

    var connecting by remember {
        mutableStateOf(false)
    }

    var micOn by remember {
        mutableStateOf(false)
    }

    var volume by remember {
        mutableFloatStateOf(50f)
    }

    var volumeInfo by remember {
        mutableStateOf<
                AxisSpeakerClient.VolumeInfo?
                >(null)
    }

    var volumeReady by remember {
        mutableStateOf(false)
    }

    DisposableEffect(Unit) {

        onDispose {

            micRecorder.stopRecording()

            audioStreamer.stop()
        }
    }

    Surface(
        modifier =
            Modifier.fillMaxSize(),
        color =
            AppBackground
    ) {

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = 40.dp,
                        vertical = 28.dp
                    )
        ) {

            /* =========================
               HEADER
               ========================= */

            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Column(
                    modifier =
                        Modifier.weight(1f)
                ) {

                    Text(
                        text =
                            "AXIS LIVE MIC",
                        color =
                            TextPrimary,
                        fontSize =
                            30.sp,
                        fontWeight =
                            FontWeight.Bold
                    )

                    Spacer(
                        modifier =
                            Modifier.height(4.dp)
                    )

                    Text(
                        text =
                            "Network Speaker Control",
                        color =
                            TextSecondary,
                        fontSize =
                            15.sp
                    )
                }

                ConnectionBadge(
                    connected =
                        speakerConnected
                )
            }

            Spacer(
                modifier =
                    Modifier.height(24.dp)
            )

            /* =========================
               MAIN AREA
               ========================= */

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                horizontalArrangement =
                    Arrangement.spacedBy(
                        22.dp
                    )
            ) {

                /* =====================
                   LEFT SIDE
                   ===================== */

                Column(
                    modifier =
                        Modifier
                            .weight(1.15f)
                            .fillMaxHeight()
                ) {

                    ConnectionCard(
                        speakerIp =
                            speakerIp,
                        onSpeakerIpChange = {
                            speakerIp = it
                        },
                        username =
                            username,
                        onUsernameChange = {
                            username = it
                        },
                        password =
                            password,
                        onPasswordChange = {
                            password = it
                        },
                        connecting =
                            connecting,
                        connected =
                            speakerConnected,
                        onConnect = {

                            if (
                                speakerIp.isBlank() ||
                                username.isBlank() ||
                                password.isBlank()
                            ) {

                                connectionStatus =
                                    "Please enter speaker details"

                                speakerConnected =
                                    false

                                return@ConnectionCard
                            }
                            preferences.edit()
                                .putString(
                                    "speaker_ip",
                                    speakerIp.trim()
                                )
                                .putString(
                                    "username",
                                    username
                                )
                                .putString(
                                    "password",
                                    password
                                )
                                .apply()

                            connecting =
                                true

                            speakerConnected =
                                false

                            volumeReady =
                                false

                            connectionStatus =
                                "Connecting..."

                            axisSpeakerClient
                                .testConnection(
                                    ip =
                                        speakerIp.trim(),
                                    username =
                                        username,
                                    password =
                                        password
                                ) {
                                        success,
                                        message ->

                                    connecting =
                                        false

                                    speakerConnected =
                                        success

                                    connectionStatus =
                                        message

                                    if (success) {

                                        axisSpeakerClient
                                            .getVolumeInfo(
                                                ip =
                                                    speakerIp.trim(),
                                                username =
                                                    username,
                                                password =
                                                    password
                                            ) {
                                                    volumeSuccess,
                                                    info,
                                                    volumeMessage ->

                                                if (
                                                    volumeSuccess &&
                                                    info != null
                                                ) {

                                                    volumeInfo =
                                                        info

                                                    volumeReady =
                                                        true

                                                    val range =
                                                        info.maxGain -
                                                                info.minGain

                                                    volume =
                                                        if (
                                                            range > 0
                                                        ) {

                                                            (
                                                                    (
                                                                            info.currentGain -
                                                                                    info.minGain
                                                                            ) /
                                                                            range *
                                                                            100.0
                                                                    )
                                                                .toFloat()
                                                                .coerceIn(
                                                                    0f,
                                                                    100f
                                                                )

                                                        } else {

                                                            50f
                                                        }

                                                } else {

                                                    volumeReady =
                                                        false

                                                    connectionStatus =
                                                        "Connected - $volumeMessage"
                                                }
                                            }
                                    }
                                }
                        }
                    )

                    Spacer(
                        modifier =
                            Modifier.height(18.dp)
                    )

                    StatusCard(
                        connected =
                            speakerConnected,
                        micOn =
                            micOn,
                        status =
                            connectionStatus
                    )
                }

                /* =====================
                   RIGHT SIDE
                   ===================== */

                Column(
                    modifier =
                        Modifier
                            .weight(0.85f)
                            .fillMaxHeight()
                ) {

                    VolumeCard(
                        volume =
                            volume,
                        enabled =
                            speakerConnected &&
                                    volumeReady,
                        onVolumeChange = {

                            volume = it
                        },
                        onVolumeChangeFinished = {

                            val info =
                                volumeInfo

                            if (
                                speakerConnected &&
                                volumeReady &&
                                info != null
                            ) {

                                axisSpeakerClient
                                    .setVolume(
                                        ip =
                                            speakerIp.trim(),
                                        username =
                                            username,
                                        password =
                                            password,
                                        volumeInfo =
                                            info,
                                        percent =
                                            volume
                                                .roundToInt()
                                    ) {
                                            _,
                                            message ->

                                        connectionStatus =
                                            message
                                    }
                            }
                        }
                    )

                    Spacer(
                        modifier =
                            Modifier.height(18.dp)
                    )

                    MicrophoneCard(
                        modifier = Modifier.weight(1f),
                        micOn = micOn,
                        connected = speakerConnected,

                        onMicOn = {

                            if (!speakerConnected) {
                                connectionStatus = "Connect to AXIS speaker first"
                                return@MicrophoneCard
                            }

                            scope.launch {

                                connectionStatus = "Starting audio stream..."

                                audioStreamer.start(
                                    ip = speakerIp.trim(),
                                    username = username,
                                    password = password
                                ) { message ->
                                    connectionStatus = message
                                }

                                delay(500)

                                val started =
                                    micRecorder.startRecording { pcmData ->
                                        audioStreamer.sendPcm(pcmData)
                                    }

                                if (started) {

                                    micOn = true

                                    connectionStatus =
                                        "LIVE - Microphone transmitting"

                                } else {

                                    audioStreamer.stop()

                                    micOn = false

                                    connectionStatus =
                                        "Microphone failed to start"
                                }
                            }
                        },

                        onMicOff = {

                            micRecorder.stopRecording()
                            audioStreamer.stop()

                            micOn = false

                            connectionStatus =
                                "Microphone stopped"
                        }
                    )
                }
            }
        }
    }
}

/* =========================================================
   CONNECTION BADGE
   ========================================================= */

@Composable
fun ConnectionBadge(
    connected: Boolean
) {

    Surface(
        color =
            if (connected)
                ConnectedGreen.copy(
                    alpha = 0.15f
                )
            else
                Color.White.copy(
                    alpha = 0.06f
                ),
        shape =
            RoundedCornerShape(50.dp)
    ) {

        Row(
            modifier =
                Modifier.padding(
                    horizontal = 18.dp,
                    vertical = 10.dp
                ),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Box(
                modifier =
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (connected)
                                ConnectedGreen
                            else
                                TextSecondary
                        )
            )

            Spacer(
                modifier =
                    Modifier.width(9.dp)
            )

            Text(
                text =
                    if (connected)
                        "CONNECTED"
                    else
                        "OFFLINE",
                color =
                    if (connected)
                        ConnectedGreen
                    else
                        TextSecondary,
                fontSize =
                    13.sp,
                fontWeight =
                    FontWeight.Bold
            )
        }
    }
}

/* =========================================================
   CONNECTION CARD
   ========================================================= */

@Composable
fun ConnectionCard(
    speakerIp: String,
    onSpeakerIpChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    connecting: Boolean,
    connected: Boolean,
    onConnect: () -> Unit
) {

    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    CardBackground
            ),
        shape =
            RoundedCornerShape(22.dp),
        modifier =
            Modifier.fillMaxWidth()
    ) {

        Column(
            modifier =
                Modifier.padding(24.dp)
        ) {

            Text(
                text =
                    "SPEAKER CONNECTION",
                color =
                    TextSecondary,
                fontSize =
                    13.sp,
                fontWeight =
                    FontWeight.Bold,
                letterSpacing =
                    1.sp
            )

            Spacer(
                modifier =
                    Modifier.height(20.dp)
            )

            DarkTextField(
                value =
                    speakerIp,
                onValueChange =
                    onSpeakerIpChange,
                label =
                    "Speaker IP Address",
                keyboardType =
                    KeyboardType.Uri
            )

            Spacer(
                modifier =
                    Modifier.height(14.dp)
            )

            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(
                        14.dp
                    )
            ) {

                Box(
                    modifier =
                        Modifier.weight(1f)
                ) {

                    DarkTextField(
                        value =
                            username,
                        onValueChange =
                            onUsernameChange,
                        label =
                            "Username"
                    )
                }

                Box(
                    modifier =
                        Modifier.weight(1f)
                ) {

                    DarkTextField(
                        value =
                            password,
                        onValueChange =
                            onPasswordChange,
                        label =
                            "Password",
                        password =
                            true
                    )
                }
            }

            Spacer(
                modifier =
                    Modifier.height(20.dp)
            )

            Button(
                onClick =
                    onConnect,
                enabled =
                    !connecting,
                shape =
                    RoundedCornerShape(
                        14.dp
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
            ) {

                if (connecting) {

                    CircularProgressIndicator(
                        modifier =
                            Modifier.size(
                                22.dp
                            ),
                        strokeWidth =
                            2.dp,
                        color =
                            Color.White
                    )

                    Spacer(
                        modifier =
                            Modifier.width(
                                12.dp
                            )
                    )

                    Text(
                        "CONNECTING..."
                    )

                } else {

                    Text(
                        text =
                            if (connected)
                                "RECONNECT SPEAKER"
                            else
                                "CONNECT SPEAKER",
                        fontWeight =
                            FontWeight.Bold
                    )
                }
            }
        }
    }
}

/* =========================================================
   TEXT FIELD
   ========================================================= */

@Composable
fun DarkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType =
        KeyboardType.Text,
    password: Boolean = false
) {

    OutlinedTextField(
        value =
            value,
        onValueChange =
            onValueChange,
        label = {
            Text(label)
        },
        singleLine =
            true,
        keyboardOptions =
            KeyboardOptions(
                keyboardType =
                    keyboardType
            ),
        visualTransformation =
            if (password)
                PasswordVisualTransformation()
            else
                androidx.compose.ui.text.input.VisualTransformation.None,
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedTextColor =
                    TextPrimary,
                unfocusedTextColor =
                    TextPrimary,
                focusedContainerColor =
                    InputBackground,
                unfocusedContainerColor =
                    InputBackground,
                focusedBorderColor =
                    PrimaryBlue,
                unfocusedBorderColor =
                    BorderColor,
                focusedLabelColor =
                    PrimaryBlue,
                unfocusedLabelColor =
                    TextSecondary,
                cursorColor =
                    PrimaryBlue
            ),
        shape =
            RoundedCornerShape(14.dp),
        modifier =
            Modifier.fillMaxWidth()
    )
}

/* =========================================================
   VOLUME CARD
   ========================================================= */

@Composable
fun VolumeCard(
    volume: Float,
    enabled: Boolean,
    onVolumeChange: (Float) -> Unit,
    onVolumeChangeFinished: () -> Unit
) {

    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    CardBackground
            ),
        shape =
            RoundedCornerShape(22.dp),
        modifier =
            Modifier.fillMaxWidth()
    ) {

        Column(
            modifier =
                Modifier.padding(24.dp)
        ) {

            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Column(
                    modifier =
                        Modifier.weight(1f)
                ) {

                    Text(
                        text =
                            "SPEAKER VOLUME",
                        color =
                            TextSecondary,
                        fontSize =
                            13.sp,
                        fontWeight =
                            FontWeight.Bold,
                        letterSpacing =
                            1.sp
                    )

                    Spacer(
                        modifier =
                            Modifier.height(
                                6.dp
                            )
                    )

                    Text(
                        text =
                            if (enabled)
                                "Output level"
                            else
                                "Connect speaker first",
                        color =
                            TextSecondary,
                        fontSize =
                            13.sp
                    )
                }

                Text(
                    text =
                        "${volume.roundToInt()}%",
                    color =
                        if (enabled)
                            TextPrimary
                        else
                            TextSecondary,
                    fontSize =
                        30.sp,
                    fontWeight =
                        FontWeight.Bold
                )
            }

            Spacer(
                modifier =
                    Modifier.height(14.dp)
            )

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Text(
                    text = "🔈",
                    fontSize = 20.sp
                )

                Slider(
                    value =
                        volume,
                    onValueChange =
                        onVolumeChange,
                    onValueChangeFinished =
                        onVolumeChangeFinished,
                    valueRange =
                        0f..100f,
                    enabled =
                        enabled,
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(
                                horizontal =
                                    12.dp
                            )
                )

                Text(
                    text = "🔊",
                    fontSize = 20.sp
                )
            }
        }
    }
}

/* =========================================================
   MICROPHONE CARD
   ========================================================= */

@Composable
fun MicrophoneCard(
    modifier: Modifier = Modifier,
    micOn: Boolean,
    connected: Boolean,
    onMicOn: () -> Unit,
    onMicOff: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(22.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(
                        if (micOn) LiveRed.copy(alpha = 0.15f)
                        else PrimaryBlue.copy(alpha = 0.12f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "🎙", fontSize = 42.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (micOn) "● LIVE" else "MICROPHONE READY",
                color = if (micOn) LiveRed
                else if (connected) ConnectedGreen
                else TextSecondary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Button(
                    onClick = onMicOn,
                    enabled = connected && !micOn,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(68.dp)
                ) {
                    Text(
                        text = "🎙  MIC ON",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onMicOff,
                    enabled = micOn,
                    colors = ButtonDefaults.buttonColors(containerColor = LiveRed),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(68.dp)
                ) {
                    Text(
                        text = "■  MIC OFF",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/* =========================================================
   STATUS CARD
   ========================================================= */

@Composable
fun StatusCard(
    connected: Boolean,
    micOn: Boolean,
    status: String
) {

    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    CardBackground
            ),
        shape =
            RoundedCornerShape(18.dp),
        modifier =
            Modifier.fillMaxWidth()
    ) {

        Row(
            modifier =
                Modifier.padding(
                    20.dp
                ),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Box(
                modifier =
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                micOn ->
                                    LiveRed

                                connected ->
                                    ConnectedGreen

                                else ->
                                    TextSecondary
                            }
                        )
            )

            Spacer(
                modifier =
                    Modifier.width(14.dp)
            )

            Column {

                Text(
                    text =
                        "SYSTEM STATUS",
                    color =
                        TextSecondary,
                    fontSize =
                        11.sp,
                    fontWeight =
                        FontWeight.Bold
                )

                Spacer(
                    modifier =
                        Modifier.height(
                            3.dp
                        )
                )

                Text(
                    text =
                        status,
                    color =
                        TextPrimary,
                    fontSize =
                        14.sp
                )
            }
        }
    }
}
