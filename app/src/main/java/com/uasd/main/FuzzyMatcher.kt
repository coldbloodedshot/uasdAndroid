package com.uasd.main

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

data class MatchResult(
    val index: Int,
    val nivelCoincidencia: Double,
    val nivelDiscriminacion: Double
)

object FuzzyMatcher {

    fun findBestMatch(query: String, list: List<String>, type: String): MatchResult? {
        if (list.isEmpty()) return null

        val results = list.mapIndexed { index, item ->
            val score = if (type == "id") {
                calculateLevenshteinSimilarity(query, item)
            } else {
                calculateNameSimilarity(query, item)
            }
            Pair(index, score)
        }.sortedByDescending { it.second }

        val best = results[0]
        val secondBestScore = if (results.size > 1) results[1].second else 0.0

        return MatchResult(
            index = best.first,
            nivelCoincidencia = best.second,
            nivelDiscriminacion = best.second - secondBestScore
        )
    }

    private fun calculateLevenshteinSimilarity(s1: String, s2: String): Double {
        val str1 = s1.lowercase()
        val str2 = s2.lowercase()

        val m = str1.length
        val n = str2.length
        
        if (m == 0 && n == 0) return 1.0
        if (m == 0 || n == 0) return 0.0

        val d = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) d[i][0] = i
        for (j in 0..n) d[0][j] = j

        for (j in 1..n) {
            for (i in 1..m) {
                if (str1[i - 1] == str2[j - 1]) {
                    d[i][j] = d[i - 1][j - 1]
                } else {
                    d[i][j] = min(
                        d[i - 1][j] + 1,
                        min(d[i][j - 1] + 1, d[i - 1][j - 1] + 1)
                    )
                }
            }
        }

        val distance = d[m][n]
        val maxLength = max(m, n)
        return if (maxLength == 0) 1.0 else 1.0 - (distance.toDouble() / maxLength.toDouble())
    }

    private fun calculateNameSimilarity(name1: String, name2: String): Double {
        val normalize: (String) -> List<String> = { s ->
            var str = s
            if (str.contains(",")) {
                val parts = str.split(",")
                if (parts.size >= 2) {
                    str = parts[1].trim() + " " + parts[0].trim()
                }
            }
            val normalized = Normalizer.normalize(str.lowercase(), Normalizer.Form.NFD)
            val withoutAccents = Regex("[\\p{InCombiningDiacriticalMarks}]").replace(normalized, "")
            withoutAccents.split(Regex("\\W+")).filter { it.isNotEmpty() }
        }

        val tokens1 = normalize(name1)
        val tokens2 = normalize(name2)

        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0

        var totalScore = 0.0

        tokens1.forEach { t1 ->
            var bestTokenScore = 0.0
            tokens2.forEach { t2 ->
                val s = calculateJaroWinkler(t1, t2)
                if (s > bestTokenScore) bestTokenScore = s
            }

            if (bestTokenScore > 0.85) {
                totalScore += bestTokenScore
            }
        }

        val precision = totalScore / tokens1.size
        val recall = totalScore / tokens2.size

        return if (precision + recall == 0.0) 0.0 else (2 * precision * recall) / (precision + recall)
    }

    private fun calculateJaroWinkler(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0

        val len1 = s1.length
        val len2 = s2.length
        val matchWindow = max(1, max(len1, len2) / 2 - 1)

        val matches1 = BooleanArray(len1)
        val matches2 = BooleanArray(len2)

        var matches = 0
        for (i in 0 until len1) {
            val start = max(0, i - matchWindow)
            val end = min(i + matchWindow + 1, len2)
            for (j in start until end) {
                if (matches2[j] || s1[i] != s2[j]) continue
                matches1[i] = true
                matches2[j] = true
                matches++
                break
            }
        }

        if (matches == 0) return 0.0

        var transpositions = 0
        var k = 0
        for (i in 0 until len1) {
            if (!matches1[i]) continue
            while (!matches2[k]) k++
            if (s1[i] != s2[k]) transpositions++
            k++
        }

        val jaro = (matches.toDouble() / len1 + matches.toDouble() / len2 + (matches.toDouble() - transpositions.toDouble() / 2) / matches) / 3.0

        var prefix = 0
        val maxPrefix = 4
        for (i in 0 until min(min(len1, len2), maxPrefix)) {
            if (s1[i] == s2[i]) prefix++
            else break
        }

        return jaro + prefix * 0.1 * (1.0 - jaro)
    }
}
