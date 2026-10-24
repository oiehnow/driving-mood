package kr.drivingmood.pleospreview

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/** Isolated, permission-free interaction preview. Every result is a fixed example fixture. */
class PreviewActivity : ComponentActivity() {
    private val ink = Color.rgb(0, 44, 95)
    private val muted = Color.rgb(102, 119, 137)
    private val paper = Color.rgb(246, 247, 249)
    private val uiScale: Float
        get() = when {
            resources.configuration.screenWidthDp >= 1200 -> 1.6f
            resources.configuration.screenWidthDp >= 760 -> 1.15f
            else -> 1f
        }
    private var finished = false
    private var playing = false
    private var palette = 0
    private var brightness = 65
    private var animator: ValueAnimator? = null
    private lateinit var root: LinearLayout
    private lateinit var body: FrameLayout
    private val font by lazy { resources.getFont(R.font.pretendard_variable) }
    private val previewBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = cancelPreview()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, previewBack)
        finished = savedInstanceState?.getBoolean("finished") ?: false
        playing = savedInstanceState?.getBoolean("playing") ?: false
        palette = savedInstanceState?.getInt("palette") ?: 0
        brightness = savedInstanceState?.getInt("brightness") ?: 65
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
            setPadding(dp(28), dp(18), dp(28), dp(18))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(28) + insets.systemWindowInsetLeft, dp(18) + insets.systemWindowInsetTop,
                dp(28) + insets.systemWindowInsetRight, dp(18) + insets.systemWindowInsetBottom)
            insets
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(logo(34), LinearLayout.LayoutParams(dp(34), dp(34)))
        header.addView(text("Driving Mood", 20f, true).apply { setPadding(dp(12), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text("UI 프로토타입", 12f, color = muted))
        root.addView(header)
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(20) })
        setContentView(root)
        if (finished) showResult() else showStart()
    }

    private fun showStart() {
        previewBack.isEnabled = false
        body.animate().cancel(); body.alpha = 1f
        body.removeAllViews()
        val center = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val mark = logo(144).apply {
            isClickable = true; isFocusable = true
            elevation = dp(8).toFloat()
            foreground = RippleDrawable(ColorStateList.valueOf(0x24002c5f), null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            contentDescription = "무드 분석 화면 미리보기 시작"
            setOnClickListener { beginPreview() }
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
                title.text = if (fraction < .55f) "주변을 담고 있어요" else "무드를 찾고 있어요"
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
        val wide = resources.configuration.screenWidthDp >= 760
        // The paired cards determine landscape height instead of stretching the hero into empty space.
        val scroll = ScrollView(this).apply { isFillViewport = !wide }
        val panels = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        scroll.addView(panels)
        body.addView(scroll)
        val hero = FrameLayout(this).apply { minimumHeight = dp(370); clipToOutline = true; background = rounded(Color.WHITE) }
        hero.addView(MoodWash(this), FrameLayout.LayoutParams(-1, -1))
        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(28), dp(30), dp(28), dp(24))
        }
        summary.addView(text("지금 주변의 무드 · 예시", 14f, color = muted).apply { gravity = Gravity.CENTER })
        summary.addView(text("따뜻한", 48f, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(8)) })
        summary.addView(text("차분한  ·  여유로운", 19f).apply { gravity = Gravity.CENTER })
        summary.addView(text("노을이 남긴 온기", 15f, color = muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(28)) })
        val scores = LinearLayout(this).apply { gravity = Gravity.CENTER; background = rounded(0xdfffffff.toInt(), 18) }
        listOf("색감" to "76", "활기" to "42", "여유" to "81").forEach { (label, score) ->
            scores.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(8), dp(16), dp(8), dp(16))
                addView(text(label, 13f, color = muted).apply { gravity = Gravity.CENTER })
                addView(text(score, 27f, true).apply { gravity = Gravity.CENTER })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        summary.addView(scores, LinearLayout.LayoutParams(-1, -2))
        summary.addView(action("다시 살펴보기") { finished = false; beginPreview() },
            LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(20) })
        hero.addView(summary, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        panels.addView(hero, if (wide) LinearLayout.LayoutParams(0, -1, 1.15f).apply { rightMargin = dp(20) }
            else LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panels.addView(side, if (wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        musicCard(side)
        lightingCard(side)
        val context = card(side, "주행 맥락")
        context.addView(text("강변도로  ·  해 질 무렵", 17f, true))
        context.addView(text("예시 맥락 · 맑음, 여유로운 흐름", 12f, color = muted).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun musicCard(parent: LinearLayout) {
        val card = card(parent, "분위기에 어울리는 음악")
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val album = TextView(this).apply {
            text = "♪"; textSize = 30f * uiScale; gravity = Gravity.CENTER; setTextColor(ink)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xffffdcc7.toInt(), 0xffd8e5ef.toInt())).apply { cornerRadius = dp(14).toFloat() }
        }
        row.addView(album, LinearLayout.LayoutParams(dp(60), dp(60)))
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
            addView(text("노을 드라이브", 18f, true)); addView(text("Soft pop · 예시 플레이리스트", 12f, color = muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)
        val playback = action(if (playing) "일시정지 화면" else "재생 화면", false) { }
        playback.setOnClickListener { playing = !playing; playback.text = if (playing) "일시정지 화면" else "재생 화면" }
        card.addView(playback, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
    }

    private fun lightingCard(parent: LinearLayout) {
        val card = card(parent, "조명 미리보기")
        val strip = View(this).apply { background = lightGradient() }
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
            max = 100; progress = brightness; contentDescription = "조명 미리보기 밝기"
            strip.alpha = brightness / 100f
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    brightness = progress; level.text = getString(R.string.preview_brightness, progress); strip.alpha = progress / 100f
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(40)))
    }

    private fun lightGradient() = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, when (palette) {
        1 -> intArrayOf(0xffabd6e4.toInt(), 0xff778dc7.toInt())
        2 -> intArrayOf(0xffd6b8da.toInt(), 0xffa6afe1.toInt())
        else -> intArrayOf(0xffffb77c.toInt(), 0xffe9a4af.toInt())
    }).apply { cornerRadius = dp(12).toFloat() }

    private fun card(parent: LinearLayout, title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(17), dp(20), dp(17)); background = rounded(Color.WHITE, 20)
        addView(text(title, 13f, color = muted).apply { setPadding(0, 0, 0, dp(12)) })
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun logo(size: Int) = object : ImageView(this) {
        private val crop = Matrix()
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val art = drawable ?: return
            if (w <= 0 || h <= 0 || art.intrinsicWidth <= 0 || art.intrinsicHeight <= 0) return
            // Adaptive launcher artwork exposes its central 2/3; match the phone's composition.
            val scale = maxOf(w.toFloat() / art.intrinsicWidth, h.toFloat() / art.intrinsicHeight) * 1.5f
            crop.setScale(scale, scale)
            crop.postTranslate((w - art.intrinsicWidth * scale) / 2f, (h - art.intrinsicHeight * scale) / 2f)
            imageMatrix = crop
        }
    }.apply {
        setImageResource(R.drawable.driving_mood_icon_art); scaleType = ImageView.ScaleType.MATRIX
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val diameter = minOf(view.width, view.height)
                val left = (view.width - diameter) / 2
                val top = (view.height - diameter) / 2
                outline.setOval(left, top, left + diameter, top + diameter)
            }
        }
        clipToOutline = true
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
        setTextColor(if (primary) Color.WHITE else ink)
        background = rounded(if (primary) ink else 0xffeef2f5.toInt(), 14)
        setOnClickListener { click() }
    }
    private fun rounded(color: Int, radius: Int = 26) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density * uiScale).toInt()
    override fun onPause() { animator?.pause(); super.onPause() }
    override fun onResume() { super.onResume(); animator?.resume() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("finished", finished); outState.putBoolean("playing", playing)
        outState.putInt("palette", palette); outState.putInt("brightness", brightness)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { animator?.removeAllListeners(); animator?.cancel(); animator = null; super.onDestroy() }
}

