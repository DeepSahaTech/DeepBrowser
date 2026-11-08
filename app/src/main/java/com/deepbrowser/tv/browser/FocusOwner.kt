package com.deepbrowser.tv.browser

/** Which layer owns the remote right now. Exactly one does; the others are out of the focus tree. */
enum class FocusOwner {
    PANEL, COMMAND_BAR, INFO, HOME, PAGE;

    /** True for the native (Compose) layers: they get D-pad, OK and Back as-is. */
    val isCompose: Boolean get() = this != PAGE

    companion object {
        /** Priority: Panel > CommandBar > Info > Home > Page. */
        fun of(panelOpen: Boolean, commandBar: Boolean, info: Boolean, isHome: Boolean): FocusOwner = when {
            panelOpen -> PANEL
            commandBar -> COMMAND_BAR
            info -> INFO
            isHome -> HOME
            else -> PAGE
        }
    }
}
