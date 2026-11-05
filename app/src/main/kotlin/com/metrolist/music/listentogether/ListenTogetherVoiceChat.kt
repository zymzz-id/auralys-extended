/*
 * Auralys Extended (C) 2026 — based on Metrolist (GPL-3.0)
 * Licensed under GPL-3.0
 */
package com.metrolist.music.listentogether

import android.content.Context
import android.media.AudioManager
import com.metrolist.music.utils.dataStore
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.AudioTrack
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import timber.log.Timber
import java.nio.ByteBuffer

/**
 * Auralys Extended: Listen Together voice chat.
 *
 * A small full-mesh WebRTC audio room (max 4 participants) laid on top of the
 * existing Listen Together WebSocket: join/leave and SDP/ICE signalling are
 * carried as JSON messages over the same socket, media goes peer-to-peer.
 */
object ListenTogetherVoiceChat {
    private const val TAG = "LTVoiceChat"
    const val MAX_PARTICIPANTS = 4

    data class VoiceState(
        val active: Boolean = false,
        val muted: Boolean = false,
        val peers: Set<String> = emptySet(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state

    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var eglBase: EglBase? = null
    private var localAudioTrack: AudioTrack? = null
    private val peers = HashMap<String, PeerConnection>()
    private var microphoneEnabled = false
    private var selfUserId: String = "@self"

    /** Signalling sender — wired to ListenTogetherClient's WebSocket. */
    @Volatile var signallingSender: ((payload: com.metrolist.music.listentogether.VoiceSignalPayload) -> Unit)? = null

    fun init(context: Context) {
        if (factory != null) return
        appContext = context.applicationContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        eglBase = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        audioDeviceModule = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
        Timber.tag(TAG).i("VoiceChat factory initialized")
    }

    fun setSelfUserId(userId: String) {
        selfUserId = userId
    }

    private fun sendSignal(
        to: String,
        signalType: String,
        sdp: String? = null,
        candidate: String? = null,
        muted: Boolean? = null,
    ) {
        val payload = com.metrolist.music.listentogether.VoiceSignalPayload.newBuilder().apply {
            this.fromUserId = selfUserId
            this.toUserId = to
            this.signalType = signalType
            sdp?.let { this.sdp = it }
            candidate?.let { this.candidate = it }
            muted?.let { this.muted = it }
        }.build()
        signallingSender?.invoke(payload)
    }

    fun joinVoice() {
        val ctx = appContext ?: return
        if (_state.value.active) return
        if (_state.value.peers.size >= MAX_PARTICIPANTS) {
            _state.value = _state.value.copy(error = "Voice room full")
            return
        }
        try {
            ensureLocalAudioTrack(ctx)
            val rtcConfig = PeerConnection.RTCConfiguration(
                listOf(
                    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                    PeerConnection.IceServer.builder("stun:global.stun.twilio.com:3478").createIceServer(),
                )
            ).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }

            val pc = factory!!.createPeerConnection(rtcConfig, PeerObserver()) ?: run {
                _state.value = _state.value.copy(error = "Failed to create peer connection")
                return
            }
            pc.addTrack(localAudioTrack, listOf("lt_voice"))
            peers[selfUserId] = pc
            _state.value = _state.value.copy(active = true, error = null)
            // announce via signalling (broadcast: empty to)
            sendSignal(to = "", signalType = "voice_join")
            Timber.tag(TAG).i("Joined voice chat")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "joinVoice failed")
            _state.value = _state.value.copy(active = false, error = e.message)
        }
    }

    fun leaveVoice() {
        try {
            sendSignal(to = "", signalType = "voice_leave")
        } catch (_: Exception) {}
        peers.values.forEach { it.close() }
        peers.clear()
        microphoneEnabled = false
        _state.value = VoiceState(active = false)
        Timber.tag(TAG).i("Left voice chat")
    }

