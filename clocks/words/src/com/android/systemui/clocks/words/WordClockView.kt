/*
 * Copyright (C) 2026 FundamentalOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package com.android.systemui.clocks.words

import android.content.Context
import android.graphics.Canvas
import android.graphics.Point
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.android.systemui.animation.TextAnimator
import com.android.systemui.clocks.AssetLoader
import com.android.systemui.clocks.DigitalClockFaceView
import com.android.systemui.clocks.HorizontalAlignment
import com.android.systemui.clocks.SimpleDigitalClockTextView
import com.android.systemui.clocks.VerticalAlignment
import com.android.systemui.clocks.saveBlock
import com.android.systemui.customization.clocks.R as clocksR
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * The time in words, drawn as a left-aligned stack of lines with a fixed baseline pitch. The design
 * supplies digital-hand layers for the hour (DIGIT_PAIR "hh") and the minute (DIGIT_PAIR "mm"),
 * optionally preceded by a prefix line (TIME_FULL_FORMAT); their text is replaced with words here.
 * The view sizes itself to the stack plus a small gap that keeps the date row below at a distance.
 */
abstract class WordClockViewBase(context: Context, messageBuffer: MessageBuffer) :
    DigitalClockFaceView(context, messageBuffer) {
    override var digitalClockTextViewMap = mutableMapOf<Int, SimpleDigitalClockTextView>()
    override val positionedByLayout = true
    // Words are read at their real size in AOD; the keyguard translates the block with the date
    // row instead of shrinking it (burn-in protection stays on for everything else).
    override val useAlternateSmartspaceAODTransition = true

    // [3-line] Needed to build the extra line and to style it like the design's lines.
    private val tailBuffer: MessageBuffer = messageBuffer
    protected abstract val tailAssets: AssetLoader

    // [3-line] When true, compound Portuguese minutes ("E trinta e dois") are split over two lines
    // ("E trinta" / "e dois"), so the stack is narrower and the smartspace fits beside it.
    protected open val splitMinutes: Boolean = false

    var prefixView: SimpleDigitalClockTextView? = null
        private set
    lateinit var hourView: SimpleDigitalClockTextView
    lateinit var minuteView: SimpleDigitalClockTextView
    private var lastWords: List<String> = emptyList()

    // [3-line] Extra line holding the second half of a split minute. Not supplied by the design:
    // created once, on demand, and kept attached (it is registered in digitalClockTextViewMap by
    // the base class, so theme / doze / font changes reach it). It only takes part in drawing and
    // measuring while tailText != null.
    private var minuteTailView: SimpleDigitalClockTextView? = null
    private var tailText: String? = null
    private var tailSyncedSize = -1f
    private var tailDozeApplied = false

    val lines: List<SimpleDigitalClockTextView>
        get() = listOfNotNull(prefixView, hourView, minuteView, minuteTailView.takeIf { tailText != null })

    val isReady: Boolean
        get() = this::hourView.isInitialized && this::minuteView.isInitialized

    protected val density: Float
        get() = context.resources.displayMetrics.density

    /** Gap kept below the last line (the date row hangs off this view's bottom). */
    val bottomGap: Int
        get() = (DATE_GAP_DP * density).roundToInt()

    init {
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    override fun onViewAdded(child: View?) {
        if (child == null) return
        super.onViewAdded(child)
        if (child !is SimpleDigitalClockTextView) return
        child.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        child.setSingleLine()
        child.horizontalAlignment = HorizontalAlignment.LEFT
        child.verticalAlignment = VerticalAlignment.TOP
        child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        when (child.id) {
            ClockViewIds.TIME_FULL_FORMAT -> prefixView = child
            ClockViewIds.HOUR_DIGIT_PAIR -> hourView = child
            ClockViewIds.MINUTE_DIGIT_PAIR -> minuteView = child
        }
    }

    /** Width a line view reports for itself (mirrors SimpleDigitalClockTextView.onMeasure). */
    fun lineWidth(view: SimpleDigitalClockTextView): Int =
        view.textBounds.width() + 2 * view.lockScreenPaint.strokeWidth.toInt()

    /** Widest a line may be: the screen minus the keyguard's clock padding on both sides. */
    private fun maxLineWidth(): Int =
        context.resources.displayMetrics.widthPixels -
            2 * context.resources.getDimensionPixelSize(clocksR.dimen.clock_padding_start)

    /** Scale (<= 1) that makes a line fit the screen; long Portuguese minutes get shrunk. */
    fun lineScale(view: SimpleDigitalClockTextView): Float {
        val width = lineWidth(view)
        val max = maxLineWidth()
        return if (width > max && width > 0) max.toFloat() / width else 1f
    }

    /** Distance between consecutive baselines, relative to the hour line's font size. */
    // [3-line] Tighter pitch when the minutes use two lines so the taller stack does not push the
    // media card down as much.
    fun linePitch(): Int {
        val pitch = if (tailText != null) LINE_PITCH_SPLIT else LINE_PITCH
        return (hourView.lockScreenPaint.textSize * pitch).roundToInt()
    }

    /**
     * Baseline of each line: the font's ascent for the first, then [linePitch] per line. Taken from
     * the font metrics rather than from the glyphs on screen, so the stack (and the date row the
     * keyguard hangs below it) stays put when the words change, whatever their ascenders and
     * descenders.
     */
    fun lineBaselines(): IntArray {
        val first = lines.maxOf { -it.lockScreenPaint.fontMetricsInt.ascent + it.lockScreenPaint.strokeWidth.toInt() }
        val pitch = linePitch()
        return IntArray(lines.size) { i -> first + i * pitch }
    }

    /**
     * Top offset of each line view. A TOP-aligned line draws its glyph top at (view top + stroke
     * width), i.e. its baseline sits at (top + stroke - textBounds.top).
     */
    fun lineTops(): IntArray {
        val baselines = lineBaselines()
        return IntArray(lines.size) { i ->
            val line = lines[i]
            baselines[i] + line.textBounds.top - line.lockScreenPaint.strokeWidth.toInt()
        }
    }

    /** Last baseline plus the font's descent: constant for a given font size. */
    fun stackHeight(): Int =
        lineBaselines().last() + lines.maxOf { it.lockScreenPaint.fontMetricsInt.descent + it.lockScreenPaint.strokeWidth.toInt() }

    override fun calculateSize(widthMeasureSpec: Int, heightMeasureSpec: Int): Point? {
        if (!isReady) return null
        // [3-line] Font size may have changed since the tail was styled.
        if (tailText != null) syncTailStyle()
        // Measure the lines first (the base class only measures children when it lays out itself).
        for (line in lines) line.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        syncTailDoze() // [3-line] needs the animators, which exist only after the first measure
        return Point(lines.maxOf { (lineWidth(it) * lineScale(it)).roundToInt() }, stackHeight() + bottomGap)
    }

    override fun refreshTime() {
        super.refreshTime()
        if (!isReady) return
        val now = Calendar.getInstance()
        val is24Hour = DateFormat.is24HourFormat(context)
        // Words follow the system language (Portuguese when it is "pt", English otherwise).
        val locale = context.resources.configuration.locales[0]
        val hourOfDay = now.get(Calendar.HOUR_OF_DAY)
        // [3-line] Minutes may come as 2 parts; the 2nd one goes to the extra tail line. If the
        // tail line cannot be set up (styles not ready), the minutes stay on a single line.
        val parts =
            TimeWords.minuteLines(now.get(Calendar.MINUTE), locale).let {
                if (splitMinutes) it else listOf(it.joinToString(" "))
            }
        val minuteParts = if (updateTail(parts.getOrNull(1))) parts else listOf(parts.joinToString(" "))
        val words =
            listOfNotNull(
                prefixView?.let { TimeWords.prefix(hourOfDay, is24Hour, locale) },
                TimeWords.hour(hourOfDay, is24Hour, locale),
                minuteParts[0],
                minuteParts.getOrNull(1),
            )
        // The digital-hand layers have just written their digits ("02", "46") into these views on
        // this same tick; always put the words back, but only relayout when the words changed.
        lines.zip(words).forEach { (view, text) -> setLine(view, text) }
        if (words == lastWords) return
        lastWords = words
        contentDescription = words.joinToString(" ")
        requestLayout()
        invalidate()
    }

    // [3-line] Whether the design's minute layer already has its styles (applyStyles ran).
    private fun minuteStyled(): Boolean =
        runCatching { minuteView.textStyle.let { } ; minuteView.aodStyle.let { } }.isSuccess

    /**
     * [3-line] Turns the tail line on (text != null) or off. Returns false when it was asked to be
     * on but could not be set up; the caller then keeps the minutes on one line.
     */
    private fun updateTail(text: String?): Boolean {
        if (text == null) {
            tailText = null
            return true
        }
        if (minuteTailView == null) {
            if (!minuteStyled()) {
                tailText = null
                return false
            }
            minuteTailView =
                SimpleDigitalClockTextView(context, tailBuffer).also {
                    it.id = View.generateViewId()
                    addView(it) // onViewAdded: same layout params / alignment, and registers it in the map
                }
        }
        if (!syncTailStyle()) {
            tailText = null
            return false
        }
        tailText = text
        return true
    }

    /**
     * The design only styles its own layers, so style the tail the same way they were styled:
     * same text/AOD style objects, same font size. Returns false if that failed.
     */
    private fun syncTailStyle(): Boolean {
        val tail = minuteTailView ?: return false
        val size = minuteView.lockScreenPaint.textSize
        if (size == tailSyncedSize) return true
        return runCatching {
            if (tailSyncedSize < 0f) {
                tail.applyStyles(tailAssets, minuteView.textStyle, minuteView.aodStyle)
                // updateColors() needs a ThemeConfig we don't have here; copy the resolved color.
                // The next theme change goes through the base class and sets everything properly.
                tail.lockScreenPaint.color = minuteView.lockScreenPaint.color
            }
            // applyTextSize multiplies by fontSizeScale; minuteView's paint already includes it.
            tail.applyTextSize(size / (minuteView.textStyle.fontSizeScale ?: 1f))
            tailSyncedSize = size
        }.isSuccess
    }

    /** Puts the tail in the same (doze / awake) state as the minute line, once its animators exist. */
    private fun syncTailDoze() {
        val tail = minuteTailView ?: return
        if (tailDozeApplied || tail.animators == null) return
        tail.dozeFraction = minuteView.dozeFraction
        if (minuteView.dozeFraction < 1f) {
            tail.animators?.textAnimator?.setTextStyle(TextAnimator.Style(color = tail.lockScreenPaint.color))
        }
        tail.animateDoze(minuteView.dozeFraction > 0f, false)
        tailDozeApplied = true
    }

    private fun setLine(view: SimpleDigitalClockTextView, text: String) {
        if (view.getText() == text) return
        view.setText(text)
        view.refreshText()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isReady) return
        val tops = lineTops()
        val baselines = lineBaselines()
        for (i in lines.indices) {
            canvas.saveBlock().use {
                canvas.translate(0f, tops[i].toFloat())
                val scale = lineScale(lines[i])
                if (scale < 1f) {
                    // Shrink around the line's start and baseline so it stays aligned with the others.
                    canvas.scale(scale, scale, 0f, (baselines[i] - tops[i]).toFloat())
                }
                lines[i].draw(canvas)
            }
        }
    }

    companion object {
        const val LINE_PITCH = 1.25f
        const val LINE_PITCH_SPLIT = 1.1f // [3-line]
        const val DATE_GAP_DP = 20f
    }
}

