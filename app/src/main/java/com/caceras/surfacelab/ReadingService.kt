package com.caceras.surfacelab

import android.app.*
import android.content.*
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.text.BreakIterator
import java.util.Locale

/** Explicit playback of saved text, independently of foreground model/capture lifecycle. */
class ReadingService : Service() {
    private lateinit var session: MediaSession
    private var tts: TextToSpeech? = null
    private var ready = false
    private var generation = 0
    private val main = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var focus: AudioFocusRequest
    private var focusHeld = false
    private var chunks = emptyList<String>()
    private var index = 0
    private var speed = 1f
    private var text = ""
    private var playing = false
    private var error = ""
    private val noisy = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { pause() } }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate(); active = this
        audio = getSystemService(AudioManager::class.java)
        val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).setOnAudioFocusChangeListener { if(it<0) pause() }.build()
        session=MediaSession(this,"AegenticaReader").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { resume() }
                override fun onPause() { pause() }
                override fun onStop() { stopSelf() }
                override fun onSkipToNext() { move(1) }
                override fun onSkipToPrevious() { move(-1) }
            })
            setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"Ægentica AI · Read aloud").build())
            isActive=true
        }
        if(Build.VERSION.SDK_INT>=33) registerReceiver(noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") registerReceiver(noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) }
        tts=TextToSpeech(applicationContext) { code -> main.post {
            if(active!==this@ReadingService) return@post
            val locale=Ears(this).locale()
            val voice=tts?.voices.orEmpty().filter { it.locale.language==locale.language && !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
                .sortedByDescending { it.locale==locale }.firstOrNull()
            if(code!=TextToSpeech.SUCCESS || voice==null) { error="Install an offline voice in Android Text-to-speech settings."; pause(); return@post }
            tts?.setVoice(voice); tts?.setAudioAttributes(attributes); ready=true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id:String?) = Unit
                override fun onDone(id:String?) { main.post { if(id=="$generation:$index" && playing) { index++; speak() } } }
                @Deprecated("Framework callback") override fun onError(id:String?) { main.post { if(id=="$generation:$index") { error="Playback failed. Your text is safe."; pause() } } }
            })
            if(playing) speak()
        } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action=="stop") { stopSelf(); return START_NOT_STICKY }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Read aloud",NotificationManager.IMPORTANCE_LOW))
        // Required immediately when launched with startForegroundService, including TTS initialization.
        startForeground(42, notification())
        when(intent?.action) {
            "pause" -> pause()
            "play" -> resume()
            "next" -> move(1)
            "previous" -> move(-1)
            else -> {
                val incoming=intent?.getStringExtra("text")
                if(incoming!=null) {
                    pause(); text=incoming.take(200000); chunks=segments(text); index=0
                    WorkspaceStore(this).use { speed=it.value("reading-speed","1.0").toFloatOrNull()?.coerceIn(.5f,2f) ?: 1f }
                } else if(text.isEmpty()) WorkspaceStore(this).use {
                    text=it.value("reading-text"); chunks=segments(text); index=it.value("reading-index","0").toIntOrNull()?.coerceIn(0,(chunks.size-1).coerceAtLeast(0)) ?: 0
                    speed=it.value("reading-speed","1.0").toFloatOrNull()?.coerceIn(.5f,2f) ?: 1f
                }
                resume()
            }
        }
        return START_NOT_STICKY
    }
    private fun speak() {
        if(!playing) return
        if(index>=chunks.size) { index=0; pause(); return }
        if(!ready) { publish(); return }
        tts?.setSpeechRate(speed)
        if(tts?.speak(chunks[index],TextToSpeech.QUEUE_FLUSH,null,"$generation:$index")!=TextToSpeech.SUCCESS) { error="Could not start playback."; pause() }
        else publish()
    }
    private fun resume() {
        if(chunks.isEmpty()) { pause(); return }
        focusHeld=audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if(!focusHeld) { error="Audio is in use. Tap Play when it is free."; pause(); return }
        error=""; generation++; playing=true; startForeground(42,notification()); speak()
    }
    private fun pause() {
        generation++; playing=false; tts?.stop()
        if(focusHeld) audio.abandonAudioFocusRequest(focus)
        focusHeld=false
        publish()
        // Paused playback is no longer ongoing; Android may reclaim the service. Position is durable.
        stopForeground(STOP_FOREGROUND_DETACH)
    }
    private fun move(delta:Int) { generation++; tts?.stop(); index=(index+delta).coerceIn(0,(chunks.size-1).coerceAtLeast(0)); if(playing) speak() else publish() }
    private fun rate(value:Float) { speed=value.coerceIn(.5f,2f); generation++; tts?.stop(); if(playing) speak() else publish() }
    private fun publish() {
        snapshot=ReadingSnapshot(text,index,chunks.size,playing,speed,error)
        WorkspaceStore(this).use { it.put("reading-text",text); it.put("reading-index",index.toString()); it.put("reading-speed",speed.toString()) }
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if(playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,PlaybackState.PLAYBACK_POSITION_UNKNOWN,if(playing) speed else 0f).build())
        getSystemService(NotificationManager::class.java).notify(42,notification())
    }
    private fun notification():Notification {
        val flags=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        fun command(action:String)=PendingIntent.getService(this,action.hashCode(),Intent(this,ReadingService::class.java).setAction(action),flags)
        val open=PendingIntent.getActivity(this,42,Intent(this,ReadingActivity::class.java),flags)
        return Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_surface).setContentTitle("Ægentica AI · Read aloud")
            .setContentText(if(playing) "Reading · ${index+1} of ${chunks.size}" else "Paused · Resume in the reader")
            .setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setOngoing(playing)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,android.R.drawable.ic_media_previous),"Previous",command("previous")).build())
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,if(playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),if(playing) "Pause" else "Play",command(if(playing) "pause" else "play")).build())
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,android.R.drawable.ic_menu_close_clear_cancel),"Stop",command("stop")).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0,1,2)).build()
    }
    override fun onDestroy() {
        generation++; playing=false; tts?.stop(); tts?.shutdown()
        if(focusHeld) audio.abandonAudioFocusRequest(focus)
        snapshot=snapshot.copy(playing=false)
        unregisterReceiver(noisy); session.release(); active=null
        getSystemService(NotificationManager::class.java).cancel(42)
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL="reader"
        private var active:ReadingService?=null
        var snapshot=ReadingSnapshot(); private set
        fun pauseForCapture() { active?.pause() }
        fun speed(value:Float) { active?.rate(value) }
        fun toggle(context:Context) {
            active?.let { if(it.playing) it.pause() else it.resume(); return }
            context.startForegroundService(Intent(context,ReadingService::class.java))
        }
        fun move(delta:Int) { active?.move(delta) }
        fun start(context:Context,text:String) {
            context.startForegroundService(Intent(context,ReadingService::class.java).putExtra("text",Markdown.strip(text)))
            context.startActivity(Intent(context,ReadingActivity::class.java))
        }
        fun segments(text:String):List<String> {
            val iterator=BreakIterator.getSentenceInstance(Locale.getDefault()); iterator.setText(text)
            val out=mutableListOf<String>(); var begin=iterator.first(); var end=iterator.next()
            while(end!=BreakIterator.DONE) {
                var rest=text.substring(begin,end)
                while(rest.length>2000) { var cut=2000; if(rest[cut-1].isHighSurrogate()) cut--; out+=rest.take(cut); rest=rest.drop(cut) }
                if(rest.isNotBlank()) out+=rest
                begin=end; end=iterator.next()
            }
            return out
        }
    }
}
data class ReadingSnapshot(val text:String="",val index:Int=0,val count:Int=0,val playing:Boolean=false,val speed:Float=1f,val error:String="")
