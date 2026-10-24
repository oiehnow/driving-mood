package kr.drivingmood.pleospreview

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/** Mood/lighting are a staged example; local music and navigation use actual device services. */
class PreviewActivity : ComponentActivity() {
    private val ink = Color.rgb(0, 44, 95)
    private val muted = Color.rgb(102, 119, 137)
    private val paper = Color.rgb(246, 247, 249)
    // Activity configuration reflects this resizable app window, not the full vehicle display.
    private val wideLayout: Boolean
        get() = resources.configuration.let { it.screenWidthDp >= 1000 && it.screenWidthDp > it.screenHeightDp }
    private val uiScale: Float
        get() = when {
            wideLayout && resources.configuration.screenWidthDp >= 1200 -> 1.6f
            resources.configuration.screenWidthDp >= 600 -> 1.35f
            else -> 1f
        }
    private val layoutScale get() = if (wideLayout) uiScale else 1f
    private val pagePadding get() = if (wideLayout) 28 else 16
    private var finished = false
    private var palette = 1
    private var brightness = 45
    private var musicLighting = false
    private var lightStrip: View? = null
    private var musicLightingButton: Button? = null
    private var animator: ValueAnimator? = null
    private lateinit var navigation: NavigationLauncher
    private lateinit var clickSound: ClickSoundPlayer
    private lateinit var music: MidnightMusicPlayer
    private var albumView: ImageView? = null
    private var trackTitle: TextView? = null
    private var trackArtist: TextView? = null
    private var playbackButton: Button? = null
    private var trackTime: TextView? = null
    private var trackSeek: SeekBar? = null
    private var seeking = false
    private val navigationHandler = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var navigationStatus = "내비에 연결하고 있어요."
    private var destinationStatus = "현재 경로를 눌러 목적지를 확인하세요."
    private var remainingStatus = "남은 시간 —  ·  남은 거리 —"
    private var routeStatus = ""
    private var navigationStatusView: TextView? = null
    private var destinationStatusView: TextView? = null
    private var remainingStatusView: TextView? = null
    private var routeStatusView: TextView? = null
    private val navigationButtons = mutableListOf<Button>()
    private var reconnectButton: Button? = null
    private var readStartedAt: Long? = null
    private var destinationReceived = false
    private var routeReceived = false
    private var searchDraft = ""
    private var searchDialog: AlertDialog? = null
    private var searchInput: EditText? = null
    private val navigationReadTimeout = Runnable {
        readStartedAt = null
        if (!destinationReceived) destinationStatus = "목적지 정보를 받지 못했어요."
        if (!routeReceived) routeStatus = "경로 상태를 받지 못했어요."
        navigationStatus = if (destinationReceived || routeReceived) "내비에서 일부 정보만 받았어요." else "내비 응답을 받지 못했어요. 다시 조회해 주세요."
        renderNavigationStatus()
    }
    private lateinit var root: LinearLayout
    private lateinit var body: FrameLayout
    private val ambientWash by lazy { MoodWash(this, ambient = true) }
    private val font by lazy { resources.getFont(R.font.pretendard_variable) }
    private val previewBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = cancelPreview()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clickSound = ClickSoundPlayer(this)
        music = MidnightMusicPlayer(this) { renderMusic() }
        onBackPressedDispatcher.addCallback(this, previewBack)
        val returnState = getSharedPreferences("window-return", MODE_PRIVATE)
        finished = savedInstanceState?.getBoolean("finished") ?: returnState.getBoolean("result", false)
        returnState.edit().remove("result").apply()
        palette = savedInstanceState?.getInt("palette") ?: returnState.getInt("palette", 1)
        brightness = savedInstanceState?.getInt("brightness") ?: returnState.getInt("brightness", 45)
        musicLighting = savedInstanceState?.getBoolean("musicLighting") ?: returnState.getBoolean("musicLighting", false)
        searchDraft = savedInstanceState?.getString("navigationSearchDraft").orEmpty()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
            setPadding(dp(pagePadding), dp(14), dp(pagePadding), dp(14))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(pagePadding) + insets.systemWindowInsetLeft, dp(14) + insets.systemWindowInsetTop,
                dp(pagePadding) + insets.systemWindowInsetRight, dp(14) + insets.systemWindowInsetBottom)
            insets
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(logo(34), LinearLayout.LayoutParams(dp(34), dp(34)))
        header.addView(text("Driving Mood", 20f, true).apply { setPadding(dp(12), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text("UI 프로토타입", 12f, color = muted))
        header.addView(Button(this).apply {
            text = "−"; textSize = 26f; setTextColor(ink)
            contentDescription = "최소화 · 음악 계속 재생"; tooltipText = "최소화"
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(0, 0, 0, 0); setBackgroundColor(Color.TRANSPARENT)
            isSoundEffectsEnabled = false
            setOnClickListener {
                getSharedPreferences("window-return", MODE_PRIVATE).edit().putBoolean("result", finished)
                    .putInt("palette", palette).putInt("brightness", brightness).putBoolean("musicLighting", musicLighting).apply()
                finish()
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(Button(this).apply {
            text = "×"; textSize = 28f; setTextColor(ink)
            contentDescription = "앱 닫기"
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(0, 0, 0, 0); setBackgroundColor(Color.TRANSPARENT)
            isSoundEffectsEnabled = false
            tooltipText = "종료"
            setOnClickListener {
                getSharedPreferences("window-return", MODE_PRIVATE).edit().remove("result").apply()
                music.stopAndClose(); finishAndRemoveTask()
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })
        root.addView(header)
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(if (wideLayout) 20 else 12) })
        setContentView(root)
        navigation = NavigationLauncher(this, ::onNavigationEvent)
        if (finished) showResult() else showStart()
        navigation.connect()
        music.prepare()
        if (savedInstanceState?.getBoolean("navigationSearchOpen") == true && finished) {
            body.post { if (!destroyed) showNavigationSearch() }
        }
    }

    private fun showStart() {
        previewBack.isEnabled = false
        body.animate().cancel(); body.alpha = 1f
        body.removeAllViews()
        body.addView(ambientWash, FrameLayout.LayoutParams(-1, -1))
        val center = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val mark = logo(144).apply {
            isClickable = true; isFocusable = true
            elevation = dp(8).toFloat()
            foreground = RippleDrawable(ColorStateList.valueOf(0x24002c5f), null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            contentDescription = "무드 분석 화면 미리보기 시작"
            isSoundEffectsEnabled = false
            setOnClickListener { clickSound.play(); beginPreview() }
        }
        center.addView(mark, LinearLayout.LayoutParams(dp(144), dp(144)))
        addScrollableCenter(center)
        body.addView(text("oieh", 11f, color = muted), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
    }

    private fun beginPreview() {
        if (animator != null) return
        previewBack.isEnabled = true
        body.animate().cancel(); body.alpha = 1f
        body.removeAllViews()
        body.addView(ambientWash, FrameLayout.LayoutParams(-1, -1))
        val center = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val ring = LogoProgress(this)
        val mark = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(-1, -1))
            addView(logo(128), FrameLayout.LayoutParams(dp(128), dp(128), Gravity.CENTER))
        }
        center.addView(mark, LinearLayout.LayoutParams(dp(156), dp(156)))
        val title = text("주변을 담고 있어요", 18f, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(22), 0, dp(8))
        }
        center.addView(title)
        val percent = text(getString(R.string.preview_progress, 0), 13f, color = muted).apply { gravity = Gravity.CENTER }
        center.addView(percent)
        center.addView(action("취소", primary = false) { cancelPreview() },
            LinearLayout.LayoutParams(dp(100), dp(48)).apply { topMargin = dp(22) })
        addScrollableCenter(center)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 5200
            interpolator = DecelerateInterpolator(1f)
            addUpdateListener {
                val fraction = it.animatedValue as Float
                ring.fraction = fraction
                percent.text = getString(R.string.preview_progress, (fraction * 100).toInt())
                title.text = if (fraction < .55f) "주변을 담고 있어요" else "무드를 분석하고 있어요"
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null; finished = true; showResult()
                    body.alpha = 0f; body.animate().alpha(1f).setDuration(500).start()
                }
            })
        }
        animator?.start()
    }

    private fun cancelPreview() {
        animator?.removeAllListeners(); animator?.cancel(); animator = null
        finished = false; showStart()
    }

    private fun showResult() {
        previewBack.isEnabled = true
        body.removeAllViews()
        val wide = wideLayout
        // The paired cards determine landscape height instead of stretching the hero into empty space.
        val scroll = ScrollView(this)
        val panels = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        scroll.addView(panels)
        body.addView(scroll)
        val hero = FrameLayout(this).apply { minimumHeight = dp(if (wide) 370 else 260); clipToOutline = true; background = rounded(Color.WHITE) }
        hero.addView(MoodWash(this), FrameLayout.LayoutParams(-1, -1))
        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(if (wide) 20 else 16), dp(12), dp(if (wide) 20 else 16), dp(12))
        }
        summary.addView(text("지금 주변의 무드 · 예시", 14f, color = muted).apply { gravity = Gravity.CENTER })
        summary.addView(text("차가운", if (wide) 40f else 34f, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(2))
        })
        summary.addView(text("차분한  ·  감성적인", if (wide) 19f else 16f).apply { gravity = Gravity.CENTER })
        summary.addView(text("새벽 · 한산한 고속도로", if (wide) 15f else 12f, color = muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(8))
        })
        val scores = LinearLayout(this).apply { gravity = Gravity.CENTER; background = rounded(0xdfffffff.toInt(), 18) }
        listOf("색감" to "28", "활기" to "12", "우울감" to "86").forEach { (label, score) ->
            scores.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(6))
                addView(text(label, 13f, color = muted).apply { gravity = Gravity.CENTER })
                addView(text(score, if (wide) 27f else 22f, true).apply { gravity = Gravity.CENTER })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        summary.addView(scores, LinearLayout.LayoutParams(-1, -2))
        summary.addView(action("다시 분석") { finished = false; beginPreview() },
            LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
        hero.addView(summary, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        val resultColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panels.addView(resultColumn, if (wide) LinearLayout.LayoutParams(0, -2, 1.15f).apply { rightMargin = dp(20) }
            else LinearLayout.LayoutParams(-1, -2))
        resultColumn.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        navigationCard(resultColumn)
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panels.addView(side, if (wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        musicCard(side)
        lightingCard(side)
        if (navigation.connection == NavigationLauncher.Connection.CONNECTED && !destinationReceived && readStartedAt == null) readNavigation()
    }

    private fun navigationCard(parent: LinearLayout) {
        val panel = card(parent, "내비게이션")
        navigationButtons.clear()
        val actions = LinearLayout(this)
        val open = action("내비 열기", false) {
            showNavigationRequest(navigation.openNavigation(), "내비 화면을 요청했어요.")
        }
        val search = action("장소 검색", false) { showNavigationSearch() }
        navigationButtons += open
        navigationButtons += search
        actions.addView(open, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(search, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        panel.addView(actions)
        val read = action("현재 경로", false) { readNavigation() }
        navigationButtons += read
        actions.addView(read, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        destinationStatusView = text(destinationStatus, 14f, true).also {
            it.setPadding(0, dp(12), 0, 0); panel.addView(it)
        }
        remainingStatusView = text(remainingStatus, 16f, true).also {
            it.setPadding(0, dp(6), 0, dp(4)); panel.addView(it)
        }
        routeStatusView = text(routeStatus, 12f, color = muted).also { panel.addView(it) }
        navigationStatusView = text(navigationStatus, 12f, color = muted).also {
            it.setPadding(0, dp(8), 0, 0); panel.addView(it)
        }
        reconnectButton = action("다시 연결", false) {
            navigation.close()
            readStartedAt = null
            navigationHandler.removeCallbacks(navigationReadTimeout)
            destinationStatus = "현재 경로를 눌러 목적지를 확인하세요."
            routeStatus = ""
            navigation = NavigationLauncher(this, ::onNavigationEvent)
            navigation.connect()
        }.also { panel.addView(it, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) }) }
        renderNavigationStatus()
    }

    private fun renderNavigationStatus() {
        if (destroyed) return
        navigationStatusView?.text = navigationStatus
        destinationStatusView?.text = destinationStatus
        remainingStatusView?.text = remainingStatus
        routeStatusView?.apply { text = routeStatus; visibility = if (routeStatus.isBlank()) View.GONE else View.VISIBLE }
        val connected = navigation.connection == NavigationLauncher.Connection.CONNECTED
        navigationButtons.forEach { it.isEnabled = connected; it.alpha = if (connected) 1f else .5f }
        reconnectButton?.visibility = if (navigation.connection in setOf(NavigationLauncher.Connection.FAILED,
                NavigationLauncher.Connection.UNAVAILABLE)) View.VISIBLE else View.GONE
    }

    private fun showNavigationRequest(result: NavigationLauncher.RequestResult, submitted: String) {
        navigationStatus = when (result) {
            NavigationLauncher.RequestResult.SUBMITTED -> submitted
            NavigationLauncher.RequestResult.NOT_CONNECTED -> "내비 연결을 확인해 주세요."
            NavigationLauncher.RequestResult.PERMISSION_MISSING -> "내비 접근 권한을 확인해 주세요."
            NavigationLauncher.RequestResult.INVALID_INPUT -> "검색어 또는 목적지를 확인해 주세요."
            NavigationLauncher.RequestResult.FAILED -> "내비에 요청하지 못했어요."
        }
        renderNavigationStatus()
    }

    private fun showNavigationSearch() {
        if (searchDialog?.isShowing == true) return
        val input = EditText(this).apply {
            setText(searchDraft); hint = "장소 이름 또는 주소"; contentDescription = "내비에서 검색할 장소"
            typeface = font; textSize = 16f; maxLines = 2
            inputType = InputType.TYPE_CLASS_TEXT; filters = arrayOf(InputFilter.LengthFilter(200))
        }
        searchInput = input
        val wrapper = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), dp(8)); addView(input, FrameLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this).setTitle("장소 검색").setView(wrapper)
            .setPositiveButton("검색", null).setNegativeButton("취소", null).create()
        searchDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isSoundEffectsEnabled = false
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                clickSound.play()
                searchDraft = input.text.toString()
                if (searchDraft.isBlank()) { input.error = "검색어를 입력해 주세요."; return@setOnClickListener }
                val result = navigation.searchPlace(searchDraft)
                showNavigationRequest(result, "내비에 검색 화면을 요청했어요.")
                if (result == NavigationLauncher.RequestResult.SUBMITTED) dialog.dismiss()
                else input.error = navigationStatus
            }
        }
        dialog.setOnDismissListener {
            searchDraft = input.text.toString()
            if (searchDialog === dialog) { searchDialog = null; searchInput = null }
        }
        dialog.show()
    }

    private fun readNavigation() {
        navigationHandler.removeCallbacks(navigationReadTimeout)
        destinationReceived = false; routeReceived = false
        readStartedAt = SystemClock.elapsedRealtime()
        destinationStatus = "목적지 정보를 기다리고 있어요."
        remainingStatus = "남은 시간 —  ·  남은 거리 —"
        routeStatus = "경로 상태를 기다리고 있어요."
        val result = navigation.readNavigationContext()
        if (result == NavigationLauncher.RequestResult.SUBMITTED) {
            navigationHandler.postDelayed(navigationReadTimeout, 10_000)
        } else {
            readStartedAt = null
            destinationStatus = "목적지 정보를 받지 못했어요."
            routeStatus = ""
        }
        showNavigationRequest(result, "현재 경로를 요청했어요.")
    }

    private fun onNavigationEvent(event: NavigationLauncher.Event) {
        if (destroyed) return
        when (event) {
            is NavigationLauncher.Event.ConnectionChanged -> navigationStatus = when (event.connection) {
                NavigationLauncher.Connection.NEW, NavigationLauncher.Connection.CONNECTING -> "내비에 연결하고 있어요."
                NavigationLauncher.Connection.CONNECTED -> "내비 서비스에 연결됐어요."
                NavigationLauncher.Connection.UNAVAILABLE -> "이 기기에서 내비 서비스를 찾지 못했어요."
                NavigationLauncher.Connection.FAILED -> "내비에 연결하지 못했어요."
                NavigationLauncher.Connection.CLOSED -> "내비 연결을 종료했어요."
            }
            is NavigationLauncher.Event.DestinationReceived -> {
                val started = readStartedAt ?: return
                if (event.receivedAtElapsedMs < started) return
                destinationReceived = true
                destinationStatus = event.value.name.trim().takeIf { it.isNotEmpty() }
                    ?.let { "내비 목적지 · $it" } ?: "내비에서 목적지 이름을 보내지 않았어요."
                val seconds = event.value.remainTime.toLong()
                val minutes = (seconds + 59) / 60
                val time = when {
                    seconds < 0 -> "—"
                    seconds in 1..59 -> "1분 미만"
                    minutes >= 60 -> "${minutes / 60}시간 ${minutes % 60}분"
                    else -> "${minutes}분"
                }
                val distance = if (event.value.remainDistance < 0) "—"
                    else String.format(java.util.Locale.KOREA, "%.1f km", event.value.remainDistance / 1000.0)
                remainingStatus = "남은 시간 $time  ·  남은 거리 $distance"
            }
            is NavigationLauncher.Event.RouteStateReceived -> {
                val started = readStartedAt ?: return
                if (event.receivedAtElapsedMs < started) return
                routeReceived = true
                routeStatus = if (event.value.isRouting) "길 안내 중" else "안내 상태 확인 필요"
            }
            is NavigationLauncher.Event.NavigationError -> {
                navigationHandler.removeCallbacks(navigationReadTimeout)
                readStartedAt = null
                if (!destinationReceived) destinationStatus = "목적지 정보를 받지 못했어요."
                if (!routeReceived) routeStatus = ""
                navigationStatus = "내비에서 요청 처리 오류를 알려왔어요."
            }
            is NavigationLauncher.Event.LocationReceived, NavigationLauncher.Event.RoutingCompleted -> return
        }
        if (readStartedAt != null && destinationReceived && routeReceived) {
            navigationHandler.removeCallbacks(navigationReadTimeout)
            readStartedAt = null
            navigationStatus = "내비에서 현재 경로 정보를 받았어요."
        }
        renderNavigationStatus()
        if (event is NavigationLauncher.Event.ConnectionChanged && event.connection == NavigationLauncher.Connection.CONNECTED && finished) readNavigation()
    }

    private fun musicCard(parent: LinearLayout) {
        val panel = card(parent, "분위기에 어울리는 음악")
        val trackRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        panel.addView(trackRow)
        albumView = ImageView(this).apply {
            contentDescription = "음악 파일의 앨범 이미지"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.TRANSPARENT)
            clipToOutline = false
            isSoundEffectsEnabled = false
            setOnClickListener {
                music.state.art?.let { art ->
                    clickSound.play()
                    val image = ImageView(this@PreviewActivity).apply {
                        setImageBitmap(art); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    AlertDialog.Builder(this@PreviewActivity).setTitle(music.state.title).setView(image)
                        .setPositiveButton("닫기", null).show()
                }
            }
        }.also { trackRow.addView(it, LinearLayout.LayoutParams(dp(144), dp(81))) }
        val trackLabels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
        }
        trackRow.addView(trackLabels, LinearLayout.LayoutParams(0, -2, 1f))
        trackTitle = text("Nightcall", 20f, true).also {
            trackLabels.addView(it)
        }
        trackArtist = text("Kavinsky", 14f, color = muted).also { trackLabels.addView(it) }
        trackSeek = SeekBar(this).apply {
            max = 1000; contentDescription = "음악 재생 위치"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar?) { seeking = true }
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    music.seekTo((bar?.progress ?: 0) * music.state.durationMs / 1000)
                    seeking = false
                }
            })
        }.also { panel.addView(it, LinearLayout.LayoutParams(-1, dp(40))) }
        trackTime = text("0:00 / 0:00", 12f, color = muted).also { panel.addView(it) }
        playbackButton = action("재생", false) {
            if (music.state.stopped || (!music.state.prepared && music.state.error != null)) music.prepare()
            else if (music.state.playing) music.pause() else music.play()
        }.also { trackRow.addView(it, LinearLayout.LayoutParams(dp(120), dp(56)).apply { leftMargin = dp(8) }) }
        renderMusic()
    }

    private fun renderMusic() {
        if (!::music.isInitialized || destroyed) return
        val state = music.state
        albumView?.setImageBitmap(state.art)
        trackTitle?.text = state.title
        trackArtist?.text = state.error ?: state.artist
        playbackButton?.apply {
            text = when { state.stopped || state.error != null -> "재시도"; state.playing -> "일시정지"; state.prepared -> "재생"; else -> "불러오는 중" }
            isEnabled = state.prepared || state.error != null || state.stopped
        }
        trackSeek?.isEnabled = state.prepared
        if (!seeking) trackSeek?.progress = if (state.durationMs > 0) (state.positionMs * 1000 / state.durationMs).toInt() else 0
        fun time(value: Long) = "%d:%02d".format(value / 60_000, value / 1000 % 60)
        trackTime?.text = "${time(state.positionMs)} / ${time(state.durationMs)}"
        renderMusicLighting()
    }
    private fun lightingCard(parent: LinearLayout) {
        val card = card(parent, "추천 조명 세팅")
        val strip = View(this).apply { background = lightGradient() }
        lightStrip = strip
        card.addView(strip, LinearLayout.LayoutParams(-1, dp(24)))
        val presets = LinearLayout(this)
        val names = listOf("선셋", "오션", "라일락")
        val buttons = mutableListOf<Button>()
        names.forEachIndexed { index, name ->
            val button = action(name, false) {
                palette = index; strip.background = lightGradient()
                buttons.forEachIndexed { i, b -> b.isSelected = i == palette; b.setTextColor(if (i == palette) ink else muted) }
            }
            button.isSelected = index == palette
            button.setTextColor(if (index == palette) ink else muted)
            buttons += button
            presets.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (index > 0) leftMargin = dp(6) })
        }
        card.addView(presets, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val level = text(getString(R.string.preview_brightness, brightness), 12f, color = muted)
        card.addView(level)
        card.addView(SeekBar(this).apply {
            max = 100; progress = brightness; contentDescription = "추천 조명 밝기"
            thumbTintList = null
            thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize(dp(32), dp(32))
                setColor(Color.rgb(0, 132, 145))
                setStroke(dp(3), Color.WHITE)
            }
            // Keep the large thumb inside the view at both ends, with a full-height drag target.
            setPadding(dp(20), 0, dp(20), 0)
            strip.alpha = brightness / 100f
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    brightness = progress; level.text = getString(R.string.preview_brightness, progress); strip.alpha = progress / 100f
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(56)))
        musicLightingButton = action("음악과 연동", false) {
            musicLighting = !musicLighting
            renderMusicLighting()
        }.also { card.addView(it, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) }) }
        renderMusicLighting()
    }

    private fun renderMusicLighting() {
        musicLightingButton?.text = if (musicLighting) "음악 연동 켜짐" else "음악과 연동"
        val strip = lightStrip ?: return
        // Preview follows the real playback clock; this does not claim beat analysis or vehicle control.
        val level = if (musicLighting && ::music.isInitialized && music.state.playing)
            .78f + .22f * kotlin.math.sin(music.state.positionMs / 2400.0).toFloat() else 1f
        strip.animate().alpha(brightness / 100f * level).setDuration(500).start()
    }

    private fun lightGradient() = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, when (palette) {
        1 -> intArrayOf(0xffabd6e4.toInt(), 0xff778dc7.toInt())
        2 -> intArrayOf(0xffd6b8da.toInt(), 0xffa6afe1.toInt())
        else -> intArrayOf(0xffffb77c.toInt(), 0xffe9a4af.toInt())
    }).apply { cornerRadius = dp(12).toFloat() }

    private fun card(parent: LinearLayout, title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val horizontal = dp(if (wideLayout) 20 else 16)
        val vertical = dp(if (wideLayout) 17 else 12)
        setPadding(horizontal, vertical, horizontal, vertical); background = rounded(Color.WHITE, 20)
        addView(text(title, 13f, color = muted).apply { setPadding(0, 0, 0, dp(if (wideLayout) 12 else 8)) })
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun logo(size: Int) = ImageView(this).apply {
        val artwork = requireNotNull(getDrawable(R.drawable.driving_mood_icon_art)) as BitmapDrawable
        setImageDrawable(PreviewLogoDrawable(artwork.bitmap))
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(paper) }
        // One antialiased mask matches the phone UI, without a second outline cutting its edge.
        clipToOutline = false
        minimumWidth = dp(size); minimumHeight = dp(size); contentDescription = "Driving Mood"
    }
    private fun addScrollableCenter(center: LinearLayout) {
        body.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(center, FrameLayout.LayoutParams(-1, -2))
        }, FrameLayout.LayoutParams(-1, -1))
    }
    private fun text(value: String, size: Float, bold: Boolean = false, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size * uiScale; setTextColor(color); typeface = Typeface.create(font, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    private fun action(label: String, primary: Boolean = true, click: () -> Unit) = Button(this).apply {
        text = label; textSize = 14f * uiScale; typeface = font; isAllCaps = false; minHeight = dp(48)
        minWidth = 0; minimumWidth = 0
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setTextColor(if (primary) Color.WHITE else ink)
        background = rounded(if (primary) ink else 0xffeef2f5.toInt(), 14)
        isSoundEffectsEnabled = false
        setOnClickListener { clickSound.play(); click() }
    }
    private fun rounded(color: Int, radius: Int = 26) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density * layoutScale).toInt()
    override fun onPause() { music.setForeground(false); clickSound.setForeground(false); animator?.pause(); super.onPause() }
    override fun onResume() { super.onResume(); music.setForeground(true); clickSound.setForeground(true); animator?.resume() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("finished", finished)
        outState.putInt("palette", palette); outState.putInt("brightness", brightness)
        outState.putBoolean("musicLighting", musicLighting)
        outState.putString("navigationSearchDraft", searchInput?.text?.toString() ?: searchDraft)
        outState.putBoolean("navigationSearchOpen", searchDialog?.isShowing == true)
        // Destination/route replies are intentionally not restored as if freshly observed.
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        destroyed = true
        clickSound.close()
        music.close()
        navigationHandler.removeCallbacksAndMessages(null)
        searchDialog?.dismiss(); searchDialog = null; searchInput = null
        if (::navigation.isInitialized) navigation.close()
        animator?.removeAllListeners(); animator?.cancel(); animator = null
        super.onDestroy()
    }
}