private class LogoProgress(context: Context) : View(context) {
    var fraction = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = paint.strokeWidth
        oval.set(inset, inset, w - inset, h - inset)
    }
    override fun onDraw(canvas: Canvas) {
        paint.color = 0x22007f88; canvas.drawOval(oval, paint)
        paint.color = 0xff007f88.toInt(); canvas.drawArc(oval, -90f, fraction * 360, false, paint)
    }
}

private class MoodWash(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var motion: ValueAnimator? = null
    private val matrix = Matrix()
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xfff4d7c2.toInt(), 0xffefdfd7.toInt(), 0xffd9e8e9.toInt()), null, Shader.TileMode.MIRROR)
    }
    override fun onDraw(canvas: Canvas) {
        val offset = kotlin.math.sin(phase.toDouble()).toFloat() * width * .2f
        matrix.setTranslate(offset, 0f)
        paint.shader?.setLocalMatrix(matrix)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); startMotion() }
    private fun startMotion() {
        if (motion != null || !ValueAnimator.areAnimatorsEnabled() || windowVisibility != VISIBLE) return
        motion = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 14000; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }; start()
        }
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startMotion() else { motion?.cancel(); motion = null }
    }
    override fun onDetachedFromWindow() { motion?.cancel(); motion = null; super.onDetachedFromWindow() }
}
