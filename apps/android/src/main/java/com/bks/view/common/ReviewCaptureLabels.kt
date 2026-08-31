package com.bks.view.common

import com.bks.model.domain.ledger.CaptureReason
import com.bks.model.domain.ledger.PaymentSource
internal fun PaymentSource.toReviewSourceLabel(): String = when (this) {
    PaymentSource.WECHAT -> "微信"
    PaymentSource.ALIPAY -> "支付宝"
    PaymentSource.OTHER -> "其他应用"
}

internal fun String.toReviewPaymentSource(): PaymentSource = when (trim()) {
    PaymentSource.WECHAT.toReviewSourceLabel() -> PaymentSource.WECHAT
    PaymentSource.ALIPAY.toReviewSourceLabel() -> PaymentSource.ALIPAY
    PaymentSource.OTHER.toReviewSourceLabel() -> PaymentSource.OTHER
    else -> PaymentSource.OTHER
}

internal fun CaptureReason.toReviewCaptureReasonLabel(): String = when (this) {
    CaptureReason.NOTIFICATION -> "通知捕获"
    CaptureReason.ACCESSIBILITY_AUTO -> "支付结果自动捕获"
    CaptureReason.BILL_SYNC -> "补录账单"
    CaptureReason.DUPLICATE_MERGE -> "重复合并"
    CaptureReason.MANUAL_SAMPLE -> "手动样例"
}

internal fun String.toReviewCaptureReason(): CaptureReason = when (trim()) {
    "历史采集", CaptureReason.ACCESSIBILITY_AUTO.toReviewCaptureReasonLabel() ->
        CaptureReason.ACCESSIBILITY_AUTO
    "账单同步", "本机 OCR 补录", CaptureReason.BILL_SYNC.toReviewCaptureReasonLabel() ->
        CaptureReason.BILL_SYNC
    CaptureReason.DUPLICATE_MERGE.toReviewCaptureReasonLabel() -> CaptureReason.DUPLICATE_MERGE
    CaptureReason.MANUAL_SAMPLE.toReviewCaptureReasonLabel() -> CaptureReason.MANUAL_SAMPLE
    else -> CaptureReason.NOTIFICATION
}
