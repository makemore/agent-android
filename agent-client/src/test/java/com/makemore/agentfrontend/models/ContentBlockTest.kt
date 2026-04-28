package com.makemore.agentfrontend.models

import org.junit.Assert.*
import org.junit.Test

class ContentBlockTest {

    // -------------------------------------------------------------------------
    // Card
    // -------------------------------------------------------------------------

    @Test
    fun `parse card block with all fields`() {
        val json = listOf(mapOf<String, Any?>(
            "type" to "card",
            "title" to "Beach House",
            "subtitle" to "\$450/night",
            "badge" to "Featured",
            "image" to "https://example.com/beach.jpg"
        ))
        val blocks = ContentBlock.parse(json)

        assertEquals(1, blocks.size)
        val card = blocks[0] as CardBlock
        assertEquals("Beach House", card.title)
        assertEquals("\$450/night", card.subtitle)
        assertEquals("Featured", card.badge)
        assertEquals("https://example.com/beach.jpg", card.image)
    }

    @Test
    fun `parse card with metadata and actions`() {
        val json = listOf(mapOf<String, Any?>(
            "type" to "card",
            "title" to "Product",
            "metadata" to listOf(
                mapOf("label" to "Price", "value" to "\$99"),
                mapOf("label" to "Stock", "value" to "12")
            ),
            "actions" to listOf(
                mapOf("type" to "link", "label" to "View", "url" to "https://example.com", "style" to "primary"),
                mapOf("type" to "callback", "label" to "Buy", "callbackId" to "buy-123")
            )
        ))
        val blocks = ContentBlock.parse(json)

        val card = blocks[0] as CardBlock
        assertEquals(2, card.metadata?.size)
        assertEquals("Price", card.metadata?.get(0)?.label)
        assertEquals("\$99", card.metadata?.get(0)?.value)
        assertEquals(2, card.actions?.size)
        assertEquals("link", card.actions?.get(0)?.type)
        assertEquals("https://example.com", card.actions?.get(0)?.url)
        assertEquals("callback", card.actions?.get(1)?.type)
        assertEquals("buy-123", card.actions?.get(1)?.callbackId)
    }

    // -------------------------------------------------------------------------
    // Card List
    // -------------------------------------------------------------------------

    @Test
    fun `parse card list block`() {
        val json = listOf(mapOf<String, Any?>(
            "type" to "cardList",
            "layout" to "horizontal",
            "items" to listOf(
                mapOf("type" to "card", "title" to "Item 1"),
                mapOf("type" to "card", "title" to "Item 2")
            )
        ))
        val blocks = ContentBlock.parse(json)

        val list = blocks[0] as CardListBlock
        assertEquals("horizontal", list.layout)
        assertEquals(2, list.items.size)
        assertEquals("Item 1", list.items[0].title)
        assertEquals("Item 2", list.items[1].title)
    }

    // -------------------------------------------------------------------------
    // Callout
    // -------------------------------------------------------------------------

    @Test
    fun `parse callout block`() {
        val json = listOf(mapOf<String, Any?>(
            "type" to "callout",
            "style" to "warning",
            "title" to "Watch out",
            "body" to "Be careful here"
        ))
        val blocks = ContentBlock.parse(json)

        val callout = blocks[0] as CalloutBlock
        assertEquals("warning", callout.style)
        assertEquals("Watch out", callout.title)
        assertEquals("Be careful here", callout.body)
    }

    // -------------------------------------------------------------------------
    // Table
    // -------------------------------------------------------------------------

    @Test
    fun `parse table block`() {
        val json = listOf(mapOf<String, Any?>(
            "type" to "table",
            "headers" to listOf("Name", "Price"),
            "rows" to listOf(listOf("Widget", "\$10"), listOf("Gadget", "\$20"))
        ))
        val blocks = ContentBlock.parse(json)

        val table = blocks[0] as TableBlock
        assertEquals(listOf("Name", "Price"), table.headers)
        assertEquals(2, table.rows?.size)
        assertEquals(listOf("Widget", "\$10"), table.rows?.get(0))
    }

    // -------------------------------------------------------------------------
    // Code
    // -------------------------------------------------------------------------

    @Test
    fun `parse code block`() {
        val json = listOf(mapOf<String, Any?>(
            "type" to "code",
            "language" to "python",
            "code" to "print('hello')",
            "filename" to "main.py",
            "copyable" to true
        ))
        val blocks = ContentBlock.parse(json)

        val code = blocks[0] as CodeBlockData
        assertEquals("python", code.language)
        assertEquals("print('hello')", code.code)
        assertEquals("main.py", code.filename)
        assertTrue(code.copyable)
    }

    // -------------------------------------------------------------------------
    // Divider
    // -------------------------------------------------------------------------

    @Test
    fun `parse divider block`() {
        val json = listOf(mapOf<String, Any?>("type" to "divider"))
        val blocks = ContentBlock.parse(json)

        assertEquals(1, blocks.size)
        assertSame(DividerBlock, blocks[0])
    }

    // -------------------------------------------------------------------------
    // Status
    // -------------------------------------------------------------------------

    @Test
    fun `parse status block`() {
        val json = listOf(mapOf<String, Any?>(
            "type" to "status",
            "state" to "loading",
            "title" to "Processing",
            "body" to "Please wait",
            "progress" to 0.5
        ))
        val blocks = ContentBlock.parse(json)

        val status = blocks[0] as StatusBlock
        assertEquals("loading", status.state)
        assertEquals("Processing", status.title)
        assertEquals("Please wait", status.body)
        assertEquals(0.5, status.progress!!, 0.001)
    }

