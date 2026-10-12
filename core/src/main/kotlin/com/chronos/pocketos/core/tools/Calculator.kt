package com.chronos.pocketos.core.tools

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Small recursive-descent calculator supporting + - * / % parentheses, unary minus and decimals.
 * Uses BigDecimal so 0.1 + 0.2 displays as 0.3.
 */
object Calculator {
    sealed interface Result {
        data class Value(val value: BigDecimal) : Result {
            fun display(): String {
                val stripped = value.round(MathContext(16, RoundingMode.HALF_UP)).stripTrailingZeros()
                return if (stripped.scale() < 0) stripped.setScale(0).toPlainString() else stripped.toPlainString()
            }
        }
        data class Error(val message: String) : Result
    }

    fun evaluate(expression: String): Result = try {
        val p = Parser(expression.replace('×', '*').replace('÷', '/').replace('−', '-'))
        val v = p.parseExpression()
        p.skipWs()
        if (!p.atEnd()) Result.Error("Unexpected '${p.peek()}'") else Result.Value(v)
    } catch (e: ArithmeticException) {
        Result.Error("Cannot divide by zero")
    } catch (e: IllegalArgumentException) {
        Result.Error(e.message ?: "Invalid expression")
    }

    private class Parser(val s: String) {
        var i = 0
        fun atEnd() = i >= s.length
        fun peek() = s[i]
        fun skipWs() { while (!atEnd() && s[i].isWhitespace()) i++ }

        fun parseExpression(): BigDecimal {
            var v = parseTerm()
            while (true) {
                skipWs()
                if (atEnd()) return v
                when (peek()) {
                    '+' -> { i++; v = v.add(parseTerm()) }
                    '-' -> { i++; v = v.subtract(parseTerm()) }
                    else -> return v
                }
            }
        }

        fun parseTerm(): BigDecimal {
            var v = parseFactor()
            while (true) {
                skipWs()
                if (atEnd()) return v
                when (peek()) {
                    '*' -> { i++; v = v.multiply(parseFactor()) }
                    '/' -> { i++; val d = parseFactor(); if (d.signum() == 0) throw ArithmeticException(); v = v.divide(d, MathContext.DECIMAL64) }
                    '%' -> { i++; val d = parseFactor(); if (d.signum() == 0) throw ArithmeticException(); v = v.remainder(d) }
                    else -> return v
                }
            }
        }

        fun parseFactor(): BigDecimal {
            skipWs()
            require(!atEnd()) { "Incomplete expression" }
            return when (peek()) {
                '-' -> { i++; parseFactor().negate() }
                '+' -> { i++; parseFactor() }
                '(' -> {
                    i++
                    val v = parseExpression()
                    skipWs()
                    require(!atEnd() && peek() == ')') { "Missing ')'" }
                    i++
                    v
                }
                else -> parseNumber()
            }
        }

        fun parseNumber(): BigDecimal {
            val start = i
            while (!atEnd() && (peek().isDigit() || peek() == '.')) i++
            require(i > start) { if (atEnd()) "Incomplete expression" else "Unexpected '${s[i]}'" }
            return s.substring(start, i).toBigDecimalOrNull() ?: throw IllegalArgumentException("Invalid number")
        }
    }
}
