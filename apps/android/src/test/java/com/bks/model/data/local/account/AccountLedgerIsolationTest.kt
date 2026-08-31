package com.bks.model.data.local.account

import com.bks.model.data.local.account.AccountSessionCipher
import com.bks.model.data.local.account.SecureAccountSessionStore
import com.bks.model.domain.account.AccountCredentials
import com.bks.model.domain.ledger.LedgerRepository
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bks.model.data.local.database.BksDatabaseProvider
import com.bks.model.domain.ledger.FlowDirection
import com.bks.model.data.local.ledger.LedgerEntryInput
import com.bks.model.data.local.ledger.LocalLedgerRepository
import com.bks.model.domain.ledger.PaymentSource
import com.bks.model.domain.ledger.TransactionKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountLedgerIsolationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ledgerRepository by lazy {
        LocalLedgerRepository(BksDatabaseProvider.get(context))
    }

    @Before
    fun setUp() {
        runBlocking { ledgerRepository.clearLocalData() }
        context.getSharedPreferences("account_session_secure", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        runBlocking { ledgerRepository.clearLocalData() }
        context.getSharedPreferences("account_session_secure", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun persistingAndSigningOutAccountSessionDoesNotChangeLocalLedger() = runBlocking {
        val ledger = ledgerRepository.ensureDefaultLedgerBook()
        ledgerRepository.createManualEntry(
            ledger.id,
            LedgerEntryInput(
                flowDirection = FlowDirection.OUTFLOW,
                transactionKind = TransactionKind.EXPENSE,
                amountMinor = 1_200,
                transactionTimeEpochMillis = 1_000,
                merchantTitle = "本机账目",
                categoryId = null,
                fundingAccountId = null,
                newFundingAccountLabel = null,
                note = null,
                paymentSource = PaymentSource.ALIPAY
            )
        )
        val before = ledgerRepository.listLedgerEntries()
        val sessionStore = SecureAccountSessionStore(context, ReversibleCipher())

        assert(sessionStore.save(AccountCredentials("13800138000", "token-1")))
        assert(sessionStore.clear())

        assertEquals(before, ledgerRepository.listLedgerEntries())
    }

    private class ReversibleCipher : AccountSessionCipher {
        override fun encrypt(plainText: ByteArray): ByteArray = plainText.reversedArray()
        override fun decrypt(cipherText: ByteArray): ByteArray = cipherText.reversedArray()
    }
}
