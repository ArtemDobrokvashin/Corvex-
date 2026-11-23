package com.corvex.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.corvex.mobile.data.security.TokenStore
import com.corvex.mobile.ui.auth.LoginScreen
import com.corvex.mobile.ui.home.HomeScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var tokenStore: TokenStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startDestination = if (tokenStore.isLoggedIn()) "home" else "login"

        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = startDestination) {
                    composable("login") {
                        LoginScreen(
                            onAuthenticated = {
                                navController.navigate("home") {
                                    popUpTo("login") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("home") {
                        HomeScreen(
                            onLoggedOut = {
                                navController.navigate("login") {
                                    popUpTo("home") { inclusive = true }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
