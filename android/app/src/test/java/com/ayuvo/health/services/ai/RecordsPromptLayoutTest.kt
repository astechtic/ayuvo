package com.ayuvo.health.services.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/health-records.md §32: exact layout of the records lines in the Coach system prompt. */
class RecordsPromptLayoutTest {
    @Test
    fun availableGuardrailsThenSelection() {
        val lines = ChatService.recordsDataLines(
            ChatService.RecordsTurn(
                availableLine = "AVAILABLE", guardrails = "GUARD\n- rule", selectedLines = listOf("HEADER", "- r1: CBC — 2026-07-10 (Lab Report)")
            )
        )
        assertEquals("- AVAILABLE\n\nGUARD\n- rule\n\nHEADER\n- r1: CBC — 2026-07-10 (Lab Report)", lines.joinToString("\n"))
        assertEquals("- AVAILABLE\n\nGUARD", ChatService.recordsDataLines(ChatService.RecordsTurn(availableLine = "AVAILABLE", guardrails = "GUARD")).joinToString("\n"))
        assertEquals(listOf("- NOT"), ChatService.recordsDataLines(ChatService.RecordsTurn(notAvailableLine = "NOT")))
    }

    @Test
    fun onDeviceBlockThenGuardrails() {
        assertEquals("## Health records (selected by the user)\n### X\n\nGUARD", ChatService.onDeviceRecordsTail("## Health records (selected by the user)\n### X", "GUARD"))
    }
}
