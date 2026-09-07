package com.bks.model.platform.account

import com.bks.model.domain.account.WechatAuthCallback
import com.bks.model.platform.account.WechatAuthCallbackDelivery
import com.bks.model.platform.account.WechatAuthCallbackIntent
import com.bks.model.platform.account.WechatAuthCallbackProcessor
import com.bks.model.application.account.WechatAuthCoordinator
import com.bks.model.domain.account.WechatAuthGateway
import com.bks.model.domain.account.WechatAuthLaunchResult
import com.bks.model.domain.account.WechatAuthPurpose
import com.bks.model.platform.account.WechatAuthStateStore
import com.bks.model.platform.account.WechatSdkAuthError
import com.bks.model.platform.account.WechatSdkAuthResponse
import com.bks.model.domain.account.wechatSessionFingerprint
import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.bks.view.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WechatAuthCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearState() {
        context.getSharedPreferences("wechat_auth_request", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun fakeGatewayDrivesAuthorizationThroughValidatedMainActivityIntent() {
        val state = "E".repeat(43)
        val stateStore = WechatAuthStateStore(context, stateGenerator = { state })
        val gateway = object : WechatAuthGateway {
            override fun startAuthorization(
                purpose: WechatAuthPurpose,
                sessionFingerprint: String?
            ): WechatAuthLaunchResult {
                stateStore.begin(purpose, sessionFingerprint)
                return WechatAuthLaunchResult.Started
            }
        }
        val coordinator = WechatAuthCoordinator(gateway)

        assertEquals(
            WechatAuthLaunchResult.Started,
            coordinator.startAuthorization(true, WechatAuthPurpose.SignInOrRegister)
        )
        val delivery = WechatAuthCallbackProcessor(stateStore).process(
            WechatSdkAuthResponse("one-time-code", state, WechatSdkAuthError.None)
        )
        val delivered = WechatAuthCallbackIntent.consume(
            context,
            WechatAuthCallbackIntent.create(context, requireNotNull(delivery))
        )

        assertEquals(
            WechatAuthCallback.Authorized("one-time-code", WechatAuthPurpose.SignInOrRegister),
            delivered
        )
    }

    @Test
    fun stateIsSingleUsePurposeBoundAndMismatchDoesNotConsumeValidRequest() {
        val state = "A".repeat(43)
        val store = WechatAuthStateStore(
            context = context,
            clock = Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneId.of("UTC")),
            stateGenerator = { state }
        )

        val fingerprint = wechatSessionFingerprint("session-a")
        assertEquals(state, store.begin(WechatAuthPurpose.LinkCurrentAccount, fingerprint))
        assertNull(store.consumeSdkResponse("B".repeat(43)))
        val sdkContext = requireNotNull(store.consumeSdkResponse(state))
        assertEquals(WechatAuthPurpose.LinkCurrentAccount, sdkContext.purpose)
        assertEquals(fingerprint, sdkContext.sessionFingerprint)
        assertNull(store.consumeSdkResponse(state))
        assertEquals(sdkContext, store.consumeRelay(sdkContext.relayToken))
        assertNull(store.consumeRelay(sdkContext.relayToken))
    }

    @Test
    fun clearingAuthorizationIsPurposeBoundAndRejectsItsLateCallback() {
        val state = "F".repeat(43)
        val store = WechatAuthStateStore(context, stateGenerator = { state })

        store.begin(WechatAuthPurpose.LinkCurrentAccount)
        store.clear(WechatAuthPurpose.SignInOrRegister)
        assertEquals(WechatAuthPurpose.LinkCurrentAccount, store.consumeSdkResponse(state)?.purpose)

        store.begin(WechatAuthPurpose.SignInOrRegister)
        store.clear(WechatAuthPurpose.SignInOrRegister)
        assertNull(store.consumeSdkResponse(state))
    }

    @Test
    fun expiredStateIsDiscarded() {
        val clock = TestClock(1_000L)
        val state = "C".repeat(43)
        val store = WechatAuthStateStore(context, clock) { state }
        store.begin(WechatAuthPurpose.SignInOrRegister)

        clock.nowMillis += 5 * 60_000L + 1

        assertNull(store.consumeSdkResponse(state))
    }

    @Test
    fun callbackProcessorDropsInvalidStateAndMapsSdkOutcomes() {
        val state = "D".repeat(43)
        val store = WechatAuthStateStore(context, stateGenerator = { state })
        val processor = WechatAuthCallbackProcessor(store)

        store.begin(WechatAuthPurpose.SignInOrRegister)
        assertNull(
            processor.process(
                WechatSdkAuthResponse("one-time-code", "wrong-state", WechatSdkAuthError.None)
            )
        )
        assertEquals(
            WechatAuthCallback.Authorized("one-time-code", WechatAuthPurpose.SignInOrRegister),
            processor.process(WechatSdkAuthResponse("one-time-code", state, WechatSdkAuthError.None))?.callback
        )

        store.begin(WechatAuthPurpose.LinkCurrentAccount)
        assertEquals(
            WechatAuthCallback.Cancelled(WechatAuthPurpose.LinkCurrentAccount),
            processor.process(WechatSdkAuthResponse(null, state, WechatSdkAuthError.Cancelled))?.callback
        )

        store.begin(WechatAuthPurpose.LinkCurrentAccount)
        assertEquals(
            WechatAuthCallback.Denied(WechatAuthPurpose.LinkCurrentAccount),
            processor.process(WechatSdkAuthResponse(null, state, WechatSdkAuthError.Denied))?.callback
        )
    }

    @Test
    fun callbackIntentUsesManifestActionClearsSensitiveCodeAndSupportsBothDeliveryPaths() {
        val state = "F".repeat(43)
        val stateStore = WechatAuthStateStore(context, stateGenerator = { state })
        val processor = WechatAuthCallbackProcessor(stateStore)

        fun delivery(): WechatAuthCallbackDelivery {
            stateStore.begin(WechatAuthPurpose.SignInOrRegister)
            return requireNotNull(
                processor.process(WechatSdkAuthResponse("one-time-code", state, WechatSdkAuthError.None))
            )
        }

        val expected = WechatAuthCallback.Authorized("one-time-code", WechatAuthPurpose.SignInOrRegister)
        val coldStartIntent = WechatAuthCallbackIntent.create(context, delivery())

        assertNull(coldStartIntent.component)
        assertEquals(context.packageName, coldStartIntent.`package`)
        assertEquals(WechatAuthCallbackIntent.ACTION, coldStartIntent.action)
        assertTrue(coldStartIntent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(coldStartIntent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertEquals(expected, WechatAuthCallbackIntent.consume(context, coldStartIntent))
        assertFalse(coldStartIntent.hasExtra("com.bks.extra.WECHAT_CODE"))

        val onNewIntent = WechatAuthCallbackIntent.create(context, delivery())
        assertEquals(expected, WechatAuthCallbackIntent.consume(context, onNewIntent))
        assertNull(WechatAuthCallbackIntent.consume(context, onNewIntent))
    }

    @Test
    fun forgedMainActivityIntentCannotConsumePendingAuthorization() {
        val state = "G".repeat(43)
        val stateStore = WechatAuthStateStore(context, stateGenerator = { state })
        stateStore.begin(WechatAuthPurpose.SignInOrRegister)
        val forged = Intent(context, MainActivity::class.java).apply {
            action = WechatAuthCallbackIntent.ACTION
            putExtra("com.bks.extra.WECHAT_STATUS", "authorized")
            putExtra("com.bks.extra.WECHAT_CODE", "forged-code")
            putExtra("com.bks.extra.WECHAT_RELAY_TOKEN", "forged-relay")
        }

        assertNull(WechatAuthCallbackIntent.consume(context, forged))
        val validDelivery = requireNotNull(
            WechatAuthCallbackProcessor(stateStore).process(
                WechatSdkAuthResponse("real-code", state, WechatSdkAuthError.None)
            )
        )
        assertEquals(
            WechatAuthCallback.Authorized("real-code", WechatAuthPurpose.SignInOrRegister),
            WechatAuthCallbackIntent.consume(
                context,
                WechatAuthCallbackIntent.create(context, validDelivery)
            )
        )
    }

    @Test
    fun debugCallbackActivityIsExportedSingleTaskWithDebugAffinity() {
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, "com.bks.debug.wxapi.WXEntryActivity"),
            0
        )

        assertTrue(info.exported)
        assertEquals(android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
        assertEquals("com.bks.debug", info.taskAffinity)
    }

    private class TestClock(
        var nowMillis: Long
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(nowMillis)

        override fun millis(): Long = nowMillis
    }
}
