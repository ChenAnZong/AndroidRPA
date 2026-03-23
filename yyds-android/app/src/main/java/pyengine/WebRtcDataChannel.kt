package pyengine

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import uiautomator.ExtSystem

/**
 * WebRTC DataChannel handler for P2P screen streaming.
 * Currently a NO-OP stub — the `org.webrtc:google-webrtc` dependency is
 * unavailable on Maven. Re-enable once a working WebRTC AAR is provided.
 */
class WebRtcDataChannel(
    private val browserId: String,
    private val session: DefaultClientWebSocketSession,
) {
    fun handleOffer(sdpJson: String) {
        ExtSystem.printInfo("[WebRTC] WebRTC dependency not available, ignoring offer for $browserId")
    }

    fun addIceCandidate(candidateJson: String) {
        // no-op
    }

    fun close() {
        // no-op
    }
}