/**
 * Large face: "It\u2019s" / hour / minute, placed by [WordClockFaceLayoutLarge].
 *
 * Deliberately not a custom clock scene: the only thing that flag does on the keyguard is refresh
 * the blueprint (with an unanimated constraint pass) whenever the AOD notification icons appear or
 * disappear, which the weather clock needs for its icon barrier and which here only interferes
 * with the size transition that the same notification triggers.
 */
class WordClockViewLarge(context: Context, val assets: AssetLoader, messageBuffer: MessageBuffer) :
    WordClockViewBase(context, messageBuffer) {
    override val tailAssets: AssetLoader
        get() = assets
}

/** Small (collapsed) face: hour / minute at the top of the small clock slot, tinted by the design. */
class WordClockViewSmall(context: Context, val assets: AssetLoader, messageBuffer: MessageBuffer) :
    WordClockViewBase(context, messageBuffer) {
    override val tailAssets: AssetLoader
        get() = assets

    // [3-line] Compact mode: "Dezessete / E trinta / e dois" instead of 2 wide lines.
    override val splitMinutes = true

    /** The words keep the large face's size when notifications push the clock into the small slot. */
    override fun onFontSettingChanged(fontSizePx: Float) =
        super.onFontSettingChanged(context.resources.getDimensionPixelSize(clocksR.dimen.large_clock_text_size).toFloat())
}
