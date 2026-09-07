package com.bks.model.domain.beta

import java.io.File

internal object SourceSecretScanner {
    data class Finding(
        val path: String,
        val patternName: String
    )

    fun scan(files: List<File>): List<Finding> = files
        .flatMap { it.sourceFiles() }
        .flatMap { file ->
            val text = file.readText()
            SECRET_PATTERNS.mapNotNull { pattern ->
                if (pattern.regex.containsMatchIn(text)) {
                    Finding(file.path, pattern.name)
                } else {
                    null
                }
            }
        }
}

private fun File.sourceFiles(): List<File> {
    if (!exists()) return emptyList()
    if (isFile) return listOf(this).filter { it.isScannableSource() }
    return walkTopDown()
        .filter { it.isFile && it.isScannableSource() }
        .toList()
}

private fun File.isScannableSource(): Boolean =
    extension in setOf("kt", "kts", "xml", "properties", "gradle")

private data class SecretPattern(
    val name: String,
    val regex: Regex
)

private val SECRET_PATTERNS = listOf(
    SecretPattern("OpenAI style API key", Regex("""sk-[A-Za-z0-9_-]{20,}""")),
    SecretPattern("Private key block", Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----""")),
    SecretPattern(
        "Hard-coded API key assignment",
        Regex("""api[_-]?key\s*=\s*["'][^"']{12,}["']""", RegexOption.IGNORE_CASE)
    ),
    SecretPattern(
        "Hard-coded secret assignment",
        Regex("""secret\s*=\s*["'][^"']{12,}["']""", RegexOption.IGNORE_CASE)
    )
)
