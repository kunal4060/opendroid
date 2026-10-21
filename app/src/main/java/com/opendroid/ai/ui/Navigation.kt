package com.opendroid.ai.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.scale
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.opendroid.ai.core.intent.ExternalIntentRouter
import com.opendroid.ai.core.intent.ExternalIntentRouterEntryPoint
import com.opendroid.ai.core.intent.ExternalLaunch
import com.opendroid.ai.ui.screens.*
import com.opendroid.ai.ui.theme.*
import com.opendroid.ai.ui.viewmodel.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

/**
 * Route names of the top-level navigation graph.
 *
 * Kept next to [navigateAfterSplash] and [navigateAfterOnboarding] so the back-stack
 * rules of the entry flow can be exercised without composing the screens themselves.
 */
object OpenDroidRoutes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val BENCHMARK = "benchmark"
    const val USAGE = "usage"
    const val PRIVACY_POLICY = "privacy_policy"
    const val TERMS_OF_USE = "terms_of_use"
    const val HELP_CENTER = "help_center"
    const val LICENSE = "license"
    const val ABOUT = "about"
    const val AUTO_REPLY_SETTINGS = "auto_reply_settings"
    const val NOTIFICATION_HISTORY = "notification_history"
    const val PERMISSIONS = "permissions"
    const val CRASH_LOG = "crash_log"
    const val ACTION_LEARNING = "action_learning"
    const val ROUTINES = "routines"
    const val SOCIAL = "social"
    const val PERSONAS = "personas"
    const val SCHEDULES = "schedules"
    const val KNOWLEDGE_BASE = "knowledge_base"
}

/**
 * Leaves the splash screen for onboarding or the main dashboard, dropping splash from the
 * back stack so system back from the first real screen exits the app instead of replaying it.
 */
fun NavHostController.navigateAfterSplash(isOnboardingCompleted: Boolean) {
    val destination = if (isOnboardingCompleted) OpenDroidRoutes.MAIN else OpenDroidRoutes.ONBOARDING
    navigate(destination) {
        popUpTo(OpenDroidRoutes.SPLASH) { inclusive = true }
    }
}

/** Enters the dashboard after onboarding, so back never returns to the completed flow. */
fun NavHostController.navigateAfterOnboarding() {
    navigate(OpenDroidRoutes.MAIN) {
        popUpTo(OpenDroidRoutes.ONBOARDING) { inclusive = true }
    }
}

