package com.bks.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class MvvmPackageArchitectureTest {
    private val ruleChecker = KotlinArchitectureRuleChecker()
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
                ruleChecker.codeDependencies(text).forEach { dependency ->
                    LEGACY_PACKAGES.firstOrNull(dependency::startsWith)?.let {
                        add("$relative still references legacy dependency $dependency")
                    }
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
                val text = source.readText()
                when {
                    relative.startsWith("model/domain/") -> checkDependencies(
                        relative,
                        text,
                        DOMAIN_FORBIDDEN_IMPORTS,
                        this
                    )
                    relative.startsWith("model/application/") -> checkDependencies(
                        relative,
                        text,
                        APPLICATION_FORBIDDEN_IMPORTS,
                        this
                    )
                    relative.startsWith("model/data/") || relative.startsWith("model/platform/") ->
                        checkDependencies(relative, text, MODEL_REVERSE_IMPORTS, this)
                    relative.startsWith("viewmodel/") -> checkDependencies(
                        relative,
                        text,
                        VIEW_MODEL_FORBIDDEN_IMPORTS,
                        this
                    )
                    relative.startsWith("view/") && !relative.startsWith("view/app/") -> {
                        checkDependencies(relative, text, VIEW_FORBIDDEN_IMPORTS, this)
                        if (ruleChecker.codeDependencies(text).any { it.endsWith(".BksAppContainer") }) {
                            add("$relative references BksAppContainer outside view/app")
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
                val code = ruleChecker.codeOnly(text)
                if ("@Composable" in code && !relative.startsWith("view/")) {
                    add("$relative declares Compose UI outside view")
                }
                if (VIEW_MODEL_DECLARATION.containsMatchIn(code) && !relative.startsWith("viewmodel/")) {
                    add("$relative declares an AndroidX ViewModel outside viewmodel")
                }
                if (
                    ruleChecker.codeDependencies(text).any { it.startsWith("androidx.room.") } &&
                    !relative.startsWith("model/data/local/")
                ) {
                    add("$relative uses Room outside model/data/local")
                }
            }
        }
        assertNoViolations(violations)
    }

    @Test
    fun screenEntrypointsDoNotAcceptOrAssembleBusinessOwners() {
        val violations = buildList {
            sources.forEach { source ->
                val relative = relativePath(source)
                if (relative.startsWith("view/") && source.fileName.toString().contains("Screen")) {
                    addAll(ruleChecker.screenBoundaryViolations(relative, source.readText()))
                }
            }
        }
        assertNoViolations(violations)
    }

    private fun checkDependencies(
        relative: String,
        source: String,
        forbiddenPrefixes: List<String>,
        violations: MutableList<String>
    ) {
        violations += ruleChecker.forbiddenDependencyViolations(
            relativePath = relative,
            source = source,
            forbiddenPrefixes = forbiddenPrefixes
        )
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
            "com.bks.viewmodel.", "java.io.", "java.nio.file.", "kotlin.io.path."
        )
        val APPLICATION_FORBIDDEN_IMPORTS = listOf(
            "android.", "androidx.compose.", "androidx.lifecycle.", "androidx.room.",
            "com.bks.api.", "com.bks.model.data.", "com.bks.model.platform.",
            "com.bks.view.", "com.bks.viewmodel."
        )
        val MODEL_REVERSE_IMPORTS = listOf("com.bks.view.", "com.bks.viewmodel.")
        val VIEW_MODEL_FORBIDDEN_IMPORTS = listOf(
            "android.", "androidx.activity.", "androidx.compose.", "androidx.room.",
            "com.bks.api.", "com.bks.model.data.", "com.bks.model.platform.", "com.bks.view."
        )
        val VIEW_FORBIDDEN_IMPORTS = listOf("com.bks.model.data.", "androidx.room.")
    }
}
