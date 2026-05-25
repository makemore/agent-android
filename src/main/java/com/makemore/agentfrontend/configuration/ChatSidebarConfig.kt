package com.makemore.agentfrontend.configuration

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Static nav entry shown in the chat sidebar above "Recents". The
 * library ships a default set (Chats, Projects, Artifacts, Code,
 * Dispatch) but host apps can supply their own list — every item is
 * rendered as a plain icon + label row with the supplied handler
 * invoked on tap.
 *
 * Mirrors the iOS `SidebarItem` struct.
 */
data class SidebarItem(
    val id: String,
    /** Material vector icon. Drawn next to the row label. */
    val icon: ImageVector,
    /** Row label. */
    val title: String,
    /** Optional trailing badge text (e.g. "New"). */
    val badge: String? = null,
    /** Tap handler. Defaults to a no-op so hosts can build the list
     *  declaratively and wire actions later. */
    val onClick: () -> Unit = {},
)

/**
 * Controls the slide-in conversation sidebar shown by the bundled
 * `ChatWidgetView`. Disabled by default in this data type so existing
 * consumers see no change; the library's bundled widget opts in for
 * its new warm-dark baseline.
 *
 * Mirrors the iOS `ChatSidebarConfig` struct field-for-field.
 */
data class ChatSidebarConfig(
    /** When `true`, `ChatWidgetView` renders a hamburger button in
     *  its top bar that toggles a slide-in panel. */
    val enabled: Boolean = false,
    /** Brand wordmark rendered at the top of the panel. Empty string
     *  hides the wordmark. */
    val wordmark: String = "S'Ai",
    /** Static nav items rendered above the "Recents" section. Host
     *  apps override this to integrate their own destinations. */
    val items: List<SidebarItem> = defaultItems(),
    /** Section heading for the dynamic conversation list. Set to
     *  empty string to suppress the heading. */
    val recentsTitle: String = "Recents",
    /** When `true`, the panel queries `APIClient.loadConversations`
     *  on appear and renders one row per result. When `false` the
     *  recents section is suppressed. */
    val showRecents: Boolean = true,
    /** Maximum recents to fetch / render. */
    val recentsLimit: Int = 30,
    /** Initials shown in the footer avatar (e.g. "CB"). When `null`
     *  the avatar circle is rendered without text. */
    val footerInitials: String? = null,
    /** Footer caption shown next to the avatar (e.g. user's full
     *  name or plan tier). */
    val footerCaption: String? = null,
    /** Label for the footer's primary "New chat" pill. Set to empty
     *  string to hide the button. */
    val newChatLabel: String = "New chat",
) {
    companion object {
        /** Starter rows shipped with the library. Action defaults to a
         *  no-op; host apps replace the list to wire real destinations. */
        fun defaultItems(): List<SidebarItem> = listOf(
            SidebarItem("chats", Icons.AutoMirrored.Outlined.Chat, "Chats"),
            SidebarItem("projects", Icons.Outlined.Folder, "Projects"),
            SidebarItem("artifacts", Icons.Outlined.Layers, "Artifacts"),
            SidebarItem("code", Icons.Outlined.Code, "Code"),
            SidebarItem("dispatch", Icons.AutoMirrored.Outlined.Send, "Dispatch"),
        )
    }
}
