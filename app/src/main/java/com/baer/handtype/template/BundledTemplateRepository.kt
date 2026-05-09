package com.baer.handtype.template

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import com.baer.handtype.R
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
    val isNew: Boolean = false,
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

    private val builtInTemplates = listOf(
        TemplateDescriptor(
            id = "classic_script",
            displayName = context.getString(R.string.template_ink_script_name),
            sampleText = "Meet HandType.",
            description = context.getString(R.string.template_ink_script_description),
            premium = false,
        ),
        TemplateDescriptor(
            id = "flowing_cursive",
            displayName = context.getString(R.string.template_flowing_cursive_name),
            sampleText = "Elegant flow.",
            description = context.getString(R.string.template_flowing_cursive_description),
            premium = false,
            fontFamily = "cursive",
        ),
    )

    private val premiumTemplateDescriptor = TemplateDescriptor(
        id = "custom_capture",
        displayName = context.getString(R.string.premium_default_template_name),
        sampleText = context.getString(R.string.template_premium_sample),
        description = context.getString(R.string.template_premium_description),
        premium = true,
    )

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

        // Stroke-based template (cursive system-font renderer; strokeData unused at runtime)
        if (descriptor.fontFamily != null) {
            return@withContext HandwritingTemplate(
                descriptor = descriptor,
                glyphs = emptyMap(),
                strokeData = emptyMap(),
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

        private val supportedCharacters: List<Char> = buildList {
            addAll(('A'..'Z').toList())
            addAll(('a'..'z').toList())
            addAll(('0'..'9').toList())
            addAll(listOf('.', ',', '!', '?', '\'', '-'))
        }
    }
}

private fun Char.toVariantFileName(variant: Int): String = "u%04X_v%d.png".format(code, variant)