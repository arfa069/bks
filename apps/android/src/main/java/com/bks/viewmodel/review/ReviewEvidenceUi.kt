package com.bks.viewmodel.review

internal fun mergeReviewEvidenceText(vararg evidenceTexts: String): String {
    val sections = linkedMapOf<String, String>()
    evidenceTexts.filter(String::isNotBlank).forEach { evidence ->
        parseReviewEvidenceText(evidence).forEach { (label, text) ->
            val existing = sections[label]
            when {
                existing == null -> sections[label] = text
                existing != text -> sections[label] = "$existing\n---\n$text"
            }
        }
    }
    return sections.entries.joinToString("\n\n") { (label, text) -> "[$label]\n$text" }
}

internal fun parseReviewEvidenceText(evidenceText: String): List<Pair<String, String>> {
    val matches = REVIEW_EVIDENCE_HEADER_REGEX.findAll(evidenceText).toList()
    if (matches.isEmpty()) {
        return evidenceText.trim().takeIf(String::isNotBlank)
            ?.let { listOf("原始文本" to it) }
            .orEmpty()
    }
    return matches.mapIndexedNotNull { index, match ->
        val start = match.range.last + 1
        val end = matches.getOrNull(index + 1)?.range?.first ?: evidenceText.length
        evidenceText.substring(start, end).trim().takeIf(String::isNotBlank)?.let { text ->
            match.groupValues[1] to text
        }
    }
}

private val REVIEW_EVIDENCE_HEADER_REGEX = Regex(
    pattern = "(?m)^\\[([^]\\r\\n]+)]\\s*$"
)
