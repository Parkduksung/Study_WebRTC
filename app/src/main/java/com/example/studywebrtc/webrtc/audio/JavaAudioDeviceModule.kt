/*
 *  Copyright 2018 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */
package com.example.studywebrtc.webrtc.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.example.studywebrtc.webrtc.JniCommon
import java.util.concurrent.ScheduledExecutorService

/**
 * AudioDeviceModule implemented using android.media.AudioRecord as input and
 * android.media.AudioTrack as output.
 */
class JavaAudioDeviceModule private constructor(
    private val context: Context,
    private val audioManager: AudioManager,
    private val audioInput: WebRtcAudioRecord,
    private val audioOutput: WebRtcAudioTrack,
    private val inputSampleRate: Int,
    private val outputSampleRate: Int,
    private val useStereoInput: Boolean,
    private val useStereoOutput: Boolean
) : AudioDeviceModule {
    class Builder constructor(private val context: Context) {
        private var scheduler: ScheduledExecutorService? = null
        private val audioManager: AudioManager
        private var inputSampleRate: Int
        private var outputSampleRate: Int
        private var audioSource = WebRtcAudioRecord.DEFAULT_AUDIO_SOURCE
        private var audioFormat = WebRtcAudioRecord.DEFAULT_AUDIO_FORMAT
        private var audioTrackErrorCallback: AudioTrackErrorCallback? = null
        private var audioRecordErrorCallback: AudioRecordErrorCallback? = null
        private var samplesReadyCallback: SamplesReadyCallback? = null
        private var audioTrackStateCallback: AudioTrackStateCallback? = null
        private var audioRecordStateCallback: AudioRecordStateCallback? = null
        private var useHardwareAcousticEchoCanceler = isBuiltInAcousticEchoCancelerSupported
        private var useHardwareNoiseSuppressor = isBuiltInNoiseSuppressorSupported
        private var useStereoInput = false
        private var useStereoOutput = false
        private var audioAttributes: AudioAttributes? = null
        private var useLowLatency: Boolean
        private var enableVolumeLogger: Boolean

        init {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            inputSampleRate = WebRtcAudioManager.getSampleRate(audioManager)
            outputSampleRate = WebRtcAudioManager.getSampleRate(audioManager)
            useLowLatency = false
            enableVolumeLogger = true
        }

        fun setScheduler(scheduler: ScheduledExecutorService?): Builder {
            this.scheduler = scheduler
            return this
        }

        /**
         * Call this method if the default handling of querying the native sample rate shall be
         * overridden. Can be useful on some devices where the available Android APIs are known to
         * return invalid results.
         */
        fun setSampleRate(sampleRate: Int): Builder {
            inputSampleRate = sampleRate
            outputSampleRate = sampleRate
            return this
        }

        /**
         * Call this method to specifically override input sample rate.
         */
        fun setInputSampleRate(inputSampleRate: Int): Builder {
            this.inputSampleRate = inputSampleRate
            return this
        }

        /**
         * Call this method to specifically override output sample rate.
         */
        fun setOutputSampleRate(outputSampleRate: Int): Builder {
            this.outputSampleRate = outputSampleRate
            return this
        }

        /**
         * Call this to change the audio source. The argument should be one of the values from
         * android.media.MediaRecorder.AudioSource. The default is AudioSource.VOICE_COMMUNICATION.
         */
        fun setAudioSource(audioSource: Int): Builder {
            this.audioSource = audioSource
            return this
        }

        /**
         * Call this to change the audio format. The argument should be one of the values from
         * android.media.AudioFormat ENCODING_PCM_8BIT, ENCODING_PCM_16BIT or ENCODING_PCM_FLOAT.
         * Default audio data format is PCM 16 bit per sample.
         * Guaranteed to be supported by all devices.
         */
        fun setAudioFormat(audioFormat: Int): Builder {
            this.audioFormat = audioFormat
            return this
        }

        /**
         * Set a callback to retrieve errors from the AudioTrack.
         */
        fun setAudioTrackErrorCallback(audioTrackErrorCallback: AudioTrackErrorCallback?): Builder {
            this.audioTrackErrorCallback = audioTrackErrorCallback
            return this
        }

        /**
         * Set a callback to retrieve errors from the AudioRecord.
         */
        fun setAudioRecordErrorCallback(audioRecordErrorCallback: AudioRecordErrorCallback?): Builder {
            this.audioRecordErrorCallback = audioRecordErrorCallback
            return this
        }

        /**
         * Set a callback to listen to the raw audio input from the AudioRecord.
         */
        fun setSamplesReadyCallback(samplesReadyCallback: SamplesReadyCallback?): Builder {
            this.samplesReadyCallback = samplesReadyCallback
            return this
        }

        /**
         * Set a callback to retrieve information from the AudioTrack on when audio starts and stop.
         */
        fun setAudioTrackStateCallback(audioTrackStateCallback: AudioTrackStateCallback?): Builder {
            this.audioTrackStateCallback = audioTrackStateCallback
            return this
        }

        /**
         * Set a callback to retrieve information from the AudioRecord on when audio starts and stops.
         */
        fun setAudioRecordStateCallback(audioRecordStateCallback: AudioRecordStateCallback?): Builder {
            this.audioRecordStateCallback = audioRecordStateCallback
            return this
        }

        /**
         * Control if the built-in HW noise suppressor should be used or not. The default is on if it is
         * supported. It is possible to query support by calling isBuiltInNoiseSuppressorSupported().
         */
        fun setUseHardwareNoiseSuppressor(useHardwareNoiseSuppressor: Boolean): Builder {
            var useHardwareNoiseSuppressor = useHardwareNoiseSuppressor
            if (useHardwareNoiseSuppressor && !isBuiltInNoiseSuppressorSupported) {
                useHardwareNoiseSuppressor = false
            }
            this.useHardwareNoiseSuppressor = useHardwareNoiseSuppressor
            return this
        }

        /**
         * Control if the built-in HW acoustic echo canceler should be used or not. The default is on if
         * it is supported. It is possible to query support by calling
         * isBuiltInAcousticEchoCancelerSupported().
         */
        fun setUseHardwareAcousticEchoCanceler(useHardwareAcousticEchoCanceler: Boolean): Builder {
            var useHardwareAcousticEchoCanceler = useHardwareAcousticEchoCanceler
            if (useHardwareAcousticEchoCanceler && !isBuiltInAcousticEchoCancelerSupported) {
                useHardwareAcousticEchoCanceler = false
            }
            this.useHardwareAcousticEchoCanceler = useHardwareAcousticEchoCanceler
            return this
        }

        /**
         * Control if stereo input should be used or not. The default is mono.
         */
        fun setUseStereoInput(useStereoInput: Boolean): Builder {
            this.useStereoInput = useStereoInput
            return this
        }

        /**
         * Control if stereo output should be used or not. The default is mono.
         */
        fun setUseStereoOutput(useStereoOutput: Boolean): Builder {
            this.useStereoOutput = useStereoOutput
            return this
        }

        /**
         * Control if the low-latency mode should be used. The default is disabled.
         */
        fun setUseLowLatency(useLowLatency: Boolean): Builder {
            this.useLowLatency = useLowLatency
            return this
        }

        /**
         * Set custom [AudioAttributes] to use.
         */
        fun setAudioAttributes(audioAttributes: AudioAttributes?): Builder {
            this.audioAttributes = audioAttributes
            return this
        }

        /**
         * Disables the volume logger on the audio output track.
         */
        fun setEnableVolumeLogger(enableVolumeLogger: Boolean): Builder {
            this.enableVolumeLogger = enableVolumeLogger
            return this
        }

        /**
         * Construct an AudioDeviceModule based on the supplied arguments. The caller takes ownership
         * and is responsible for calling release().
         */
        fun createAudioDeviceModule(): JavaAudioDeviceModule {
            if (useHardwareNoiseSuppressor) {
            } else {
                if (isBuiltInNoiseSuppressorSupported) {
                }
            }
            if (useHardwareAcousticEchoCanceler) {
            } else {
                if (isBuiltInAcousticEchoCancelerSupported) {
                }
            }
            // Low-latency mode was introduced in API version 26, see
            // https://developer.android.com/reference/android/media/AudioTrack#PERFORMANCE_MODE_LOW_LATENCY
            val MIN_LOW_LATENCY_SDK_VERSION = 26
            if (useLowLatency && Build.VERSION.SDK_INT >= MIN_LOW_LATENCY_SDK_VERSION) {
            }
            var executor = scheduler
            if (executor == null) {
                executor = WebRtcAudioRecord.newDefaultScheduler()
            }
            val audioInput = WebRtcAudioRecord(
                context, executor, audioManager,
                audioSource, audioFormat, audioRecordErrorCallback, audioRecordStateCallback,
                samplesReadyCallback, useHardwareAcousticEchoCanceler, useHardwareNoiseSuppressor
            )
            val audioOutput = WebRtcAudioTrack(
                context, audioManager, audioAttributes, audioTrackErrorCallback,
                audioTrackStateCallback, useLowLatency, enableVolumeLogger
            )
            return JavaAudioDeviceModule(
                context, audioManager, audioInput, audioOutput,
                inputSampleRate, outputSampleRate, useStereoInput, useStereoOutput
            )
        }
    }

    /* AudioRecord */ // Audio recording error handler functions.
    enum class AudioRecordStartErrorCode {
        AUDIO_RECORD_START_EXCEPTION, AUDIO_RECORD_START_STATE_MISMATCH
    }

    interface AudioRecordErrorCallback {
        fun onWebRtcAudioRecordInitError(errorMessage: String?)
        fun onWebRtcAudioRecordStartError(
            errorCode: AudioRecordStartErrorCode?,
            errorMessage: String?
        )

        fun onWebRtcAudioRecordError(errorMessage: String?)
    }

    /** Called when audio recording starts and stops.  */
    interface AudioRecordStateCallback {
        fun onWebRtcAudioRecordStart()
        fun onWebRtcAudioRecordStop()
    }

    /**
     * Contains audio sample information.
     */
    class AudioSamples(
        /** See [AudioRecord.getAudioFormat]  */
        val audioFormat: Int,
        /** See [AudioRecord.getChannelCount]  */
        val channelCount: Int,
        /** See [AudioRecord.getSampleRate]  */
        val sampleRate: Int, val data: ByteArray
    )

    /** Called when new audio samples are ready. This should only be set for debug purposes  */
    interface SamplesReadyCallback {
        fun onWebRtcAudioRecordSamplesReady(samples: AudioSamples?)
    }

    /* AudioTrack */ // Audio playout/track error handler functions.
    enum class AudioTrackStartErrorCode {
        AUDIO_TRACK_START_EXCEPTION, AUDIO_TRACK_START_STATE_MISMATCH
    }

    interface AudioTrackErrorCallback {
        fun onWebRtcAudioTrackInitError(errorMessage: String?)
        fun onWebRtcAudioTrackStartError(
            errorCode: AudioTrackStartErrorCode?,
            errorMessage: String?
        )

        fun onWebRtcAudioTrackError(errorMessage: String?)
    }

    /** Called when audio playout starts and stops.  */
    interface AudioTrackStateCallback {
        fun onWebRtcAudioTrackStart()
        fun onWebRtcAudioTrackStop()
    }

    private val nativeLock = Any()
    private var nativeAudioDeviceModule: Long = 0
    override fun getNativeAudioDeviceModulePointer(): Long {
        synchronized(nativeLock) {
            if (nativeAudioDeviceModule == 0L) {
                nativeAudioDeviceModule = nativeCreateAudioDeviceModule(
                    context, audioManager, audioInput,
                    audioOutput, inputSampleRate, outputSampleRate, useStereoInput, useStereoOutput
                )
            }
            return nativeAudioDeviceModule
        }
    }

    override fun release() {
        synchronized(nativeLock) {
            if (nativeAudioDeviceModule != 0L) {
                JniCommon.nativeReleaseRef(nativeAudioDeviceModule)
                nativeAudioDeviceModule = 0
            }
        }
    }

    override fun setSpeakerMute(mute: Boolean) {
        WebRtcAudioTrack.setSpeakerMute(mute)
    }

    override fun setMicrophoneMute(mute: Boolean) {
        WebRtcAudioRecord.setMicrophoneMute(mute)
    }

    /**
     * Start to prefer a specific [AudioDeviceInfo] device for recording. Typically this should
     * only be used if a client gives an explicit option for choosing a physical device to record
     * from. Otherwise the best-matching device for other parameters will be used. Calling after
     * recording is started may cause a temporary interruption if the audio routing changes.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun setPreferredInputDevice(preferredInputDevice: AudioDeviceInfo?) {
        audioInput.setPreferredDevice(preferredInputDevice)
    }

    companion object {
        private const val TAG = "JavaAudioDeviceModule"
        fun builder(context: Context): Builder {
            return Builder(context)
        }

        /**
         * Returns true if the device supports built-in HW AEC, and the UUID is approved (some UUIDs can
         * be excluded).
         */
        val isBuiltInAcousticEchoCancelerSupported: Boolean
            get() = WebRtcAudioEffects.isAcousticEchoCancelerSupported()

        /**
         * Returns true if the device supports built-in HW NS, and the UUID is approved (some UUIDs can be
         * excluded).
         */
        val isBuiltInNoiseSuppressorSupported: Boolean
            get() = WebRtcAudioEffects.isNoiseSuppressorSupported()

        private external fun nativeCreateAudioDeviceModule(
            context: Context,
            audioManager: AudioManager,
            audioInput: WebRtcAudioRecord,
            audioOutput: WebRtcAudioTrack,
            inputSampleRate: Int,
            outputSampleRate: Int,
            useStereoInput: Boolean,
            useStereoOutput: Boolean
        ): Long
    }
}