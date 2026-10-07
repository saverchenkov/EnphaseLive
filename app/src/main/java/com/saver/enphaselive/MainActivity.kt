package com.saver.enphaselive

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val zone = ZoneId.systemDefault()

    // Gateway Configuration & Client
    private val gatewayPrefs by lazy { getSharedPreferences("gateway_config", Context.MODE_PRIVATE) }
    private fun getGatewayHost(): String = gatewayPrefs.getString("host", "envoy.local")?.ifBlank { "envoy.local" } ?: "envoy.local"
    private fun setGatewayHost(host: String) {
        gatewayPrefs.edit().putString("host", host.trim()).apply()
    }
    private fun getPinnedGatewayFingerprint(): String? = gatewayPrefs.getString("cert_fp", null)
    private fun savePinnedGatewayFingerprint(fp: String) {
        gatewayPrefs.edit().putString("cert_fp", fp).apply()
    }
    private fun clearPinnedGatewayFingerprint() {
        gatewayPrefs.edit().remove("cert_fp").apply()
    }

    private val gatewayClient by lazy {
        GatewayClient(
            hostProvider = { getGatewayHost() },
            tokenProvider = { TokenStore(this).read() },
            bundledCert = runCatching { resources.openRawResource(R.raw.gateway_cert).use { it.readBytes() } }.getOrNull(),
            pinnedFingerprintProvider = { getPinnedGatewayFingerprint() },
            onPinFingerprint = { fp -> savePinnedGatewayFingerprint(fp) }
        )
    }
    private val cloudClient by lazy { CloudClient(this) }

    // Workers
    private val localWorker = Executors.newSingleThreadExecutor()
    private val cloudWorker = Executors.newSingleThreadExecutor()

    // State
    private var resumed = false
    private var localPolling = false
    private var nextLocalPoll = 0L
    private var localFailures = 0

    private var activePeriod = "day" // "day" or "month"
    private var selectedDay = LocalDate.now(zone)
    private var selectedMonth = YearMonth.now(zone)
    private var lastKnownToday = LocalDate.now(zone)
    private var lastKnownMonth = YearMonth.now(zone)
    private var cloudLoading = false
    private var lastCloudHistoryFetchMs = 0L
    private var lastCloudHistoryFetchDay: LocalDate? = null
    private var lastChargerFetch = 0L
    private var hasEvCharger: Boolean? = null
    private var nextBackgroundCheck = 0L
    private var currentProfile = "Self-Consumption"
    private var nextTariffPoll = 0L

    // Native Views
    private lateinit var energyFlowView: EnergyFlowView
    private lateinit var historyChartView: HistoryChartView

    // Live panel views
    private lateinit var gridBadge: TextView
    private lateinit var profileBadge: TextView
    private lateinit var liveStatusText: TextView

    // History panel views
    private lateinit var btnDay: TextView
    private lateinit var btnMonth: TextView
    private lateinit var btnPrevDate: TextView
    private lateinit var btnNextDate: TextView
    private lateinit var dateLabel: TextView
    private lateinit var historyFooterText: TextView

    // History Totals views
    private lateinit var totalProducedTitle: TextView
    private lateinit var totalProducedValue: TextView
    private lateinit var totalProducedSub: TextView
    private lateinit var totalConsumedTitle: TextView
    private lateinit var totalConsumedValue: TextView
    private lateinit var totalConsumedSub: TextView
    private lateinit var totalGridTitle: TextView
    private lateinit var totalGridValue: TextView
    private lateinit var totalGridSub: TextView

    // Realtime Power Chart & Data
    private lateinit var realtimeDataStore: RealtimeDataStore
    private lateinit var realtimeChartView: RealtimeChartView
    private var activeRealtimeDuration: Long = 3600L
    private val realtimeRangeButtons = ArrayList<Pair<Long, TextView>>()
    private lateinit var rtSolarBadge: TextView
    private lateinit var rtHomeBadge: TextView
    private lateinit var rtBatteryBadge: TextView
    private lateinit var rtGridBadge: TextView
    private var rtShowSolar = true
    private var rtShowBattery = true
    private var rtShowHome = true
    private var rtShowGrid = true
    private var lastRtProdW = 0f
    private var lastRtConsW = 0f
    private var lastRtDischW = 0f
    private var lastRtImpW = 0f
    private var lastRtExpW = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            window.attributes = lp
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        realtimeDataStore = RealtimeDataStore(this)
        setContentView(buildNativeLayout())
        applyImmersiveMode()
        selectRealtimeDuration(3600L)

        lastKnownToday = LocalDate.now(zone)
        lastKnownMonth = YearMonth.now(zone)
        selectedDay = lastKnownToday
        selectedMonth = lastKnownMonth
        updateDateNavControls()
    }

    private fun applyImmersiveMode() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
            val controller = window.decorView.windowInsetsController
            controller?.hide(
                android.view.WindowInsets.Type.statusBars() or
                android.view.WindowInsets.Type.navigationBars()
            )
            controller?.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    // -------------------------------------------------------------------------
    // View Building
    // -------------------------------------------------------------------------

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun cardBackground(
        bgColor: Int = Color.rgb(16, 35, 47),
        strokeColor: Int = Color.rgb(37, 66, 79),
        radiusDp: Float = 12f
    ): GradientDrawable {
        val d = resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(bgColor)
            setStroke((1.2f * d).toInt(), strokeColor)
            cornerRadius = radiusDp * d
        }
    }

    private fun pillBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(14f).toFloat()
        }
    }

    private fun buildNativeLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(10, 22, 32))
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        }

        // 1. Top Header Bar: Badges on left, Spacer, Icon buttons on right
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(3f))
        }

        gridBadge = TextView(this).apply {
            text = "On Grid"
            textSize = 12f
            setTextColor(Color.rgb(135, 229, 177))
            background = pillBackground(Color.rgb(18, 48, 45))
            setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(gridBadge)

        profileBadge = TextView(this).apply {
            text = "Self-Consumption"
            textSize = 12f
            setTextColor(Color.rgb(86, 216, 238))
            background = pillBackground(Color.rgb(18, 44, 58))
            setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val profileParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8f)
        }
        header.addView(profileBadge, profileParams)

        val spacer = View(this)
        header.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

        val btnReload = TextView(this).apply {
            text = "↻"
            textSize = 18f
            setTextColor(Color.rgb(225, 240, 250))
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = cardBackground(Color.rgb(22, 45, 60), Color.rgb(42, 69, 83), 6f)
            contentDescription = "Reload"
            setOnClickListener {
                nextLocalPoll = 0L
                nextTariffPoll = 0L
                hasEvCharger = null
                pollLocal()
                requestCloudHistory(force = true)
                requestEvCharger(force = true)
            }
        }
        val reloadParams = LinearLayout.LayoutParams(dp(30f), dp(28f))
        header.addView(btnReload, reloadParams)

        val btnSettings = TextView(this).apply {
            text = "⚙\uFE0E"
            textSize = 16f
            setTextColor(Color.rgb(225, 240, 250))
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = cardBackground(Color.rgb(22, 45, 60), Color.rgb(42, 69, 83), 6f)
            contentDescription = "Settings"
            setOnClickListener { showSettingsDialog() }
        }
        val settingsParams = LinearLayout.LayoutParams(dp(30f), dp(28f)).apply {
            leftMargin = dp(8f)
        }
        header.addView(btnSettings, settingsParams)

        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 2. Main Content Body (Left: Live Flow + Cards, Right: History Chart + Totals)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val leftPanel = buildLeftPanel()
        val rightPanel = buildRightPanel()

        val leftParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f).apply {
            rightMargin = dp(8f)
        }
        val rightParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.05f).apply {
            leftMargin = dp(8f)
        }

        body.addView(leftPanel, leftParams)
        body.addView(rightPanel, rightParams)

        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        return root
    }

    private fun buildLeftPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(Color.rgb(13, 27, 37), Color.rgb(28, 48, 62), 14f)
            setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
        }

        // Energy Flow canvas
        energyFlowView = EnergyFlowView(this)
        val flowParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(290f))
        panel.addView(energyFlowView, flowParams)

        // Live Footer
        liveStatusText = TextView(this).apply {
            text = "Connecting to IQ Gateway (192.168.86.83)..."
            textSize = 10.5f
            setTextColor(Color.rgb(130, 155, 170))
            setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
        }
        panel.addView(liveStatusText)

        // Divider
        val divider = View(this).apply {
            setBackgroundColor(Color.rgb(28, 48, 62))
        }
        panel.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)).apply {
            topMargin = dp(4f)
            bottomMargin = dp(4f)
        })

        // Real-Time Power Section
        val rtContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Header Row: Live Metric Badges (evenly distributed across top of graph)
        val rtHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(3f))
        }

        fun createMiniBadge(textColor: Int): TextView {
            val r = Color.red(textColor)
            val g = Color.green(textColor)
            val b = Color.blue(textColor)
            return TextView(this).apply {
                textSize = 10.5f
                setTextColor(textColor)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                background = cardBackground(Color.rgb(16, 36, 48), Color.argb(125, r, g, b), 6f)
                setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            }
        }

        rtSolarBadge = createMiniBadge(Color.rgb(0, 216, 246)).apply {
            text = "☀️ Solar —"
            contentDescription = "Toggle Solar on real-time graph"
            setOnClickListener {
                rtShowSolar = !rtShowSolar
                onRealtimeVisibilityChanged()
            }
        }
        rtBatteryBadge = createMiniBadge(Color.rgb(82, 229, 140)).apply {
            text = "🔋 Battery —"
            contentDescription = "Toggle Battery on real-time graph"
            setOnClickListener {
                rtShowBattery = !rtShowBattery
                onRealtimeVisibilityChanged()
            }
        }
        rtHomeBadge = createMiniBadge(Color.rgb(255, 140, 40)).apply {
            text = "⌂ Home —"
            contentDescription = "Toggle Home Consumption on real-time graph"
            setOnClickListener {
                rtShowHome = !rtShowHome
                onRealtimeVisibilityChanged()
            }
        }
        rtGridBadge = createMiniBadge(Color.rgb(176, 150, 255)).apply {
            text = "⚡ Import —"
            contentDescription = "Toggle Grid Import/Export on real-time graph"
            setOnClickListener {
                rtShowGrid = !rtShowGrid
                onRealtimeVisibilityChanged()
            }
        }

        val badgeParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(2f)
            rightMargin = dp(2f)
        }
        rtHeaderRow.addView(rtSolarBadge, badgeParams)
        rtHeaderRow.addView(rtBatteryBadge, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(2f)
            rightMargin = dp(2f)
        })
        rtHeaderRow.addView(rtHomeBadge, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(2f)
            rightMargin = dp(2f)
        })
        rtHeaderRow.addView(rtGridBadge, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(2f)
            rightMargin = dp(2f)
        })

        rtContainer.addView(rtHeaderRow)

        // Real-Time Chart View
        realtimeChartView = RealtimeChartView(this)
        val chartParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(2f)
            bottomMargin = dp(4f)
        }
        rtContainer.addView(realtimeChartView, chartParams)

        // Range Selector Row: 1 min, 1 hr, 12 hr, 24 hr
        val selectorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val intervals = listOf(
            60L to "1 min",
            3600L to "1 hr",
            43200L to "12 hr",
            86400L to "24 hr"
        )
        realtimeRangeButtons.clear()
        for ((sec, label) in intervals) {
            val btn = TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, dp(4f), 0, dp(4f))
                setOnClickListener { selectRealtimeDuration(sec) }
            }
            realtimeRangeButtons.add(sec to btn)
            val btnParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(2f)
                rightMargin = dp(2f)
            }
            selectorRow.addView(btn, btnParams)
        }
        rtContainer.addView(selectorRow)

        panel.addView(rtContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        return panel
    }

    private fun buildRightPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(Color.rgb(13, 27, 37), Color.rgb(28, 48, 62), 14f)
            setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
        }

        // History Navigation Bar
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(2f))
        }

        // Period Toggle: Day / Month (Left)
        btnDay = TextView(this).apply {
            text = "Day"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { switchPeriod("day") }
        }
        btnMonth = TextView(this).apply {
            text = "Month"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { switchPeriod("month") }
        }
        val toggleParams = LinearLayout.LayoutParams(dp(48f), dp(26f))
        navBar.addView(btnDay, toggleParams)
        navBar.addView(btnMonth, LinearLayout.LayoutParams(dp(56f), dp(26f)).apply { leftMargin = dp(4f) })

        // Spacer pushing Date Controls to the right
        val navSpacer = View(this)
        navBar.addView(navSpacer, LinearLayout.LayoutParams(0, 1, 1f))

        // Date Controls: < [Date] >
        btnPrevDate = TextView(this).apply {
            text = "<"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = cardBackground(Color.rgb(22, 45, 60), Color.rgb(42, 69, 83), 6f)
            setOnClickListener { navigateDate(-1) }
        }
        dateLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(235, 245, 250))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(8f), 0, dp(8f), 0)
        }
        btnNextDate = TextView(this).apply {
            text = ">"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = cardBackground(Color.rgb(22, 45, 60), Color.rgb(42, 69, 83), 6f)
            setOnClickListener { navigateDate(1) }
        }

        val navBtnParams = LinearLayout.LayoutParams(dp(28f), dp(26f))
        navBar.addView(btnPrevDate, LinearLayout.LayoutParams(dp(28f), dp(26f)).apply { leftMargin = dp(8f) })
        navBar.addView(dateLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        navBar.addView(btnNextDate, navBtnParams)

        panel.addView(navBar)

        // History Chart View
        historyChartView = HistoryChartView(this)
        val chartParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        panel.addView(historyChartView, chartParams)

        // Legend Row
        val legendRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(1f), 0, dp(2f))
        }
        fun addLegendItem(label: String, color: Int) {
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = dp(4f).toFloat()
                }
            }
            val lbl = TextView(this).apply {
                text = " $label   "
                textSize = 11f
                setTextColor(Color.rgb(160, 180, 195))
            }
            legendRow.addView(dot, LinearLayout.LayoutParams(dp(8f), dp(8f)).apply { gravity = Gravity.CENTER_VERTICAL })
            legendRow.addView(lbl)
        }
        addLegendItem("Produced", Color.rgb(0, 182, 211))
        addLegendItem("Consumed", Color.rgb(248, 125, 38))
        addLegendItem("Grid", Color.rgb(143, 155, 166))
        addLegendItem("Battery", Color.rgb(124, 205, 65))
        panel.addView(legendRow)

        // History Period Totals Row (matching style of Battery Card on bottom left)
        val totalsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4f), 0, dp(2f))
        }

        // 1. Produced Card
        val prodCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(Color.rgb(18, 38, 50), Color.rgb(35, 62, 77), 10f)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        }
        totalProducedTitle = TextView(this).apply {
            text = "PRODUCED"
            textSize = 10f
            setTextColor(Color.rgb(147, 172, 186))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
        }
        totalProducedValue = TextView(this).apply {
            text = "—"
            textSize = 24f
            setTextColor(Color.rgb(0, 182, 211))
            typeface = Typeface.DEFAULT_BOLD
        }
        totalProducedSub = TextView(this).apply {
            text = "Day total · Solar"
            textSize = 11f
            setTextColor(Color.rgb(180, 200, 212))
        }
        prodCard.addView(totalProducedTitle)
        prodCard.addView(totalProducedValue)
        prodCard.addView(totalProducedSub)

        // 2. Consumed Card
        val consCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(Color.rgb(18, 38, 50), Color.rgb(35, 62, 77), 10f)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        }
        totalConsumedTitle = TextView(this).apply {
            text = "CONSUMED"
            textSize = 10f
            setTextColor(Color.rgb(147, 172, 186))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
        }
        totalConsumedValue = TextView(this).apply {
            text = "—"
            textSize = 24f
            setTextColor(Color.rgb(248, 125, 38))
            typeface = Typeface.DEFAULT_BOLD
        }
        totalConsumedSub = TextView(this).apply {
            text = "Day total · Home"
            textSize = 11f
            setTextColor(Color.rgb(180, 200, 212))
        }
        consCard.addView(totalConsumedTitle)
        consCard.addView(totalConsumedValue)
        consCard.addView(totalConsumedSub)

        // 3. Net Grid Card
        val gridCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(Color.rgb(18, 38, 50), Color.rgb(35, 62, 77), 10f)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        }
        totalGridTitle = TextView(this).apply {
            text = "NET GRID"
            textSize = 10f
            setTextColor(Color.rgb(147, 172, 186))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
        }
        totalGridValue = TextView(this).apply {
            text = "—"
            textSize = 24f
            setTextColor(Color.rgb(180, 200, 212))
            typeface = Typeface.DEFAULT_BOLD
        }
        totalGridSub = TextView(this).apply {
            text = "Day total · Grid"
            textSize = 11f
            setTextColor(Color.rgb(180, 200, 212))
        }
        gridCard.addView(totalGridTitle)
        gridCard.addView(totalGridValue)
        gridCard.addView(totalGridSub)

        val prodParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(4f)
        }
        val consParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(4f)
            rightMargin = dp(4f)
        }
        val gridParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(4f)
        }

        totalsRow.addView(prodCard, prodParams)
        totalsRow.addView(consCard, consParams)
        totalsRow.addView(gridCard, gridParams)

        panel.addView(totalsRow)

        // History Footer
        historyFooterText = TextView(this).apply {
            text = "Enphase cloud · Cached · Budget: 850/mo"
            textSize = 10f
            setTextColor(Color.rgb(130, 155, 170))
            setPadding(dp(4f), dp(2f), dp(4f), dp(0f))
        }
        panel.addView(historyFooterText)

        return panel
    }

    // -------------------------------------------------------------------------
    // Real-Time Range & Badges
    // -------------------------------------------------------------------------

    private fun selectRealtimeDuration(duration: Long) {
        activeRealtimeDuration = duration
        for ((sec, btn) in realtimeRangeButtons) {
            if (sec == duration) {
                btn.setTextColor(Color.rgb(10, 22, 32))
                btn.background = cardBackground(Color.rgb(86, 216, 238), Color.rgb(86, 216, 238), 6f)
                btn.typeface = Typeface.DEFAULT_BOLD
            } else {
                btn.setTextColor(Color.rgb(180, 200, 212))
                btn.background = cardBackground(Color.rgb(22, 45, 60), Color.rgb(42, 69, 83), 6f)
                btn.typeface = Typeface.DEFAULT
            }
        }
        realtimeChartView.setDuration(duration)
        val samples = realtimeDataStore.getSamplesForWindow(duration)
        realtimeChartView.updateSamples(samples)
    }

    private fun onRealtimeVisibilityChanged() {
        realtimeChartView.setSeriesVisibility(rtShowSolar, rtShowBattery, rtShowHome, rtShowGrid)
        updateRealtimeBadges(lastRtProdW, lastRtConsW, lastRtDischW, lastRtImpW, lastRtExpW)
    }

    private fun updateRealtimeBadges(prodW: Float, consW: Float, dischW: Float, impW: Float, expW: Float) {
        lastRtProdW = prodW
        lastRtConsW = consW
        lastRtDischW = dischW
        lastRtImpW = impW
        lastRtExpW = expW

        fun fmt(w: Float): String = if (w >= 1000f) String.format(Locale.US, "%.1f kW", w / 1000f) else "${w.toInt()} W"

        fun updateBadge(badge: TextView, isVisible: Boolean, text: String, activeColor: Int) {
            badge.text = text
            if (isVisible) {
                val r = Color.red(activeColor)
                val g = Color.green(activeColor)
                val b = Color.blue(activeColor)
                badge.setTextColor(activeColor)
                badge.background = cardBackground(Color.rgb(16, 36, 48), Color.argb(125, r, g, b), 6f)
                badge.alpha = 1.0f
            } else {
                badge.setTextColor(Color.rgb(105, 125, 138))
                badge.background = cardBackground(Color.rgb(12, 22, 30), Color.rgb(26, 42, 53), 6f)
                badge.alpha = 0.45f
            }
        }

        updateBadge(rtSolarBadge, rtShowSolar, "☀️ Solar ${fmt(prodW)}", Color.rgb(0, 216, 246))
        updateBadge(rtBatteryBadge, rtShowBattery, "🔋 Battery ${fmt(dischW)}", Color.rgb(82, 229, 140))
        updateBadge(rtHomeBadge, rtShowHome, "⌂ Home ${fmt(consW)}", Color.rgb(255, 140, 40))

        val isExporting = expW > 10f
        val gridColor = if (isExporting) Color.rgb(120, 160, 255) else Color.rgb(176, 150, 255)
        val gridText = if (impW > 10f) {
            "⚡ Import ${fmt(impW)}"
        } else if (isExporting) {
            "↔ Export ${fmt(expW)}"
        } else {
            "⚡ Import 0 W"
        }
        updateBadge(rtGridBadge, rtShowGrid, gridText, gridColor)
    }

    // -------------------------------------------------------------------------
    // Navigation & Date Handling
    // -------------------------------------------------------------------------

    private fun switchPeriod(period: String) {
        if (activePeriod == period) return
        activePeriod = period
        if (period == "day") {
            selectedDay = LocalDate.now(zone)
        } else {
            selectedMonth = YearMonth.now(zone)
        }
        historyChartView.clearSelection()
        updateDateNavControls()
        requestCloudHistory()
    }

    private fun navigateDate(delta: Int) {
        val today = LocalDate.now(zone)
        val thisMonth = YearMonth.now(zone)

        if (activePeriod == "day") {
            val candidate = selectedDay.plusDays(delta.toLong())
            if (candidate <= today) {
                selectedDay = candidate
                historyChartView.clearSelection()
                updateDateNavControls()
                requestCloudHistory()
            }
        } else {
            val candidate = selectedMonth.plusMonths(delta.toLong())
            if (candidate <= thisMonth) {
                selectedMonth = candidate
                historyChartView.clearSelection()
                updateDateNavControls()
                requestCloudHistory()
            }
        }
    }

    private fun resetHistoryTotals() {
        val periodLabel = if (activePeriod == "day") "Day total" else "Month total"
        totalProducedValue.text = "..."
        totalProducedSub.text = "$periodLabel · Solar"
        totalConsumedValue.text = "..."
        totalConsumedSub.text = "$periodLabel · Home"
        totalGridTitle.text = "NET GRID"
        totalGridValue.text = "..."
        totalGridValue.setTextColor(Color.rgb(180, 200, 212))
        totalGridSub.text = "$periodLabel · Grid"
    }

    private fun updateDateNavControls() {
        val today = LocalDate.now(zone)
        val thisMonth = YearMonth.now(zone)

        if (activePeriod == "day") {
            btnDay.setTextColor(Color.rgb(10, 22, 32))
            btnDay.background = cardBackground(Color.rgb(86, 216, 238), Color.rgb(86, 216, 238), 6f)
            btnMonth.setTextColor(Color.rgb(180, 200, 212))
            btnMonth.background = cardBackground(Color.rgb(22, 45, 60), Color.rgb(42, 69, 83), 6f)

            dateLabel.text = if (selectedDay == today) "Today, " + selectedDay.format(DateTimeFormatter.ofPattern("MMM d"))
                             else selectedDay.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
            btnNextDate.isEnabled = selectedDay < today
            btnNextDate.alpha = if (selectedDay < today) 1.0f else 0.35f
        } else {
            btnMonth.setTextColor(Color.rgb(10, 22, 32))
            btnMonth.background = cardBackground(Color.rgb(86, 216, 238), Color.rgb(86, 216, 238), 6f)
            btnDay.setTextColor(Color.rgb(180, 200, 212))
            btnDay.background = cardBackground(Color.rgb(22, 45, 60), Color.rgb(42, 69, 83), 6f)

            dateLabel.text = if (selectedMonth == thisMonth) "This Month (" + selectedMonth.format(DateTimeFormatter.ofPattern("MMM yyyy")) + ")"
                             else selectedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
            btnNextDate.isEnabled = selectedMonth < thisMonth
            btnNextDate.alpha = if (selectedMonth < thisMonth) 1.0f else 0.35f
        }
    }

    private fun checkDayRollover() {
        val nowToday = LocalDate.now(zone)
        val nowMonth = YearMonth.now(zone)

        if (nowToday != lastKnownToday) {
            val wasViewingToday = (activePeriod == "day" && selectedDay == lastKnownToday)
            val wasViewingThisMonth = (activePeriod == "month" && selectedMonth == lastKnownMonth)

            lastKnownToday = nowToday
            lastKnownMonth = nowMonth

            if (wasViewingToday) {
                selectedDay = nowToday
                historyChartView.clearSelection()
                updateDateNavControls()
                resetHistoryTotals()
                val emptyBars = HistoryParser.day(nowToday.toString(), emptyMap())
                val freshDay = JSONObject().put("period", "day").put("date", nowToday.toString()).put("bars", emptyBars)
                historyChartView.update(freshDay)
                historyChartView.message("Awaiting first readings for today")
                historyFooterText.text = "Enphase cloud · New day · Refreshes at 6:30 AM"
            } else if (wasViewingThisMonth && nowMonth != selectedMonth) {
                selectedMonth = nowMonth
                historyChartView.clearSelection()
                updateDateNavControls()
                resetHistoryTotals()
                val emptyBars = HistoryParser.month(nowMonth.toString(), emptyMap())
                val freshMonth = JSONObject().put("period", "month").put("date", nowMonth.toString()).put("bars", emptyBars)
                historyChartView.update(freshMonth)
                historyChartView.message("Awaiting first readings for this month")
                historyFooterText.text = "Enphase cloud · New month · Refreshes at 6:30 AM"
            } else {
                updateDateNavControls()
            }
        }
    }

    private fun checkBackgroundRefresh() {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed < nextBackgroundCheck) return
        nextBackgroundCheck = nowElapsed + 5000L

        checkDayRollover()

        if (!cloudClient.configured() || cloudLoading) return
        if (cloudClient.callCount() >= 850) {
            historyFooterText.text = "Enphase cloud · Budget cap reached (850/850)"
            return
        }

        val nowToday = LocalDate.now(zone)
        val nowMonth = YearMonth.now(zone)

        val isViewingCurrent = if (activePeriod == "day") selectedDay == nowToday else selectedMonth == nowMonth
        if (!isViewingCurrent) return

        val nowLocalTime = java.time.LocalTime.now(zone)
        val minuteOfDay = nowLocalTime.hour * 60 + nowLocalTime.minute

        // Active daytime window: 6:30 AM to 10:30 PM (390 to 1350 minutes)
        val inActiveWindow = minuteOfDay in 390..1350
        if (!inActiveWindow) return

        val refreshIntervalMs = 3 * 3600 * 1000L + 30 * 60 * 1000L // 3.5 hours
        val isFirstMorningFetch = (lastCloudHistoryFetchDay != nowToday)
        val intervalElapsed = (nowElapsed - lastCloudHistoryFetchMs >= refreshIntervalMs)

        if (isFirstMorningFetch || intervalElapsed) {
            requestCloudHistory(force = true)
            if (hasEvCharger != false) {
                requestEvCharger(force = false)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Local Gateway Polling Loop (1 second cadence, independent of cloud)
    // -------------------------------------------------------------------------

    private fun pollLocal() {
        if (localPolling || !resumed || SystemClock.elapsedRealtime() < nextLocalPoll) return
        localPolling = true
        val started = SystemClock.elapsedRealtime()

        localWorker.execute {
            val result = runCatching { gatewayClient.live() }

            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs >= nextTariffPoll) {
                nextTariffPoll = nowMs + 60000L
                runCatching {
                    val raw = gatewayClient.readRaw("/admin/lib/tariff")
                    val json = JSONObject(raw)
                    val mode = json.optJSONObject("tariff")?.optJSONObject("storage_settings")?.optString("mode")
                        ?: json.optJSONObject("schedule")?.optString("battery_mode")
                        ?: json.optJSONObject("schedule")?.optString("batt_mode")
                        ?: ""
                    val override = json.optJSONObject("schedule")?.optBoolean("override", false) == true
                    val resolved = if (override && mode.contains("backup", true)) {
                        "Full Backup (Storm)"
                    } else when (mode.lowercase(Locale.US)) {
                        "self-consumption", "self_consumption" -> "Self-Consumption"
                        "savings", "time-of-use", "tou" -> "Savings"
                        "backup", "full-backup", "full_backup" -> "Full Backup"
                        "ai-optimized", "ai_optimized" -> "AI Optimized"
                        else -> if (mode.isNotEmpty()) {
                            mode.replace("-", " ").replace("_", " ").split(" ").joinToString(" ") { word ->
                                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                            }
                        } else currentProfile
                    }
                    runOnUiThread {
                        if (!isDestroyed && resumed) updateProfileBadge(resolved)
                    }
                }.onFailure {
                    nextTariffPoll = nowMs + 15000L
                }
            }

            runOnUiThread {
                localPolling = false
                if (isDestroyed || !resumed) return@runOnUiThread

                result.onSuccess { data ->
                    localFailures = 0
                    nextLocalPoll = started + 1000L
                    energyFlowView.update(data)

                    val meters = data.optJSONObject("meters")
                    if (meters != null) {
                        val storagePw = meters.optJSONObject("storage")?.optDouble("agg_p_mw", 0.0) ?: 0.0

                        // Grid status badge
                        val relayState = meters.optInt("main_relay_state", 1)
                        if (relayState == 1) {
                            gridBadge.text = "On Grid"
                            gridBadge.setTextColor(Color.rgb(135, 229, 177))
                            gridBadge.background = pillBackground(Color.rgb(18, 48, 45))
                        } else {
                            gridBadge.text = "Off Grid"
                            gridBadge.setTextColor(Color.rgb(255, 196, 118))
                            gridBadge.background = pillBackground(Color.rgb(55, 38, 20))
                        }

                        // Last update freshness
                        val lastUpdate = meters.optLong("last_update", 0L)
                        val nowSec = System.currentTimeMillis() / 1000L
                        val ageSec = if (lastUpdate > 0) nowSec - lastUpdate else 0L
                        val timeStr = if (lastUpdate > 0) {
                            Instant.ofEpochSecond(lastUpdate).atZone(zone).format(DateTimeFormatter.ofPattern("h:mm:ss a"))
                        } else "Live"

                        liveStatusText.text = if (ageSec > 30) {
                            "Gateway reading: $timeStr · Stale (${ageSec}s ago) · 1s polling"
                        } else {
                            "Gateway reading: $timeStr · ${ageSec}s ago · 1s local polling"
                        }

                        // Feed real-time telemetry into RealtimeDataStore
                        val pvMw = meters.optJSONObject("pv")?.optDouble("agg_p_mw", 0.0) ?: 0.0
                        val loadMw = meters.optJSONObject("load")?.optDouble("agg_p_mw", 0.0) ?: 0.0
                        val gridMw = meters.optJSONObject("grid")?.optDouble("agg_p_mw", 0.0) ?: 0.0

                        val pvW = max(0.0, pvMw / 1000.0).toFloat()
                        val loadW = max(0.0, loadMw / 1000.0).toFloat()
                        val dischW = if (storagePw > 0.0) (storagePw / 1000.0).toFloat() else 0f
                        val expW = if (gridMw < 0.0) (-gridMw / 1000.0).toFloat() else 0f
                        val impW = if (gridMw > 0.0) (gridMw / 1000.0).toFloat() else 0f

                        realtimeDataStore.addSample(pvW, loadW, dischW, expW, impW, nowSec)
                        val windowSamples = realtimeDataStore.getSamplesForWindow(activeRealtimeDuration)
                        realtimeChartView.updateSamples(windowSamples)
                        updateRealtimeBadges(pvW, loadW, dischW, impW, expW)
                    }
                }.onFailure { err ->
                    localFailures++
                    val backoffMs = minOf(30000L, 5000L * localFailures)
                    nextLocalPoll = SystemClock.elapsedRealtime() + backoffMs
                    energyFlowView.offline()

                    val message = when (err) {
                        is javax.net.ssl.SSLException -> "Gateway SSL certificate verification failed"
                        is java.io.IOException -> "Gateway unreachable. Checking Wi-Fi..."
                        else -> err.message ?: "Gateway data unavailable"
                    }
                    liveStatusText.text = "$message · Retrying in ${backoffMs / 1000}s"
                }
            }
        }
    }

    private fun updateProfileBadge(profile: String) {
        currentProfile = profile
        profileBadge.text = profile
        when {
            profile.contains("Backup", ignoreCase = true) -> {
                profileBadge.setTextColor(Color.rgb(255, 196, 118))
                profileBadge.background = pillBackground(Color.rgb(55, 38, 20))
            }
            profile.contains("Savings", ignoreCase = true) -> {
                profileBadge.setTextColor(Color.rgb(169, 185, 247))
                profileBadge.background = pillBackground(Color.rgb(35, 35, 60))
            }
            profile.contains("AI", ignoreCase = true) -> {
                profileBadge.setTextColor(Color.rgb(135, 229, 177))
                profileBadge.background = pillBackground(Color.rgb(18, 48, 45))
            }
            else -> { // Self-Consumption
                profileBadge.setTextColor(Color.rgb(86, 216, 238))
                profileBadge.background = pillBackground(Color.rgb(18, 44, 58))
            }
        }
    }

    private val localTicker = object : Runnable {
        override fun run() {
            if (resumed) {
                pollLocal()
                checkBackgroundRefresh()
                handler.postDelayed(this, 250L)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cloud History & EV Charger Requests
    // -------------------------------------------------------------------------

    private fun requestCloudHistory(force: Boolean = false) {
        if (!cloudClient.configured()) {
            historyChartView.message("Connect Enphase history in Settings")
            historyFooterText.text = "Cloud configuration not provisioned · See Settings"
            return
        }

        val period = activePeriod
        val dateStr = if (period == "day") selectedDay.toString() else selectedMonth.toString()

        // 1. Instant cache check: if cached locally, render immediately without API calls or lag!
        if (!force) {
            val cached = cloudClient.getCachedHistory(period, dateStr)
            if (cached != null) {
                cloudLoading = false
                historyChartView.update(cached)
                updateHistoryTotals(cached)
                val isPrior = cloudClient.isPriorPeriod(period, dateStr)
                val cacheNote = if (isPrior) "Cached permanently" else "Cached locally"
                val calls = cloudClient.callCount()
                historyFooterText.text = "Enphase cloud · $cacheNote · Calls: $calls / 850"
                val fetchedAtSec = cached.optLong("_fetched_at", 0)
                if (fetchedAtSec > 0) {
                    val fetchedDate = Instant.ofEpochSecond(fetchedAtSec).atZone(zone).toLocalDate()
                    if (fetchedDate == LocalDate.now(zone)) {
                        lastCloudHistoryFetchDay = fetchedDate
                        if (lastCloudHistoryFetchMs == 0L) {
                            lastCloudHistoryFetchMs = SystemClock.elapsedRealtime()
                        }
                    }
                }
                return
            }
        }

        if (cloudLoading && !force) return
        cloudLoading = true
        resetHistoryTotals()

        val label = if (period == "month") selectedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                    else selectedDay.format(DateTimeFormatter.ofPattern("MMM d"))
        historyChartView.showLoading("Loading $label...")

        cloudWorker.execute {
            val result = runCatching { cloudClient.history(period, dateStr, force) }
            runOnUiThread {
                cloudLoading = false
                if (isDestroyed || !resumed) return@runOnUiThread

                result.onSuccess { hist ->
                    lastCloudHistoryFetchMs = SystemClock.elapsedRealtime()
                    lastCloudHistoryFetchDay = LocalDate.now(zone)
                    historyChartView.update(hist)
                    updateHistoryTotals(hist)
                    val warning = hist.optString("warning")
                    val calls = cloudClient.callCount()
                    val warningSuffix = if (warning.isNotEmpty()) " · $warning" else ""
                    val isPrior = cloudClient.isPriorPeriod(period, dateStr)
                    val cacheNote = if (isPrior) "Cached permanently" else "Cached"
                    historyFooterText.text = "Enphase cloud · $cacheNote · Calls: $calls / 850$warningSuffix"
                }.onFailure { err ->
                    // On failure, retry in 15 minutes instead of waiting full 3.5 hours
                    lastCloudHistoryFetchMs = SystemClock.elapsedRealtime() - (3 * 3600 * 1000L + 15 * 60 * 1000L)
                    historyChartView.message(err.message ?: "History unavailable")
                    val calls = cloudClient.callCount()
                    historyFooterText.text = "History error: ${err.message} · Calls: $calls / 850"
                }
            }
        }
    }

    private fun updateHistoryTotals(hist: JSONObject) {
        val bars = hist.optJSONArray("bars") ?: return
        var prodSum = 0.0
        var consSum = 0.0
        var impSum = 0.0
        var expSum = 0.0
        var hasProd = false
        var hasCons = false
        var hasImp = false
        var hasExp = false

        for (i in 0 until bars.length()) {
            val b = bars.optJSONObject(i) ?: continue
            if (b.has("production") && !b.isNull("production")) {
                prodSum += b.optDouble("production", 0.0)
                hasProd = true
            }
            if (b.has("consumption") && !b.isNull("consumption")) {
                consSum += b.optDouble("consumption", 0.0)
                hasCons = true
            }
            if (b.has("import") && !b.isNull("import")) {
                impSum += b.optDouble("import", 0.0)
                hasImp = true
            }
            if (b.has("export") && !b.isNull("export")) {
                expSum += b.optDouble("export", 0.0)
                hasExp = true
            }
        }

        val periodLabel = if (activePeriod == "day") "Day total" else "Month total"

        if (hasProd) {
            totalProducedValue.text = String.format(Locale.US, "%.1f kWh", prodSum)
            totalProducedSub.text = "$periodLabel · Solar"
        } else {
            totalProducedValue.text = "—"
            totalProducedSub.text = "$periodLabel · No data"
        }

        if (hasCons) {
            totalConsumedValue.text = String.format(Locale.US, "%.1f kWh", consSum)
            totalConsumedSub.text = "$periodLabel · Home"
        } else {
            totalConsumedValue.text = "—"
            totalConsumedSub.text = "$periodLabel · No data"
        }

        if (hasImp || hasExp) {
            val net = expSum - impSum // positive if net export, negative if net import
            val impStr = String.format(Locale.US, "%.1f", impSum)
            val expStr = String.format(Locale.US, "%.1f", expSum)
            if (net >= 0.05) {
                totalGridTitle.text = "NET EXPORT"
                totalGridValue.text = String.format(Locale.US, "%.1f kWh", net)
                totalGridValue.setTextColor(Color.rgb(135, 229, 177)) // Green
                totalGridSub.text = "Imp: $impStr · Exp: $expStr kWh"
            } else if (net <= -0.05) {
                totalGridTitle.text = "NET IMPORT"
                totalGridValue.text = String.format(Locale.US, "%.1f kWh", -net)
                totalGridValue.setTextColor(Color.rgb(255, 196, 118)) // Orange
                totalGridSub.text = "Imp: $impStr · Exp: $expStr kWh"
            } else {
                totalGridTitle.text = "NET GRID"
                totalGridValue.text = "0.0 kWh"
                totalGridValue.setTextColor(Color.rgb(180, 200, 212))
                totalGridSub.text = "Imp: $impStr · Exp: $expStr kWh"
            }
        } else {
            totalGridTitle.text = "NET GRID"
            totalGridValue.text = "—"
            totalGridValue.setTextColor(Color.rgb(180, 200, 212))
            totalGridSub.text = "$periodLabel · No data"
        }
    }

    private fun requestEvCharger(force: Boolean = false) {
        if (!cloudClient.configured()) {
            energyFlowView.setEvCharger(null, "Cloud off")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastChargerFetch < 30 * 60 * 1000L) return
        lastChargerFetch = now

        cloudWorker.execute {
            val result = runCatching { cloudClient.charger() }
            runOnUiThread {
                if (isDestroyed || !resumed) return@runOnUiThread

                result.onSuccess { telemetry ->
                    val devices = telemetry.optJSONObject("devices")
                    val evseList = devices?.optJSONArray("evse")
                    if (evseList != null && evseList.length() > 0) {
                        hasEvCharger = true
                        val ev = evseList.getJSONObject(0)
                        val rawMode = if (ev.isNull("operational_mode")) "" else ev.optString("operational_mode", "")
                        val mode = if (rawMode.isEmpty() || rawMode.equals("null", true)) "Not Plugged-in" else rawMode.replace('_', ' ')
                        val powerW = if (ev.isNull("power")) 0.0 else ev.optDouble("power", 0.0)
                        val powerKw = if (powerW > 0) powerW / 1000.0 else 0.0

                        energyFlowView.setEvCharger(powerKw, mode)
                    } else {
                        hasEvCharger = false
                        energyFlowView.setEvCharger(null, "No charger")
                    }
                }.onFailure {
                    energyFlowView.setEvCharger(null, "Offline")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Settings Dialog
    // -------------------------------------------------------------------------

    private fun showSettingsDialog() {
        val calls = cloudClient.callCount()
        val configured = cloudClient.configured()
        val currentHost = getGatewayHost()
        val statusMsg = if (configured) "Configured (Calls this month: $calls / 850 cap)" else "Not configured"

        val options = arrayOf(
            "Configure Gateway Host (Current: $currentHost)",
            "Enter / Replace Gateway Token",
            "Configure Enphase Cloud API ($statusMsg)",
            "Refresh Cloud History & Charger Now"
        )

        AlertDialog.Builder(this)
            .setTitle("Dashboard Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptGatewayHost()
                    1 -> promptGatewayToken()
                    2 -> promptCloudConfig()
                    3 -> {
                        hasEvCharger = null
                        requestCloudHistory(force = true)
                        requestEvCharger(force = true)
                        Toast.makeText(this, "Cloud refresh requested", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun promptGatewayHost() {
        val currentHost = getGatewayHost()
        val pinnedFp = getPinnedGatewayFingerprint() ?: "Default / First-Use"
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
        }
        val info = TextView(this).apply {
            text = "Enter the IP address or mDNS hostname of your Enphase Gateway on your local network (e.g. envoy.local or 192.168.1.50).\n\nPinned Cert: $pinnedFp"
            textSize = 13f
            setPadding(0, 0, 0, dp(12f))
        }
        val input = EditText(this).apply {
            setText(currentHost)
            hint = "envoy.local or IP address"
            setSelection(text.length)
        }
        layout.addView(info)
        layout.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Gateway Host / IP")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newHost = input.text.toString().trim()
                if (newHost.isNotBlank()) {
                    setGatewayHost(newHost)
                    localFailures = 0
                    nextLocalPoll = 0L
                    pollLocal()
                    Toast.makeText(this, "Gateway host updated to $newHost", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Reset Cert Pinning") { _, _ ->
                clearPinnedGatewayFingerprint()
                Toast.makeText(this, "Certificate pinning reset. Will trust next valid gateway cert.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptGatewayToken() {
        val input = EditText(this).apply {
            hint = "Paste token or JSON response"
            inputType = 0x00080081 // textPassword
        }
        AlertDialog.Builder(this)
            .setTitle("Gateway Access Token")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                runCatching {
                    TokenStore(this).save(input.text.toString())
                    localFailures = 0
                    nextLocalPoll = 0L
                    pollLocal()
                    Toast.makeText(this, "Gateway token saved", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, "Could not save token: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptCloudConfig() {
        val existing = cloudClient.getCredentials() ?: JSONObject()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
        }

        val info = TextView(this).apply {
            text = "Enphase Cloud API v4 Credentials (optional, for historical charts and EV charger status):"
            textSize = 13f
            setPadding(0, 0, 0, dp(8f))
        }
        layout.addView(info)

        val edApiKey = EditText(this).apply {
            hint = "API Key"
            setText(existing.optString("api_key", ""))
        }
        layout.addView(edApiKey)

        val edClientId = EditText(this).apply {
            hint = "Client ID"
            setText(existing.optString("client_id", ""))
        }
        layout.addView(edClientId)

        val edClientSecret = EditText(this).apply {
            hint = "Client Secret"
            setText(existing.optString("client_secret", ""))
            inputType = 0x00080081 // textPassword
        }
        layout.addView(edClientSecret)

        val edSystemId = EditText(this).apply {
            hint = "System / Site ID (optional, auto-discovered if empty)"
            setText(existing.optString("system_id", existing.optString("site_id", "")))
        }
        layout.addView(edSystemId)

        AlertDialog.Builder(this)
            .setTitle("Cloud API Configuration")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val config = existing
                config.put("api_key", edApiKey.text.toString().trim())
                config.put("client_id", edClientId.text.toString().trim())
                config.put("client_secret", edClientSecret.text.toString().trim())
                val sysId = edSystemId.text.toString().trim()
                if (sysId.isNotBlank()) {
                    config.put("system_id", sysId)
                }
                cloudClient.save(config)
                Toast.makeText(this, "Cloud API configuration saved", Toast.LENGTH_SHORT).show()
                hasEvCharger = null
                requestCloudHistory(force = true)
                requestEvCharger(force = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        resumed = true
        applyImmersiveMode()
        checkDayRollover()
        handler.removeCallbacks(localTicker)
        handler.post(localTicker)
        val windowSamples = realtimeDataStore.getSamplesForWindow(activeRealtimeDuration)
        realtimeChartView.updateSamples(windowSamples)
        requestCloudHistory()
        if (hasEvCharger != false) {
            requestEvCharger()
        }
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(localTicker)
        realtimeDataStore.flushToDiskSync()
        super.onPause()
    }

    override fun onDestroy() {
        realtimeDataStore.flushToDiskSync()
        localWorker.shutdownNow()
        cloudWorker.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
