package com.caceras.surfacelab

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.StreamingCallback
import com.google.mlkit.genai.prompt.Content
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.java.GenerativeModelFutures
import java.util.concurrent.Executor

/**
 * The on-device brain: Gemini Nano through the ML Kit Prompt API.
 *
 * This is a general generative client, not a fixed menu of tasks. The user
 * writes the prompt; "Summarise" and the other presets are ordinary prompts
 * with a system instruction, defined in Prompts.kt. That is deliberate --
 * the narrower task APIs (genai-summarization and friends) cap you at
 * English, Japanese and Korean, and cannot be asked anything else.
 *
 * Every call goes to Android AICore. No model is bundled and nothing reaches
 * the network -- turn off wifi and mobile data and it still answers.
 *
 * Two things the surrounding docs get wrong, both handled here: these APIs
 * hand back Guava ListenableFuture rather than a Play Services Task, and
 * FeatureStatus is an int constant rather than an enum.
 */
object BrainProvider {
    fun get(): SurfaceBrain = NanoBrain
}

private val main = Handler(Looper.getMainLooper())
private val mainExecutor = Executor { command -> main.post(command) }

private fun model(): GenerativeModelFutures =
    GenerativeModelFutures.from(Generation.getClient())

private object NanoBrain : SurfaceBrain {

    override val tasks = listOf(Task.ASK, Task.SUMMARIZE, Task.PROOFREAD, Task.REWRITE)

    override fun status(context: Context, onStatus: (BrainStatus) -> Unit) {
        model().checkStatus().whenDone { result ->
            onStatus(describe(result.getOrNull(), result.exceptionOrNull()))
        }
    }

    override fun prepare(context: Context, onStatus: (BrainStatus) -> Unit) {
        val client = model()
        client.checkStatus().whenDone { result ->
            if (result.getOrNull() != FeatureStatus.DOWNLOADABLE) {
                onStatus(describe(result.getOrNull(), result.exceptionOrNull()))
                return@whenDone
            }
            onStatus(BrainStatus("Downloading model...", ready = false))
            client.download(object : DownloadCallback {
                override fun onDownloadStarted(bytes: Long) {}
                override fun onDownloadProgress(bytes: Long) {}

                override fun onDownloadCompleted() {
                    main.post {
                        onStatus(BrainStatus("Nano ready - fully offline", ready = true))
                    }
                }

                override fun onDownloadFailed(e: GenAiException) {
                    main.post {
                        onStatus(BrainStatus("Download failed: " + e.message, ready = false))
                    }
                }
            })
        }
    }

    override fun run(
        context: Context,
        task: Task,
        input: String,
        instruction: String,
        onPartial: (String) -> Unit,
        onResult: (BrainResult) -> Unit
    ) {
        val client = model()
        val prompt = Prompts.user(task, input, instruction)

        if (prompt.isBlank()) {
            onResult(BrainResult.failure("Nothing to send."))
            return
        }

        client.checkStatus().whenDone { checked ->
            when (checked.getOrNull()) {
                FeatureStatus.AVAILABLE ->
                    generate(client, task, prompt, onPartial, onResult)

                FeatureStatus.DOWNLOADABLE ->
                    client.download(object : DownloadCallback {
                        override fun onDownloadStarted(bytes: Long) {}
                        override fun onDownloadProgress(bytes: Long) {}

                        override fun onDownloadCompleted() {
                            main.post { generate(client, task, prompt, onPartial, onResult) }
                        }

                        override fun onDownloadFailed(e: GenAiException) {
                            main.post {
                                onResult(BrainResult.failure(
                                    "The model could not be downloaded: " + e.message
                                ))
                            }
                        }
                    })

                FeatureStatus.DOWNLOADING ->
                    onResult(BrainResult.failure(
                        "The model is still downloading. Try again shortly."
                    ))

                else -> onResult(BrainResult.failure(unavailableMessage()))
            }
        }
    }
}

/**
 * System instructions are not supported on every device, so the instruction
 * is folded into the prompt when the model cannot take it separately. Getting
 * this wrong looks like the model ignoring its instructions for no reason.
 */
private fun generate(
    client: GenerativeModelFutures,
    task: Task,
    prompt: String,
    onPartial: (String) -> Unit,
    onResult: (BrainResult) -> Unit
) {
    val instruction = Prompts.system(task)

    client.isSystemPromptAvailable().whenDone { supported ->
        val useSystem = supported.getOrNull() == true && instruction.isNotEmpty()

        val text = if (useSystem || instruction.isEmpty()) prompt
                   else instruction + "\n\n" + prompt

        val builder = GenerateContentRequest.Builder(
            listOf(Content.Builder().text(text).build())
        )
        // Low temperature: these are transformations of the user's own text,
        // not creative writing. Raise it for the Ask preset if you disagree.
        builder.temperature = if (task == Task.ASK) 0.7f else 0.2f
        builder.maxOutputTokens = 512
        if (useSystem) builder.systemInstruction = SystemInstruction(instruction)

        val collected = StringBuilder()
        val streaming = StreamingCallback { chunk ->
            collected.append(chunk)
            main.post { onPartial(collected.toString()) }
        }

        client.generateContent(builder.build(), streaming).whenDone { response ->
            val answer = response.getOrNull()
                ?.candidates
                ?.firstOrNull()
                ?.text
                ?.trim()
                .orEmpty()
                .ifEmpty { collected.toString().trim() }

            if (answer.isEmpty()) {
                onResult(BrainResult.failure(reason(response)))
            } else {
                onResult(BrainResult(answer, ok = true))
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

private fun reason(result: Result<*>): String =
    result.exceptionOrNull()?.message
        ?: "The model returned nothing. Try rephrasing, or shorten the selection."
