package com.baer.handtype.template

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

data class TemplateDescriptor(
    val id: String,
    val displayName: String,
    val sampleText: String,
    val description: String,
    val premium: Boolean,
    val fontFamily: String? = null,
)

data class HandwritingTemplate(
    val descriptor: TemplateDescriptor,
    val glyphs: Map<Char, List<Bitmap>>,
    val typeface: Typeface? = null,
    val strokeData: Map<String, HandwritingBitmapRenderer.GlyphOutline>? = null,
) {
    val isStrokeBased: Boolean get() = strokeData != null
}

class BundledTemplateRepository(private val context: Context) {

    private val userRepository = UserTemplateRepository(context)

    fun listBuiltInTemplates(): List<TemplateDescriptor> = builtInTemplates

    fun listUserTemplates(): List<TemplateDescriptor> = userRepository.listUserTemplates()

    fun premiumTemplate(): TemplateDescriptor = premiumTemplateDescriptor

    /** Exposes the user-template store for save operations during the premium capture flow. */
    fun userRepository(): UserTemplateRepository = userRepository

    /** Deletes a user-captured template. No-op for built-in templates. */
    fun deleteUserTemplate(templateId: String): Boolean =
        if (userRepository.isUserTemplateId(templateId)) userRepository.deleteTemplate(templateId)
        else false

    suspend fun loadTemplate(templateId: String): HandwritingTemplate = withContext(Dispatchers.IO) {
        if (userRepository.isUserTemplateId(templateId)) {
            return@withContext userRepository.loadTemplate(templateId)
        }
        val descriptor = builtInTemplates.firstOrNull { it.id == templateId }
            ?: error("Unknown bundled template id: $templateId")

        // Stroke-based template (uses pre-computed pen strokes from font skeletons)
        if (descriptor.fontFamily != null) {
            val json = context.assets.open("strokes/stroke_data.json")
                .bufferedReader().use { it.readText() }
            val strokeData = HandwritingBitmapRenderer.parseStrokeData(json)
            return@withContext HandwritingTemplate(
                descriptor = descriptor,
                glyphs = emptyMap(),
                strokeData = strokeData,
            )
        }

        // Glyph-based template
        val glyphs = linkedMapOf<Char, List<Bitmap>>()

        supportedCharacters.forEach { character ->
            val variants = mutableListOf<Bitmap>()
            for (variantIndex in 0 until MAX_VARIANTS) {
                val assetPath = "templates/$templateId/glyphs/${character.toVariantFileName(variantIndex)}"
                val bitmap = try {
                    context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
                } catch (_: IOException) {
                    null
                }
                if (bitmap != null) {
                    variants += bitmap
                }
            }
            if (variants.isNotEmpty()) {
                glyphs[character] = variants
            }
        }

        check(glyphs.isNotEmpty()) {
            "No glyph assets were found for bundled template '$templateId'."
        }

        HandwritingTemplate(
            descriptor = descriptor,
            glyphs = glyphs,
        )
    }

    companion object {
        private const val MAX_VARIANTS = 8

        private val builtInTemplates = listOf(
            TemplateDescriptor(
                id = "classic_script",
                displayName = "Ink Script",
                sampleText = "Meet HandType.",
                description = "A natural ink-style handwriting with organic variation and hand-drawn character.",
                premium = false,
            ),
            TemplateDescriptor(
                id = "flowing_cursive",
                displayName = "Flowing Cursive",
                sampleText = "Elegant flow.",
                description = "A smooth cursive style with connected letters and natural rhythm.",
                premium = false,
                fontFamily = "cursive",
            ),
        )

        private val premiumTemplateDescriptor = TemplateDescriptor(
            id = "custom_capture",
            displayName = "My Handwriting",
            sampleText = "Capture your own style",
            description = "Premium feature: extract your personal handwriting from a photographed sample sheet.",
            premium = true,
        )

        private val supportedCharacters: List<Char> = buildList {
            addAll(('A'..'Z').toList())
            addAll(('a'..'z').toList())
            addAll(('0'..'9').toList())
            addAll(listOf('.', ',', '!', '?', '\'', '-'))
        }
    }
}

private fun Char.toVariantFileName(variant: Int): String = "u%04X_v%d.png".format(code, variant)