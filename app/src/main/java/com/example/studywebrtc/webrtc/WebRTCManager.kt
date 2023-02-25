package com.example.studywebrtc.webrtc

import android.app.Application
import android.util.Log
import org.webrtc.*
import org.webrtc.CameraVideoCapturer.CameraEventsHandler
import org.webrtc.PeerConnectionFactory.InitializationOptions

class WebRTCManager(
    application: Application,
    private val peerConnectionObserver: PeerConnection.Observer
) {

    private val eglBase: EglBase = EglBase.create()

    private lateinit var peerConnectionFactory: PeerConnectionFactory

    private lateinit var localVideoSource: VideoSource

    private lateinit var audioSource: AudioSource

    private lateinit var videoCapture: CameraVideoCapturer

    private val iceServer =
        listOf(PeerConnection.IceServer.builder(ICE_SERVER_URL).createIceServer())

    private var peerConnection: PeerConnection? = null

    init {
        init(application)
    }

    private fun init(application: Application) {

        //------------------------initializationOptions-----------------------

        //InitializationOptions 객체는 WebRTC 초기화에 필요한 다양한 설정값들을 담고 있습니다.
        val initializationOptions = InitializationOptions.builder(application)

        initializationOptions.apply {
            //WebRTC 내부 추적을 사용하여 로그를 생성하는 데 사용됩니다.
            //이 변수를 true로 설정하면 WebRTC에서 생성된 로그를 확인할 수 있습니다.
            setEnableInternalTracer(true)

            //WebRTC에서 제공하는 로그 기능을 사용자가 직접 구현한 로그 기능으로 대체할 수 있는 메서드
            setInjectableLogger(LoggingImpl.getInstance(), Logging.Severity.LS_VERBOSE)

            //WebRTC에서 사용하는 실험용 기능(Field Trials)을 활성화 또는 비활성화하는 역할
            //실험적인 새로운 기능을 추가할 때, 이를 일반 사용자에게 노출하지 않고 일부 사용자만 테스트하고자 할 때 실험용 기능을 사용
            //setFieldTrials() 메서드를 사용하여, 활성화할 실험용 기능과 대상을 설정
            //실험용 기능 필요하지 않을 경우 호출하지 않거나 빈 문자열로 인자 전달하면 비활성화됨
            setFieldTrials(FIELD_TRIALS)
        }

        //---------------------------PeerConnectionFactory.initialize-------------------------

        //WebRTC를 사용하려면 PeerConnectionFactory 클래스를 먼저 생성해야 합니다.
        //PeerConnectionFactory 클래스를 생성하면, WebRTC 엔진을 초기화하는 과정이 자동으로 수행됩니다.
        //이때 initialize() 메서드가 호출되며, WebRTC의 모든 기능을 사용할 수 있도록 준비됩니다.
        /**
         *  initialize() 메서드는 애플리케이션의 전체 라이프사이클 동안 한 번만 호출해야 합니다.
         *  따라서, 이 메서드는 일반적으로 애플리케이션의 onCreate() 메서드에서 호출됩니다.
         */
        PeerConnectionFactory.initialize(initializationOptions.createInitializationOptions())


        //--------------------------PeerConnectionFactory.build--------------------

        //초기화 구성: 초기화 구성에서는 WebRTC 라이브러리에 대한 기본 구성을 정의합니다.
        //예를 들어, MediaCodec 비디오 코덱을 사용할지, VP8 비디오 코덱을 사용할지 등을 정의할 수 있습니다.
        //네트워크 구성: 네트워크 구성에서는 WebRTC의 네트워크 동작을 제어할 수 있습니다.
        //예를 들어, NAT 및 방화벽을 통과할 때 STUN 및 TURN 서버를 사용할지 여부를 정의할 수 있습니다.
        //코덱 및 인코더/디코더 구성: 코덱 및 인코더/디코더 구성에서는 WebRTC 미디어 코덱 및 인코더/디코더를 구성할 수 있습니다.
        peerConnectionFactory =
            PeerConnectionFactory.builder().apply {

                setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(
                        eglBase.eglBaseContext,
                        true,
                        true
                    )
                )

                //NetworkIgnoreMask - 네트워크 유형에 대한 우선 순위를 설정할 수 있는 옵션입니다.
                //WebRTC 라이브러리가 특정 유형의 네트워크 연결을 무시하도록 지정할 수 있습니다.
                //
                //DisableEncryption - 이 옵션을 사용하면 WebRTC 라이브러리에서 암호화를 사용하지 않도록 설정할 수 있습니다.
                //보안 요구 사항이 적은 테스트 환경에서 사용됩니다.
                //
                //DisableNetworkMonitor - 이 옵션을 사용하면 WebRTC 라이브러리에서 네트워크 감시를 비활성화할 수 있습니다.
                //모바일 장치에서 전력 소모를 줄이기 위해 사용됩니다.
                //
                //EnableDtlsSrtp - 이 옵션을 사용하면 WebRTC 라이브러리에서 DTLS-SRTP (Datagram Transport Layer Security-Secure Real-Time Transport Protocol) 암호화를 사용할 수 있습니다.
                //
                //EnableIPv6 - 이 옵션을 사용하면 WebRTC 라이브러리에서 IPv6를 사용할 수 있습니다.
                //
                //SuspendBelowMinBitrate - 이 옵션을 사용하면 WebRTC 라이브러리에서 최소 비트 전송률 미만으로 전송되는 미디어 스트림을 일시 중지할 수 있습니다.
                //네트워크 연결이 불안정한 경우 사용됩니다.

                setOptions(PeerConnectionFactory.Options().apply {
                    disableEncryption = true
                    disableNetworkMonitor = true
                })
            }.createPeerConnectionFactory()


        localVideoSource = peerConnectionFactory.createVideoSource(true, true)

        audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())

        videoCapture = Camera2Enumerator(application).run {
            deviceNames.find {
                isFrontFacing(it)
            }?.let {
                createCapturer(it, CameraEventsHandlerImpl())
            } ?: throw IllegalArgumentException()
        }

        peerConnection =
            peerConnectionFactory.createPeerConnection(iceServer, peerConnectionObserver)
    }

    companion object {

        //group1 에 속한 사용자만에게 적용
        private const val FIELD_TRIALS = "WebRTC-H264HighProfile/Enabled/group1/"
        private const val ICE_SERVER_URL = "stun:stun.l.google.com:19302"
    }
}


