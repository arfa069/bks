package com.bks.viewmodel.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bks.model.domain.billsync.BillBackfillGateway
import com.bks.model.domain.ledger.PaymentSource
import kotlinx.coroutines.launch

internal class BillBackfillViewModel(private val gateway: BillBackfillGateway) : ViewModel() {
    val state = gateway.state

    fun start(source: PaymentSource) {
        viewModelScope.launch { gateway.start(source) }
    }

    fun finish() {
        viewModelScope.launch { gateway.finish() }
    }
}
