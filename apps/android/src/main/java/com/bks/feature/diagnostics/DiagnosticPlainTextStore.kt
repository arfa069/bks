package com.bks.feature.diagnostics

import java.io.File
import java.security.KeyStore

@Suppress("TooManyFunctions")
internal class DiagnosticPlainTextStore(
    private val directory: File,
    private val maxSegmentBytes: Long = 1L * 1024 * 1024,
    private val maxTotalBytes: Long = 10L * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
    private val clearLegacyKey: () -> Unit = ::deleteLegacyDiagnosticKey
) {
    init {
        directory.mkdirs()
    }

    fun append(event: DiagnosticEvent): Boolean {
        directory.mkdirs()
        val bytes = (DiagnosticEventCodec.encode(event) + "\n").toByteArray(Charsets.UTF_8)
        var segment = segments().lastOrNull()
        if (segment == null || segment.length() + bytes.size > maxSegmentBytes) {
            segment = nextSegment()
        }
        segment.appendBytes(bytes)
        return rotateIfNeeded()
    }

    fun readAll(): List<DiagnosticEvent> = readLatest(Int.MAX_VALUE)

    fun readLatest(limit: Int): List<DiagnosticEvent> {
        if (limit <= 0) return emptyList()
        val newestFirst = mutableListOf<DiagnosticEvent>()
        for (segment in segments().asReversed()) {
            val lines = runCatching { segment.readLines(Charsets.UTF_8) }
                .getOrDefault(emptyList())
            for (line in lines.asReversed()) {
                decodeLine(line)?.let(newestFirst::add)
                if (newestFirst.size == limit) return newestFirst.asReversed()
            }
        }
        return newestFirst.asReversed()
    }

    fun eventCount(): Int = segments().sumOf { segment ->
        runCatching {
            segment.useLines(Charsets.UTF_8) { lines -> lines.count { decodeLine(it) != null } }
        }.getOrDefault(0)
    }

    fun storedBytes(): Long = segments().sumOf(File::length)

    fun segmentCount(): Int = segments().size

    fun clear() {
        clearableFiles().forEach { runCatching { it.delete() } }
        runCatching(clearLegacyKey)
        runCatching { directory.delete() }
    }

    private fun segments(): List<File> = directory.listFiles { file ->
        file.isFile && file.extension == SEGMENT_EXTENSION
    }?.sortedBy(File::getName).orEmpty()

    private fun clearableFiles(): List<File> = directory.listFiles { file ->
        file.isFile && file.extension in CLEARABLE_EXTENSIONS
    }?.toList().orEmpty()

    private fun decodeLine(line: String): DiagnosticEvent? =
        runCatching { DiagnosticEventCodec.decode(line) }.getOrNull()

    private fun nextSegment(): File {
        val base = clock()
        var sequence = 0
        while (true) {
            val candidate = File(
                directory,
                "segment-${base.toString().padStart(20, '0')}-$sequence.$SEGMENT_EXTENSION"
            )
            if (!candidate.exists()) return candidate
            sequence += 1
        }
    }

    private fun rotateIfNeeded(): Boolean {
        val current = segments().toMutableList()
        var total = current.sumOf(File::length)
        var rotated = false
        while (total > maxTotalBytes && current.size > 1) {
            val oldest = current.removeAt(0)
            val size = oldest.length()
            if (oldest.delete()) {
                total -= size
                rotated = true
            } else {
                break
            }
        }
        return rotated
    }

    private companion object {
        const val SEGMENT_EXTENSION = "log"
        val CLEARABLE_EXTENSIONS = setOf(SEGMENT_EXTENSION, "aadlog")
    }
}

private fun deleteLegacyDiagnosticKey() {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    if (keyStore.containsAlias(LEGACY_KEY_ALIAS)) keyStore.deleteEntry(LEGACY_KEY_ALIAS)
}

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val LEGACY_KEY_ALIAS = "bks_diagnostics_v1"
