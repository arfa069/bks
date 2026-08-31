package com.bks.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class MvvmPackageArchitectureTest {
    private val sourceRoot = Path.of(
        requireNotNull(System.getProperty(MAIN_SOURCE_ROOT_PROPERTY)) {
            "$MAIN_SOURCE_ROOT_PROPERTY must be supplied by Gradle"
        }
    )
    private val packageRoot = sourceRoot.resolve("com/bks")
    private val sources = Files.walk(packageRoot).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.extension == "kt" }
            .sorted()
            .toList()
    }
    private val testPackageRoot = Path.of(
        requireNotNull(System.getProperty(TEST_SOURCE_ROOT_PROPERTY)) {
            "$TEST_SOURCE_ROOT_PROPERTY must be supplied by Gradle"
        }
    ).resolve("com/bks")

    @Test
    fun sourcePathsMatchPackagesAndUseOnlyThreeTopLevelLayers() {
        val violations = buildList {
            sources.forEach { source ->
                val relative = packageRoot.relativize(source).invariantSeparatorsPathString
                val segments = relative.split('/')
                if (segments.size < 2 || segments.first() !in TOP_LEVEL_LAYERS) {
                    add("$relative is outside model/viewmodel/view")
                }
                val expectedPackage = "com.bks." + segments.dropLast(1).joinToString(".")
                val declaredPackage = PACKAGE_PATTERN.find(source.readText())?.groupValues?.get(1)
                if (declaredPackage != expectedPackage) {
                    add("$relative declares $declaredPackage instead of $expectedPackage")
                }
            }
        }
        assertNoViolations(violations)
    }

    @Test
    fun testSourcePathsMatchPackagesAndMirrorProductionLayers() {
        val violations = Files.walk(testPackageRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension == "kt" }
                .sorted()
                .toList()
                .mapNotNull { source ->
                    val relative = testPackageRoot.relativize(source).invariantSeparatorsPathString
                    val segments = relative.split('/')
                    val topLevel = segments.firstOrNull()
                    val expectedPackage = "com.bks." + segments.dropLast(1).joinToString(".")
                    val declaredPackage = PACKAGE_PATTERN.find(source.readText())?.groupValues?.get(1)
                    when {
                        topLevel !in TEST_TOP_LEVEL_LAYERS ->
                            "$relative is outside model/viewmodel/view test mirrors"
                        declaredPackage != expectedPackage ->
                            "$relative declares $declaredPackage instead of $expectedPackage"
                        else -> null
                    }
                }
        }
        assertNoViolations(violations)
    }

    @Test
    fun modelSubpackagesAndLegacyPackagesAreConstrained() {
        val violations = buildList {
            sources.forEach { source ->
                val relative = relativePath(source)
                val text = source.readText()
                if (relative.startsWith("model/")) {
                    val modelSection = relative.substringAfter("model/").substringBefore('/')
                    if (modelSection !in MODEL_SECTIONS) {
                        add("$relative uses unsupported model section $modelSection")
                    }
                }
                LEGACY_PACKAGES.filter(text::contains).forEach { legacy ->
                    add("$relative still references $legacy")
                }
            }
        }
        assertNoViolations(violations)
    }

    @Test
    fun layersFollowMvvmDependencyDirection() {
        val violations = buildList {
            sources.forEach { source ->
                val relative = relativePath(source)
                val imports = source.readText().lineSequence()
                    .filter { it.startsWith("import ") }
                    .map { it.removePrefix("import ").substringBefore(" as ") }
                    .toList()
                when {
                    relative.startsWith("model/domain/") -> checkImports(
                        relative,
                        imports,
                        DOMAIN_FORBIDDEN_IMPORTS,
                        this
                    )
                    relative.startsWith("model/application/") -> checkImports(
                        relative,
                        imports,
                        APPLICATION_FORBIDDEN_IMPORTS,
                        this
                    )
                    relative.startsWith("model/data/") || relative.startsWith("model/platform/") ->
                        checkImports(relative, imports, MODEL_REVERSE_IMPORTS, this)
                    relative.startsWith("viewmodel/") -> checkImports(
                        relative,
                        imports,
                        VIEW_MODEL_FORBIDDEN_IMPORTS,
                        this
                    )
                    relative.startsWith("view/") && !relative.startsWith("view/app/") -> {
                        checkImports(relative, imports, VIEW_FORBIDDEN_IMPORTS, this)
                        if (imports.any { it.endsWith(".BksAppContainer") }) {
                            add("$relative imports BksAppContainer outside view/app")
                        }
                    }
                }
            }
        }
        assertNoViolations(violations)
    }

    @Test
    fun frameworkTypesStayInTheirOwningLayer() {
        val violations = buildList {
            sources.forEach { source ->
                val relative = relativePath(source)
                val text = source.readText()
                if ("@Composable" in text && !relative.startsWith("view/")) {
                    add("$relative declares Compose UI outside view")
                }
                if (VIEW_MODEL_DECLARATION.containsMatchIn(text) && !relative.startsWith("viewmodel/")) {
                    add("$relative declares an AndroidX ViewModel outside viewmodel")
                }
                if (
                    "import androidx.room" in text &&
                    !relative.startsWith("model/data/local/")
                ) {
                    add("$relative uses Room outside model/data/local")
                }
            }
        }
        assertNoViolations(violations)
    }

    private fun checkImports(
        relative: String,
        imports: List<String>,
        forbiddenPrefixes: List<String>,
        violations: MutableList<String>
    ) {
        imports.forEach { dependency ->
            forbiddenPrefixes.firstOrNull(dependency::startsWith)?.let {
                violations.add("$relative imports forbidden dependency $dependency")
            }
        }
    }

    private fun relativePath(source: Path): String =
        packageRoot.relativize(source).invariantSeparatorsPathString

    private fun assertNoViolations(violations: List<String>) {
        assertTrue(violations.joinToString(separator = "\n"), violations.isEmpty())
    }

    private companion object {
        const val MAIN_SOURCE_ROOT_PROPERTY = "bks.android.mainSourceRoot"
        const val TEST_SOURCE_ROOT_PROPERTY = "bks.android.testSourceRoot"
        val PACKAGE_PATTERN = Regex("(?m)^package\\s+([A-Za-z0-9_.]+)\\s*$")
        val VIEW_MODEL_DECLARATION = Regex(":\\s*ViewModel\\s*\\(")
        val TOP_LEVEL_LAYERS = setOf("model", "viewmodel", "view")
        val TEST_TOP_LEVEL_LAYERS = TOP_LEVEL_LAYERS + "architecture"
        val MODEL_SECTIONS = setOf("domain", "application", "data", "platform")
        val LEGACY_PACKAGES = listOf("com.bks.feature.", "com.bks.data.local.", "com.bks.ui.")
        val DOMAIN_FORBIDDEN_IMPORTS = listOf(
            "android.", "androidx.", "com.bks.api.", "com.bks.model.application.",
            "com.bks.model.data.", "com.bks.model.platform.", "com.bks.view.",
            "com.bks.viewmodel."
        )
        val APPLICATION_FORBIDDEN_IMPORTS = listOf(
            "android.", "androidx.compose.", "androidx.lifecycle.", "androidx.room.",
            "com.bks.api.", "com.bks.model.data.", "com.bks.model.platform.",
            "com.bks.view.", "com.bks.viewmodel."
        )
        val MODEL_REVERSE_IMPORTS = listOf("com.bks.view.", "com.bks.viewmodel.")
        val VIEW_MODEL_FORBIDDEN_IMPORTS = listOf(
            "android.", "androidx.activity.", "androidx.compose.", "androidx.room.",
            "com.bks.api.", "com.bks.model.data.", "com.bks.view."
        )
        val VIEW_FORBIDDEN_IMPORTS = listOf("com.bks.model.data.", "androidx.room.")
    }
}
