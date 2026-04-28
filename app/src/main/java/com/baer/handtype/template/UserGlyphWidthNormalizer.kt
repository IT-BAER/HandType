package com.baer.handtype.template

internal object UserGlyphWidthNormalizer {

    fun scales(
        character: Char,
        templateId: String,
        glyphWidthPx: Int,
        glyphHeightPx: Int,
        targetInkHeightPx: Int,
    ): Pair<Float, Float> {
        if (glyphWidthPx <= 0 || glyphHeightPx <= 0 || targetInkHeightPx <= 0) {
            return 1f to 1f
        }

        val scaleY = targetInkHeightPx.toFloat() / glyphHeightPx.toFloat()
        if (!templateId.startsWith(UserTemplateRepository.USER_ID_PREFIX) || !character.isLowerCase()) {
            return scaleY to scaleY
        }

        val maxAspect = when (character.lowercaseChar()) {
            'm', 'w' -> 1.02f
            'n', 'u', 'h' -> 0.88f
            'a', 'c', 'e', 'o', 's', 'v', 'x', 'z' -> 0.78f
            else -> 0.84f
        }
        val currentAspect = glyphWidthPx.toFloat() / glyphHeightPx.toFloat()
        if (currentAspect <= maxAspect) {
            return scaleY to scaleY
        }

        val scaleX = (targetInkHeightPx.toFloat() * maxAspect) / glyphWidthPx.toFloat()
        return scaleX to scaleY
    }
}