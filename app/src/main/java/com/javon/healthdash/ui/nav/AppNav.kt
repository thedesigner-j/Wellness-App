package com.javon.healthdash.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.javon.healthdash.AppContainer
import com.javon.healthdash.domain.DashboardState
import com.javon.healthdash.ui.components.Tab
import com.javon.healthdash.domain.TrendMetric
import com.javon.healthdash.ui.screens.A1cScreen
import com.javon.healthdash.ui.screens.DayContextScreen
import com.javon.healthdash.ui.screens.MetricDetailScreen
import com.javon.healthdash.ui.screens.TrendsScreen
import com.javon.healthdash.ui.screens.ApiKeysScreen
import com.javon.healthdash.ui.screens.DataSourcesScreen
import com.javon.healthdash.ui.screens.FoodDetailScreen
import com.javon.healthdash.ui.screens.LogMealViewModel
import com.javon.healthdash.ui.screens.LogStep
import com.javon.healthdash.ui.screens.MealDetailScreen
import com.javon.healthdash.ui.screens.MealsScreen
import com.javon.healthdash.ui.screens.MoreScreen
import com.javon.healthdash.ui.screens.OnboardingScreen
import com.javon.healthdash.ui.screens.SettingsScreen
import com.javon.healthdash.ui.screens.TargetsScreen
import com.javon.healthdash.ui.screens.TodayScreen
import com.javon.healthdash.ui.screens.AdjustmentsScreen
import com.javon.healthdash.ui.screens.ExportScreen
import com.javon.healthdash.ui.screens.PlanBuilderScreen
import com.javon.healthdash.ui.screens.RacePlansScreen
import com.javon.healthdash.ui.screens.TrainingScreen
import com.javon.healthdash.ui.screens.WeekViewScreen
import com.javon.healthdash.ui.screens.WorkoutDetailScreen
import com.javon.healthdash.ui.screens.logMealGraph
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardViewModel(private val container: AppContainer) : ViewModel() {
    val state: StateFlow<DashboardState> = container.dashboard.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    fun sync() {
        viewModelScope.launch { runCatching { if (!container.prefs.state.value.sampleData) container.syncNow() } }
    }
}

object Routes {
    const val ONBOARDING = "onboarding"
    const val TODAY = "today"
    const val A1C = "a1c"
    const val MEALS = "meals?tab={tab}"
    const val TRAINING = "training"
    const val MORE = "more"
    const val MEAL = "meal/{id}"
    const val FOOD = "food/{key}"
    const val SETTINGS = "settings"
    const val SOURCES = "settings/sources"
    const val KEYS = "settings/keys"
    const val TARGETS = "settings/targets"
    const val RECURRING = "settings/recurring"
    const val GARMIN_IMPORT = "settings/garmin-import"
    const val GARMIN_API = "settings/garmin-api"
    const val TRENDS = "trends"
    const val METRIC = "trends/{metric}/{days}"
    const val DAY = "day/{epochDay}"

    const val WORKOUT = "workout/{id}"
    const val PLANS = "plans"
    const val PLAN_NEW = "plans/new"
    const val WEEK = "week/{epochDay}"
    const val ADJUSTMENTS = "adjustments"
    const val EXPORT = "export"
    const val STRENGTH = "strength/{id}"
    const val RACE_DAY = "raceday/{planId}"

    fun workout(id: String) = "workout/${android.net.Uri.encode(id)}"
    fun week(monday: java.time.LocalDate) = "week/${monday.toEpochDay()}"
    fun strength(id: Long) = "strength/$id"
    fun raceDay(planId: Long) = "raceday/$planId"
    fun metric(m: TrendMetric, days: Int) = "trends/${m.name}/$days"
    fun day(d: java.time.LocalDate) = "day/${d.toEpochDay()}"

