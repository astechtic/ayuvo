package com.ayuvo.health.records.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The embedded prompts equal the fenced blocks of `shared/records/ai_extraction.md`, in order:
 * full system prompt, user template, compact system prompt (no trailing newline inside a block).
 */
class RecordsAiPromptContractTest {
    @Test
    fun embeddedPromptsMatchSharedFile() {
        val shared = listOf("../../shared/records/ai_extraction.md", "../shared/records/ai_extraction.md", "shared/records/ai_extraction.md")
            .map(::File).firstOrNull { it.exists() }
        assertTrue("shared/records/ai_extraction.md not found", shared != null)
        val text = shared!!.readText().replace("\r\n", "\n")
        val blocks = Regex("\n```\n(.*?)\n```", RegexOption.DOT_MATCHES_ALL).findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(3, blocks.size)
        assertEquals(blocks[0], RecordsAiPrompt.SYSTEM_FULL)
        assertEquals(blocks[1], RecordsAiPrompt.USER_TEMPLATE)
        assertEquals(blocks[2], RecordsAiPrompt.SYSTEM_COMPACT)
        assertTrue(!RecordsAiPrompt.IS_DRAFT_PROMPT)
    }

    @Test
    fun placeholdersAreReplacedOnceHintFirst() {
        val prompt = RecordsAiPrompt.render(local = false, recordType = "other", pages = "=== Page 1 ===\nsays {record_type_hint}")
        assertTrue(prompt.contains("Document type hint: unknown"))
        assertTrue(prompt.contains("says {record_type_hint}"))
        assertTrue(prompt.endsWith("Return the JSON object."))
    }
}
