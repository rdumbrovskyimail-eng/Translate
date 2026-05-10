package com.translator.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.translator.app.presentation.onboarding.OnboardingScreen
import com.translator.app.presentation.settings.SettingsScreen
import com.translator.app.presentation.translator.TranslateScreen

@Composable
fun AppNavGraph() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "onboarding") {
        composable("onboarding") {
            OnboardingScreen(onNavigateToTranslator = {
                navController.navigate("translator") { popUpTo("onboarding") { inclusive = true } }
            })
        }
        composable("translator") {
            TranslateScreen(
                onNavigateToSettings = { navController.navigate("settings") },
                onBack = { navController.popBackStack() }
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}