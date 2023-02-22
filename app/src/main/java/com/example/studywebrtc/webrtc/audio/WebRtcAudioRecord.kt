/*
 *  Copyright (c) 2015 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */
package com.example.studywebrtc.webrtc.audio

import android.annotation.TargetApi
import android.content.Context
import android.media.*
import android.media.MediaRecorder.AudioSource
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import com.example.studywebrtc.webrtc.CalledByNative
import com.example.studywebrtc.webrtc.ThreadUtils
import com.example.studywebrtc.webrtc.audio.JavaAudioDeviceModule.*
import com.example.studywebrtc.webrtc.audio.WebRtcAudioEffects
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class WebRtcAudioRecord(
    context: Context?, scheduler: ScheduledExecutorService,
    audioManager: AudioManager, audioSource: Int, audioFormat: Int,
    errorCallback: AudioRecordErrorCallback?,
    stateCallback: AudioRecordStateCallback?,
    audioSamplesReadyCallback: SamplesReadyCallback?,
    isAcousticEchoCancelerSupported: Boolean, isNoiseSuppressorSupported: Boolean
) {
    private val context: Context?
    private val audioManager: AudioManager
    private val audioSource: Int
    private val audioFormat: Int
    private var nativeAudioRecord: Long = 0
    private val effects = WebRtcAudioEffects()
    private var byteBuffer: ByteBuffer? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: AudioRecordThread? = null
    private var preferredDevice: AudioDeviceInfo? = null
    private val executor: ScheduledExecutorService
    private var future: ScheduledFuture<String>? = null
    private val audioSourceMatchesRecordingSessionRef = AtomicReference<Boolean>()
    private lateinit var emptyBytes: ByteArray
    private val errorCallback: AudioRecordErrorCallback?
    private val stateCallback: AudioRecordStateCallback?
    private val audioSamplesReadyCallback: SamplesReadyCallback?

    @get:CalledByNative
    val isAcousticEchoCancelerSupported: Boolean

    @get:CalledByNative
    val isNoiseSuppressorSupported: Boolean

    /**
     * Audio thread which keeps calling ByteBuffer.read() waiting for audio
     * to be recorded. Feeds recorded data to the native counterpart as a
     * periodic sequence of callbacks using DataIsRecorded().
     * This thread uses a Process.THREAD_PRIORITY_URGENT_AUDIO priority.
     */
    private inner class AudioRecordThread(name: String?) : Thread(name) {
        @Volatile
        private var keepAlive = true
        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            assertTrue(audioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING)

            // Audio recording has started and the client is informed about it.
            doAudioRecordStateCallback(AUDIO_RECORD_START)
            val lastTime = System.nanoTime()
            var audioTimestamp: AudioTimestamp? = null
            if (Build.VERSION.SDK_INT >= 24) {
                audioTimestamp = AudioTimestamp()
            }
            while (keepAlive) {
                val bytesRead = audioRecord!!.read(byteBuffer!!, byteBuffer!!.capacity())
                if (bytesRead == byteBuffer!!.capacity()) {
                    if (microphoneMute) {
                        byteBuffer!!.clear()
                        byteBuffer!!.put(emptyBytes)
                    }
                    // It's possible we've been shut down during the read, and stopRecording() tried and
                    // failed to join this thread. To be a bit safer, try to avoid calling any native methods
                    // in case they've been unregistered after stopRecording() returned.
                    if (keepAlive) {
                        var captureTimeNs: Long = 0
                        if (Build.VERSION.SDK_INT >= 24) {
                            if (audioRecord!!.getTimestamp(
                                    audioTimestamp!!,
                                    AudioTimestamp.TIMEBASE_MONOTONIC
                                )
                                == AudioRecord.SUCCESS
                            ) {
                                captureTimeNs = audioTimestamp.nanoTime
                            }
                        }
                        nativeDataIsRecorded(nativeAudioRecord, bytesRead, captureTimeNs)
                    }
                    if (audioSamplesReadyCallback != null) {
                        // Copy the entire byte buffer array. The start of the byteBuffer is not necessarily
                        // at index 0.
                        val data = Arrays.copyOfRange(
                            byteBuffer!!.array(), byteBuffer!!.arrayOffset(),
                            byteBuffer!!.capacity() + byteBuffer!!.arrayOffset()
                        )
                        audioSamplesReadyCallback.onWebRtcAudioRecordSamplesReady(
                            AudioSamples(
                                audioRecord!!.audioFormat,
                                audioRecord!!.channelCount, audioRecord!!.sampleRate, data
                            )
                        )
                    }
                } else {
                    val errorMessage = "AudioRecord.read failed: $bytesRead"
                    if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                        keepAlive = false
                        reportWebRtcAudioRecordError(errorMessage)
                    }
                }
            }
            try {
                if (audioRecord != null) {
                    audioRecord!!.stop()
                    doAudioRecordStateCallback(AUDIO_RECORD_STOP)
                }
            } catch (e: IllegalStateException) {
            }
        }

        // Stops the inner thread loop and also calls AudioRecord.stop().
        // Does not block the calling thread.
        fun stopThread() {
            keepAlive = false
        }
    }

    @CalledByNative
    constructor(context: Context?, audioManager: AudioManager) : this(
        context, newDefaultScheduler() /* scheduler */, audioManager, DEFAULT_AUDIO_SOURCE,
        DEFAULT_AUDIO_FORMAT, null /* errorCallback */, null /* stateCallback */,
        null /* audioSamplesReadyCallback */, WebRtcAudioEffects.isAcousticEchoCancelerSupported,
        WebRtcAudioEffects.isNoiseSuppressorSupported
    )

    @CalledByNative
    fun setNativeAudioRecord(nativeAudioRecord: Long) {
        this.nativeAudioRecord = nativeAudioRecord
    }

    // Returns true if a valid call to verifyAudioConfig() has been done. Should always be
    // checked before using the returned value of isAudioSourceMatchingRecordingSession().
    @get:CalledByNative
    val isAudioConfigVerified: Boolean
        get() = audioSourceMatchesRecordingSessionRef.get() != null

    // Returns true if verifyAudioConfig() succeeds. This value is set after a specific delay when
    // startRecording() has been called. Hence, should preferably be called in combination with
    // stopRecording() to ensure that it has been set properly. `isAudioConfigVerified` is
    // enabled in WebRtcAudioRecord to ensure that the returned value is valid.
    @get:CalledByNative
    val isAudioSourceMatchingRecordingSession: Boolean
        get() = audioSourceMatchesRecordingSessionRef.get()
            ?: false

    @CalledByNative
    private fun enableBuiltInAEC(enable: Boolean): Boolean {
        return effects.setAEC(enable)
    }

    @CalledByNative
    private fun enableBuiltInNS(enable: Boolean): Boolean {
        return effects.setNS(enable)
    }

    @CalledByNative
    private fun initRecording(sampleRate: Int, channels: Int): Int {
        if (audioRecord != null) {
            reportWebRtcAudioRecordInitError("InitRecording called twice without StopRecording.")
            return -1
        }
        val bytesPerFrame = channels * getBytesPerSample(audioFormat)
        val framesPerBuffer = sampleRate / BUFFERS_PER_SECOND
        byteBuffer = ByteBuffer.allocateDirect(bytesPerFrame * framesPerBuffer)
        if (byteBuffer?.hasArray() != true) {
            reportWebRtcAudioRecordInitError("ByteBuffer does not have backing array.")
            return -1
        }
        emptyBytes = ByteArray(byteBuffer?.capacity() ?: 0)
        // Rather than passing the ByteBuffer with every callback (requiring
        // the potentially expensive GetDirectBufferAddress) we simply have the
        // the native class cache the address to the memory once.
        nativeCacheDirectBufferAddress(nativeAudioRecord, byteBuffer)

        // Get the minimum buffer size required for the successful creation of
        // an AudioRecord object, in byte units.
        // Note that this size doesn't guarantee a smooth recording under load.
        val channelConfig = channelCountToConfiguration(channels)
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            reportWebRtcAudioRecordInitError("AudioRecord.getMinBufferSize failed: $minBufferSize")
            return -1
        }

        // Use a larger buffer size than the minimum required when creating the
        // AudioRecord instance to ensure smooth recording under load. It has been
        // verified that it does not increase the actual recording latency.
        val bufferSizeInBytes = Math.max(BUFFER_SIZE_FACTOR * minBufferSize, byteBuffer?.capacity()?:0)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Use the AudioRecord.Builder class on Android M (23) and above.
                // Throws IllegalArgumentException.
                audioRecord = createAudioRecordOnMOrHigher(
                    audioSource, sampleRate, channelConfig, audioFormat, bufferSizeInBytes
                )
                audioSourceMatchesRecordingSessionRef.set(null)
                if (preferredDevice != null) {
                    setPreferredDevice(preferredDevice)
                }
            } else {
                // Use the old AudioRecord constructor for API levels below 23.
                // Throws UnsupportedOperationException.
                audioRecord = createAudioRecordOnLowerThanM(
                    audioSource, sampleRate, channelConfig, audioFormat, bufferSizeInBytes
                )
                audioSourceMatchesRecordingSessionRef.set(null)
            }
        } catch (e: IllegalArgumentException) {
            // Report of exception message is sufficient. Example: "Cannot create AudioRecord".
            reportWebRtcAudioRecordInitError(e.message)
            releaseAudioResources()
            return -1
        } catch (e: UnsupportedOperationException) {
            reportWebRtcAudioRecordInitError(e.message)
            releaseAudioResources()
            return -1
        }
        if (audioRecord == null || audioRecord!!.state != AudioRecord.STATE_INITIALIZED) {
            reportWebRtcAudioRecordInitError("Creation or initialization of audio recorder failed.")
            releaseAudioResources()
            return -1
        }
        effects.enable(audioRecord!!.audioSessionId)
        logMainParameters()
        logMainParametersExtended()
        // Check number of active recording sessions. Should be zero but we have seen conflict cases
        // and adding a log for it can help us figure out details about conflicting sessions.
        val numActiveRecordingSessions =
            logRecordingConfigurations(audioRecord, false /* verifyAudioConfig */)
        if (numActiveRecordingSessions != 0) {
            // Log the conflict as a warning since initialization did in fact succeed. Most likely, the
            // upcoming call to startRecording() will fail under these conditions.
        }
        return framesPerBuffer
    }

    /**
     * Prefer a specific [AudioDeviceInfo] device for recording. Calling after recording starts
     * is valid but may cause a temporary interruption if the audio routing changes.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    @TargetApi(Build.VERSION_CODES.M)
    fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) {
        this.preferredDevice = preferredDevice
        if (audioRecord != null) {
            if (!audioRecord!!.setPreferredDevice(preferredDevice)) {
            }
        }
    }

    @CalledByNative
    private fun startRecording(): Boolean {
        assertTrue(audioRecord != null)
        assertTrue(audioThread == null)
        try {
            audioRecord!!.startRecording()
        } catch (e: IllegalStateException) {
            reportWebRtcAudioRecordStartError(
                AudioRecordStartErrorCode.AUDIO_RECORD_START_EXCEPTION,
                "AudioRecord.startRecording failed: " + e.message
            )
            return false
        }
        if (audioRecord!!.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            reportWebRtcAudioRecordStartError(
                AudioRecordStartErrorCode.AUDIO_RECORD_START_STATE_MISMATCH,
                "AudioRecord.startRecording failed - incorrect state: "
                        + audioRecord!!.recordingState
            )
            return false
        }
        audioThread = AudioRecordThread("AudioRecordJavaThread")
        audioThread!!.start()
        scheduleLogRecordingConfigurationsTask(audioRecord)
        return true
    }

    @CalledByNative
    private fun stopRecording(): Boolean {
        assertTrue(audioThread != null)
        if (future != null) {
            if (!future!!.isDone) {
                // Might be needed if the client calls startRecording(), stopRecording() back-to-back.
                future!!.cancel(true /* mayInterruptIfRunning */)
            }
            future = null
        }
        audioThread!!.stopThread()
        if (!ThreadUtils.joinUninterruptibly(audioThread, AUDIO_RECORD_THREAD_JOIN_TIMEOUT_MS)) {
            WebRtcAudioUtils.logAudioState(TAG, context, audioManager)
        }
        audioThread = null
        effects.release()
        releaseAudioResources()
        return true
    }

    private fun logMainParameters() {}

    @TargetApi(Build.VERSION_CODES.M)
    private fun logMainParametersExtended() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        }
    }

    @TargetApi(Build.VERSION_CODES.N) // Checks the number of active recording sessions and logs the states of all active sessions.
    // Returns number of active sessions. Note that this could occur on arbituary thread.
    private fun logRecordingConfigurations(
        audioRecord: AudioRecord?,
        verifyAudioConfig: Boolean
    ): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return 0
        }
        if (audioRecord == null) {
            return 0
        }

        // Get a list of the currently active audio recording configurations of the device (can be more
        // than one). An empty list indicates there is no recording active when queried.
        val configs = audioManager.activeRecordingConfigurations
        val numActiveRecordingSessions = configs.size
        if (numActiveRecordingSessions > 0) {
            logActiveRecordingConfigs(audioRecord.audioSessionId, configs)
            if (verifyAudioConfig) {
                // Run an extra check to verify that the existing audio source doing the recording (tied
                // to the AudioRecord instance) is matching what the audio recording configuration lists
                // as its client parameters. If these do not match, recording might work but under invalid
                // conditions.
                audioSourceMatchesRecordingSessionRef.set(
                    verifyAudioConfig(
                        audioRecord.audioSource, audioRecord.audioSessionId,
                        audioRecord.format, audioRecord.routedDevice, configs
                    )
                )
            }
        }
        return numActiveRecordingSessions
    }

    private fun channelCountToConfiguration(channels: Int): Int {
        return if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
    }

    private external fun nativeCacheDirectBufferAddress(
        nativeAudioRecordJni: Long, byteBuffer: ByteBuffer?
    )

    private external fun nativeDataIsRecorded(
        nativeAudioRecordJni: Long, bytes: Int, captureTimestampNs: Long
    )

    // Releases the native AudioRecord resources.
    private fun releaseAudioResources() {
        if (audioRecord != null) {
            audioRecord!!.release()
            audioRecord = null
        }
        audioSourceMatchesRecordingSessionRef.set(null)
    }

    private fun reportWebRtcAudioRecordInitError(errorMessage: String?) {
        WebRtcAudioUtils.logAudioState(TAG, context, audioManager)
        logRecordingConfigurations(audioRecord, false /* verifyAudioConfig */)
        errorCallback?.onWebRtcAudioRecordInitError(errorMessage)
    }

    private fun reportWebRtcAudioRecordStartError(
        errorCode: AudioRecordStartErrorCode, errorMessage: String
    ) {
        WebRtcAudioUtils.logAudioState(TAG, context, audioManager)
        logRecordingConfigurations(audioRecord, false /* verifyAudioConfig */)
        errorCallback?.onWebRtcAudioRecordStartError(errorCode, errorMessage)
    }

    private fun reportWebRtcAudioRecordError(errorMessage: String) {
        WebRtcAudioUtils.logAudioState(TAG, context, audioManager)
        errorCallback?.onWebRtcAudioRecordError(errorMessage)
    }

    private fun doAudioRecordStateCallback(audioState: Int) {
        if (stateCallback != null) {
            if (audioState == AUDIO_RECORD_START) {
                stateCallback.onWebRtcAudioRecordStart()
            } else if (audioState == AUDIO_RECORD_STOP) {
                stateCallback.onWebRtcAudioRecordStop()
            } else {
            }
        }
    }

    // Use an ExecutorService to schedule a task after a given delay where the task consists of
    // checking (by logging) the current status of active recording sessions.
    private fun scheduleLogRecordingConfigurationsTask(audioRecord: AudioRecord?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return
        }
        val callable = Callable {
            if (this.audioRecord === audioRecord) {
                logRecordingConfigurations(audioRecord, true /* verifyAudioConfig */)
            } else {
            }
            "Scheduled task is done"
        }
        if (future != null && !future!!.isDone) {
            future!!.cancel(true /* mayInterruptIfRunning */)
        }
        // Schedule call to logRecordingConfigurations() from executor thread after fixed delay.
        future =
            executor.schedule(callable, CHECK_REC_STATUS_DELAY_MS.toLong(), TimeUnit.MILLISECONDS)
    }

    init {
        require(!(isAcousticEchoCancelerSupported && !WebRtcAudioEffects.isAcousticEchoCancelerSupported)) { "HW AEC not supported" }
        require(!(isNoiseSuppressorSupported && !WebRtcAudioEffects.isNoiseSuppressorSupported)) { "HW NS not supported" }
        this.context = context
        executor = scheduler
        this.audioManager = audioManager
        this.audioSource = audioSource
        this.audioFormat = audioFormat
        this.errorCallback = errorCallback
        this.stateCallback = stateCallback
        this.audioSamplesReadyCallback = audioSamplesReadyCallback
        this.isAcousticEchoCancelerSupported = isAcousticEchoCancelerSupported
        this.isNoiseSuppressorSupported = isNoiseSuppressorSupported
    }

    companion object {
        private const val TAG = "WebRtcAudioRecordExternal"

        // Requested size of each recorded buffer provided to the client.
        private const val CALLBACK_BUFFER_SIZE_MS = 10

        // Average number of callbacks per second.
        private const val BUFFERS_PER_SECOND = 1000 / CALLBACK_BUFFER_SIZE_MS

        // We ask for a native buffer size of BUFFER_SIZE_FACTOR * (minimum required
        // buffer size). The extra space is allocated to guard against glitches under
        // high load.
        private const val BUFFER_SIZE_FACTOR = 2

        // The AudioRecordJavaThread is allowed to wait for successful call to join()
        // but the wait times out afther this amount of time.
        private const val AUDIO_RECORD_THREAD_JOIN_TIMEOUT_MS: Long = 2000
        const val DEFAULT_AUDIO_SOURCE = AudioSource.VOICE_COMMUNICATION

        // Default audio data format is PCM 16 bit per sample.
        // Guaranteed to be supported by all devices.
        const val DEFAULT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Indicates AudioRecord has started recording audio.
        private const val AUDIO_RECORD_START = 0

        // Indicates AudioRecord has stopped recording audio.
        private const val AUDIO_RECORD_STOP = 1

        // Time to wait before checking recording status after start has been called. Tests have
        // shown that the result can sometimes be invalid (our own status might be missing) if we check
        // directly after start.
        private const val CHECK_REC_STATUS_DELAY_MS = 100

        @Volatile
        private var microphoneMute = false

        @TargetApi(Build.VERSION_CODES.M)
        private fun createAudioRecordOnMOrHigher(
            audioSource: Int,
            sampleRate: Int,
            channelConfig: Int,
            audioFormat: Int,
            bufferSizeInBytes: Int
        ): AudioRecord {
            return AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSizeInBytes)
                .build()
        }

        private fun createAudioRecordOnLowerThanM(
            audioSource: Int,
            sampleRate: Int,
            channelConfig: Int,
            audioFormat: Int,
            bufferSizeInBytes: Int
        ): AudioRecord {
            return AudioRecord(
                audioSource,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSizeInBytes
            )
        }

        // Helper method which throws an exception  when an assertion has failed.
        private fun assertTrue(condition: Boolean) {
            if (!condition) {
                throw AssertionError("Expected condition to be true")
            }
        }

        // Sets all recorded samples to zero if `mute` is true, i.e., ensures that
        // the microphone is muted.
        fun setMicrophoneMute(mute: Boolean) {
            microphoneMute = mute
        }

        // Reference from Android code, AudioFormat.getBytesPerSample. BitPerSample / 8
        // Default audio data format is PCM 16 bits per sample.
        // Guaranteed to be supported by all devices
        private fun getBytesPerSample(audioFormat: Int): Int {
            return when (audioFormat) {
                AudioFormat.ENCODING_PCM_8BIT -> 1
                AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_IEC61937, AudioFormat.ENCODING_DEFAULT -> 2
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                AudioFormat.ENCODING_INVALID -> throw IllegalArgumentException(
                    "Bad audio format $audioFormat"
                )
                else -> throw IllegalArgumentException("Bad audio format $audioFormat")
            }
        }

        @TargetApi(Build.VERSION_CODES.N)
        private fun logActiveRecordingConfigs(
            session: Int, configs: List<AudioRecordingConfiguration>
        ): Boolean {
            assertTrue(!configs.isEmpty())
            val it = configs.iterator()
            while (it.hasNext()) {
                val config = it.next()
                val conf = StringBuilder()
                // The audio source selected by the client.
                val audioSource = config.clientAudioSource
                conf.append("  client audio source=")
                    .append(WebRtcAudioUtils.audioSourceToString(audioSource))
                    .append(", client session id=")
                    .append(config.clientAudioSessionId) // Compare with our own id (based on AudioRecord#getAudioSessionId()).
                    .append(" (")
                    .append(session)
                    .append(")")
                    .append("\n")
                // Audio format at which audio is recorded on this Android device. Note that it may differ
                // from the client application recording format (see getClientFormat()).
                var format = config.format
                conf.append("  Device AudioFormat: ")
                    .append("channel count=")
                    .append(format.channelCount)
                    .append(", channel index mask=")
                    .append(format.channelIndexMask) // Only AudioFormat#CHANNEL_IN_MONO is guaranteed to work on all devices.
                    .append(", channel mask=")
                    .append(WebRtcAudioUtils.channelMaskToString(format.channelMask))
                    .append(", encoding=")
                    .append(WebRtcAudioUtils.audioEncodingToString(format.encoding))
                    .append(", sample rate=")
                    .append(format.sampleRate)
                    .append("\n")
                // Audio format at which the client application is recording audio.
                format = config.clientFormat
                conf.append("  Client AudioFormat: ")
                    .append("channel count=")
                    .append(format.channelCount)
                    .append(", channel index mask=")
                    .append(format.channelIndexMask) // Only AudioFormat#CHANNEL_IN_MONO is guaranteed to work on all devices.
                    .append(", channel mask=")
                    .append(WebRtcAudioUtils.channelMaskToString(format.channelMask))
                    .append(", encoding=")
                    .append(WebRtcAudioUtils.audioEncodingToString(format.encoding))
                    .append(", sample rate=")
                    .append(format.sampleRate)
                    .append("\n")
                // Audio input device used for this recording session.
                val device = config.audioDevice
                if (device != null) {
                    assertTrue(device.isSource)
                    conf.append("  AudioDevice: ")
                        .append("type=")
                        .append(WebRtcAudioUtils.deviceTypeToString(device.type))
                        .append(", id=")
                        .append(device.id)
                }
            }
            return true
        }

        // Verify that the client audio configuration (device and format) matches the requested
        // configuration (same as AudioRecord's).
        @TargetApi(Build.VERSION_CODES.N)
        private fun verifyAudioConfig(
            source: Int, session: Int, format: AudioFormat,
            device: AudioDeviceInfo, configs: List<AudioRecordingConfiguration>
        ): Boolean {
            assertTrue(!configs.isEmpty())
            val it = configs.iterator()
            while (it.hasNext()) {
                val config = it.next()
                val configDevice = config.audioDevice ?: continue
                if (config.clientAudioSource == source && config.clientAudioSessionId == session && config.clientFormat.encoding == format.encoding && config.clientFormat.sampleRate == format.sampleRate && config.clientFormat.channelMask == format.channelMask && config.clientFormat.channelIndexMask == format.channelIndexMask && config.format.encoding != AudioFormat.ENCODING_INVALID && config.format.sampleRate > 0 && (config.format.channelMask != AudioFormat.CHANNEL_INVALID || config.format.channelIndexMask) != AudioFormat.CHANNEL_INVALID
                    && checkDeviceMatch(configDevice, device)
                ) {
                    return true
                }
            }
            return false
        }

        @TargetApi(Build.VERSION_CODES.N) // Returns true if device A parameters matches those of device B.
        // TODO(henrika): can be improved by adding AudioDeviceInfo#getAddress() but it requires API 29.
        private fun checkDeviceMatch(devA: AudioDeviceInfo, devB: AudioDeviceInfo): Boolean {
            return devA.id == devB.id && devA.type == devB.type
        }

        private fun audioStateToString(state: Int): String {
            return when (state) {
                AUDIO_RECORD_START -> "START"
                AUDIO_RECORD_STOP -> "STOP"
                else -> "INVALID"
            }
        }

        private val nextSchedulerId = AtomicInteger(0)
        fun newDefaultScheduler(): ScheduledExecutorService {
            val nextThreadId = AtomicInteger(0)
            return Executors.newScheduledThreadPool(0) { r ->

                /**
                 * Constructs a new `Thread`
                 */
                val thread = Executors.defaultThreadFactory().newThread(r)
                thread.name = String.format(
                    "WebRtcAudioRecordScheduler-%s-%s",
                    nextSchedulerId.getAndIncrement(), nextThreadId.getAndIncrement()
                )
                thread
            }
        }
    }
}