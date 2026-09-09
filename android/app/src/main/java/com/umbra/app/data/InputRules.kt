package com.umbra.app.data

object InputRules {
    const val MAX_TEXT_LENGTH = 16_000
    fun username(raw: String): String = raw.trim().removePrefix("@")
    fun validUsername(raw: String): Boolean = username(raw).matches(Regex("[A-Za-z0-9_]{3,32}"))

    fun normalizePhone(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        var hasPlus = s[0] == '+'
        var digits = buildString {
            for ((i, c) in s.withIndex()) when {
                c in '0'..'9' -> append(c)
                i == 0 && c == '+' -> Unit
                c in " -().\u00a0" -> Unit
                else -> return null
            }
        }
        if (digits.startsWith("00")) { digits = digits.substring(2); hasPlus = true }
        if (!hasPlus) when {
            digits.length == 11 && digits[0] == '8' -> digits = "7" + digits.substring(1)
            digits.length == 10 -> digits = "7" + digits
        }
        if (digits.length !in 7..15 || digits[0] == '0') return null
        return "+$digits"
    }
}
