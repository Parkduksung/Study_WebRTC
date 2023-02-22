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
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaRecorder.AudioSource
import android.os.Build
import java.util.*

internal object WebRtcAudioUtils {
    private const val TAG = "WebRtcAudioUtilsExternal"

    // Helper method for building a string of thread information.
    val threadInfo: String
        get() = ("@[name=" + Thread.currentThread().name + ", id=" + Thread.currentThread().id
                + "]")

    // Returns true if we're running on emulator.
    fun runningOnEmulator(): Boolean {
        return Build.HARDWARE == "goldfish" && Build.BRAND.startsWith("generic_")
    }

    // Information about the current build, taken from system properties.
    fun logDeviceInfo(tag: String?) {}

    // Logs information about the current audio state. The idea is to call this
    // method when errors are detected to log under what conditions the error
    // occurred. Hopefully it will provide clues to what might be the root cause.
    fun logAudioState(tag: String?, context: Context, audioManager: AudioManager) {
        logDeviceInfo(tag)
        logAudioStateBasic(tag, context, audioManager)
        logAudioStateVolume(tag, audioManager)
        logAudioDeviceInfo(tag, audioManager)
    }

    // Converts AudioDeviceInfo types to local string representation.
    fun deviceTypeToString(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_UNKNOWN -> "TYPE_UNKNOWN"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "TYPE_BUILTIN_EARPIECE"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "TYPE_BUILTIN_SPEAKER"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "TYPE_WIRED_HEADSET"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "TYPE_WIRED_HEADPHONES"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "TYPE_LINE_ANALOG"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "TYPE_LINE_DIGITAL"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "TYPE_BLUETOOTH_SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "TYPE_BLUETOOTH_A2DP"
            AudioDeviceInfo.TYPE_HDMI -> "TYPE_HDMI"
            AudioDeviceInfo.TYPE_HDMI_ARC -> "TYPE_HDMI_ARC"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "TYPE_USB_DEVICE"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "TYPE_USB_ACCESSORY"
            AudioDeviceInfo.TYPE_DOCK -> "TYPE_DOCK"
            AudioDeviceInfo.TYPE_FM -> "TYPE_FM"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "TYPE_BUILTIN_MIC"
            AudioDeviceInfo.TYPE_FM_TUNER -> "TYPE_FM_TUNER"
            AudioDeviceInfo.TYPE_TV_TUNER -> "TYPE_TV_TUNER"
            AudioDeviceInfo.TYPE_TELEPHONY -> "TYPE_TELEPHONY"
            AudioDeviceInfo.TYPE_AUX_LINE -> "TYPE_AUX_LINE"
            AudioDeviceInfo.TYPE_IP -> "TYPE_IP"
            AudioDeviceInfo.TYPE_BUS -> "TYPE_BUS"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "TYPE_USB_HEADSET"
            else -> "TYPE_UNKNOWN"
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    fun audioSourceToString(source: Int): String {
        // AudioSource.UNPROCESSED requires API level 29. Use local define instead.
        val VOICE_PERFORMANCE = 10
        return when (source) {
            AudioSource.DEFAULT -> "DEFAULT"
            AudioSource.MIC -> "MIC"
            AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
            AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
            AudioSource.VOICE_CALL -> "VOICE_CALL"
            AudioSource.CAMCORDER -> "CAMCORDER"
            AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            AudioSource.UNPROCESSED -> "UNPROCESSED"
            VOICE_PERFORMANCE -> "VOICE_PERFORMANCE"
            else -> "INVALID"
        }
    }

    fun channelMaskToString(mask: Int): String {
        // For input or AudioRecord, the mask should be AudioFormat#CHANNEL_IN_MONO or
        // AudioFormat#CHANNEL_IN_STEREO. AudioFormat#CHANNEL_IN_MONO is guaranteed to work on all
        // devices.
        return when (mask) {
            AudioFormat.CHANNEL_IN_STEREO -> "IN_STEREO"
            AudioFormat.CHANNEL_IN_MONO -> "IN_MONO"
            else -> "INVALID"
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    fun audioEncodingToString(enc: Int): String {
        return when (enc) {
            AudioFormat.ENCODING_INVALID -> "INVALID"
            AudioFormat.ENCODING_PCM_16BIT -> "PCM_16BIT"
            AudioFormat.ENCODING_PCM_8BIT -> "PCM_8BIT"
            AudioFormat.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
            AudioFormat.ENCODING_AC3 -> "AC3"
            AudioFormat.ENCODING_E_AC3 -> "AC3"
            AudioFormat.ENCODING_DTS -> "DTS"
            AudioFormat.ENCODING_DTS_HD -> "DTS_HD"
            AudioFormat.ENCODING_MP3 -> "MP3"
            else -> "Invalid encoding: $enc"
        }
    }

    // Reports basic audio statistics.
    private fun logAudioStateBasic(tag: String?, context: Context, audioManager: AudioManager) {}

    // Adds volume information for all possible stream types.
    private fun logAudioStateVolume(tag: String?, audioManager: AudioManager) {
        val streams = intArrayOf(
            AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING, AudioManager.STREAM_ALARM, AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_SYSTEM
        )
        // Some devices may not have volume controls and might use a fixed volume.
        val fixedVolume = audioManager.isVolumeFixed
        if (!fixedVolume) {
            for (stream in streams) {
                val info = StringBuilder()
                info.append("  " + streamTypeToString(stream) + ": ")
                info.append("volume=").append(audioManager.getStreamVolume(stream))
                info.append(", max=").append(audioManager.getStreamMaxVolume(stream))
                logIsStreamMute(tag, audioManager, stream, info)
            }
        }
    }

    private fun logIsStreamMute(
        tag: String?, audioManager: AudioManager, stream: Int, info: StringBuilder
    ) {
        if (Build.VERSION.SDK_INT >= 23) {
            info.append(", muted=").append(audioManager.isStreamMute(stream))
        }
    }

    private fun logAudioDeviceInfo(tag: String?, audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT < 23) {
            return
        }
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
        if (devices.size == 0) {
            return
        }
        for (device in devices) {
            val info = StringBuilder()
            info.append("  ").append(deviceTypeToString(device.type))
            info.append(if (device.isSource) "(in): " else "(out): ")
            // An empty array indicates that the device supports arbitrary channel counts.
            if (device.channelCounts.size > 0) {
                info.append("channels=").append(Arrays.toString(device.channelCounts))
                info.append(", ")
            }
            if (device.encodings.size > 0) {
                // Examples: ENCODING_PCM_16BIT = 2, ENCODING_PCM_FLOAT = 4.
                info.append("encodings=").append(Arrays.toString(device.encodings))
                info.append(", ")
            }
            if (device.sampleRates.size > 0) {
                info.append("sample rates=").append(Arrays.toString(device.sampleRates))
                info.append(", ")
            }
            info.append("id=").append(device.id)
        }
    }

    // Converts media.AudioManager modes into local string representation.
    fun modeToString(mode: Int): String {
        return when (mode) {
            AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
            AudioManager.MODE_NORMAL -> "MODE_NORMAL"
            AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
            else -> "MODE_INVALID"
        }
    }

    private fun streamTypeToString(stream: Int): String {
        return when (stream) {
            AudioManager.STREAM_VOICE_CALL -> "STREAM_VOICE_CALL"
            AudioManager.STREAM_MUSIC -> "STREAM_MUSIC"
            AudioManager.STREAM_RING -> "STREAM_RING"
            AudioManager.STREAM_ALARM -> "STREAM_ALARM"
            AudioManager.STREAM_NOTIFICATION -> "STREAM_NOTIFICATION"
            AudioManager.STREAM_SYSTEM -> "STREAM_SYSTEM"
            else -> "STREAM_INVALID"
        }
    }

    // Returns true if the device can record audio via a microphone.
    private fun hasMicrophone(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    }
}