package com.bks.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinArchitectureRuleCheckerTest {
    private val checker = KotlinArchitectureRuleChecker()

    @Test
    fun forbiddenDependenciesIncludeImportsAndFullyQualifiedReferences() {
        val violations = checker.forbiddenDependencyViolations(
            relativePath = "viewmodel/account/BadViewModel.kt",
            source = """
                package com.bks.viewmodel.account

                import com.bks.model.data.local.database.BksDatabase

                val gateway = com.bks.model.platform.account.AndroidWechatAuthGateway()
            """.trimIndent(),
            forbiddenPrefixes = listOf("com.bks.model.data.", "com.bks.model.platform.")
        )

        assertEquals(2, violations.size)
        assertTrue(violations.any { "BksDatabase" in it })
        assertTrue(violations.any { "AndroidWechatAuthGateway" in it })
    }

    @Test
    fun commentsAndStringLiteralsDoNotCreateDependencyViolations() {
        val source = """
            package com.bks.viewmodel.account

            // com.bks.model.platform.account.AndroidWechatAuthGateway
            /* import com.bks.model.data.local.database.BksDatabase */
            val message = "com.bks.model.platform.account.AndroidWechatAuthGateway"
            val raw = """ + "\"\"\"com.bks.model.data.local.database.BksDatabase\"\"\"" + """
        """.trimIndent()

        assertTrue(
            checker.forbiddenDependencyViolations(
                relativePath = "viewmodel/account/GoodViewModel.kt",
                source = source,
                forbiddenPrefixes = listOf("com.bks.model.data.", "com.bks.model.platform.")
            ).isEmpty()
        )
    }

    @Test
    fun screenRulesRejectOwnersAndAssemblyButAllowStateAndActions() {
        val invalid = """
            package com.bks.view.account

            fun AccountScreen(viewModel: AccountEntryViewModel) {
                val repository = RoomAccountRepository()
            }
        """.trimIndent()
        val valid = """
            package com.bks.view.account

            fun AccountScreen(state: AccountUiState, actions: AccountScreenActions) = Unit
        """.trimIndent()

        val violations = checker.screenBoundaryViolations("view/account/AccountScreen.kt", invalid)
        assertTrue(violations.any { "AccountEntryViewModel" in it })
        assertTrue(violations.any { "RoomAccountRepository" in it })
        assertTrue(checker.screenBoundaryViolations("view/account/AccountScreen.kt", valid).isEmpty())
    }

    @Test
    fun domainFileIoRuleRejectsCodeButIgnoresDocumentationExamples() {
        val violations = checker.forbiddenDependencyViolations(
            relativePath = "model/domain/beta/BadScanner.kt",
            source = """
                package com.bks.model.domain.beta

                // java.io.File("documented-only")
                fun scan() = java.io.File("actual").readText()
            """.trimIndent(),
            forbiddenPrefixes = listOf("java.io.", "java.nio.file.", "kotlin.io.path.")
        )

        assertEquals(1, violations.size)
        assertTrue("java.io.File" in violations.single())
    }
}
