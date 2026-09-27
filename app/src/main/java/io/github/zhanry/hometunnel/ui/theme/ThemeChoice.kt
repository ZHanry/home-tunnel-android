package io.github.zhanry.hometunnel.ui.theme

/** Device-wide appearance. Stored as the enum name, never as an account credential. */
enum class ThemeChoice {
    SYSTEM,
    LIGHT,
    DARK;

    fun nightMode(): Int = when (this) {
        SYSTEM -> NIGHT_FOLLOW_SYSTEM
        LIGHT -> NIGHT_NO
        DARK -> NIGHT_YES
    }

    companion object {
        const val NIGHT_FOLLOW_SYSTEM = -1
        const val NIGHT_NO = 1
        const val NIGHT_YES = 2

        fun fromStored(value: String?): ThemeChoice = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}
