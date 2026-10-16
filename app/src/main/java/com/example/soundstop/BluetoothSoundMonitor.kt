package com.example.soundstop

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.ln
import kotlin.math.sqrt

data class SoundInputDevice(
    val id: Int,
    val name: String,
    val type: Int
) {
    val isBluetooth: Boolean
        get() = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET

    val isUsb: Boolean
        get() = type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    val connectionLabel: String
        get() = if (isBluetooth) "Bluetooth" else "USB"
}

class BluetoothSoundMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    fun availableInputs(): List<SoundInputDevice> = runCatching {
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource && it.type in supportedInputTypes }
            .map { device ->
                SoundInputDevice(
                    id = device.id,
                    name = device.productName.toString().ifBlank { deviceTypeName(device.type) },
                    type = device.type
                )
            }
            .sortedWith(compareBy<SoundInputDevice> { !it.isUsb }.thenBy { it.name })
    }.getOrDefault(emptyList())

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var sessionId = 0L

    @Volatile
    private var sessionActive = false

    private val monitorLock = Any()
    private var worker: Thread? = null

    fun start(
        thresholdDbfs: Float,
        holdDurationMs: Long,
        selectedInputId: Int = -1,
        onLevel: (Float, String) -> Unit,
        onTriggered: () -> Unit,
        onError: (String) -> Unit
    ) {
        val currentSession = synchronized(this) {
            if (sessionActive) return
            sessionActive = true
            ++sessionId
        }
        worker = Thread {
            synchronized(monitorLock) {
                if (isSessionActive(currentSession)) {
                    monitor(
                        currentSession,
                        selectedInputId,
                        thresholdDbfs,
                        holdDurationMs,
                        onLevel,
                        onTriggered,
                        onError
                    )
                }
            }
        }.apply {
            name = "BluetoothSoundMonitor"
            start()
        }
    }

    fun stop() {
        synchronized(this) {
            sessionActive = false
            ++sessionId
        }
        runCatching { recorder?.stop() }
        worker?.interrupt()
    }

    private fun monitor(
        currentSession: Long,
        selectedInputId: Int,
        thresholdDbfs: Float,
        holdDurationMs: Long,
        onLevel: (Float, String) -> Unit,
        onTriggered: () -> Unit,
        onError: (String) -> Unit
    ) {
        val previousMode = audioManager.mode
        val previousCommunicationDevice = audioManager.communicationDevice
        var changedAudioMode = false
        var changedCommunicationDevice = false
        var localRecorder: AudioRecord? = null

        try {
            var inputDevice = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.id == selectedInputId && it.isSource && it.type in supportedInputTypes }
                ?: audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.isSource && it.type in supportedInputTypes }
            if (inputDevice == null) {
                reportError(
                    currentSession,
                    onError,
                    "USBまたはBluetoothのマイクが見つかりません。接続を確認してください。"
                )
                return
            }

            val isBluetoothInput = inputDevice.type in bluetoothInputTypes
            if (isBluetoothInput) {
                val communicationDevice = audioManager.availableCommunicationDevices
                    .firstOrNull {
                        it.type == inputDevice.type &&
                            it.productName.toString() == inputDevice.productName.toString()
                    }
                    ?: audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == inputDevice.type
                    }
                if (communicationDevice == null) {
                    reportError(
                        currentSession,
                        onError,
                        "Bluetoothマイクの通話用音声経路が見つかりません。"
                    )
                    return
                }

                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                changedAudioMode = true
                if (!audioManager.setCommunicationDevice(communicationDevice)) {
                    reportError(currentSession, onError, "Bluetoothマイクへ切り替えられませんでした。")
                    return
                }
                changedCommunicationDevice = true
                val routeDeadline = SystemClock.elapsedRealtime() + COMMUNICATION_ROUTE_TIMEOUT_MS
                while (isSessionActive(currentSession) &&
                    audioManager.communicationDevice?.type != communicationDevice.type &&
                    SystemClock.elapsedRealtime() < routeDeadline
                ) {
                    Thread.sleep(50)
                }
                if (!isSessionActive(currentSession)) return
                if (audioManager.communicationDevice?.type != communicationDevice.type) {
                    reportError(
                        currentSession,
                        onError,
                        "Bluetoothの通話用音声経路を確認できません。Bluetooth設定でヘッドセットの通話を有効にしてください。"
                    )
                    return
                }

                val routedInputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .filter { it.isSource && it.type in bluetoothInputTypes }
                inputDevice = routedInputDevices.firstOrNull {
                    it.type == inputDevice.type &&
                        it.productName.toString().equals(inputDevice.productName.toString(), ignoreCase = true)
                } ?: routedInputDevices.singleOrNull { it.type == inputDevice.type } ?: inputDevice
            }

            val preferredRates = if (inputDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                listOf(8_000, 16_000, 24_000, 48_000)
            } else if (inputDevice.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                inputDevice.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                inputDevice.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            ) listOf(48_000, 44_100, 16_000) else listOf(16_000, 24_000, 48_000)
            val reportedRates = inputDevice.sampleRates.toSet()
            val candidateRates = if (reportedRates.isEmpty()) {
                preferredRates
            } else {
                preferredRates.filter { it in reportedRates } +
                    (reportedRates - preferredRates.toSet()).sorted()
            }
            for (sampleRate in candidateRates) {
                val minimumBufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minimumBufferSize <= 0) continue

                val candidateRecorder = createRecorder(inputDevice, sampleRate, minimumBufferSize)
                if (candidateRecorder != null) {
                    localRecorder = candidateRecorder
                    break
                }
            }

            var recordingRecorder: AudioRecord = localRecorder ?: run {
                reportError(
                    currentSession,
                    onError,
                    "選択したマイクを開始できませんでした。別の入力機器を選んでください。"
                )
                return
            }
            recorder = recordingRecorder
            recordingRecorder.startRecording()
            if (isBluetoothInput) {
                val routeDeadline = SystemClock.elapsedRealtime() + COMMUNICATION_ROUTE_TIMEOUT_MS
                var routedInputDevice = recordingRecorder.routedDevice
                while (isSessionActive(currentSession) &&
                    routedInputDevice?.type !in bluetoothInputTypes &&
                    SystemClock.elapsedRealtime() < routeDeadline
                ) {
                    Thread.sleep(50)
                    routedInputDevice = recordingRecorder.routedDevice
                }
                if (!isSessionActive(currentSession)) return
                if (routedInputDevice?.type !in bluetoothInputTypes) {
                    val currentInputName = routedInputDevice?.productName?.toString()
                        ?.takeIf { it.isNotBlank() } ?: "未接続"
                    reportError(
                        currentSession,
                        onError,
                        "Bluetooth音声入力へ切り替わりません（現在の入力: $currentInputName）。Bluetooth設定でヘッドセットの「通話」を有効にして再試行してください。"
                    )
                    return
                }
            }
            val sampleRate = recordingRecorder.sampleRate
            val samples = ShortArray(sampleRate / 50)
            var loudSince = 0L
            var deadObjectRecoveries = 0
            var triggeredForCurrentSound = false
            var zeroReadStartedAt = 0L

            while (isSessionActive(currentSession)) {
                val count = recordingRecorder.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                if (count == 0) {
                    if (!isSessionActive(currentSession)) break
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (zeroReadStartedAt == 0L) zeroReadStartedAt = now
                    if (now - zeroReadStartedAt >= ZERO_READ_TIMEOUT_MS) {
                        val routedDevice = recordingRecorder.routedDevice
                        val message = if (isBluetoothInput) {
                            val routedName = routedDevice?.productName?.toString()
                                ?.takeIf { it.isNotBlank() } ?: "未確認"
                            if (audioManager.isMicrophoneMute) {
                                "端末のマイクがミュートされています。クイック設定のマイクアクセスを有効にしてください。"
                            } else {
                                "Bluetoothマイクから音声が届きません（入力経路: $routedName、${sampleRate}Hz）。ヘッドセットのマイクミュートと「通話」接続を確認してください。"
                            }
                        } else {
                            "${inputDevice.productName}から音声が届きません。マイクの接続と入力機器を確認してください。"
                        }
                        reportError(currentSession, onError, message)
                        break
                    }
                    Thread.sleep(10)
                    continue
                }
                if (count < 0) {
                    if (count == AudioRecord.ERROR_DEAD_OBJECT &&
                        isSessionActive(currentSession) &&
                        deadObjectRecoveries < MAX_DEAD_OBJECT_RECOVERIES
                    ) {
                        deadObjectRecoveries++
                        recordingRecorder.release()
                        localRecorder = null
                        localRecorder = createRecorder(
                            inputDevice,
                            sampleRate
                        )
                        if (localRecorder != null) {
                            recordingRecorder = localRecorder
                            recorder = recordingRecorder
                            recordingRecorder.startRecording()
                            zeroReadStartedAt = 0L
                            continue
                        }
                        reportError(
                            currentSession,
                            onError,
                            "マイク入力が切断されました。接続を確認して計測をやり直してください。"
                        )
                    } else if (isSessionActive(currentSession)) {
                        reportError(
                            currentSession,
                            onError,
                            readErrorMessage(count)
                        )
                    }
                    break
                }
                zeroReadStartedAt = 0L
                deadObjectRecoveries = 0

                var energy = 0.0
                for (index in 0 until count) {
                    val sample = samples[index].toDouble()
                    energy += sample * sample
                }
                val rms = sqrt(energy / count)
                val levelDbfs = if (rms <= 0.0) -90f else
                    (20.0 * ln(rms / 32768.0) / ln(10.0)).toFloat().coerceAtLeast(-90f)
                val routedName = if (isBluetoothInput) {
                    audioManager.communicationDevice?.productName?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?: recordingRecorder.routedDevice?.productName?.toString()
                            ?.takeIf { it.isNotBlank() }
                        ?: "Bluetoothマイク"
                } else {
                    recordingRecorder.routedDevice?.productName?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?: inputDevice.productName.toString().ifBlank { deviceTypeName(inputDevice.type) }
                }
                mainHandler.post {
                    if (isSessionActive(currentSession)) onLevel(levelDbfs, routedName)
                }

                if (levelDbfs >= thresholdDbfs) {
                    if (loudSince == 0L) loudSince = android.os.SystemClock.elapsedRealtime()
                    if (!triggeredForCurrentSound &&
                        android.os.SystemClock.elapsedRealtime() - loudSince >= holdDurationMs
                    ) {
                        triggeredForCurrentSound = true
                        mainHandler.post {
                            if (isCurrentSession(currentSession)) onTriggered()
                        }
                    }
                } else {
                    loudSince = 0L
                    triggeredForCurrentSound = false
                }
            }
        } catch (_: SecurityException) {
            reportError(currentSession, onError, "マイクとBluetoothの権限が必要です。")
        } catch (_: Exception) {
            reportError(
                currentSession,
                onError,
                "マイク入力を開始できませんでした。接続と権限を確認してください。"
            )
        } finally {
            synchronized(this) {
                if (sessionId == currentSession) sessionActive = false
            }
            recorder = null
            runCatching { localRecorder?.stop() }
            localRecorder?.release()
            if (changedCommunicationDevice) {
                if (previousCommunicationDevice != null) {
                    audioManager.setCommunicationDevice(previousCommunicationDevice)
                } else {
                    audioManager.clearCommunicationDevice()
                }
            }
            if (changedAudioMode) audioManager.mode = previousMode
        }
    }

    private fun createRecorder(
        inputDevice: AudioDeviceInfo,
        sampleRate: Int,
        minimumBufferSize: Int = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
    ): AudioRecord? {
        if (minimumBufferSize <= 0) return null
        val candidateRecorder = runCatching {
            AudioRecord.Builder()
                .setAudioSource(
                    if (inputDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        inputDevice.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    ) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    else MediaRecorder.AudioSource.MIC
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minimumBufferSize, sampleRate / 5 * 2))
                .build()
        }.getOrNull() ?: return null

        return if (candidateRecorder.state == AudioRecord.STATE_INITIALIZED &&
            candidateRecorder.setPreferredDevice(inputDevice)
        ) {
            candidateRecorder
        } else {
            candidateRecorder.release()
            null
        }
    }

    private fun isSessionActive(currentSession: Long): Boolean =
        sessionActive && isCurrentSession(currentSession)

    private fun isCurrentSession(currentSession: Long): Boolean =
        sessionId == currentSession

    private fun reportError(currentSession: Long, onError: (String) -> Unit, message: String) {
        if (isSessionActive(currentSession)) {
            mainHandler.post {
                if (isCurrentSession(currentSession)) onError(message)
            }
        }
    }

    private fun readErrorMessage(errorCode: Int): String = when (errorCode) {
        0 -> "マイクから音声データを受け取れませんでした。接続と入力設定を確認してください。"
        AudioRecord.ERROR_BAD_VALUE ->
            "マイク入力の形式が無効です。別の入力機器を選んでください。"
        AudioRecord.ERROR_INVALID_OPERATION ->
            "マイク入力を開始できませんでした。ほかのアプリがマイクを使用していないか確認してください。"
        AudioRecord.ERROR_DEAD_OBJECT ->
            "マイク入力が切断されました。接続を確認して計測をやり直してください。"
        else -> "マイク入力を読み取れませんでした（エラーコード: $errorCode）。接続を確認してください。"
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USBマイク"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetoothマイク"
        else -> "外部マイク"
    }

    private companion object {
        const val MAX_DEAD_OBJECT_RECOVERIES = 2
        const val ZERO_READ_TIMEOUT_MS = 5_000L
        const val COMMUNICATION_ROUTE_TIMEOUT_MS = 5_000L
        val bluetoothInputTypes = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET
        )

        val supportedInputTypes = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY
        )
    }
}