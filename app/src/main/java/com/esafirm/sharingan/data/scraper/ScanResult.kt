package com.esafirm.sharingan.data.scraper

/** A scanned chapter page: where it was found and the images that look like its pages. */
data class ScanResult(
    val pageUrl: String,
    val title: String?,
    val images: List<String>,
    /** True if the pages came from the browser engine (JavaScript ran), false for the plain HTML. */
    val rendered: Boolean = false,
)

enum class RenderMode {
    /** Plain HTML first; the browser engine only if that finds too few pages or is blocked. */
    AUTO,
    /** Always load with the browser engine. */
    ALWAYS,
}
