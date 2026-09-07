package com.makemore.agentfrontend.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryScrollAnchorTest {
    @Test fun prependingKeepsTheExistingRowAtTheSameViewportPosition() {
        assertEquals(460, HistoryScrollAnchor("row", 100f).target(60, 500f))
    }

    @Test fun liveTailGrowthDoesNotMoveTheAnchor() {
        // Appending, streaming and footer changes do not change this row's content Y.
        assertEquals(60, HistoryScrollAnchor("row", 100f).target(60, 100f))
    }

    @Test fun userScrollWhileLoadingIsPreservedAndNegativeTargetsAreClamped() {
        val anchor = HistoryScrollAnchor("row", 100f)
        assertEquals(550, anchor.target(150, 500f))
        assertEquals(0, anchor.target(10, 0f))
    }
}