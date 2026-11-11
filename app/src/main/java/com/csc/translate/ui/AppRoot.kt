package com.csc.translate.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.csc.translate.AppViewModel
import kotlinx.coroutines.launch
import com.csc.translate.R
import com.csc.translate.audio.SystemAudio
import com.csc.translate.data.HearSource
import com.csc.translate.service.MicCaptureService
import com.csc.translate.session.Phase

private const val ROUTE_IDLE = "idle"
private const val ROUTE_LIVE = "live"
private const val ROUTE_HISTORY = "history"
private const val ROUTE_DETAIL = "history/{id}"

@Composable
fun AppRoot(viewModel: AppViewModel) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val healthError by viewModel.healthError.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    var showSettings by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }

    var micDenied by remember { mutableStateOf(false) }
    var systemDenied by remember { mutableStateOf(false) }
    var pendingSystem by remember { mutableStateOf(false) }
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            systemDenied = true
            return@rememberLauncherForActivityResult
        }
        SystemAudio.prepare()
        MicCaptureService.startSystem(context, result.resultCode, data)
        viewModel.startSession()
    }
    fun launchSystemCapture() {
        val activity = context as? Activity ?: return
        if (Build.VERSION.SDK_INT < 29) {
            systemDenied = true
            return
        }
        val manager = activity.getSystemService(MediaProjectionManager::class.java) ?: return
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        } else {
            manager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (pendingSystem) {
            pendingSystem = false
            launchSystemCapture()
            return@rememberLauncherForActivityResult
        }
        when (result[Manifest.permission.RECORD_AUDIO]) {
            true -> viewModel.startSession()
            false -> micDenied = true
            null -> Unit
        }
    }
    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.startFile(uri)
    }
    LaunchedEffect(micDenied) {
        if (micDenied) {
            snackbar.showSnackbar(context.getString(R.string.need_mic))
            micDenied = false
        }
    }
    LaunchedEffect(systemDenied) {
        if (systemDenied) {
            snackbar.showSnackbar(context.getString(R.string.system_audio_denied))
            systemDenied = false
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    LaunchedEffect(live.phase) {
        when (live.phase) {
            Phase.Connecting, Phase.Live, Phase.Finishing -> {
                if (route == ROUTE_IDLE) {
                    nav.navigate(ROUTE_LIVE) { launchSingleTop = true }
                }
            }
            Phase.Idle -> {
                if (route == ROUTE_LIVE) {
                    nav.popBackStack(ROUTE_IDLE, inclusive = false)
                }
            }
        }
    }

    fun requestStart() {
        if (settings.hearSource == HearSource.System) {
            if (Build.VERSION.SDK_INT < 29) {
                systemDenied = true
                return
            }
            val needNotification = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            if (needNotification) {
                pendingSystem = true
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                return
            }
            launchSystemCapture()
            return
        }
        val micOk = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (micOk) {
            viewModel.startSession()
            return
        }
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(needed.toTypedArray())
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.White,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { _ ->
        NavHost(navController = nav, startDestination = ROUTE_IDLE) {
            composable(ROUTE_IDLE) {
                IdleScreen(
                    settings = settings,
                    live = live,
                    healthError = healthError,
                    onBack = { (context as? Activity)?.finish() },
                    onSwap = viewModel::toggleDirection,
                    onHistory = { nav.navigate(ROUTE_HISTORY) },
                    onStart = { requestStart() },
                    onPickAudio = {
                        if (!live.inProgress) {
                            audioLauncher.launch(
                                arrayOf(
                                    "audio/*",
                                    "audio/mpeg",
                                    "audio/mp4",
                                    "audio/x-m4a",
                                    "audio/wav",
                                    "audio/ogg",
                                    "audio/amr",
                                    "audio/3gpp",
                                ),
                            )
                        }
                    },
                    onSettings = { showSettings = true },
                    onHearSource = viewModel::setHearSource,
                )
            }
            composable(ROUTE_LIVE) {
                BackHandler { confirmStop = true }
                LiveScreen(
                    live = live,
                    settings = settings,
                    onBack = { confirmStop = true },
                    onHistory = { nav.navigate(ROUTE_HISTORY) },
                    onPause = viewModel::togglePause,
                    onStop = viewModel::stopSession,
                    onSettings = { showSettings = true },
                    onCopied = {
                        scope.launch {
                            snackbar.showSnackbar(context.getString(R.string.watch_copied))
                        }
                    },
                )
            }
            composable(ROUTE_HISTORY) {
                HistoryScreen(
                    sessions = history,
                    inProgress = live.inProgress,
                    onBack = { nav.popBackStack() },
                    onOpen = { id -> nav.navigate("history/$id") },
                    onDelete = viewModel::deleteSessions,
                )
            }
            composable(
                ROUTE_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id")
                HistoryDetailScreen(
                    session = history.firstOrNull { it.id == id } ?: viewModel.session(id.orEmpty()),
                    settings = settings,
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            health = health,
            config = config,
            healthError = healthError,
            liveLocked = live.inProgress,
            onDismiss = { showSettings = false },
            onSaveUrl = viewModel::setBaseUrl,
            onSelectPipeline = viewModel::selectPipeline,
            onChunkMode = viewModel::setResponseMode,
            onVad = viewModel::setVad,
            onRefresh = viewModel::refreshConnection,
            onDisplayMode = viewModel::setDisplayMode,
            onTypeScale = viewModel::setTypeScale,
            onHearSource = viewModel::setHearSource,
            onSaveIndex = viewModel::setIndexUrl,
            onSaveRelay = viewModel::setRelayUrl,
            onShareRelay = viewModel::setShareRelay,
            onSavePasscode = viewModel::setPasscode,
            onResetMeeting = viewModel::resetMeetingId,
            onCopied = {
                scope.launch {
                    snackbar.showSnackbar(context.getString(R.string.watch_copied))
                }
            },
        )
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.stop_confirm_title)) },
            text = { Text(stringResource(R.string.stop_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    viewModel.stopSession()
                }) { Text(stringResource(R.string.end_session)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
