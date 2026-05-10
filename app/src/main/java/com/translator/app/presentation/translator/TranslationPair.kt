package com.translator.app.presentation.translator

data class TranslationPair(
    val id: Long,
    val originalText: String = "",
    val translationText: String = "",
    val originalIsFinal: Boolean = false,
    val translationIsFinal: Boolean = false,
    val originalIsRefined: Boolean = false,
    val translationIsRefined: Boolean = false,
    val originalLang: String = "",
    val translationLang: String = "",
)