package com.brain.tracscript.core

interface Plugin {
    val id: String
    val displayName: String

    /** Вызывается один раз, когда плагин подключают к ядру */
    fun onAttach(context: PluginContext)

    /** Вызывается при остановке/уничтожении ядра */
    fun onDetach()

    /**
     * Включён ли плагин.
     * Реализация сама решает, откуда брать флаг (настройки, лицензия и т.п.).
     */
    fun isEnabled(): Boolean
}
