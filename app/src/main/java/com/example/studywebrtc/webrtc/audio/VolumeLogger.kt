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

import android.media.AudioManager
import java.util.*

// TODO(magjed): Do we really need to spawn a new thread just to log volume? Can we re-use the
// AudioTrackThread instead?
/**
 * Private utility class that periodically checks and logs the volume level of the audio stream that
 * is currently controlled by the volume control. A timer triggers logs once every 30 seconds and
 * the timer's associated thread is named "WebRtcVolumeLevelLoggerThread".
 */
internal class VolumeLogger(private val audioManager: AudioManager) {
    private var timer: Timer? = null
    fun start() {
        if (timer != null) {
            return
        }
        timer = Timer(THREAD_NAME)
        timer!!.schedule(
            LogVolumeTask(
                audioManager.getStreamMaxVolume(AudioManager.STREAM_RING),
                audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            ),
            0, (TIMER_PERIOD_IN_SECONDS * 1000).toLong()
        )
    }

    private inner class LogVolumeTask internal constructor(
        private val maxRingVolume: Int,
        private val maxVoiceCallVolume: Int
    ) : TimerTask() {
        override fun run() {
            val mode = audioManager.mode
            if (mode == AudioManager.MODE_RINGTONE) {
            } else if (mode == AudioManager.MODE_IN_COMMUNICATION) {
            }
        }
    }

    fun stop() {
        if (timer != null) {
            timer!!.cancel()
            timer = null
        }
    }

    companion object {
        private const val TAG = "VolumeLogger"
        private const val THREAD_NAME = "WebRtcVolumeLevelLoggerThread"
        private const val TIMER_PERIOD_IN_SECONDS = 30
    }
}