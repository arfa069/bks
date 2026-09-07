package com.bks.viewmodel.account

import com.bks.model.domain.account.AccountIdentifier
import com.bks.model.domain.account.AccountIdentifierType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountIdentityReducerTest {
    @Test
    fun openingPageClearsPreviousTransitionContext() {
        val state = populatedState().openIdentityPage(AccountIdentityPage.AttachIdentifier)

        assertEquals(AccountIdentityPage.AttachIdentifier, state.identityPage)
        assertNull(state.identityError)
        assertNull(state.phoneTicket)
        assertNull(state.mergeTicket)
        assertTrue(state.sourceIdentifiers.isEmpty())
        assertNull(state.sourceNickname)
        assertFalse(state.sourceWechatLinked)
    }

    @Test
    fun mergeAndResetTransitionsPreserveSingleDraftGenerationOwner() {
        val source = listOf(AccountIdentifier(AccountIdentifierType.EMAIL, "old@example.com"))
        val merge = AccountManagementOperationState(identityOperationInProgress = true)
            .showIdentityMerge("merge-ticket", source, "旧账号", sourceWechatLinked = true)

        assertEquals(AccountIdentityPage.Merge, merge.identityPage)
        assertEquals("merge-ticket", merge.mergeTicket)
        assertEquals(source, merge.sourceIdentifiers)
        assertFalse(merge.identityOperationInProgress)

        val reset = merge.resetIdentity()
        assertEquals(AccountIdentityPage.Idle, reset.identityPage)
        assertNull(reset.mergeTicket)
        assertEquals(1L, reset.draftResetGeneration)
    }

    @Test
    fun identifierValidationHonorsRequestedType() {
        assertTrue(AccountIdentityFormState(phone = "13800138000").hasValidIdentifier())
        assertFalse(
            AccountIdentityFormState(
                phone = "user@example.com",
                targetIdentifierType = AccountIdentifierType.PHONE
            ).hasValidIdentifier()
        )
        assertEquals(
            "请输入有效的邮箱",
            AccountIdentityFormState(targetIdentifierType = AccountIdentifierType.EMAIL).expectedIdentifierError()
        )
    }

    private fun populatedState() = AccountManagementOperationState(
        identityError = "old",
        phoneTicket = "phone-ticket",
        mergeTicket = "merge-ticket",
        sourceIdentifiers = listOf(AccountIdentifier(AccountIdentifierType.PHONE, "13800138000")),
        sourceNickname = "旧账号",
        sourceWechatLinked = true
    )
}
