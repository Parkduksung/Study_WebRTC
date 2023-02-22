package com.example.studywebrtc.webrtc.audio


interface AudioDeviceModule {

    fun getNativeAudioDeviceModulePointer(): Long

    fun release()

    fun setSpeakerMute(mute: Boolean)

    fun setMicrophoneMute(mute: Boolean)
}