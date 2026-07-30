package com.youtv.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelSelectionPolicyTest {
    private val ids = listOf("a", "b", "c")

    @Test
    fun fixedDefaultOverridesRememberedChannel() {
        assertEquals("b", resolveInitialChannelId(ids, 2, "c", 0))
    }

    @Test
    fun zeroDefaultRestoresStableChannelId() {
        assertEquals("c", resolveInitialChannelId(ids, 0, "c", 0))
        assertEquals("c", resolveInitialChannelId(listOf("c", "a", "b"), 0, "c", 0))
    }

    @Test
    fun missingRememberedChannelFallsBackToFirst() {
        assertEquals("a", resolveInitialChannelId(ids, 0, "missing", 2))
    }

    @Test
    fun legacyPositionIsUsedOnlyWithoutRememberedId() {
        assertEquals("c", resolveInitialChannelId(ids, 0, "", 2))
    }

    @Test
    fun invalidDefaultAndEmptyListAreSafe() {
        assertEquals("a", resolveInitialChannelId(ids, 99, "c", 2))
        assertNull(resolveInitialChannelId(emptyList(), 0, "c", 0))
    }
}
