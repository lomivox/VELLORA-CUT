package com.vellora.cut.autogen.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.vellora.cut.data.AppDatabase
import com.vellora.cut.ui.theme.BackgroundDark

/** Screens reachable from within the Auto Generator project (own back-stack). */
private sealed class AutoGenScreen {
    object ProjectList : AutoGenScreen()
    object NewProject : AutoGenScreen()
    object Settings : AutoGenScreen()
    data class PromptPaste(val projectId: Long) : AutoGenScreen()
    data class Timeline(val projectId: Long) : AutoGenScreen()
}

/**
 * Entry point for the Auto Generator project. Manages its own small
 * navigation stack; [onBack] returns to the app Hub.
 */
@Composable
fun AutoGenHomeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    var screen by remember { mutableStateOf<AutoGenScreen>(AutoGenScreen.ProjectList) }

    // ProjectList AutoGenerator ki apni "root" hai — us par back-button ye
    // handler chorr deta hai (enabled=false), taake bahar wala MainActivity
    // handler chal kar Hub par le jaye. Baqi sab screens par ye khud
    // ek qadam peeche le jata hai.
    BackHandler(enabled = screen !is AutoGenScreen.ProjectList) {
        screen = when (val current = screen) {
            is AutoGenScreen.NewProject -> AutoGenScreen.ProjectList
            is AutoGenScreen.Settings -> AutoGenScreen.ProjectList
            is AutoGenScreen.PromptPaste -> AutoGenScreen.ProjectList
            is AutoGenScreen.Timeline -> AutoGenScreen.PromptPaste(current.projectId)
            is AutoGenScreen.ProjectList -> current
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BackgroundDark)) {
        when (val current = screen) {
            is AutoGenScreen.ProjectList -> AutoGenProjectListScreen(
                db = db,
                onBack = onBack,
                onNewProject = { screen = AutoGenScreen.NewProject },
                onOpenProject = { id -> screen = AutoGenScreen.PromptPaste(id) },
                onOpenSettings = { screen = AutoGenScreen.Settings }
            )

            is AutoGenScreen.NewProject -> NewAutoGenProjectScreen(
                db = db,
                onBack = { screen = AutoGenScreen.ProjectList },
                onCreated = { id -> screen = AutoGenScreen.PromptPaste(id) }
            )

            is AutoGenScreen.Settings -> SettingsScreen(
                onBack = { screen = AutoGenScreen.ProjectList }
            )

            is AutoGenScreen.PromptPaste -> PromptPasteScreen(
                db = db,
                projectId = current.projectId,
                onBack = { screen = AutoGenScreen.ProjectList },
                onOpenSettings = { screen = AutoGenScreen.Settings },
                onOpenTimeline = { screen = AutoGenScreen.Timeline(current.projectId) }
            )

            is AutoGenScreen.Timeline -> TimelineScreen(
                db = db,
                projectId = current.projectId,
                onBack = { screen = AutoGenScreen.PromptPaste(current.projectId) }
            )
        }
    }
}
