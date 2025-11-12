package com.vivopulse.feature.results

import com.vivopulse.signal.ProcessedSignal

/**
 * View model for results display.
 * Manages signal data and chart configurations.
 */
interface ResultsViewModel {
    /**
     * Get the list of processed signals.
     * @return List of signals
     */
    fun getSignals(): List<ProcessedSignal>
    
    /**
     * Export results to file.
     * @param format Export format (CSV or JSON)
     * @return Success status
     */
    suspend fun exportResults(format: ExportFormat): Boolean
}

/**
 * Export format options.
 */
enum class ExportFormat {
    CSV,
    JSON
}

/**
 * Default implementation of ResultsViewModel.
 */
class DefaultResultsViewModel : ResultsViewModel {
    private val signals = mutableListOf<ProcessedSignal>()
    
    override fun getSignals(): List<ProcessedSignal> = signals
    
    override suspend fun exportResults(format: ExportFormat): Boolean {
        // TODO: Implement export
        return true
    }
}