    fun setMuted(muted: Boolean) {
        microphoneEnabled = !muted
        localAudioTrack?.setEnabled(!muted)
        _state.value = _state.value.copy(muted = muted)
        sendSignal(to = "", signalType = "mute", muted = muted)
    }

    // ---- Signalling ingress (called by ListenTogetherClient) ----

    fun onVoiceSignal(fromUserId: String, type: String, sdp: String? = null, candidateJson: String? = null) {
        val ctx = appContext ?: return
        scope?.launch {
            when (type) {
                "voice_offer" -> {
                    if (!_state.value.active) joinVoice()
                    val pc = getOrCreatePeer(fromUserId, ctx) ?: return@launch
                    pc.setRemoteDescription(
                        NoOpSdpObserver(),
                        SessionDescription(SessionDescription.Type.OFFER, sdp),
                    )
                    pc.createAnswer(object : SdpObserverAdapter() {
                        override fun onCreateSuccess(desc: SessionDescription) {
                            pc.setLocalDescription(NoOpSdpObserver(), desc)
                            sendSignal(to = fromUserId, signalType = "voice_answer", sdp = desc.description)
                        }
                    }, MediaConstraints())
                }
                "voice_answer" -> {
                    peers[fromUserId]?.setRemoteDescription(
                        NoOpSdpObserver(),
                        SessionDescription(SessionDescription.Type.ANSWER, sdp),
                    )
                }
                "voice_ice" -> {
                    candidateJson?.let { cand ->
                        val pc = peers[fromUserId]
                        if (pc != null) {
                            try {
                                pc.addIceCandidate(org.json.JSONObject(cand).let {
                                    IceCandidate(
                                        it.optString("sdpMid", ""),
                                        it.optInt("sdpMLineIndex", 0),
                                        it.optString("candidate", cand),
                                    )
                                })
                            } catch (e: Exception) {
                                Timber.tag(TAG).d("ICE parse failed: %s", e.message)
                            }
                        }
                    }
                }
                "voice_leave" -> {
                    peers.remove(fromUserId)?.close()
                    _state.value = _state.value.copy(peers = _state.value.peers - fromUserId)
                }
                "voice_join" -> {
                    // Another participant announced; if we're already in, initiate offer to them
                    if (_state.value.active && fromUserId > selfUserId) {
                        initiateOfferTo(fromUserId, ctx)
                    }
                }
            }
        }
    }

    private fun initiateOfferTo(userId: String, ctx: Context) {
        val pc = getOrCreatePeer(userId, ctx) ?: return
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(NoOpSdpObserver(), desc)
                sendSignal(to = userId, signalType = "voice_offer", sdp = desc.description)
            }
        }, MediaConstraints())
    }

    private fun getOrCreatePeer(userId: String, ctx: Context): PeerConnection? {
        peers[userId]?.let { return it }
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        ).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val pc = factory?.createPeerConnection(rtcConfig, PeerObserver(userId)) ?: return null
        pc.addTrack(localAudioTrack, listOf("lt_voice"))
        peers[userId] = pc
        _state.value = _state.value.copy(peers = _state.value.peers + userId)
        return pc
    }

    private fun ensureLocalAudioTrack(ctx: Context) {
        if (localAudioTrack != null) return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        val audioSource = factory?.createAudioSource(constraints)
        val track = factory?.createAudioTrack("lt_voice_local", audioSource)
        track?.setEnabled(true)
        localAudioTrack = track
        // speakerphone so music+voice are heard aloud
        (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    private class PeerObserver(private val userId: String? = null) : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            val target = userId ?: return // self mesh handled per-peer
            sendSignal(
                to = target,
                signalType = "voice_ice",
                candidate = candidate.toString(),
            )
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Timber.tag(TAG).d("ICE %s -> %s", userId, state)
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(dc: org.webrtc.DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
            Timber.tag(TAG).d("onTrack from %s", userId)
        }
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
    private class NoOpSdpObserver : SdpObserverAdapter()
}
