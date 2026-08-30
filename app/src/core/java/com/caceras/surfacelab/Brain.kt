package com.caceras.surfacelab

import android.content.Context
import java.util.Locale

/**
 * The zero-dependency brain. It does something deliberately dumb so that the
 * plumbing -- selection popup, tile, widget, share sheet -- can be proved to
 * work before any model is involved.
 *
 * Every flavour ships exactly one BrainProvider with this signature. Nothing
 * else in the app has to know which one it got.
 */
object BrainProvider {
    fun get(): SurfaceBrain = CoreBrain
}

private object CoreBrain : SurfaceBrain {

    override val tasks = listOf(Task.UPPERCASE)

    override fun status(context: Context, onStatus: (BrainStatus) -> Unit) {
        onStatus(BrainStatus("No model - framework APIs only", ready = true))
    }

    override fun prepare(context: Context, onStatus: (BrainStatus) -> Unit) {
        status(context, onStatus)
    }

    override fun run(
        context: Context,
        task: Task,
        input: String,
        instruction: String,
        onPartial: (String) -> Unit,
        onResult: (BrainResult) -> Unit
    ) {
        // The chat and the hands-free path have no selection, so the words
        // arrive as the instruction. Handling that here rather than in the
        // activity keeps the rule that no surface branches on which brain
        // it happens to be talking to.
        val material = input.ifBlank { instruction }
        onResult(BrainResult(material.uppercase(Locale.getDefault()), ok = true))
    }
}
