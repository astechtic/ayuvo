package com.ayuvo.health.records

/**
 * One-shot request from MainActivity (share sheet / "Open in") to show the Records tab.
 * Mirrors QuickActionRequest: the id lets the nav host consume each request exactly once.
 */
data class RecordsRequest(val id: Long = System.nanoTime())
