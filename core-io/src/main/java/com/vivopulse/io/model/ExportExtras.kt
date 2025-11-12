package com.vivopulse.io.model

data class ExportExtras(
    val vascularWaveProfile: Map<String, Any?>? = null,
    val vascularTrendSummary: Map<String, Any?>? = null,
    val biomarkerPanel: Map<String, Any?>? = null,
    val reactivityProtocol: Map<String, Any?>? = null
)


