/*
 *  Copyright (c) 2020 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */
package com.example.studywebrtc.webrtc.audio

import android.media.AudioTrack
import android.os.Build

// Lowers the buffer size if no underruns are detected for 100 ms. Once an
// underrun is detected, the buffer size is increased by 10 ms and it will not
// be lowered further. The buffer size will never be increased more than
// 5 times, to avoid the possibility of the buffer size increasing without
// bounds.
internal class LowLatencyAudioBufferManager {
    // The underrun count that was valid during the previous call to maybeAdjustBufferSize(). Used to
    // detect increases in the value.
    private var prevUnderrunCount = 0

    // The number of ticks to wait without an underrun before decreasing the buffer size.
    private var ticksUntilNextDecrease = 10

    // Indicate if we should continue to decrease the buffer size.
    private var keepLoweringBufferSize = true

    // How often the buffer size was increased.
    private var bufferIncreaseCounter = 0
    fun maybeAdjustBufferSize(audioTrack: AudioTrack) {
        if (Build.VERSION.SDK_INT >= 26) {
            val underrunCount = audioTrack.underrunCount
            if (underrunCount > prevUnderrunCount) {
                // Don't increase buffer more than 5 times. Continuing to increase the buffer size
                // could be harmful on low-power devices that regularly experience underruns under
                // normal conditions.
                if (bufferIncreaseCounter < 5) {
                    // Underrun detected, increase buffer size by 10ms.
                    val currentBufferSize = audioTrack.bufferSizeInFrames
                    val newBufferSize = currentBufferSize + audioTrack.playbackRate / 100
                    audioTrack.bufferSizeInFrames = newBufferSize
                    bufferIncreaseCounter++
                }
                // Stop trying to lower the buffer size.
                keepLoweringBufferSize = false
                prevUnderrunCount = underrunCount
                ticksUntilNextDecrease = 10
            } else if (keepLoweringBufferSize) {
                ticksUntilNextDecrease--
                if (ticksUntilNextDecrease <= 0) {
                    // No underrun seen for 100 ms, try to lower the buffer size by 10ms.
                    val bufferSize10ms = audioTrack.playbackRate / 100
                    // Never go below a buffer size of 10ms.
                    val currentBufferSize = audioTrack.bufferSizeInFrames
                    val newBufferSize = Math.max(bufferSize10ms, currentBufferSize - bufferSize10ms)
                    if (newBufferSize != currentBufferSize) {
                        audioTrack.bufferSizeInFrames = newBufferSize
                    }
                    ticksUntilNextDecrease = 10
                }
            }
        }
    }

    companion object {
        private const val TAG = "LowLatencyAudioBufferManager"
    }
}