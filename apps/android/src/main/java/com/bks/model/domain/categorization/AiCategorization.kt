package com.bks.model.domain.categorization

import com.bks.model.domain.ledger.TransactionKind
data class AiCategorizationSettings(
    val aiConsentGranted: Boolean = false,
    val enhancedContextGranted: Boolean = false
)

sealed interface AiCategorizationSettingsAction {
    data object EnableAi : AiCategorizationSettingsAction
    data object DisableAi : AiCategorizationSettingsAction
    data class SetEnhancedContext(val enabled: Boolean) : AiCategorizationSettingsAction
}

fun reduceAiCategorizationSettings(
    settings: AiCategorizationSettings,
    action: AiCategorizationSettingsAction
): AiCategorizationSettings = when (action) {
    AiCategorizationSettingsAction.EnableAi -> settings.copy(
        aiConsentGranted = true,
        enhancedContextGranted = false
    )
    AiCategorizationSettingsAction.DisableAi -> AiCategorizationSettings()
    is AiCategorizationSettingsAction.SetEnhancedContext -> settings.copy(
        enhancedContextGranted = settings.aiConsentGranted && action.enabled
    )
}

data class AiCategorizationPayload(
    val merchantTitle: String,
    val sourceLabel: String,
    val transactionKind: String,
    val amountRangeLabel: String,
    val categoryCandidates: List<String> = emptyList(),
    val enhancedContext: Boolean = false,
    val note: String? = null,
    val rawEvidenceText: String? = null
)

data class AiCategorizationResponse(
    val category: String,
    val confidenceLabel: String,
    val explanation: String
)

enum class AiCategorizationSkipReason {
    REQUIRES_SIGNED_IN_ACCOUNT,
    REQUIRES_AI_CONSENT
}

enum class AiCategorizationFailureReason {
    BACKEND_NOT_CONFIGURED,
    INVALID_SESSION,
    ACCOUNT_DELETION_PENDING,
    AI_CONSENT_REQUIRED,
    ENHANCED_CONTEXT_NOT_AUTHORIZED,
    CATEGORY_CANDIDATES_REQUIRED,
    RATE_LIMITED,
    SERVICE_UNAVAILABLE,
    NETWORK_FAILURE,
    INVALID_RESPONSE
}

data class AiCategorizationResult(
    val suggestion: AiCategorizationResponse? = null,
    val skipReason: AiCategorizationSkipReason? = null,
    val failureReason: AiCategorizationFailureReason? = null
)

data class AiCategorizationGatewayRequest(
    val token: String,
    val payload: AiCategorizationPayload
)

sealed interface AiCategorizationGatewayResult {
    data class Success(val suggestion: AiCategorizationResponse) : AiCategorizationGatewayResult
    data class Failure(val reason: AiCategorizationFailureReason) : AiCategorizationGatewayResult
}

interface AiCategorizationGateway {
    suspend fun suggestCategory(
        token: String,
        payload: AiCategorizationPayload
    ): AiCategorizationGatewayResult
}

class AiCategorizationClient(
    private val gateway: AiCategorizationGateway
) {
    suspend fun suggestCategory(
        token: String?,
        payload: AiCategorizationPayload,
        settings: AiCategorizationSettings
    ): AiCategorizationResult {
        val validToken = token
            ?: return AiCategorizationResult(
                skipReason = AiCategorizationSkipReason.REQUIRES_SIGNED_IN_ACCOUNT
            )
        if (!settings.aiConsentGranted) {
            return AiCategorizationResult(
                skipReason = AiCategorizationSkipReason.REQUIRES_AI_CONSENT
            )
        }
        val candidates = payload.categoryCandidates
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        if (candidates.isEmpty()) {
            return AiCategorizationResult(
                failureReason = AiCategorizationFailureReason.CATEGORY_CANDIDATES_REQUIRED
            )
        }
        val enhancedContextAllowed = settings.enhancedContextGranted && payload.enhancedContext
        val requestPayload = payload.copy(
            categoryCandidates = candidates,
            enhancedContext = enhancedContextAllowed,
            note = payload.note.takeIf { enhancedContextAllowed },
            rawEvidenceText = payload.rawEvidenceText.takeIf { enhancedContextAllowed }
        )

        return when (
            val result = gateway.suggestCategory(
                token = validToken,
                payload = requestPayload
            )
        ) {
            is AiCategorizationGatewayResult.Success -> AiCategorizationResult(
                suggestion = result.suggestion
            )
            is AiCategorizationGatewayResult.Failure -> AiCategorizationResult(
                failureReason = result.reason
            )
        }
    }
}
