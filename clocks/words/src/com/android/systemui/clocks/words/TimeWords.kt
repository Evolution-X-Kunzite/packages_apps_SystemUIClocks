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

import java.util.Locale

/**
 * Spells the time out in words. English: "It\u2019s" / "Five" / "Sixteen". Portuguese (when the
 * system locale language is "pt"): "S\u00e3o" / "Cinco" / "E dezesseis".
 */
object TimeWords {
    const val PREFIX = "It\u2019s"

    // ---------- English ----------
    private val UNITS_EN =
        arrayOf(
            "Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
            "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
            "Seventeen", "Eighteen", "Nineteen",
        )
    private val TENS_EN = arrayOf("", "Ten", "Twenty", "Thirty", "Forty", "Fifty")

    // ---------- Portugu\u00eas ----------
    private val UNITS_PT =
        arrayOf(
            "Zero", "Um", "Dois", "Tr\u00eas", "Quatro", "Cinco", "Seis", "Sete", "Oito", "Nove",
            "Dez", "Onze", "Doze", "Treze", "Catorze", "Quinze", "Dezesseis",
            "Dezessete", "Dezoito", "Dezenove",
        )
    private val TENS_PT = arrayOf("", "Dez", "Vinte", "Trinta", "Quarenta", "Cinquenta")

    private fun isPt(locale: Locale) = locale.language == "pt"

    private fun numberEn(n: Int): String =
        when {
            n < 20 -> UNITS_EN[n]
            n % 10 == 0 -> TENS_EN[n / 10]
            else -> TENS_EN[n / 10] + " " + UNITS_EN[n % 10]
        }

    private fun numberPt(n: Int): String =
        when {
            n < 20 -> UNITS_PT[n]
            n % 10 == 0 -> TENS_PT[n / 10]
            else -> TENS_PT[n / 10] + " e " + UNITS_PT[n % 10].lowercase(Locale.ROOT)
        }

    /** Hours agree in gender in Portuguese: "uma", "duas", "vinte e uma", "vinte e duas". */
    private fun hourPt(h: Int): String =
        when (h) {
            1 -> "Uma"
            2 -> "Duas"
            21 -> "Vinte e uma"
            22 -> "Vinte e duas"
            else -> numberPt(h)
        }

    fun number(n: Int, locale: Locale = Locale.getDefault()): String =
        if (isPt(locale)) numberPt(n) else numberEn(n)

    /** First line: "It\u2019s" in English, "\u00c9" (one o'clock) or "S\u00e3o" in Portuguese. */
    fun prefix(hourOfDay: Int, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String {
        if (!isPt(locale)) return PREFIX
        val h = if (is24Hour) hourOfDay else hourOfDay % 12
        return if (h == 1) "\u00c9" else "S\u00e3o"
    }

    fun hour(hourOfDay: Int, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String {
        val h =
            if (is24Hour) hourOfDay
            else (hourOfDay % 12).let { if (it == 0) 12 else it }
        return if (isPt(locale)) hourPt(h) else numberEn(h)
    }

    fun minute(minute: Int, locale: Locale = Locale.getDefault()): String =
        if (isPt(locale)) {
            when (minute) {
                0 -> "Em ponto"
                else -> "E " + numberPt(minute).lowercase(Locale.ROOT)
            }
        } else {
            when {
                minute == 0 -> "O\u2019Clock"
                minute < 10 -> "Oh " + UNITS_EN[minute]
                else -> numberEn(minute)
            }
        }

    /**
     * Minutes split into up to two display lines, so the small face can use 3 lines in total.
     * Portuguese compound numbers break at the inner " e ": "E trinta e dois" becomes
     * ["E trinta", "e dois"]. Simple numbers ("E vinte", "E quinze"), "Em ponto" and English stay
     * on one line.
     */
    fun minuteLines(minute: Int, locale: Locale = Locale.getDefault()): List<String> {
        val full = minute(minute, locale)
        if (!isPt(locale)) return listOf(full)
        // Skip the leading "E " so only the inner connector is considered.
        val i = full.indexOf(" e ", startIndex = 2)
        return if (i > 0) listOf(full.substring(0, i), full.substring(i + 1)) else listOf(full)
    }
}
