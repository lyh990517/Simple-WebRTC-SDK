package com.example.webrtc.client

import com.example.webrtc.client.api.WebRtcClient
import com.example.webrtc.client.controller.Controller
import com.example.webrtc.client.signaling.Signaling
import com.example.webrtc.client.event.EventHandler
import com.example.webrtc.client.event.WebRtcEvent
import com.example.webrtc.client.model.Message
import com.example.webrtc.client.stts.SpeechRecognitionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class WebRtcClientImpl @Inject constructor(
    private val webRtcScope: CoroutineScope,
    private val eventHandler: EventHandler,
    private val webRtcController: Controller.WebRtc,
    private val localResourceController: Controller.LocalResource,
    private val signaling: Signaling,
    private val speechRecognitionManager: SpeechRecognitionManager
) : WebRtcClient {
    override fun connect(roomID: String, speechModeOn: Boolean) {
        webRtcScope.launch {
            val isHost = !signaling.getRoomExists(roomID)

            launch { eventHandler.start() }

            launch { signaling.start(roomID, isHost) }

            webRtcController.initialize(roomID, isHost, speechModeOn)

            localResourceController.startCapture()
        }
    }

    override fun getEvent(): Flow<WebRtcEvent> = eventHandler.getEvents()

    override suspend fun getRoomList(): Flow<List<String>?> = signaling.getRoomList()

    override fun startSpeech() {
        speechRecognitionManager.startListen()
    }

    override fun sendMessage(message: String) {
        webRtcController.sendMessage(message)
    }

    override fun sendInputEvent() {
        webRtcController.sendInputEvent()
    }

    override fun getMessages(): Flow<Message> = webRtcController.getMessages()

    override fun toggleVoice() {
        localResourceController.toggleVoice()
    }

    override fun toggleVideo() {
        localResourceController.toggleVideo()
    }

    override fun getLocalSurface(): SurfaceViewRenderer =
        localResourceController.getLocalSurface()

    override fun getRemoteSurface(): SurfaceViewRenderer =
        localResourceController.getRemoteSurface()

    override fun disconnect() {
        webRtcScope.launch {
            localResourceController.dispose()
            webRtcController.closeConnection()
            signaling.terminate()
            coroutineContext.cancelChildren()
        }
    }
}