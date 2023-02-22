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

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import com.example.studywebrtc.webrtc.CalledByNative

/**
 * This class contains static functions to query sample rate and input/output audio buffer sizes.
 */
internal object WebRtcAudioManager {
    private const val TAG = "WebRtcAudioManagerExternal"
    private const val DEFAULT_SAMPLE_RATE_HZ = 16000

    // Default audio data format is PCM 16 bit per sample.
    // Guaranteed to be supported by all devices.
    private const val BITS_PER_SAMPLE = 16
    private const val DEFAULT_FRAME_PER_BUFFER = 256
    @CalledByNative
    fun getAudioManager(context: Context): AudioManager {
        return context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    @CalledByNative
    fun getOutputBufferSize(
        context: Context, audioManager: AudioManager, sampleRate: Int, numberOfOutputChannels: Int
    ): Int {
        return if (isLowLatencyOutputSupported(context)) getLowLatencyFramesPerBuffer(audioManager) else getMinOutputFrameSize(
            sampleRate,
            numberOfOutputChannels
        )
    }

    @CalledByNative
    fun getInputBufferSize(
        context: Context, audioManager: AudioManager, sampleRate: Int, numberOfInputChannels: Int
    ): Int {
        return if (isLowLatencyInputSupported(context)) getLowLatencyFramesPerBuffer(audioManager) else getMinInputFrameSize(
            sampleRate,
            numberOfInputChannels
        )
    }

    private fun isLowLatencyOutputSupported(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)
    }

    private fun isLowLatencyInputSupported(context: Context): Boolean {
        // TODO(henrika): investigate if some sort of device list is needed here
        // as well. The NDK doc states that: "As of API level 21, lower latency
        // audio input is supported on select devices. To take advantage of this
        // feature, first confirm that lower latency output is available".
        return isLowLatencyOutputSupported(context)
    }

    /**
     * Returns the native input/output sample rate for this device's output stream.
     */
    @CalledByNative
    fun getSampleRate(audioManager: AudioManager): Int {
        // Override this if we're running on an old emulator image which only
        // supports 8 kHz and doesn't support PROPERTY_OUTPUT_SAMPLE_RATE.
        return if (WebRtcAudioUtils.runningOnEmulator()) {
            8000
        } else getSampleRateForApiLevel(audioManager)
        // Deliver best possible estimate based on default Android AudioManager APIs.
    }

    private fun getSampleRateForApiLevel(audioManager: AudioManager): Int {
        val sampleRateString = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        return sampleRateString?.toInt() ?: DEFAULT_SAMPLE_RATE_HZ
    }

    // Returns the native output buffer size for low-latency output streams.
    private fun getLowLatencyFramesPerBuffer(audioManager: AudioManager): Int {
        val framesPerBuffer =
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        return framesPerBuffer?.toInt() ?: DEFAULT_FRAME_PER_BUFFER
    }

    // Returns the minimum output buffer size for Java based audio (AudioTrack).
    // This size can also be used for OpenSL ES implementations on devices that
    // lacks support of low-latency output.
    private fun getMinOutputFrameSize(sampleRateInHz: Int, numChannels: Int): Int {
        val bytesPerFrame = numChannels * (BITS_PER_SAMPLE / 8)
        val channelConfig =
            if (numChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        return (AudioTrack.getMinBufferSize(
            sampleRateInHz, channelConfig, AudioFormat.ENCODING_PCM_16BIT
        )
                / bytesPerFrame)
    }

    // Returns the minimum input buffer size for Java based audio (AudioRecord).
    // This size can calso be used for OpenSL ES implementations on devices that
    // lacks support of low-latency input.
    private fun getMinInputFrameSize(sampleRateInHz: Int, numChannels: Int): Int {
        val bytesPerFrame = numChannels * (BITS_PER_SAMPLE / 8)
        val channelConfig =
            if (numChannels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        return (AudioRecord.getMinBufferSize(
            sampleRateInHz, channelConfig, AudioFormat.ENCODING_PCM_16BIT
        )
                / bytesPerFrame)
    }
}