    // -------------------------------------------------------------------------
    // Location
    // -------------------------------------------------------------------------

    @Test
    fun `parse location block`() {
        val json = listOf(mapOf<String, Any?>(
            "type" to "location",
            "latitude" to 51.5074,
            "longitude" to -0.1278,
            "label" to "London",
            "zoom" to 12
        ))
        val blocks = ContentBlock.parse(json)

        val loc = blocks[0] as LocationBlock
        assertEquals(51.5074, loc.latitude, 0.0001)
        assertEquals(-0.1278, loc.longitude, 0.0001)
        assertEquals("London", loc.label)
        assertEquals(12, loc.zoom)
    }

    // -------------------------------------------------------------------------
    // Image
    // -------------------------------------------------------------------------

    @Test
    fun `parse image block`() {
        val json = listOf(mapOf<String, Any?>(
            "type" to "image",
            "url" to "https://example.com/pic.png",
            "alt" to "A picture",
            "caption" to "Nice view"
        ))
        val blocks = ContentBlock.parse(json)

        val img = blocks[0] as ImageBlock
        assertEquals("https://example.com/pic.png", img.url)
        assertEquals("A picture", img.alt)
        assertEquals("Nice view", img.caption)
    }

    // -------------------------------------------------------------------------
    // File
    // -------------------------------------------------------------------------

    @Test
    fun `parse file block`() {
        val json = listOf(mapOf<String, Any?>(
            "type" to "file",
            "filename" to "report.pdf",
            "url" to "https://example.com/report.pdf",
            "mimeType" to "application/pdf",
            "size" to 1024
        ))
        val blocks = ContentBlock.parse(json)

        val file = blocks[0] as FileBlock
        assertEquals("report.pdf", file.filename)
        assertEquals("https://example.com/report.pdf", file.url)
        assertEquals("application/pdf", file.mimeType)
        assertEquals(1024, file.size)
    }

    // -------------------------------------------------------------------------
    // Collapsible
    // -------------------------------------------------------------------------

    @Test
    fun `parse collapsible block`() {
        val json = listOf(mapOf<String, Any?>(
            "type" to "collapsible",
            "title" to "Details",
            "body" to "More info here",
            "defaultOpen" to true
        ))
        val blocks = ContentBlock.parse(json)

        val c = blocks[0] as CollapsibleBlock
        assertEquals("Details", c.title)
        assertEquals("More info here", c.body)
        assertTrue(c.defaultOpen)
    }

    // -------------------------------------------------------------------------
    // Action Buttons
    // -------------------------------------------------------------------------

    @Test
    fun `parse action buttons block`() {
        val json = listOf(mapOf<String, Any?>(
            "type" to "actionButtons",
            "buttons" to listOf(
                mapOf("type" to "message", "label" to "Yes", "message" to "Confirmed"),
                mapOf("type" to "link", "label" to "Docs", "url" to "https://docs.example.com")
            )
        ))
        val blocks = ContentBlock.parse(json)

        val ab = blocks[0] as ActionButtonsBlock
        assertEquals(2, ab.buttons.size)
        assertEquals("message", ab.buttons[0].type)
        assertEquals("Confirmed", ab.buttons[0].message)
        assertEquals("link", ab.buttons[1].type)
        assertEquals("https://docs.example.com", ab.buttons[1].url)
    }

    // -------------------------------------------------------------------------
    // Edge Cases
    // -------------------------------------------------------------------------

    @Test
    fun `unknown block types are skipped`() {
        val json = listOf(
            mapOf<String, Any?>("type" to "futureWidget", "data" to "something"),
            mapOf<String, Any?>("type" to "card", "title" to "Known")
        )
        val blocks = ContentBlock.parse(json)

        // Unknown types are filtered out
        assertEquals(1, blocks.size)
        assertEquals("Known", (blocks[0] as CardBlock).title)
    }

    @Test
    fun `empty array returns empty list`() {
        val blocks = ContentBlock.parse(emptyList())
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `multiple mixed block types parsed correctly`() {
        val json = listOf(
            mapOf<String, Any?>("type" to "callout", "style" to "info", "body" to "Hello"),
            mapOf<String, Any?>("type" to "divider"),
            mapOf<String, Any?>("type" to "table", "headers" to listOf("A"), "rows" to listOf(listOf("1"))),
            mapOf<String, Any?>("type" to "code", "code" to "x = 1")
        )
        val blocks = ContentBlock.parse(json)

        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is CalloutBlock)
        assertSame(DividerBlock, blocks[1])
        assertTrue(blocks[2] is TableBlock)
        assertTrue(blocks[3] is CodeBlockData)
    }

    // -------------------------------------------------------------------------
    // Message Model Integration
    // -------------------------------------------------------------------------

    @Test
    fun `content blocks message type`() {
        val msg = Message(
            role = MessageRole.ASSISTANT,
            content = "",
            type = MessageType.CONTENT_BLOCKS,
            metadata = MessageMetadata(
                contentBlocks = listOf(DividerBlock)
            )
        )

        assertEquals(MessageType.CONTENT_BLOCKS, msg.type)
        assertEquals(1, msg.metadata?.contentBlocks?.size)
        assertSame(DividerBlock, msg.metadata?.contentBlocks?.get(0))
    }

    @Test
    fun `block action defaults`() {
        val action = BlockAction(type = "link", label = "Click")
        assertEquals("primary", action.style)
        assertNull(action.url)
        assertNull(action.message)
        assertNull(action.callbackId)
    }
}

