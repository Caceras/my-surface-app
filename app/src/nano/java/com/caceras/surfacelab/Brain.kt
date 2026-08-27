package com.caceras.surfacelab

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.proofreading.ProofreadingRequest
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.rewriting.RewritingRequest
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.SummarizerOptions
import java.util.concurrent.Executor

/**
 * The on-device brain. Every call here goes to Android AICore, the system
 * service that hosts Gemini Nano. No model is bundled in the APK and no
 * request reaches the network -- turn off wifi and mobile data and it still
 * works, which is the whole point.
 *
 * Two things the sample code on the docs site gets wrong, both fixed here:
 * these methods return Guava ListenableFuture, not a Play Services Task, and
 * FeatureStatus is a plain int constant rather than an enum.
 */
object BrainProvider {
    fun get(): SurfaceBrain = NanoBrain
}

private val main = Handler(Looper.getMainLooper())
private val mainExecutor = Executor { command -> main.post(command) }

/** Adapter over the three unrelated ML Kit clients, which share no base type. */
private interface Engine {
    fun check(): ListenableFuture<Int>
    fun download(callback: DownloadCallback): ListenableFuture<Void>
    fun infer(onText: (String) -> Unit, onFail: (String) -> Unit)
    fun close()
}

private object NanoBrain : SurfaceBrain {

    override val tasks = listOf(Task.SUMMARIZE, Task.PROOFREAD, Task.REWRITE)

    override fun status(context: Context, onStatus: (BrainStatus) -> Unit) {
        val engine = engineFor(context, Task.SUMMARIZE, "status probe")
        engine.check().whenDone { result ->
            engine.close()
            onStatus(describe(result.getOrNull(), result.exceptionOrNull()))
        }
    }

    override fun prepare(context: Context, onStatus: (BrainStatus) -> Unit) {
        val engine = engineFor(context, Task.SUMMARIZE, "status probe")
        engine.check().whenDone { result ->
            when (result.getOrNull()) {
                FeatureStatus.DOWNLOADABLE -> {
                    onStatus(BrainStatus("Downloading model...", ready = false))
                    engine.download(object : DownloadCallback {
                        override fun onDownloadStarted(bytes: Long) {}
                        override fun onDownloadProgress(bytes: Long) {}
                        override fun onDownloadCompleted() {
                            main.post {
                                engine.close()
                                onStatus(BrainStatus("Nano ready - fully offline", ready = true))
                            }
                        }

                        override fun onDownloadFailed(e: GenAiException) {
                            main.post {
                                engine.close()
                                onStatus(BrainStatus("Download failed: " + e.message, ready = false))
                            }
                        }
                    })
                }
                else -> {
                    engine.close()
                    onStatus(describe(result.getOrNull(), result.exceptionOrNull()))
                }
            }
        }
    }

    override fun run(
        context: Context,
        task: Task,
        input: String,
        onResult: (BrainResult) -> Unit
    ) {
        val engine = engineFor(context, task, input)

        fun finish(result: BrainResult) {
            engine.close()
            onResult(result)
        }

        engine.check().whenDone { checked ->
            when (checked.getOrNull()) {
                FeatureStatus.AVAILABLE ->
                    engine.infer(
                        { text -> finish(BrainResult(text, ok = true)) },
                        { why -> finish(BrainResult.failure(why)) }
                    )

                FeatureStatus.DOWNLOADABLE ->
                    engine.download(object : DownloadCallback {
                        override fun onDownloadStarted(bytes: Long) {}
                        override fun onDownloadProgress(bytes: Long) {}
                        override fun onDownloadCompleted() {
                            main.post {
                                engine.infer(
                                    { text -> finish(BrainResult(text, ok = true)) },
                                    { why -> finish(BrainResult.failure(why)) }
                                )
                            }
                        }

                        override fun onDownloadFailed(e: GenAiException) {
                            main.post {
                                finish(BrainResult.failure(
                                    "The model could not be downloaded: " + e.message
                                ))
                            }
                        }
                    })

                FeatureStatus.DOWNLOADING ->
                    finish(BrainResult.failure(
                        "The model is still downloading. Try again shortly."
                    ))

                else -> finish(BrainResult.failure(unavailableMessage()))
            }
        }
    }
}