@Composable
fun OpenDroidNavigation(
    navController: NavHostController = rememberNavController(),
    initialSessionId: String? = null
) {
    // External launches: MainActivity publishes widget/shortcut intents (C1)
    // and shares (C2) to the one router; the latest launch is handed to the
    // dashboard, which is the first destination that can honour the C1
    // kinds (shares are claimed by ChatScreen instead — see MainDashboard).
    // While the app is locked this whole graph is not composed (see
    // MainActivity), so launches wait in the router's replay behind the A5
    // gate instead of bypassing it.
    val appContext = LocalContext.current.applicationContext
    val externalIntentRouter = remember {
        EntryPointAccessors.fromApplication(
            appContext,
            ExternalIntentRouterEntryPoint::class.java
        ).externalIntentRouter()
    }
    val pendingExternalLaunch by externalIntentRouter.launches.collectAsState(initial = null)

    NavHost(
        navController = navController,
        startDestination = OpenDroidRoutes.SPLASH,
        modifier = Modifier.fillMaxSize().background(AppTheme.colors.background),
        enterTransition = {
            fadeIn(animationSpec = tween(300)) + slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            )
        },
        exitTransition = {
            fadeOut(animationSpec = tween(250)) + slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                targetOffset = { it / 4 },
                animationSpec = tween(250, easing = FastOutSlowInEasing)
            )
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(300)) + slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                initialOffset = { it / 4 },
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            )
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(250)) + slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(250, easing = FastOutSlowInEasing)
            )
        }
    ) {
        composable(OpenDroidRoutes.SPLASH) {
            val startupViewModel: StartupViewModel = hiltViewModel()
            val startDestination by startupViewModel.startDestination.collectAsState()
            var splashFinished by remember { mutableStateOf(false) }

            SplashScreen(onNavigateNext = { splashFinished = true })

            // The destination is decided off the main thread, so wait for both the animation and
            // the decrypted profile check rather than guessing a route.
            LaunchedEffect(splashFinished, startDestination) {
                val destination = startDestination
                if (splashFinished && destination != null) {
                    navController.navigateAfterSplash(
                        isOnboardingCompleted = destination == StartDestination.MAIN
                    )
                }
            }
        }

        composable(OpenDroidRoutes.ONBOARDING) {
            OnboardingScreen(
                onFinished = { navController.navigateAfterOnboarding() }
            )
        }

        composable(OpenDroidRoutes.MAIN) {
            MainDashboard(
                initialSessionId = initialSessionId,
                onNavigateToBenchmark = {
                    navController.navigate(OpenDroidRoutes.BENCHMARK)
                },
                onNavigateToUsage = {
                    navController.navigate(OpenDroidRoutes.USAGE)
                },
                onNavigateToPrivacyPolicy = {
                    navController.navigate(OpenDroidRoutes.PRIVACY_POLICY)
                },
                onNavigateToTermsOfUse = {
                    navController.navigate(OpenDroidRoutes.TERMS_OF_USE)
                },
                onNavigateToHelpCenter = {
                    navController.navigate(OpenDroidRoutes.HELP_CENTER)
                },
                onNavigateToLicense = {
                    navController.navigate(OpenDroidRoutes.LICENSE)
                },
                onNavigateToAbout = {
                    navController.navigate(OpenDroidRoutes.ABOUT)
                },
                onNavigateToAutoReply = {
                    navController.navigate(OpenDroidRoutes.AUTO_REPLY_SETTINGS)
                },
                onNavigateToNotificationHistory = {
                    navController.navigate(OpenDroidRoutes.NOTIFICATION_HISTORY)
                },
                onNavigateToPermissions = {
                    navController.navigate(OpenDroidRoutes.PERMISSIONS)
                },
                onNavigateToCrashLog = {
                    navController.navigate(OpenDroidRoutes.CRASH_LOG)
                },
                onNavigateToRoutines = {
                    navController.navigate(OpenDroidRoutes.ROUTINES)
                },
                onNavigateToActionLearning = {
                    navController.navigate(OpenDroidRoutes.ACTION_LEARNING)
                },
                onNavigateToPersonas = {
                    navController.navigate(OpenDroidRoutes.PERSONAS)
                },
                onNavigateToSchedules = {
                    navController.navigate(OpenDroidRoutes.SCHEDULES)
                },
                onNavigateToKnowledgeBase = {
                    navController.navigate(OpenDroidRoutes.KNOWLEDGE_BASE)
                },
                pendingExternalLaunch = pendingExternalLaunch,
                onExternalLaunchClaim = { launch ->
                    externalIntentRouter.claim(launch)
                }
            )
        }

        composable(OpenDroidRoutes.ACTION_LEARNING) {
            UnknownActionsScreen(
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.BENCHMARK) {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            BenchmarkScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.USAGE) {
            UsageScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.PRIVACY_POLICY) {
            PrivacyPolicyScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.ABOUT) {
            AboutScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.TERMS_OF_USE) {
            TermsOfUseScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.HELP_CENTER) {
            HelpCenterScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.LICENSE) {
            LicenseScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.AUTO_REPLY_SETTINGS) {
            val settingsRepo = hiltViewModel<AutoReplyViewModel>().settingsRepository
            AutoReplySettingsScreen(
                settingsRepository = settingsRepo,
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.NOTIFICATION_HISTORY) {
            val notifDao = hiltViewModel<NotificationHistoryViewModel>().notificationDao
            NotificationHistoryScreen(
                notificationDao = notifDao,
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.PERMISSIONS) {
            PermissionsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.CRASH_LOG) {
            CrashLogScreen(
                viewModel = hiltViewModel(),
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.ROUTINES) {
            val routineViewModel: com.opendroid.ai.ui.viewmodel.RoutineViewModel = hiltViewModel()
            com.opendroid.ai.ui.screens.RoutinesScreen(
                viewModel = routineViewModel,
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.SCHEDULES) {
            val schedulesViewModel: SchedulesViewModel = hiltViewModel()
            SchedulesScreen(
                viewModel = schedulesViewModel,
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.SOCIAL) {
            val socialViewModel: SocialViewModel = hiltViewModel()
            SocialScreen(viewModel = socialViewModel)
        }

        composable(OpenDroidRoutes.PERSONAS) {
            val personasViewModel: PersonasViewModel = hiltViewModel()
            PersonasScreen(
                viewModel = personasViewModel,
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.KNOWLEDGE_BASE) {
            val knowledgeBaseViewModel: KnowledgeBaseViewModel = hiltViewModel()
            KnowledgeBaseScreen(
                viewModel = knowledgeBaseViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Chat : Screen("chat", "Chat", Icons.Default.Chat)
    object Plan : Screen("plan", "Plan", Icons.Default.List)
    object Memory : Screen("memory", "Memory", Icons.Default.Star)
    object Social : Screen("social", "Social", Icons.Default.Share)
    object Macros : Screen("macros", "Macros", Icons.Default.Build)
    object History : Screen("history", "Audit Log", Icons.Default.History)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

@Composable
fun MainDashboard(
    initialSessionId: String? = null,
    onNavigateToBenchmark: () -> Unit,
    onNavigateToUsage: () -> Unit = {},
    onNavigateToPrivacyPolicy: () -> Unit,
    onNavigateToTermsOfUse: () -> Unit,
    onNavigateToHelpCenter: () -> Unit,
    onNavigateToLicense: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToAutoReply: () -> Unit = {},
    onNavigateToNotificationHistory: () -> Unit = {},
    onNavigateToPermissions: () -> Unit = {},
    onNavigateToCrashLog: () -> Unit = {},
    onNavigateToRoutines: () -> Unit = {},
    onNavigateToActionLearning: () -> Unit = {},
    onNavigateToPersonas: () -> Unit = {},
    onNavigateToSchedules: () -> Unit = {},
    onNavigateToKnowledgeBase: () -> Unit = {},
    pendingExternalLaunch: ExternalLaunch? = null,
    onExternalLaunchClaim: (ExternalLaunch) -> Boolean = { false }
) {
    val context = LocalContext.current

    // Start the service as soon as RECORD_AUDIO is granted, and keep checking on every
    // resume - not just once on first composition - so a user who grants the microphone
    // from Settings > Permissions (rather than at onboarding) gets the service started
    // immediately on returning here, with no app restart required.
    var recordAudioServiceStarted by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (granted && !recordAudioServiceStarted) {
                    recordAudioServiceStarted = true
                    com.opendroid.ai.core.service.OpenDroidService.start(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var currentTab by remember { mutableStateOf<Screen>(Screen.Chat) }

    val chatViewModel: ChatViewModel = hiltViewModel()

    // C4 deep link: a scheduled-task notification asked to open a specific
    // chat session (the one its run wrote into). Switch to it and land on
    // the Chat tab. Fires once per distinct id, including when the id
    // arrives via onNewIntent while the dashboard is already composed.
    LaunchedEffect(initialSessionId) {
        if (initialSessionId != null) {
            chatViewModel.switchToSession(initialSessionId)
            currentTab = Screen.Chat
        }
    }
    val planViewModel: PlanViewModel = hiltViewModel()
    val memoryViewModel: MemoryViewModel = hiltViewModel()
    val socialViewModel: SocialViewModel = hiltViewModel()
    val macroViewModel: MacroViewModel = hiltViewModel()
    val historyViewModel: HistoryViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()

    // External launches (C1 widget/shortcuts): honoured once, as soon as the
    // dashboard is composed — which is after splash routing and the app-lock
    // gate, so an outside tap can never jump either queue. Voice/new-chat/
    // briefing act on the chat tab; routine/macro launches run headlessly
    // through AgentCommandRunner, the same engine paths the UI uses.
    val dashboardScope = rememberCoroutineScope()
    val agentCommandRunner = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            com.opendroid.ai.widget.OpenDroidWidgetEntryPoint::class.java
        ).agentCommandRunner()
    }
    LaunchedEffect(pendingExternalLaunch) {
        val launch = pendingExternalLaunch ?: return@LaunchedEffect
        // Shares (C2) are staged by ChatScreen's own collector off the same
        // router; the dashboard must not claim them.
        if (launch is ExternalLaunch.SharedContent) return@LaunchedEffect
        // Claim before acting: the router replays the latest launch to every
        // new collector, and the claim is what makes handling exactly-once.
        if (!onExternalLaunchClaim(launch)) return@LaunchedEffect
        when (launch) {
            ExternalLaunch.OpenChat -> currentTab = Screen.Chat
            ExternalLaunch.VoiceCommand -> {
                currentTab = Screen.Chat
                chatViewModel.requestVoiceInput()
            }
            ExternalLaunch.NewChat -> {
                currentTab = Screen.Chat
                chatViewModel.newChat()
            }
            ExternalLaunch.MorningBriefing -> {
                currentTab = Screen.Chat
                chatViewModel.showMorningBriefing()
            }
            ExternalLaunch.OpenRoutines -> onNavigateToRoutines()
            is ExternalLaunch.RunRoutine -> dashboardScope.launch {
                agentCommandRunner.runRoutine(launch.routineId)
            }
            is ExternalLaunch.RunMacro -> dashboardScope.launch {
                agentCommandRunner.runMacro(launch.macroId)
            }
            is ExternalLaunch.SharedContent -> Unit // unreachable: guarded above
        }
    }

    val tabs = listOf(
        Screen.Chat,
        Screen.Plan,
        Screen.Memory,
        Screen.Social,
        Screen.Macros,
        Screen.History,
        Screen.Settings
    )

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = AppTheme.colors.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .border(1.dp, AppTheme.colors.borderColor, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
                tonalElevation = 6.dp
            ) {
                tabs.forEach { tab ->
                    val isSelected = currentTab == tab
                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.15f else 1.0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        ),
                        label = "tabIconScale"
                    )
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                modifier = Modifier.scale(iconScale),
                                tint = if (isSelected) AppTheme.colors.textPrimary else AppTheme.colors.textSecondary
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                fontSize = 10.sp,
                                color = if (isSelected) AppTheme.colors.textPrimary else AppTheme.colors.textSecondary,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = AppTheme.colors.textPrimary.copy(alpha = 0.08f),
                            selectedIconColor = AppTheme.colors.textPrimary,
                            unselectedIconColor = AppTheme.colors.textSecondary,
                            selectedTextColor = AppTheme.colors.textPrimary,
                            unselectedTextColor = AppTheme.colors.textSecondary
                        )
                    )
                }
            }
        },
        containerColor = AppTheme.colors.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
        ) {
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(220)) + scaleIn(initialScale = 0.98f, animationSpec = tween(220)))
                        .togetherWith(fadeOut(animationSpec = tween(180)))
                },
                label = "DashboardTabTransition"
            ) { tab ->
                when (tab) {
                    Screen.Chat -> ChatScreen(
                        viewModel = chatViewModel,
                        // The A10 setup banner's way out: the Settings tab is
                        // where providers are configured.
                        onNavigateToProviderSetup = { currentTab = Screen.Settings },
                        // The B6 persona sheet's library manager.
                        onNavigateToPersonas = onNavigateToPersonas
                    )
                    Screen.Plan -> PlanScreen(viewModel = planViewModel)
                    Screen.Memory -> MemoryScreen(viewModel = memoryViewModel)
                    Screen.Social -> SocialScreen(viewModel = socialViewModel)
                    Screen.Macros -> MacrosScreen(
                        viewModel = macroViewModel,
                        onNavigateToRoutines = onNavigateToRoutines,
                        onNavigateToSchedules = onNavigateToSchedules
                    )
                    Screen.History -> LogsScreen(
                        viewModel = historyViewModel,
                        onNavigateToActionLearning = onNavigateToActionLearning
                    )
                    Screen.Settings -> SettingsScreen(
                        viewModel = settingsViewModel,
                        onNavigateToBenchmark = onNavigateToBenchmark,
                        onNavigateToUsage = onNavigateToUsage,
                        onNavigateToPrivacyPolicy = onNavigateToPrivacyPolicy,
                        onNavigateToTermsOfUse = onNavigateToTermsOfUse,
                        onNavigateToHelpCenter = onNavigateToHelpCenter,
                        onNavigateToLicense = onNavigateToLicense,
                        onNavigateToAbout = onNavigateToAbout,
                        onNavigateToAutoReply = onNavigateToAutoReply,
                        onNavigateToNotificationHistory = onNavigateToNotificationHistory,
                        onNavigateToPermissions = onNavigateToPermissions,
                        onNavigateToCrashLog = onNavigateToCrashLog,
                        onNavigateToRoutines = onNavigateToRoutines,
                        onNavigateToActionLearning = onNavigateToActionLearning,
                        onNavigateToPersonas = onNavigateToPersonas,
                        onNavigateToKnowledgeBase = onNavigateToKnowledgeBase
                    )
                }
            }
        }
    }
}