/** The same central framing and single circular mask as the phone's in-app logo. */
private class PreviewLogoDrawable(private val artwork: Bitmap) : Drawable() {
    private val shader = BitmapShader(artwork, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        this.shader = this@PreviewLogoDrawable.shader
    }
    private val matrix = Matrix()
    private var radius = 0f

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val diameter = minOf(bounds.width(), bounds.height()).toFloat()
        radius = (diameter / 2f - 1f).coerceAtLeast(0f)
        val scale = diameter / minOf(artwork.width, artwork.height) * 1.5f
        matrix.setScale(scale, scale)
        matrix.postTranslate(bounds.exactCenterX() - artwork.width * scale / 2f,
            bounds.exactCenterY() - artwork.height * scale / 2f)
        shader.setLocalMatrix(matrix)
    }
    override fun draw(canvas: Canvas) = canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), radius, paint)
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = minOf(artwork.width, artwork.height)
    override fun getIntrinsicHeight() = minOf(artwork.width, artwork.height)
}

private class LogoProgress(context: Context) : View(context) {
    var fraction = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = 10f * resources.displayMetrics.density
        oval.set(inset, inset, w - inset, h - inset)
    }
    override fun onDraw(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.color = 0x22007f88; canvas.drawOval(oval, paint)
        paint.color = 0x80007e89.toInt(); canvas.drawArc(oval, -90f, fraction * 360, false, paint)
        val angle = Math.toRadians((fraction * 360 - 90).toDouble())
        val x = oval.centerX() + kotlin.math.cos(angle).toFloat() * oval.width() / 2f
        val y = oval.centerY() + kotlin.math.sin(angle).toFloat() * oval.height() / 2f
        val pulse = .5f + .5f * kotlin.math.sin(fraction * Math.PI * 6).toFloat()
        val density = resources.displayMetrics.density
        paint.style = Paint.Style.FILL; paint.color = 0x183aabb1
        canvas.drawCircle(x, y, (6f + 3f * pulse) * density, paint)
        paint.color = 0x993aabb1.toInt()
        canvas.drawCircle(x, y, (2.3f + .5f * pulse) * density, paint)
    }
}

