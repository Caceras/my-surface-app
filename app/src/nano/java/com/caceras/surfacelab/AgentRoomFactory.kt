package com.caceras.surfacelab

import android.app.Notification
import android.content.Context
import android.content.Intent
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LiveKit room for the Nano build: publishes the microphone (hardware echo
 * cancellation is on from Android 10), plays the agent's voice, relays
 * `lk.transcription` text streams and the agent's `lk.agent.state`, answers
 * the four phone tools over RPC and shares the screen on request.
 */
object AgentRoomFactory {
    const val available = true
    private const val SCREEN_NOTIFICATION = 47
    private val TOOLS = listOf("search_notes", "read_note", "list_tasks", "propose_action")

    fun connect(context: Context, url: String, token: String, listener: AgentRoomListener): AgentRoom {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val room = LiveKit.create(context)
        fun agentState(p: Participant) { if (p.kind == Participant.Kind.AGENT) p.attributes["lk.agent.state"]?.let(listener::agentState) }

        // Tools are registered before connecting, so the agent never calls an unknown method.
        TOOLS.forEach { method -> room.registerRpcMethod(method) { data -> withContext(Dispatchers.IO) { listener.call(method, data.payload) } } }
        room.registerTextStreamHandler("lk.transcription") { reader, sender ->
            scope.launch {
                val attributes = reader.info.attributes
                val segment = attributes["lk.segment_id"] ?: reader.info.id
                val fromAgent = sender.value != room.localParticipant.identity?.value
                val text = StringBuilder()
                runCatching { reader.flow.collect { chunk -> text.append(chunk); listener.transcript(segment, text.toString(), false, fromAgent) } }
                listener.transcript(segment, text.toString(), attributes["lk.transcription_final"] != "false", fromAgent)
            }
        }
        scope.launch {
            room.events.events.collect { event ->
                when (event) {
                    is RoomEvent.ParticipantConnected -> agentState(event.participant)
                    is RoomEvent.ParticipantAttributesChanged -> agentState(event.participant)
                    is RoomEvent.Disconnected -> listener.ended(event.error?.message.orEmpty())
                    else -> {}
                }
            }
        }
        scope.launch {
            try {
                room.connect(url, token)
                room.localParticipant.setMicrophoneEnabled(true)
                listener.connected()
                room.remoteParticipants.values.forEach(::agentState)
            } catch (e: Exception) { listener.ended("Could not connect: ${e.message}") }
        }
        return object : AgentRoom {
            override fun setMicrophone(on: Boolean) { scope.launch { runCatching { room.localParticipant.setMicrophoneEnabled(on) } } }
            override fun shareScreen(permission: Intent?, notification: Notification?, onStop: () -> Unit) {
                scope.launch {
                    runCatching {
                        room.localParticipant.setScreenShareEnabled(permission != null,
                            permission?.let { ScreenCaptureParams(it, if (notification != null) SCREEN_NOTIFICATION else null, notification, onStop) })
                    }.onFailure { onStop() }
                }
            }
            override fun disconnect() {
                runCatching { room.disconnect() }
                runCatching { room.release() }
                scope.cancel()
            }
        }
    }
}
