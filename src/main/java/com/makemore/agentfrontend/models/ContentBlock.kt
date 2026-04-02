package com.makemore.agentfrontend.models

/**
 * Content block types for structured rich UI elements.
 * Mirrors the JSON schema from the server's content_blocks module.
 */

/** Base sealed interface for all content blocks. */
sealed interface ContentBlock {
    val type: String

    companion object {
        /** Parse a list of raw JSON maps into typed ContentBlock instances. */
        @Suppress("UNCHECKED_CAST")
        fun parse(list: List<Map<String, Any?>>): List<ContentBlock> = list.mapNotNull { map ->
            when (map["type"] as? String) {
                "card" -> CardBlock.fromMap(map)
                "cardList" -> CardListBlock.fromMap(map)
                "actionButtons" -> ActionButtonsBlock.fromMap(map)
                "callout" -> CalloutBlock.fromMap(map)
                "image" -> ImageBlock.fromMap(map)
                "divider" -> DividerBlock
                "table" -> TableBlock.fromMap(map)
                "code" -> CodeBlockData.fromMap(map)
                "file" -> FileBlock.fromMap(map)
                "collapsible" -> CollapsibleBlock.fromMap(map)
                "status" -> StatusBlock.fromMap(map)
                "location" -> LocationBlock.fromMap(map)
                else -> null
            }
        }
    }
}

/** Action on a block (link, message send, or callback). */
data class BlockAction(
    val type: String,           // "link" | "message" | "callback"
    val label: String,
    val url: String? = null,
    val message: String? = null,
    val callbackId: String? = null,
    val style: String? = "primary"
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): BlockAction = BlockAction(
            type = map["type"] as? String ?: "link",
            label = map["label"] as? String ?: "",
            url = map["url"] as? String,
            message = map["message"] as? String,
            callbackId = map["callbackId"] as? String,
            style = map["style"] as? String ?: "primary"
        )
    }
}

data class MetadataPair(val label: String, val value: String) {
    companion object {
        fun fromMap(map: Map<String, Any?>): MetadataPair = MetadataPair(
            label = map["label"] as? String ?: "",
            value = map["value"] as? String ?: ""
        )
    }
}

// Individual block types

@Suppress("UNCHECKED_CAST")
data class CardBlock(
    override val type: String = "card",
    val title: String = "",
    val subtitle: String? = null,
    val image: String? = null,
    val badge: String? = null,
    val metadata: List<MetadataPair>? = null,
    val actions: List<BlockAction>? = null
) : ContentBlock {
    companion object {
        fun fromMap(map: Map<String, Any?>): CardBlock = CardBlock(
            title = map["title"] as? String ?: "",
            subtitle = map["subtitle"] as? String,
            image = map["image"] as? String,
            badge = map["badge"] as? String,
            metadata = (map["metadata"] as? List<Map<String, Any?>>)?.map { MetadataPair.fromMap(it) },
            actions = (map["actions"] as? List<Map<String, Any?>>)?.map { BlockAction.fromMap(it) }
        )
    }
}

@Suppress("UNCHECKED_CAST")
data class CardListBlock(
    override val type: String = "cardList",
    val layout: String? = "vertical",
    val items: List<CardBlock> = emptyList()
) : ContentBlock {
    companion object {
        fun fromMap(map: Map<String, Any?>): CardListBlock = CardListBlock(
            layout = map["layout"] as? String ?: "vertical",
            items = (map["items"] as? List<Map<String, Any?>>)?.map { CardBlock.fromMap(it) } ?: emptyList()
        )
    }
}

@Suppress("UNCHECKED_CAST")
data class ActionButtonsBlock(
    override val type: String = "actionButtons",
    val buttons: List<BlockAction> = emptyList()
) : ContentBlock {
    companion object {
        fun fromMap(map: Map<String, Any?>): ActionButtonsBlock = ActionButtonsBlock(
            buttons = (map["buttons"] as? List<Map<String, Any?>>)?.map { BlockAction.fromMap(it) } ?: emptyList()
        )
    }
}

