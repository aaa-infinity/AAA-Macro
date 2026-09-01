package com.aaa.macro.service

/**
 * Backward-compatible alias for FloatingHubService.
 */
class FloatingMenuService : FloatingHubService() {
    companion object {
        const val ACTION_START_WITH_PROJECTION = FloatingHubService.ACTION_START_WITH_PROJECTION
        const val EXTRA_RESULT_CODE = FloatingHubService.EXTRA_RESULT_CODE
        const val EXTRA_PROJECTION_DATA = FloatingHubService.EXTRA_PROJECTION_DATA
        val isRunning: Boolean
            get() = FloatingHubService.isRunning
    }
}