private class MoodWash(context: Context, ambient: Boolean = false) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var motion: ValueAnimator? = null
    private val matrix = Matrix()
    private var base: Shader? = null
    private val palette = if (ambient) intArrayOf(0xff91c2e9.toInt(), 0xffefb38f.toInt(), 0xffb69cde.toInt())
        else intArrayOf(0xff9bb8d9.toInt(), 0xffb9b7d8.toInt(), 0xffd5e4ed.toInt())
    private val fields = palette.map { color ->
        RadialGradient(0f, 0f, 1f, intArrayOf((color and 0xffffff) or 0xc0000000.toInt(), color and 0xffffff),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
    }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false; isFocusable = false }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) base = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            palette.map { blendWithPaper(it) }.toIntArray(), null, Shader.TileMode.CLAMP)
    }
    override fun onDraw(canvas: Canvas) {
        paint.shader = base
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val radius = maxOf(width, height) * .62f
        fields.forEachIndexed { index, field ->
            val angle = phase + index * (Math.PI * 2 / 3).toFloat()
            val x = width * (.5f + .48f * kotlin.math.sin(angle))
            val y = height * (.5f + .38f * kotlin.math.cos(angle))
            matrix.setScale(radius, radius); matrix.postTranslate(x, y)
            field.setLocalMatrix(matrix); paint.shader = field
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        paint.shader = null
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); startMotion() }
    private fun startMotion() {
        if (motion != null || !isAttachedToWindow || !isShown || !ValueAnimator.areAnimatorsEnabled() || windowVisibility != VISIBLE) return
        val cycle = (Math.PI * 2).toFloat()
        motion = ValueAnimator.ofFloat(phase, phase + cycle).apply {
            duration = 10000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { phase = (it.animatedValue as Float) % cycle; invalidate() }; start()
        }
    }
    private fun stopMotion() { motion?.cancel(); motion = null }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) startMotion() else stopMotion()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startMotion() else stopMotion()
    }
    override fun onDetachedFromWindow() { stopMotion(); super.onDetachedFromWindow() }
    private fun blendWithPaper(color: Int) = Color.rgb((Color.red(color) + 246) / 2,
        (Color.green(color) + 247) / 2, (Color.blue(color) + 249) / 2)
}