//WebRTC에서 생성된 로그와 사용자가 구현한 로그를 모두 한곳에서 확인할 수 있도록 로그를 통합할 수 있습니다. 이는 디버깅 및 문제 해결에 매우 유용합니다.
class LoggingImpl : Loggable {

    override fun onLogMessage(p0: String?, p1: Logging.Severity?, p2: String?) {
        Log.d("결과", "p0 : $p0 / p1 : $p1 / p2 : $p2")
    }

    companion object {

        private var INSTANCE: LoggingImpl? = null

        fun getInstance(): Loggable =
            INSTANCE ?: LoggingImpl().also {
                INSTANCE = it
            }
    }
}


class CameraEventsHandlerImpl : CameraEventsHandler {
    override fun onCameraError(p0: String?) {
        Log.d("결과", "onCameraError")
    }

    override fun onCameraDisconnected() {
        Log.d("결과", "onCameraDisconnected")
    }

    override fun onCameraFreezed(p0: String?) {
        Log.d("결과", "onCameraFreezed")
    }

    override fun onCameraOpening(p0: String?) {
        Log.d("결과", "onCameraOpening")
    }

    override fun onFirstFrameAvailable() {
        Log.d("결과", "onFirstFrameAvailable")
    }

    override fun onCameraClosed() {
        Log.d("결과", "onCameraClosed")
    }
}