    fun meal(id: Long) = "meal/$id"
    fun food(key: String) = "food/${android.net.Uri.encode(key)}"
    fun meals(tab: Int = 0) = "meals?tab=$tab"
    fun tab(t: Tab) = when (t) {
        Tab.Today -> TODAY
        Tab.A1c -> A1C
        Tab.Meals -> meals()
        Tab.Training -> TRAINING
        Tab.More -> MORE
    }
}

fun NavHostController.goTab(t: Tab) = navigate(Routes.tab(t)) {
    popUpTo(Routes.TODAY) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun AppNav(container: AppContainer) {
    val prefs by container.prefs.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val dash: DashboardViewModel = viewModel(factory = viewModelFactory { initializer { DashboardViewModel(container) } })
    val log: LogMealViewModel = viewModel(factory = viewModelFactory { initializer { LogMealViewModel(container) } })
    val state by dash.state.collectAsStateWithLifecycle()

    LaunchedEffect(prefs.onboardingDone) { if (prefs.onboardingDone) dash.sync() }

    val onTab: (Tab) -> Unit = { nav.goTab(it) }
    val back: () -> Unit = { nav.popBackStack() }
    val startLog: () -> Unit = { log.start(); nav.navigate(LogStep.Capture.route) }

    androidx.compose.runtime.CompositionLocalProvider(com.javon.healthdash.ui.screens.LocalOpenStrength provides { id: Long -> nav.navigate(Routes.strength(id)) }) {
    NavHost(nav, startDestination = if (prefs.onboardingDone) Routes.TODAY else Routes.ONBOARDING) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(container, state) {
                nav.navigate(Routes.TODAY) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            }
        }
        composable(Routes.TODAY) {
            TodayScreen(state, onTab, onSync = dash::sync, onLogMeal = startLog, onMeal = { nav.navigate(Routes.meal(it)) }, onSources = { nav.navigate(Routes.SOURCES) }, container = container)
        }
        composable(Routes.A1C) { A1cScreen(container, state, onTab, onFastingDetail = { nav.navigate(Routes.metric(TrendMetric.Fasting, 90)) }) }
        composable(Routes.TRENDS) {
            TrendsScreen(state, onTab, onBack = back, onMetric = { m, d -> nav.navigate(Routes.metric(m, d)) }, onDay = { nav.navigate(Routes.day(it)) })
        }
        composable(Routes.METRIC, arguments = listOf(navArgument("metric") { type = NavType.StringType }, navArgument("days") { type = NavType.IntType })) { e ->
            MetricDetailScreen(state, TrendMetric.valueOf(e.arguments!!.getString("metric")!!), e.arguments!!.getInt("days"), onBack = back)
        }
        composable(Routes.DAY, arguments = listOf(navArgument("epochDay") { type = NavType.LongType })) { e ->
            DayContextScreen(state, java.time.LocalDate.ofEpochDay(e.arguments!!.getLong("epochDay")), onBack = back, onMeal = { nav.navigate(Routes.meal(it)) })
        }
        composable(Routes.TRAINING) {
            TrainingScreen(
                container, state, onTab,
                onWorkout = { nav.navigate(Routes.workout(it)) }, onPlans = { nav.navigate(Routes.PLANS) },
                onWeek = { nav.navigate(Routes.week(it)) }, onAdjustments = { nav.navigate(Routes.ADJUSTMENTS) },
                onRaceDay = { nav.navigate(Routes.raceDay(it)) },
            )
        }
        composable(Routes.WORKOUT, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
            WorkoutDetailScreen(container, state, android.net.Uri.decode(e.arguments!!.getString("id")!!), onBack = back)
        }
        composable(Routes.PLANS) {
            RacePlansScreen(container, state, onBack = back, onNew = { nav.navigate(Routes.PLAN_NEW) }, onWeek = { nav.navigate(Routes.week(it)) }, onExport = { nav.navigate(Routes.EXPORT) })
        }
        composable(Routes.PLAN_NEW) {
            PlanBuilderScreen(container, state, onClose = back, onCreated = { nav.navigate(Routes.TRAINING) { popUpTo(Routes.TRAINING) } }, onImport = { nav.navigate(Routes.GARMIN_IMPORT) })
        }
        composable(Routes.WEEK, arguments = listOf(navArgument("epochDay") { type = NavType.LongType })) { e ->
            WeekViewScreen(container, state, java.time.LocalDate.ofEpochDay(e.arguments!!.getLong("epochDay")), onBack = back,
                onWeek = { nav.navigate(Routes.week(it)) { popUpTo(Routes.WEEK) { inclusive = true } } })
        }
        composable(Routes.ADJUSTMENTS) { AdjustmentsScreen(container, state, onBack = back) }
        composable(Routes.EXPORT) { ExportScreen(container, state, onBack = back) }
        composable(Routes.STRENGTH, arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
            com.javon.healthdash.ui.screens.StrengthScreen(container, state, e.arguments!!.getLong("id"), onBack = back)
        }
        composable(Routes.RACE_DAY, arguments = listOf(navArgument("planId") { type = NavType.LongType })) { e ->
            com.javon.healthdash.ui.screens.RaceDayScreen(container, state, e.arguments!!.getLong("planId"), onBack = back)
        }
        composable(Routes.MORE) { MoreScreen(onTab, onSettings = { nav.navigate(Routes.SETTINGS) }, onSources = { nav.navigate(Routes.SOURCES) }, onTrends = { nav.navigate(Routes.TRENDS) }) }
        composable(Routes.MEALS, arguments = listOf(navArgument("tab") { type = NavType.IntType; defaultValue = 0 })) { entry ->
            MealsScreen(
                state, initialTab = entry.arguments?.getInt("tab") ?: 0, onTab = onTab, onLogMeal = startLog,
                onMeal = { nav.navigate(Routes.meal(it)) }, onFood = { nav.navigate(Routes.food(it)) },
            )
        }
        composable(Routes.MEAL, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            MealDetailScreen(container, state, entry.arguments!!.getLong("id"), onBack = back, onFood = { nav.navigate(Routes.food(it)) })
        }
        composable(Routes.FOOD, arguments = listOf(navArgument("key") { type = NavType.StringType })) { entry ->
            FoodDetailScreen(state, android.net.Uri.decode(entry.arguments!!.getString("key")!!), onBack = back, onMeal = { nav.navigate(Routes.meal(it)) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(container, onTab, onBack = back, onSources = { nav.navigate(Routes.SOURCES) }, onKeys = { nav.navigate(Routes.KEYS) }, onTargets = { nav.navigate(Routes.TARGETS) }, onRecurring = { nav.navigate(Routes.RECURRING) }, onImport = { nav.navigate(Routes.GARMIN_IMPORT) })
        }
        composable(Routes.SOURCES) { DataSourcesScreen(container, state, onBack = back, onSync = dash::sync, onGarminApi = { nav.navigate(Routes.GARMIN_API) }) }
        composable(Routes.GARMIN_API) { com.javon.healthdash.ui.screens.GarminApiScreen(container, state, onBack = back) }
        composable(Routes.KEYS) { ApiKeysScreen(container, onBack = back) }
        composable(Routes.TARGETS) { TargetsScreen(container, state, onBack = back) }
        composable(Routes.GARMIN_IMPORT) { com.javon.healthdash.ui.screens.GarminImportScreen(container, state, onBack = back) }
        composable(Routes.RECURRING) { com.javon.healthdash.ui.screens.RecurringSessionsScreen(container, onBack = back) }

        logMealGraph(
            nav, log, state,
            onDone = { nav.navigate(Routes.meals()) { popUpTo(Routes.TODAY) } },
            onViewMeal = { id -> nav.navigate(Routes.meal(id)) { popUpTo(Routes.TODAY) } },
        )
    }
    }
}
