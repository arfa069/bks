package com.bks.architecture

internal class KotlinArchitectureRuleChecker {
    fun codeDependencies(source: String): Set<String> {
        val code = codeOnly(source)
        val imports = IMPORT_PATTERN.findAll(code).map { it.groupValues[1] }
        val qualifiedReferences = QUALIFIED_REFERENCE_PATTERN.findAll(code).map { it.value }
        return (imports + qualifiedReferences).toSet()
    }

    fun forbiddenDependencyViolations(
        relativePath: String,
        source: String,
        forbiddenPrefixes: List<String>
    ): List<String> = buildList {
        codeDependencies(source).sorted().forEach { dependency ->
            forbiddenPrefixes.firstOrNull(dependency::startsWith)?.let {
                add("$relativePath references forbidden dependency $dependency")
            }
        }
    }

    fun screenBoundaryViolations(relativePath: String, source: String): List<String> {
        val code = codeOnly(source)
        return buildList {
            codeDependencies(source)
                .filter(SCREEN_FORBIDDEN_DEPENDENCY::matches)
                .sorted()
                .forEach { dependency ->
                    add("$relativePath references forbidden Screen dependency $dependency")
                }
            SCREEN_FUNCTION_PATTERN.findAll(code).forEach { declaration ->
                val openParenthesis = declaration.range.last
                val closeParenthesis = matchingParenthesis(code, openParenthesis) ?: return@forEach
                val parameters = code.substring(openParenthesis + 1, closeParenthesis)
                SCREEN_FORBIDDEN_TYPE.findAll(parameters).forEach { type ->
                    add("$relativePath accepts forbidden Screen parameter type ${type.value}")
                }
            }
            SCREEN_FORBIDDEN_CONSTRUCTION.findAll(code).forEach { construction ->
                add("$relativePath assembles forbidden dependency ${construction.value.trimEnd('(')}")
            }
            if (VIEW_MODEL_FACTORY_CREATION.containsMatchIn(code)) {
                add("$relativePath assembles a ViewModel factory inside a Screen file")
            }
        }.distinct()
    }

    fun codeOnly(source: String): String = KotlinCodeSanitizer(source).sanitize()

    private fun matchingParenthesis(source: String, openParenthesis: Int): Int? {
        var depth = 0
        for (index in openParenthesis until source.length) {
            when (source[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private class KotlinCodeSanitizer(private val source: String) {
        private val result = StringBuilder(source.length)
        private var index = 0
        private var blockCommentDepth = 0
        private var mode = MODE_CODE

        fun sanitize(): String {
            while (index < source.length) {
                when (mode) {
                    MODE_CODE -> consumeCode()
                    MODE_LINE_COMMENT -> consumeLineComment()
                    MODE_BLOCK_COMMENT -> consumeBlockComment()
                    MODE_RAW_STRING -> consumeRawString()
                    MODE_STRING -> consumeQuotedString('"')
                    MODE_CHARACTER -> consumeQuotedString('\'')
                }
            }
            return result.toString()
        }

        private fun consumeCode() {
            when {
                source.startsWith("//", index) -> enterMode(MODE_LINE_COMMENT, markerLength = 2)
                source.startsWith("/*", index) -> {
                    blockCommentDepth = 1
                    enterMode(MODE_BLOCK_COMMENT, markerLength = 2)
                }
                source.startsWith("\"\"\"", index) -> enterMode(MODE_RAW_STRING, markerLength = 3)
                source[index] == '"' -> enterMode(MODE_STRING, markerLength = 1)
                source[index] == '\'' -> enterMode(MODE_CHARACTER, markerLength = 1)
                else -> {
                    result.append(source[index])
                    index += 1
                }
            }
        }

        private fun consumeLineComment() {
            val current = source[index]
            maskCurrent()
            if (current == '\n') mode = MODE_CODE
        }

        private fun consumeBlockComment() {
            when {
                source.startsWith("/*", index) -> {
                    mask(markerLength = 2)
                    blockCommentDepth += 1
                }
                source.startsWith("*/", index) -> {
                    mask(markerLength = 2)
                    blockCommentDepth -= 1
                    if (blockCommentDepth == 0) mode = MODE_CODE
                }
                else -> maskCurrent()
            }
        }

        private fun consumeRawString() {
            if (source.startsWith("\"\"\"", index)) {
                mask(markerLength = 3)
                mode = MODE_CODE
            } else {
                maskCurrent()
            }
        }

        private fun consumeQuotedString(terminator: Char) {
            when {
                source[index] == '\\' && index + 1 < source.length -> mask(markerLength = 2)
                source[index] == terminator -> {
                    maskCurrent()
                    mode = MODE_CODE
                }
                else -> maskCurrent()
            }
        }

        private fun enterMode(nextMode: Int, markerLength: Int) {
            mask(markerLength)
            mode = nextMode
        }

        private fun mask(markerLength: Int) {
            repeat(markerLength) { result.append(' ') }
            index += markerLength
        }

        private fun maskCurrent() {
            result.append(if (source[index] == '\n') '\n' else ' ')
            index += 1
        }
    }

    private companion object {
        const val MODE_CODE = 0
        const val MODE_LINE_COMMENT = 1
        const val MODE_BLOCK_COMMENT = 2
        const val MODE_STRING = 3
        const val MODE_RAW_STRING = 4
        const val MODE_CHARACTER = 5

        val IMPORT_PATTERN = Regex(
            "(?m)^\\s*import\\s+([A-Za-z_][A-Za-z0-9_.]*)(?:\\s+as\\s+[A-Za-z_][A-Za-z0-9_]*)?\\s*$"
        )
        val QUALIFIED_REFERENCE_PATTERN = Regex(
            "\\b(?:android|androidx|com\\.bks|java\\.io|java\\.nio\\.file|kotlin\\.io\\.path)" +
                "(?:\\.[A-Za-z_][A-Za-z0-9_]*)+"
        )
        val SCREEN_FUNCTION_PATTERN = Regex("\\bfun\\s+[A-Za-z_][A-Za-z0-9_]*Screen\\s*\\(")
        val SCREEN_FORBIDDEN_TYPE = Regex(
            "\\b(?:BksAppContainer|[A-Z][A-Za-z0-9_]*(?:ViewModel|Repository|Gateway)|" +
                "ViewModelProvider\\.Factory)\\b"
        )
        val SCREEN_FORBIDDEN_DEPENDENCY = Regex(
            "com\\.bks\\..*(?:BksAppContainer|ViewModel|Repository|Gateway)$"
        )
        val SCREEN_FORBIDDEN_CONSTRUCTION = Regex(
            "\\b[A-Z][A-Za-z0-9_]*(?:ViewModel|Repository|Gateway)\\s*\\("
        )
        val VIEW_MODEL_FACTORY_CREATION = Regex(
            "\\b(?:ViewModelProvider\\.Factory|viewModel\\s*<)"
        )
    }
}
