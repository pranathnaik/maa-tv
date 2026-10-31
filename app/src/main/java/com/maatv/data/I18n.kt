package com.maatv.data

/**
 * English / Hindi for everything Mom sees. The choice lives in [AppData.language];
 * the view model keeps [hindi] in step with it.
 */
object I18n {
    @Volatile var hindi: Boolean = false

    fun t(en: String, hi: String): String = if (hindi) hi else en
}
