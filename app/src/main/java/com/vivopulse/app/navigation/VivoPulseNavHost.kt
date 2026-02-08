package com.vivopulse.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vivopulse.app.ui.screens.CaptureScreen
import com.vivopulse.app.ui.screens.ProcessingScreen
import com.vivopulse.app.ui.screens.ResultScreen
import com.vivopulse.app.ui.screens.ReactivityProtocolScreen
@Composable
fun VivoPulseNavHost(
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    
    NavHost(
        navController = navController,
        startDestination = Route.Capture.path,
        modifier = modifier
    ) {
        composable(Route.Capture.path) {
            CaptureScreen(
                onNavigateToProcessing = {
                    navController.navigate(Route.Processing.path)
                }
            )
        }
        
        composable(Route.Processing.path) {
            ProcessingScreen(
                onNavigateToResult = {
                    navController.navigate(Route.Result.path)
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        
        composable(Route.Result.path) {
            ResultScreen(
                onNavigateBack = {
                    navController.popBackStack(Route.Capture.path, inclusive = false)
                }
            )
        }
        
        composable(Route.Reactivity.path) {
            ReactivityProtocolScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToCapture = { navController.navigate(Route.Capture.path) }
            )
        }
    }
}


