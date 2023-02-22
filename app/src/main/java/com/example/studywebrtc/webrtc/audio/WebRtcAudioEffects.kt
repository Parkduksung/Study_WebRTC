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

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import java.util.*

// This class wraps control of three different platform effects. Supported
// effects are: AcousticEchoCanceler (AEC) and NoiseSuppressor (NS).
// Calling enable() will active all effects that are
// supported by the device if the corresponding `shouldEnableXXX` member is set.
internal class WebRtcAudioEffects {
    // Contains the audio effect objects. Created in enable() and destroyed
    // in release().
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    // Affects the final state given to the setEnabled() method on each effect.
    // The default state is set to "disabled" but each effect can also be enabled
    // by calling setAEC() and setNS().
    private var shouldEnableAec = false
    private var shouldEnableNs = false

    // Call this method to enable or disable the platform AEC. It modifies
    // `shouldEnableAec` which is used in enable() where the actual state
    // of the AEC effect is modified. Returns true if HW AEC is supported and
    // false otherwise.
    fun setAEC(enable: Boolean): Boolean {
        if (!isAcousticEchoCancelerSupported) {
            shouldEnableAec = false
            return false
        }
        if (aec != null && enable != shouldEnableAec) {
            return false
        }
        shouldEnableAec = enable
        return true
    }

    // Call this method to enable or disable the platform NS. It modifies
    // `shouldEnableNs` which is used in enable() where the actual state
    // of the NS effect is modified. Returns true if HW NS is supported and
    // false otherwise.
    fun setNS(enable: Boolean): Boolean {
        if (!isNoiseSuppressorSupported) {
            shouldEnableNs = false
            return false
        }
        if (ns != null && enable != shouldEnableNs) {
            return false
        }
        shouldEnableNs = enable
        return true
    }

    fun enable(audioSession: Int) {
        assertTrue(aec == null)
        assertTrue(ns == null)
        if (DEBUG) {
            // Add logging of supported effects but filter out "VoIP effects", i.e.,
            // AEC, AEC and NS. Avoid calling AudioEffect.queryEffects() unless the
            // DEBUG flag is set since we have seen crashes in this API.
            for (d in AudioEffect.queryEffects()) {
                if (effectTypeIsVoIP(d.type)) {
                }
            }
        }
        if (isAcousticEchoCancelerSupported) {
            // Create an AcousticEchoCanceler and attach it to the AudioRecord on
            // the specified audio session.
            aec = AcousticEchoCanceler.create(audioSession)
            if (aec != null) {
                val enabled = aec!!.enabled
                val enable = shouldEnableAec && isAcousticEchoCancelerSupported
                if (aec!!.setEnabled(enable) != AudioEffect.SUCCESS) {
                }
            } else {
            }
        }
        if (isNoiseSuppressorSupported) {
            // Create an NoiseSuppressor and attach it to the AudioRecord on the
            // specified audio session.
            ns = NoiseSuppressor.create(audioSession)
            if (ns != null) {
                val enabled = ns!!.enabled
                val enable = shouldEnableNs && isNoiseSuppressorSupported
                if (ns!!.setEnabled(enable) != AudioEffect.SUCCESS) {
                }
            } else {
            }
        }
    }

    // Releases all native audio effect resources. It is a good practice to
    // release the effect engine when not in use as control can be returned
    // to other applications or the native resources released.
    fun release() {
        if (aec != null) {
            aec!!.release()
            aec = null
        }
        if (ns != null) {
            ns!!.release()
            ns = null
        }
    }

    // Returns true for effect types in `type` that are of "VoIP" types:
    // Acoustic Echo Canceler (AEC) or Automatic Gain Control (AGC) or
    // Noise Suppressor (NS). Note that, an extra check for support is needed
    // in each comparison since some devices includes effects in the
    // AudioEffect.Descriptor array that are actually not available on the device.
    // As an example: Samsung Galaxy S6 includes an AGC in the descriptor but
    // AutomaticGainControl.isAvailable() returns false.
    private fun effectTypeIsVoIP(type: UUID): Boolean {
        return AudioEffect.EFFECT_TYPE_AEC == type && isAcousticEchoCancelerSupported || AudioEffect.EFFECT_TYPE_NS == type && isNoiseSuppressorSupported
    }

    companion object {
        private const val DEBUG = false
        private const val TAG = "WebRtcAudioEffectsExternal"

        // UUIDs for Software Audio Effects that we want to avoid using.
        // The implementor field will be set to "The Android Open Source Project".
        private val AOSP_ACOUSTIC_ECHO_CANCELER =
            UUID.fromString("bb392ec0-8d4d-11e0-a896-0002a5d5c51b")
        private val AOSP_NOISE_SUPPRESSOR = UUID.fromString("c06c8400-8e06-11e0-9cb6-0002a5d5c51b")

        // Contains the available effect descriptors returned from the
        // AudioEffect.getEffects() call. This result is cached to avoid doing the
        // slow OS call multiple times.
        private var cachedEffects: Array<AudioEffect.Descriptor>? = null

        // Returns true if all conditions for supporting HW Acoustic Echo Cancellation (AEC) are
        // fulfilled.
        val isAcousticEchoCancelerSupported: Boolean
            get() = isEffectTypeAvailable(AudioEffect.EFFECT_TYPE_AEC, AOSP_ACOUSTIC_ECHO_CANCELER)

        // Returns true if all conditions for supporting HW Noise Suppression (NS) are fulfilled.
        val isNoiseSuppressorSupported: Boolean
            get() = isEffectTypeAvailable(AudioEffect.EFFECT_TYPE_NS, AOSP_NOISE_SUPPRESSOR)

        // Helper method which throws an exception when an assertion has failed.
        private fun assertTrue(condition: Boolean) {
            if (!condition) {
                throw AssertionError("Expected condition to be true")
            }
        }// The caching is best effort only - if this method is called from several

        // threads in parallel, they may end up doing the underlying OS call
        // multiple times. It's normally only called on one thread so there's no
        // real need to optimize for the multiple threads case.
        // Returns the cached copy of the audio effects array, if available, or
        // queries the operating system for the list of effects.
        private val availableEffects: Array<AudioEffect.Descriptor>?
            private get() {
                if (cachedEffects != null) {
                    return cachedEffects
                }
                // The caching is best effort only - if this method is called from several
                // threads in parallel, they may end up doing the underlying OS call
                // multiple times. It's normally only called on one thread so there's no
                // real need to optimize for the multiple threads case.
                cachedEffects = AudioEffect.queryEffects()
                return cachedEffects
            }

        // Returns true if an effect of the specified type is available. Functionally
        // equivalent to (NoiseSuppressor`AutomaticGainControl`...).isAvailable(), but
        // faster as it avoids the expensive OS call to enumerate effects.
        private fun isEffectTypeAvailable(effectType: UUID, blockListedUuid: UUID): Boolean {
            val effects = availableEffects ?: return false
            for (d in effects) {
                if (d.type == effectType) {
                    return d.uuid != blockListedUuid
                }
            }
            return false
        }
    }
}