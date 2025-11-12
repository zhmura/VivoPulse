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

sealed class Screen(val route: String) {
    object Capture : Screen("capture")
    object Processing : Screen("processing")
    object Result : Screen("result")
    object Reactivity : Screen("reactivity")
}

@Composable
fun VivoPulseNavHost(
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    
    NavHost(
        navController = navController,
        startDestination = Screen.Capture.route,
        modifier = modifier
    ) {
        composable(Screen.Capture.route) {
            CaptureScreen(
                onNavigateToProcessing = {
                    navController.navigate(Screen.Processing.route)
                },
                onNavigateToReactivity = {
                    navController.navigate(Screen.Reactivity.route)
                }
            )
        }
        
        composable(Screen.Processing.route) {
            ProcessingScreen(
                onNavigateToResult = {
                    navController.navigate(Screen.Result.route)
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        
        composable(Screen.Result.route) {
            ResultScreen(
                onNavigateBack = {
                    navController.popBackStack(Screen.Capture.route, inclusive = false)
                }
            )
        }
        
        composable(Screen.Reactivity.route) {
            ReactivityProtocolScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToCapture = { navController.navigate(Screen.Capture.route) }
            )
        }
    }
}


