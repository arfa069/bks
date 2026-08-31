package com.bks.model.domain.account

import java.util.Locale

enum class AccountIdentifierType {
    USERNAME,
    EMAIL,
    PHONE
}

data class AccountIdentifier(
    val type: AccountIdentifierType,
    val value: String,
    val verified: Boolean = true
)

data class AccountIdentifierParseResult(
    val type: AccountIdentifierType,
    val normalizedValue: String,
    val displayValue: String
) {
    fun toIdentifier(verified: Boolean = true) = AccountIdentifier(type, displayValue, verified)
}

object AccountIdentifierParser {
    private val usernameRegex = Regex("^[a-zA-Z][a-zA-Z0-9_]{3,19}$")
    private val phoneRegex = Regex("^\\d{11}$")

    fun parse(rawInput: String): AccountIdentifierParseResult {
        val trimmed = rawInput.trim()
        require(trimmed.isNotEmpty()) { "Identifier cannot be empty" }
        if (trimmed.contains("@")) {
            require(isValidEmail(trimmed)) { "Invalid email format" }
            val lower = trimmed.lowercase(Locale.ROOT)
            return AccountIdentifierParseResult(AccountIdentifierType.EMAIL, lower, lower)
        }
        if (trimmed.all(Char::isDigit)) {
            require(phoneRegex.matches(trimmed)) { "Invalid phone number format" }
            return AccountIdentifierParseResult(AccountIdentifierType.PHONE, trimmed, trimmed)
        }
        require(usernameRegex.matches(trimmed)) { "Invalid username format" }
        return AccountIdentifierParseResult(
            AccountIdentifierType.USERNAME,
            trimmed.lowercase(Locale.ROOT),
            trimmed
        )
    }

    private fun isValidEmail(email: String): Boolean {
        if (email.length > 254) return false
        val parts = email.split("@")
        if (parts.size != 2) return false
        val local = parts[0]
        val domain = parts[1]
        if (!hasValidDotPlacement(local) || !hasValidDotPlacement(domain)) return false
        val labels = domain.split(".")
        if (labels.size < 2 || labels.any { !isValidDomainLabel(it) }) return false
        return local.all { it.isLetterOrDigit() || "!#$%&'*+/=?^_`{|}~.-".contains(it) }
    }

    private fun hasValidDotPlacement(value: String): Boolean =
        value.isNotEmpty() && !value.startsWith(".") && !value.endsWith(".") && !value.contains("..")

    private fun isValidDomainLabel(label: String): Boolean =
        label.isNotEmpty() &&
            !label.startsWith("-") &&
            !label.endsWith("-") &&
            label.all { it.isLetterOrDigit() || it == '-' }
}

sealed interface IdentifierLinkPreparation {
    data object AlreadyLinked : IdentifierLinkPreparation

    data class LinkTicketIssued(
        val linkTicket: String,
        val ticketExpiresAtMillis: Long
    ) : IdentifierLinkPreparation

    data class MergeRequired(
        val mergeTicket: String,
        val sourceIdentifiers: List<AccountIdentifier>,
        val sourceWechatLinked: Boolean,
        val ticketExpiresAtMillis: Long
    ) : IdentifierLinkPreparation
}

data class AccountMergePreview(
    val mergeTicket: String,
    val ticketExpiresAtMillis: Long,
    val currentIdentifiers: List<AccountIdentifier> = emptyList(),
    val currentWechatLinked: Boolean = false,
    val currentNickname: String? = null,
    val sourceIdentifiers: List<AccountIdentifier> = emptyList(),
    val sourceWechatLinked: Boolean = false,
    val sourceNickname: String? = null
)
