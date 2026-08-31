package com.bks.model.domain.categorization

sealed interface CloudAiSettingsGatewayResult {
    data class Success(
        val settings: AiCategorizationSettings,
        val defaultFundingAccountSyncId: String? = null,
        val supportsDefaultFundingAccount: Boolean = false
    ) : CloudAiSettingsGatewayResult

    data class Failure(val reason: AiCategorizationFailureReason) : CloudAiSettingsGatewayResult
}

interface CloudAiSettingsGateway {
    suspend fun read(token: String): CloudAiSettingsGatewayResult
    suspend fun write(token: String, settings: AiCategorizationSettings): CloudAiSettingsGatewayResult
    suspend fun writeDefaultFundingAccount(
        token: String,
        syncId: String?
    ): CloudAiSettingsGatewayResult = CloudAiSettingsGatewayResult.Failure(
        AiCategorizationFailureReason.BACKEND_NOT_CONFIGURED
    )
}
