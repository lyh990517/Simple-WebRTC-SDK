package com.example.signaling

import com.example.common.WebRtcEvent
import com.example.model.CandidateType
import com.example.model.Packet
import com.example.model.Packet.Companion.isAnswer
import com.example.model.Packet.Companion.isOffer
import com.example.model.RoomStatus
import com.example.util.parseData
import com.example.util.parseDate
import com.example.util.toAnswerSdp
import com.example.util.toIceCandidate
import com.example.util.toOfferSdp
import com.example.webrtc.client.Signaling
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class SignalingImpl @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val webrtcScope: CoroutineScope
) : Signaling {
    private val signalingEvent = MutableSharedFlow<WebRtcEvent>()

    override suspend fun getRoomStatus(roomID: String): RoomStatus {
        val data = getRoom(roomID)
            .get()
            .await()

        val isRoomEnded = data["type"] == "END_CALL"
        val roomStatus = if (isRoomEnded) RoomStatus.TERMINATED else RoomStatus.NEW

        return roomStatus
    }

    override suspend fun sendIce(
        roomId: String,
        ice: IceCandidate?,
        type: CandidateType
    ) {
        if (ice == null) return

        val parsedIceCandidate = ice.parseDate(type.value)

        getRoom(roomId)
            .collection(ICE_CANDIDATE)
            .document(type.value)
            .set(parsedIceCandidate)
    }

    override suspend fun sendSdp(
        roomId: String,
        sdp: SessionDescription
    ) {
        val parsedSdp = sdp.parseData()

        getRoom(roomId)
            .collection(SDP)
            .document(sdp.type.name)
            .set(parsedSdp)
    }

    override suspend fun start(roomID: String, isHost: Boolean) {
        firestore.enableNetwork()

        webrtcScope.launch {
            val packet = getSdpUpdate(roomID, isHost).first()

            when {
                packet.isOffer() -> handleOffer(packet, roomID)
                packet.isAnswer() -> handleAnswer(packet)
            }
        }

        webrtcScope.launch {
            getIceUpdate(roomID, isHost).collect { packet ->
                handleIceCandidate(packet)
            }
        }
    }

    override fun getEvent(): SharedFlow<WebRtcEvent> = signalingEvent.asSharedFlow()

    private fun getSdpUpdate(roomID: String, isHost: Boolean) = callbackFlow {
        val collection = getRoom(roomID).collection(SDP)

        if (isHost) {
            collection
                .document("ANSWER")
                .addSnapshotListener { snapshot, _ ->

                    val data = snapshot?.data

                    if (data != null) {
                        val packet = Packet(data)

                        trySend(packet)
                    }
                }
        } else {
            val snapshot = collection
                .document("OFFER")
                .get()
                .await()

            val data = snapshot.data ?: throw Exception("there is no offer")

            val packet = Packet(data)

            trySend(packet)
        }

        awaitClose { }
    }

    private fun getIceUpdate(roomID: String, isHost: Boolean) = callbackFlow {
        val collection = getRoom(roomID).collection(ICE_CANDIDATE)
        val candidate = if (isHost) CandidateType.ANSWER else CandidateType.OFFER

        collection
            .document(candidate.value)
            .addSnapshotListener { snapshot, e ->
                val data = snapshot?.data

                if (data != null) {
                    val packet = Packet(data)

                    trySend(packet)
                }
            }

        awaitClose { }
    }

    private suspend fun handleIceCandidate(packet: Packet) {
        val iceCandidate = packet.toIceCandidate()

        signalingEvent.emit(WebRtcEvent.Guest.SetRemoteIce(iceCandidate))
        signalingEvent.emit(WebRtcEvent.Host.SetRemoteIce(iceCandidate))
    }

    private suspend fun handleAnswer(packet: Packet) {
        val sdp = packet.toAnswerSdp()

        signalingEvent.emit(WebRtcEvent.Host.ReceiveAnswer(sdp))
    }

    private suspend fun handleOffer(packet: Packet, roomID: String) {
        val sdp = packet.toOfferSdp()

        signalingEvent.emit(WebRtcEvent.Guest.ReceiveOffer(sdp))

        signalingEvent.emit(WebRtcEvent.Guest.SendAnswer(roomID))
    }

    private fun ProducerScope<Packet>.sendError(e: Exception) {
        trySend(Packet(mapOf("error" to e)))
    }

    private fun getRoom(roomId: String) = firestore
        .collection(ROOT)
        .document(roomId)

    companion object {
        private const val ROOT = "calls"
        private const val ICE_CANDIDATE = "candidates"
        private const val SDP = "sdp"
    }
}
