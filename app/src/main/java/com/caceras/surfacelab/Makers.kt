package com.caceras.surfacelab

/**
 * Model makers' logos, as aegentica.se shows them beside a model (its public/brands/, monochrome,
 * tinted in the text colour). Keyed by the OpenRouter id's prefix.
 */
object Makers {
    private val LOGOS = mapOf(
        "anthropic" to R.drawable.maker_anthropic,
        "openai" to R.drawable.maker_openai,
        "google" to R.drawable.maker_google,
        "meta-llama" to R.drawable.maker_meta,
        "mistralai" to R.drawable.maker_mistral,
        "deepseek" to R.drawable.maker_deepseek,
        "x-ai" to R.drawable.maker_xai,
        "moonshotai" to R.drawable.maker_moonshot,
        "qwen" to R.drawable.maker_qwen,
        "z-ai" to R.drawable.maker_zai,
        "openrouter" to R.drawable.maker_openrouter,
    )

    /** The maker's logo for an OpenRouter model id, or null for a maker without one here. */
    fun logo(modelId: String): Int? = LOGOS[modelId.removePrefix("~").substringBefore('/')]
}