data class CalloutBlock(
    override val type: String = "callout",
    val style: String? = "info",
    val title: String? = null,
    val body: String = ""
) : ContentBlock {
    companion object {
        fun fromMap(map: Map<String, Any?>): CalloutBlock = CalloutBlock(
            style = map["style"] as? String ?: "info",
            title = map["title"] as? String,
            body = map["body"] as? String ?: ""
        )
    }
}

data class ImageBlock(
    override val type: String = "image",
    val url: String = "",
    val alt: String? = null,
    val caption: String? = null
) : ContentBlock {
    companion object {
        fun fromMap(map: Map<String, Any?>): ImageBlock = ImageBlock(
            url = map["url"] as? String ?: "",
            alt = map["alt"] as? String,
            caption = map["caption"] as? String
        )
    }
}

object DividerBlock : ContentBlock {
    override val type: String = "divider"
}



@Suppress("UNCHECKED_CAST")
data class TableBlock(
    override val type: String = "table",
    val headers: List<String>? = null,
    val rows: List<List<String>>? = null
) : ContentBlock {
    companion object {
        fun fromMap(map: Map<String, Any?>): TableBlock = TableBlock(
            headers = (map["headers"] as? List<*>)?.filterIsInstance<String>(),
            rows = (map["rows"] as? List<List<*>>)?.map { row -> row.map { it?.toString() ?: "" } }
        )
    }
}

data class CodeBlockData(
    override val type: String = "code",
    val language: String? = null,
    val code: String = "",
    val filename: String? = null,
    val copyable: Boolean = true
) : ContentBlock {
    companion object {
        fun fromMap(map: Map<String, Any?>): CodeBlockData = CodeBlockData(
            language = map["language"] as? String,
            code = map["code"] as? String ?: "",
            filename = map["filename"] as? String,
            copyable = map["copyable"] as? Boolean ?: true
        )
    }
}

data class FileBlock(
    override val type: String = "file",
    val filename: String = "",
    val url: String = "",
    val mimeType: String? = null,
    val size: Int? = null
) : ContentBlock {
    companion object {
        fun fromMap(map: Map<String, Any?>): FileBlock = FileBlock(
            filename = map["filename"] as? String ?: "",
            url = map["url"] as? String ?: "",
            mimeType = map["mimeType"] as? String,
            size = (map["size"] as? Number)?.toInt()
        )
    }
}

data class CollapsibleBlock(
    override val type: String = "collapsible",
    val title: String = "",
    val body: String = "",
    val defaultOpen: Boolean = false
) : ContentBlock {
    companion object {
        fun fromMap(map: Map<String, Any?>): CollapsibleBlock = CollapsibleBlock(
            title = map["title"] as? String ?: "",
            body = map["body"] as? String ?: "",
            defaultOpen = map["defaultOpen"] as? Boolean ?: false
        )
    }
}

data class StatusBlock(
    override val type: String = "status",
    val state: String? = "info",
    val title: String = "",
    val body: String? = null,
    val progress: Double? = null
) : ContentBlock {
    companion object {
        fun fromMap(map: Map<String, Any?>): StatusBlock = StatusBlock(
            state = map["state"] as? String ?: "info",
            title = map["title"] as? String ?: "",
            body = map["body"] as? String,
            progress = (map["progress"] as? Number)?.toDouble()
        )
    }
}

data class LocationBlock(
    override val type: String = "location",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val label: String = "",
    val zoom: Int? = null
) : ContentBlock {
    companion object {
        fun fromMap(map: Map<String, Any?>): LocationBlock = LocationBlock(
            latitude = (map["latitude"] as? Number)?.toDouble() ?: 0.0,
            longitude = (map["longitude"] as? Number)?.toDouble() ?: 0.0,
            label = map["label"] as? String ?: "",
            zoom = (map["zoom"] as? Number)?.toInt()
        )
    }
}
