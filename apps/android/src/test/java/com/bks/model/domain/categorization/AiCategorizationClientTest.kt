package com.bks.model.domain.categorization

import com.bks.model.domain.categorization.AiCategorizationClient
import com.bks.model.domain.categorization.AiCategorizationFailureReason
import com.bks.model.domain.categorization.AiCategorizationGateway
import com.bks.model.domain.categorization.AiCategorizationGatewayRequest
import com.bks.model.domain.categorization.AiCategorizationGatewayResult
import com.bks.model.domain.categorization.AiCategorizationPayload
import com.bks.model.domain.categorization.AiCategorizationResponse
import com.bks.model.domain.categorization.AiCategorizationSettings
import com.bks.model.domain.categorization.AiCategorizationSkipReason
import com.bks.model.domain.categorization.suggestCategory
import com.bks.model.domain.ledger.Category
import com.bks.model.domain.ledger.TransactionKind
import com.bks.view.review.confidenceLabel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCategorizationClientTest {
    @Test
    fun clientBoundaryDoesNotAcceptAccountOrReviewDomainTypes() {
        val parameterTypes = AiCategorizationClient::class.java.declaredMethods
            .single { it.name == "suggestCategory" }
            .parameterTypes

        assertTrue(parameterTypes.contains(String::class.java))
        assertTrue(parameterTypes.contains(AiCategorizationPayload::class.java))
        assertTrue(parameterTypes.contains(AiCategorizationSettings::class.java))
        assertTrue(
            parameterTypes.none { type ->
                type.name.startsWith("com.bks.view.account.") ||
                    type.name.startsWith("com.bks.view.review.")
            }
        )
    }

    @Test
    fun missingTokenOrConsentDoesNotCallCloudAi() = runBlocking {
        val gateway = RecordingAiCategorizationGateway()
        val client = AiCategorizationClient(gateway)

        val missingTokenResult = client.suggestCategory(
            token = null,
            payload = samplePayload(),
            settings = AiCategorizationSettings(aiConsentGranted = true)
        )
        val noConsentResult = client.suggestCategory(
            token = "token-1",
            payload = samplePayload(),
            settings = AiCategorizationSettings(aiConsentGranted = false)
        )

        assertEquals(
            AiCategorizationSkipReason.REQUIRES_SIGNED_IN_ACCOUNT,
            missingTokenResult.skipReason
        )
        assertEquals(AiCategorizationSkipReason.REQUIRES_AI_CONSENT, noConsentResult.skipReason)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun consentedTokenSendsGenericMinimalPayload() = runBlocking {
        val gateway = RecordingAiCategorizationGateway()
        val client = AiCategorizationClient(gateway)

        val result = client.suggestCategory(
            token = "token-1",
            payload = samplePayload(categoryCandidates = listOf("餐饮", "交通")),
            settings = AiCategorizationSettings(aiConsentGranted = true)
        )

        assertEquals("餐饮", result.suggestion?.category)
        assertEquals("token-1", gateway.requests.single().token)
        val payload = gateway.requests.single().payload
        assertEquals("午餐", payload.merchantTitle)
        assertEquals("微信", payload.sourceLabel)
        assertEquals("支出", payload.transactionKind)
        assertEquals("0-50", payload.amountRangeLabel)
        assertEquals(listOf("餐饮", "交通"), payload.categoryCandidates)
        assertFalse(payload.enhancedContext)
        assertNull(payload.note)
        assertNull(payload.rawEvidenceText)
    }

    @Test
    fun enhancedContextOptInPreservesOptionalPayloadContext() = runBlocking {
        val gateway = RecordingAiCategorizationGateway()
        val client = AiCategorizationClient(gateway)

        client.suggestCategory(
            token = "token-1",
            payload = samplePayload(
                categoryCandidates = listOf("餐饮"),
                enhancedContext = true,
                note = "客户会议",
                rawEvidenceText = "微信支付收款凭证 午餐 35.90"
            ),
            settings = AiCategorizationSettings(
                aiConsentGranted = true,
                enhancedContextGranted = true
            )
        )

        val payload = gateway.requests.single().payload
        assertTrue(payload.enhancedContext)
        assertEquals("客户会议", payload.note)
        assertEquals("微信支付收款凭证 午餐 35.90", payload.rawEvidenceText)
    }

    @Test
    fun unauthorizedEnhancedPayloadIsRedactedBeforeGateway() = runBlocking {
        val gateway = RecordingAiCategorizationGateway()

        AiCategorizationClient(gateway).suggestCategory(
            token = "token-1",
            payload = samplePayload(
                enhancedContext = true,
                note = "客户会议",
                rawEvidenceText = "微信支付收款凭证 午餐 35.90"
            ),
            settings = AiCategorizationSettings(aiConsentGranted = true)
        )

        val payload = gateway.requests.single().payload
        assertFalse(payload.enhancedContext)
        assertNull(payload.note)
        assertNull(payload.rawEvidenceText)
    }

    @Test
    fun emptyCandidatesFailBeforeNetwork() = runBlocking {
        val gateway = RecordingAiCategorizationGateway()

        val result = AiCategorizationClient(gateway).suggestCategory(
            token = "token-1",
            payload = samplePayload(categoryCandidates = listOf(" ")),
            settings = AiCategorizationSettings(aiConsentGranted = true)
        )

        assertEquals(AiCategorizationFailureReason.CATEGORY_CANDIDATES_REQUIRED, result.failureReason)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun gatewayFailureIsPreserved() = runBlocking {
        val gateway = RecordingAiCategorizationGateway(
            result = AiCategorizationGatewayResult.Failure(
                AiCategorizationFailureReason.ACCOUNT_DELETION_PENDING
            )
        )

        val result = AiCategorizationClient(gateway).suggestCategory(
            token = "token-1",
            payload = samplePayload(),
            settings = AiCategorizationSettings(aiConsentGranted = true)
        )

        assertEquals(AiCategorizationFailureReason.ACCOUNT_DELETION_PENDING, result.failureReason)
    }

    private fun samplePayload(
        categoryCandidates: List<String> = listOf("餐饮"),
        enhancedContext: Boolean = false,
        note: String? = null,
        rawEvidenceText: String? = null
    ): AiCategorizationPayload = AiCategorizationPayload(
        merchantTitle = "午餐",
        sourceLabel = "微信",
        transactionKind = "支出",
        amountRangeLabel = "0-50",
        categoryCandidates = categoryCandidates,
        enhancedContext = enhancedContext,
        note = note,
        rawEvidenceText = rawEvidenceText
    )

    private class RecordingAiCategorizationGateway(
        private val result: AiCategorizationGatewayResult = AiCategorizationGatewayResult.Success(
            AiCategorizationResponse(
                category = "餐饮",
                confidenceLabel = "高",
                explanation = "测试建议"
            )
        )
    ) : AiCategorizationGateway {
        val requests = mutableListOf<AiCategorizationGatewayRequest>()

        override suspend fun suggestCategory(
            token: String,
            payload: AiCategorizationPayload
        ): AiCategorizationGatewayResult {
            requests += AiCategorizationGatewayRequest(token, payload)
            return result
        }
    }
}
