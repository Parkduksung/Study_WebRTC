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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import com.example.studywebrtc.webrtc.CalledByNative
import com.example.studywebrtc.webrtc.ThreadUtils
import com.example.studywebrtc.webrtc.audio.JavaAudioDeviceModule.*
import java.nio.ByteBuffer

internal class WebRtcAudioTrack(
    context: Context, audioManager: AudioManager,
    audioAttributes: AudioAttributes?, errorCallback: AudioTrackErrorCallback?,
    stateCallback: AudioTrackStateCallback?, useLowLatency: Boolean,
    enableVolumeLogger: Boolean
) {
    private var nativeAudioTrack: Long = 0
    private lateinit var context: Context
    private val audioManager: AudioManager
    private val threadChecker = ThreadUtils.ThreadChecker()
    private var byteBuffer: ByteBuffer? = null
    private val audioAttributes: AudioAttributes?
    private var audioTrack: AudioTrack? = null
    private var audioThread: AudioTrackThread? = null
    private val volumeLogger: VolumeLogger?
    private lateinit var emptyBytes: ByteArray
    private var useLowLatency: Boolean

    @get:CalledByNative
    private var initialBufferSizeInFrames = 0
    private val errorCallback: AudioTrackErrorCallback?
    private val stateCallback: AudioTrackStateCallback?

    /**
     * Audio thread which keeps calling AudioTrack.write() to stream audio.
     * Data is periodically acquired from the native WebRTC layer using the
     * nativeGetPlayoutData callback function.
     * This thread uses a Process.THREAD_PRIORITY_URGENT_AUDIO priority.
     */
    private inner class AudioTrackThread(name: String?) : Thread(name) {
        @Volatile
        private var keepAlive = true
        private val bufferManager: LowLatencyAudioBufferManager

        init {
            bufferManager = LowLatencyAudioBufferManager()
        }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            assertTrue(audioTrack!!.playState == AudioTrack.PLAYSTATE_PLAYING)

            // Audio playout has started and the client is informed about it.
            doAudioTrackStateCallback(AUDIO_TRACK_START)

            // Fixed size in bytes of each 10ms block of audio data that we ask for
            // using callbacks to the native WebRTC client.
            val sizeInBytes = byteBuffer!!.capacity()
            while (keepAlive) {
                // Get 10ms of PCM data from the native WebRTC client. Audio data is
                // written into the common ByteBuffer using the address that was
                // cached at construction.
                //RM-change code start
                try {
                    nativeGetPlayoutData(nativeAudioTrack, sizeInBytes)
                } catch (e: UnsatisfiedLinkError) {
                }
                //RM-change code end
                // Write data until all data has been written to the audio sink.
                // Upon return, the buffer position will have been advanced to reflect
                // the amount of data that was successfully written to the AudioTrack.
                assertTrue(sizeInBytes <= byteBuffer!!.remaining())
                if (speakerMute) {
                    byteBuffer!!.clear()
                    byteBuffer!!.put(emptyBytes)
                    byteBuffer!!.position(0)
                }
                val bytesWritten =
                    audioTrack!!.write(byteBuffer!!, sizeInBytes, AudioTrack.WRITE_BLOCKING)
                if (bytesWritten != sizeInBytes) {
                    // If a write() returns a negative value, an error has occurred.
                    // Stop playing and report an error in this case.
                    if (bytesWritten < 0) {
                        keepAlive = false
                        reportWebRtcAudioTrackError("AudioTrack.write failed: $bytesWritten")
                    }
                }
                if (useLowLatency) {
                    bufferManager.maybeAdjustBufferSize(audioTrack!!)
                }
                // The byte buffer must be rewinded since byteBuffer.position() is
                // increased at each call to AudioTrack.write(). If we don't do this,
                // next call to AudioTrack.write() will fail.
                byteBuffer!!.rewind()

                // TODO(henrika): it is possible to create a delay estimate here by
                // counting number of written frames and subtracting the result from
                // audioTrack.getPlaybackHeadPosition().
            }
        }

        // Stops the inner thread loop which results in calling AudioTrack.stop().
        // Does not block the calling thread.
        fun stopThread() {
            keepAlive = false
        }
    }

    @CalledByNative
    constructor(context: Context?, audioManager: AudioManager) : this(
        context, audioManager, null /* audioAttributes */, null /* errorCallback */,
        null /* stateCallback */, false /* useLowLatency */, true /* enableVolumeLogger */
    ) {
    }

    init {
        threadChecker.detachThread()
        this.context = context
        this.audioManager = audioManager
        this.audioAttributes = audioAttributes
        this.errorCallback = errorCallback
        this.stateCallback = stateCallback
        volumeLogger = if (enableVolumeLogger) VolumeLogger(audioManager) else null
        this.useLowLatency = useLowLatency
    }

    @CalledByNative
    fun setNativeAudioTrack(nativeAudioTrack: Long) {
        this.nativeAudioTrack = nativeAudioTrack
    }

    @CalledByNative
    private fun initPlayout(sampleRate: Int, channels: Int, bufferSizeFactor: Double): Int {
        threadChecker.checkIsOnValidThread()
        val bytesPerFrame = channels * (BITS_PER_SAMPLE / 8)
        byteBuffer = ByteBuffer.allocateDirect(bytesPerFrame * (sampleRate / BUFFERS_PER_SECOND))
        emptyBytes = ByteArray(byteBuffer.capacity())
        // Rather than passing the ByteBuffer with every callback (requiring
        // the potentially expensive GetDirectBufferAddress) we simply have the
        // the native class cache the address to the memory once.
        nativeCacheDirectBufferAddress(nativeAudioTrack, byteBuffer)

        // Get the minimum buffer size required for the successful creation of an
        // AudioTrack object to be created in the MODE_STREAM mode.
        // Note that this size doesn't guarantee a smooth playback under load.
        val channelConfig = channelCountToConfiguration(channels)
        val minBufferSizeInBytes = (AudioTrack.getMinBufferSize(
            sampleRate, channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )
                * bufferSizeFactor).toInt()
        // For the streaming mode, data must be written to the audio sink in
        // chunks of size (given by byteBuffer.capacity()) less than or equal
        // to the total buffer size `minBufferSizeInBytes`. But, we have seen
        // reports of "getMinBufferSize(): error querying hardware". Hence, it
        // can happen that `minBufferSizeInBytes` contains an invalid value.
        if (minBufferSizeInBytes < byteBuffer.capacity()) {
            reportWebRtcAudioTrackInitError("AudioTrack.getMinBufferSize returns an invalid value.")
            return -1
        }

        // Don't use low-latency mode when a bufferSizeFactor > 1 is used. When bufferSizeFactor > 1
        // we want to use a larger buffer to prevent underruns. However, low-latency mode would
        // decrease the buffer size, which makes the bufferSizeFactor have no effect.
        if (bufferSizeFactor > 1.0) {
            useLowLatency = false
        }

        // Ensure that prevision audio session was stopped correctly before trying
        // to create a new AudioTrack.
        if (audioTrack != null) {
            reportWebRtcAudioTrackInitError("Conflict with existing AudioTrack.")
            return -1
        }
        audioTrack = try {
            // Create an AudioTrack object and initialize its associated audio buffer.
            // The size of this buffer determines how long an AudioTrack can play
            // before running out of data.
            if (useLowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // On API level 26 or higher, we can use a low latency mode.
                createAudioTrackOnOreoOrHigher(
                    sampleRate, channelConfig, minBufferSizeInBytes, audioAttributes
                )
            } else {
                // As we are on API level 21 or higher, it is possible to use a special AudioTrack
                // constructor that uses AudioAttributes and AudioFormat as input. It allows us to
                // supersede the notion of stream types for defining the behavior of audio playback,
                // and to allow certain platforms or routing policies to use this information for more
                // refined volume or routing decisions.
                createAudioTrackBeforeOreo(
                    sampleRate, channelConfig, minBufferSizeInBytes, audioAttributes
                )
            }
        } catch (e: IllegalArgumentException) {
            reportWebRtcAudioTrackInitError(e.message)
            releaseAudioResources()
            return -1
        }

        // It can happen that an AudioTrack is created but it was not successfully
        // initialized upon creation. Seems to be the case e.g. when the maximum
        // number of globally available audio tracks is exceeded.
        if (audioTrack == null || audioTrack!!.state != AudioTrack.STATE_INITIALIZED) {
            reportWebRtcAudioTrackInitError("Initialization of audio track failed.")
            releaseAudioResources()
            return -1
        }
        initialBufferSizeInFrames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioTrack!!.bufferSizeInFrames
        } else {
            -1
        }
        logMainParameters()
        logMainParametersExtended()
        return minBufferSizeInBytes
    }

    @CalledByNative
    private fun startPlayout(): Boolean {
        threadChecker.checkIsOnValidThread()
        volumeLogger?.start()
        assertTrue(audioTrack != null)
        assertTrue(audioThread == null)

        // Starts playing an audio track.
        try {
            audioTrack!!.play()
        } catch (e: IllegalStateException) {
            reportWebRtcAudioTrackStartError(
                AudioTrackStartErrorCode.AUDIO_TRACK_START_EXCEPTION,
                "AudioTrack.play failed: " + e.message
            )
            releaseAudioResources()
            return false
        }
        if (audioTrack!!.playState != AudioTrack.PLAYSTATE_PLAYING) {
            reportWebRtcAudioTrackStartError(
                AudioTrackStartErrorCode.AUDIO_TRACK_START_STATE_MISMATCH,
                "AudioTrack.play failed - incorrect state :" + audioTrack!!.playState
            )
            releaseAudioResources()
            return false
        }

        // Create and start new high-priority thread which calls AudioTrack.write()
        // and where we also call the native nativeGetPlayoutData() callback to
        // request decoded audio from WebRTC.
        audioThread = AudioTrackThread("AudioTrackJavaThread")
        audioThread!!.start()
        return true
    }

    @CalledByNative
    private fun stopPlayout(): Boolean {
        threadChecker.checkIsOnValidThread()
        volumeLogger?.stop()
        assertTrue(audioThread != null)
        logUnderrunCount()
        audioThread!!.stopThread()
        audioThread!!.interrupt()
        if (!ThreadUtils.joinUninterruptibly(audioThread, AUDIO_TRACK_THREAD_JOIN_TIMEOUT_MS)) {
            WebRtcAudioUtils.logAudioState(TAG, context, audioManager)
        }
        audioThread = null
        if (audioTrack != null) {
            try {
                audioTrack!!.stop()
                doAudioTrackStateCallback(AUDIO_TRACK_STOP)
            } catch (e: IllegalStateException) {
            }
        }
        releaseAudioResources()
        return true
    }

    // Get max possible volume index for a phone call audio stream.
    @get:CalledByNative
    private val streamMaxVolume: Int
        private get() {
            threadChecker.checkIsOnValidThread()
            return audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        }

    // Set current volume level for a phone call audio stream.
    @CalledByNative
    private fun setStreamVolume(volume: Int): Boolean {
        threadChecker.checkIsOnValidThread()
        if (audioManager.isVolumeFixed) {
            return false
        }
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, volume, 0)
        return true
    }

    /** Get current volume level for a phone call audio stream.  */
    @get:CalledByNative
    private val streamVolume: Int
        private get() {
            threadChecker.checkIsOnValidThread()
            return audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        }

    @CalledByNative
    private fun GetPlayoutUnderrunCount(): Int {
        return if (Build.VERSION.SDK_INT >= 24) {
            if (audioTrack != null) {
                audioTrack!!.underrunCount
            } else {
                -1
            }
        } else {
            -2
        }
    }

    private fun logMainParameters() {}
    private fun logBufferSizeInFrames() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        }
    }

    @get:CalledByNative
    private val bufferSizeInFrames: Int
        private get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioTrack!!.bufferSizeInFrames
        } else -1

    private fun logBufferCapacityInFrames() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        }
    }

    private fun logMainParametersExtended() {
        logBufferSizeInFrames()
        logBufferCapacityInFrames()
    }

    // Prints the number of underrun occurrences in the application-level write
    // buffer since the AudioTrack was created. An underrun occurs if the app does
    // not write audio data quickly enough, causing the buffer to underflow and a
    // potential audio glitch.
    // TODO(henrika): keep track of this value in the field and possibly add new
    // UMA stat if needed.
    private fun logUnderrunCount() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        }
    }

    private fun channelCountToConfiguration(channels: Int): Int {
        return if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
    }

    // Releases the native AudioTrack resources.
    private fun releaseAudioResources() {
        if (audioTrack != null) {
            audioTrack!!.release()
            audioTrack = null
        }
    }

    private fun reportWebRtcAudioTrackInitError(errorMessage: String?) {
        WebRtcAudioUtils.logAudioState(TAG, context, audioManager)
        errorCallback?.onWebRtcAudioTrackInitError(errorMessage)
    }

    private fun reportWebRtcAudioTrackStartError(
        errorCode: AudioTrackStartErrorCode, errorMessage: String
    ) {
        WebRtcAudioUtils.logAudioState(TAG, context, audioManager)
        errorCallback?.onWebRtcAudioTrackStartError(errorCode, errorMessage)
    }

    private fun reportWebRtcAudioTrackError(errorMessage: String) {
        WebRtcAudioUtils.logAudioState(TAG, context, audioManager)
        errorCallback?.onWebRtcAudioTrackError(errorMessage)
    }

    private fun doAudioTrackStateCallback(audioState: Int) {
        if (stateCallback != null) {
            if (audioState == AUDIO_TRACK_START) {
                stateCallback.onWebRtcAudioTrackStart()
            } else if (audioState == AUDIO_TRACK_STOP) {
                stateCallback.onWebRtcAudioTrackStop()
            } else {
            }
        }
    }

    companion object {
        private const val TAG = "WebRtcAudioTrackExternal"

        // Default audio data format is PCM 16 bit per sample.
        // Guaranteed to be supported by all devices.
        private const val BITS_PER_SAMPLE = 16

        // Requested size of each recorded buffer provided to the client.
        private const val CALLBACK_BUFFER_SIZE_MS = 10

        // Average number of callbacks per second.
        private const val BUFFERS_PER_SECOND = 1000 / CALLBACK_BUFFER_SIZE_MS

        // The AudioTrackThread is allowed to wait for successful call to join()
        // but the wait times out afther this amount of time.
        private const val AUDIO_TRACK_THREAD_JOIN_TIMEOUT_MS: Long = 2000

        // By default, WebRTC creates audio tracks with a usage attribute
        // corresponding to voice communications, such as telephony or VoIP.
        private const val DEFAULT_USAGE = AudioAttributes.USAGE_VOICE_COMMUNICATION

        // Indicates the AudioTrack has started playing audio.
        private const val AUDIO_TRACK_START = 0

        // Indicates the AudioTrack has stopped playing audio.
        private const val AUDIO_TRACK_STOP = 1

        // Samples to be played are replaced by zeros if `speakerMute` is set to true.
        // Can be used to ensure that the speaker is fully muted.
        @Volatile
        private var speakerMute = false
        private fun logNativeOutputSampleRate(requestedSampleRateInHz: Int) {
            val nativeOutputSampleRate =
                AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_VOICE_CALL)
            if (requestedSampleRateInHz != nativeOutputSampleRate) {
            }
        }

        private fun getAudioAttributes(overrideAttributes: AudioAttributes?): AudioAttributes {
            var attributesBuilder = AudioAttributes.Builder()
                .setUsage(DEFAULT_USAGE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            if (overrideAttributes != null) {
                if (overrideAttributes.usage != AudioAttributes.USAGE_UNKNOWN) {
                    attributesBuilder.setUsage(overrideAttributes.usage)
                }
                if (overrideAttributes.contentType != AudioAttributes.CONTENT_TYPE_UNKNOWN) {
                    attributesBuilder.setContentType(overrideAttributes.contentType)
                }
                attributesBuilder.setFlags(overrideAttributes.flags)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    attributesBuilder =
                        applyAttributesOnQOrHigher(attributesBuilder, overrideAttributes)
                }
            }
            return attributesBuilder.build()
        }

        // Creates and AudioTrack instance using AudioAttributes and AudioFormat as input.
        // It allows certain platforms or routing policies to use this information for more
        // refined volume or routing decisions.
        private fun createAudioTrackBeforeOreo(
            sampleRateInHz: Int, channelConfig: Int,
            bufferSizeInBytes: Int, overrideAttributes: AudioAttributes?
        ): AudioTrack {
            logNativeOutputSampleRate(sampleRateInHz)

            // Create an audio track where the audio usage is for VoIP and the content type is speech.
            return AudioTrack(
                getAudioAttributes(overrideAttributes),
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRateInHz)
                    .setChannelMask(channelConfig)
                    .build(),
                bufferSizeInBytes, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        }

        // Creates and AudioTrack instance using AudioAttributes and AudioFormat as input.
        // Use the low-latency mode to improve audio latency. Note that the low-latency mode may
        // prevent effects (such as AEC) from working. Assuming AEC is working, the delay changes
        // that happen in low-latency mode during the call will cause the AEC to perform worse.
        // The behavior of the low-latency mode may be device dependent, use at your own risk.
        @TargetApi(Build.VERSION_CODES.O)
        private fun createAudioTrackOnOreoOrHigher(
            sampleRateInHz: Int,
            channelConfig: Int,
            bufferSizeInBytes: Int,
            overrideAttributes: AudioAttributes?
        ): AudioTrack {
            logNativeOutputSampleRate(sampleRateInHz)

            // Create an audio track where the audio usage is for VoIP and the content type is speech.
            return AudioTrack.Builder()
                .setAudioAttributes(getAudioAttributes(overrideAttributes))
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateInHz)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSizeInBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                .build()
        }

        @TargetApi(Build.VERSION_CODES.Q)
        private fun applyAttributesOnQOrHigher(
            builder: AudioAttributes.Builder, overrideAttributes: AudioAttributes
        ): AudioAttributes.Builder {
            return builder.setAllowedCapturePolicy(overrideAttributes.allowedCapturePolicy)
        }

        // Helper method which throws an exception  when an assertion has failed.
        private fun assertTrue(condition: Boolean) {
            if (!condition) {
                throw AssertionError("Expected condition to be true")
            }
        }

        private external fun nativeCacheDirectBufferAddress(
            nativeAudioTrackJni: Long, byteBuffer: ByteBuffer?
        )

        private external fun nativeGetPlayoutData(nativeAudioTrackJni: Long, bytes: Int)

        // Sets all samples to be played out to zero if `mute` is true, i.e.,
        // ensures that the speaker is muted.
        fun setSpeakerMute(mute: Boolean) {
            speakerMute = mute
        }
    }
}