private fun describe(code: Int?, error: Throwable?): BrainStatus = when (code) {
    FeatureStatus.AVAILABLE ->
        BrainStatus("Nano ready - fully offline", ready = true)
    FeatureStatus.DOWNLOADABLE ->
        BrainStatus("Model not downloaded - tap to fetch it", ready = false, preparable = true)
    FeatureStatus.DOWNLOADING ->
        BrainStatus("Model downloading...", ready = false)
    FeatureStatus.UNAVAILABLE ->
        BrainStatus("Not supported on this device", ready = false)
    else ->
        BrainStatus("Could not reach AICore" + (error?.message?.let { ": $it" } ?: ""), ready = false)
}

private fun unavailableMessage() =
    "Gemini Nano is not available here. It needs a supported Pixel with a " +
        "locked bootloader and a current version of the Android AICore system app."

/** Runs the callback on the main thread, exactly once, success or failure. */
private fun <T> ListenableFuture<T>.whenDone(callback: (Result<T>) -> Unit) {
    addListener({ callback(runCatching { get() }) }, mainExecutor)
}

private fun engineFor(context: Context, task: Task, input: String): Engine = when (task) {

    Task.SUMMARIZE -> {
        val client = Summarization.getClient(
            SummarizerOptions.builder(context)
                .setInputType(SummarizerOptions.InputType.ARTICLE)
                .setOutputType(SummarizerOptions.OutputType.THREE_BULLETS)
                .setLanguage(SummarizerOptions.Language.ENGLISH)
                // Without this, anything over the window fails outright
                // instead of being trimmed.
                .setLongInputAutoTruncationEnabled(true)
                .build()
        )
        object : Engine {
            override fun check() = client.checkFeatureStatus()
            override fun download(callback: DownloadCallback) = client.downloadFeature(callback)
            override fun close() = client.close()
            override fun infer(onText: (String) -> Unit, onFail: (String) -> Unit) {
                client.runInference(SummarizationRequest.builder(input).build())
                    .whenDone { r ->
                        val summary = r.getOrNull()?.summary
                        if (summary.isNullOrBlank()) onFail(reason(r)) else onText(summary)
                    }
            }
        }
    }

    Task.PROOFREAD -> {
        val client = Proofreading.getClient(
            ProofreaderOptions.builder(context)
                .setInputType(ProofreaderOptions.InputType.KEYBOARD)
                .setLanguage(ProofreaderOptions.Language.ENGLISH)
                .build()
        )
        object : Engine {
            override fun check() = client.checkFeatureStatus()
            override fun download(callback: DownloadCallback) = client.downloadFeature(callback)
            override fun close() = client.close()
            override fun infer(onText: (String) -> Unit, onFail: (String) -> Unit) {
                client.runInference(ProofreadingRequest.builder(input).build())
                    .whenDone { r ->
                        val results = r.getOrNull()?.results
                        // Suggestions come back sorted by descending
                        // confidence, so the first one is the answer.
                        if (results == null || results.isEmpty()) onFail(reason(r))
                        else onText(results[0].text)
                    }
            }
        }
    }

    Task.REWRITE -> {
        val client = Rewriting.getClient(
            RewriterOptions.builder(context)
                .setOutputType(RewriterOptions.OutputType.PROFESSIONAL)
                .setLanguage(RewriterOptions.Language.ENGLISH)
                .build()
        )
        object : Engine {
            override fun check() = client.checkFeatureStatus()
            override fun download(callback: DownloadCallback) = client.downloadFeature(callback)
            override fun close() = client.close()
            override fun infer(onText: (String) -> Unit, onFail: (String) -> Unit) {
                client.runInference(RewritingRequest.builder(input).build())
                    .whenDone { r ->
                        val results = r.getOrNull()?.results
                        if (results == null || results.isEmpty()) onFail(reason(r))
                        else onText(results[0].text)
                    }
            }
        }
    }

    Task.UPPERCASE -> error("The nano flavour does not register an Uppercase surface")
}

private fun reason(result: Result<*>): String =
    result.exceptionOrNull()?.message
        ?: "The model returned nothing. Very short input is the usual cause."
