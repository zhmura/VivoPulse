package com.vivopulse.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navigation
import com.vivopulse.app.ui.screens.CaptureScreen
import com.vivopulse.app.ui.screens.ProcessingScreen
import com.vivopulse.app.ui.screens.ResultScreen
import com.vivopulse.app.ui.screens.ReactivityProtocolScreen

/** Route for the nested graph that shares a single [ProcessingViewModel]. */
const val PROCESSING_GRAPH_ROUTE = "processing_graph"

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
        
        // Nested graph so Processing & Result share the same ProcessingViewModel
        navigation(
            startDestination = Route.Processing.path,
            route = PROCESSING_GRAPH_ROUTE
        ) {
            composable(Route.Processing.path) {
                ProcessingScreen(
                    navController = navController,
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
                    navController = navController,
                    onNavigateBack = {
                        navController.popBackStack(Route.Capture.path, inclusive = false)
                    }
                )
            }
        }
        
        composable(Route.Reactivity.path) {
            ReactivityProtocolScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToCapture = { navController.navigate(Route.Capture.path) }
            )
        }
    }
}


