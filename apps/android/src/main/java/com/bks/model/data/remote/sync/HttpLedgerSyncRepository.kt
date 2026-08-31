package com.bks.model.data.remote.sync

import com.bks.api.AccountApiJsonContracts
import com.bks.api.LEDGER_SYNC_MAX_BATCH_SIZE
import com.bks.api.LedgerSyncConflictChoiceContract
import com.bks.api.LedgerSyncInitializeRequestContract
import com.bks.api.LedgerSyncInitializeResponseContract
import com.bks.api.LedgerSyncJsonContracts
import com.bks.api.LedgerSyncMutationContract
import com.bks.api.LedgerSyncPullRequestContract
import com.bks.api.LedgerSyncPullResponseContract
import com.bks.api.LedgerSyncPushRequestContract
import com.bks.api.LedgerSyncPushResponseContract
import com.bks.api.LedgerSyncResolveConflictRequestContract
import com.bks.api.LedgerSyncResolveConflictResponseContract
import com.bks.api.LedgerSyncSnapshotRequestContract
import com.bks.api.LedgerSyncSnapshotResponseContract
import com.bks.model.domain.sync.LedgerSyncConflictChoice
import com.bks.model.domain.sync.LedgerSyncInitializeResult
import com.bks.model.domain.sync.LedgerSyncMutation
import com.bks.model.domain.sync.LedgerSyncPullResult
import com.bks.model.domain.sync.LedgerSyncPushResult
import com.bks.model.domain.sync.LedgerSyncRecord
import com.bks.model.domain.sync.LedgerSyncRemoteResult
import com.bks.model.domain.sync.LedgerSyncRepository
import com.bks.model.domain.sync.LedgerSyncSnapshotPage
import com.bks.model.data.remote.isPrivateTestHost
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HttpLedgerSyncRepository(
    backendUrl: String,
    private val allowHttp: Boolean
) : LedgerSyncRepository {
    private val baseUrl = backendUrl.trim().trimEnd('/')
    private val uri = runCatching { URI(baseUrl) }.getOrNull()

    override val insecureHttpTestMode: Boolean =
        uri?.scheme.equals("http", ignoreCase = true) && allowHttp && uri?.host.isPrivateTestHost()

    override val available: Boolean = when {
        baseUrl.isBlank() || uri?.host.isNullOrBlank() -> false
        uri.scheme.equals("https", ignoreCase = true) -> true
        else -> insecureHttpTestMode
    }

    override suspend fun initialize(
        token: String,
        deviceId: String
    ): LedgerSyncRemoteResult<LedgerSyncInitializeResult> = post(
        "/account/ledger-sync/initialize",
        token,
        LedgerSyncJsonContracts.encodeInitializeRequest(LedgerSyncInitializeRequestContract(deviceId)),
        { LedgerSyncJsonContracts.parseInitializeResponse(it).toDomain() }
    )

    override suspend fun snapshot(
        token: String,
        offset: Int
    ): LedgerSyncRemoteResult<LedgerSyncSnapshotPage> = post(
        "/account/ledger-sync/snapshot",
        token,
        LedgerSyncJsonContracts.encodeSnapshotRequest(
            LedgerSyncSnapshotRequestContract(offset, LEDGER_SYNC_MAX_BATCH_SIZE)
        ),
        { LedgerSyncJsonContracts.parseSnapshotResponse(it).toDomain() }
    )

    override suspend fun push(
        token: String,
        deviceId: String,
        mutations: List<LedgerSyncMutation>
    ): LedgerSyncRemoteResult<LedgerSyncPushResult> = post(
        "/account/ledger-sync/push",
        token,
        LedgerSyncJsonContracts.encodePushRequest(
            LedgerSyncPushRequestContract(deviceId, mutations.map { it.toContract() })
        ),
        { LedgerSyncJsonContracts.parsePushResponse(it).toDomain() }
    )

    override suspend fun pull(
        token: String,
        deviceId: String,
        cursor: Long
    ): LedgerSyncRemoteResult<LedgerSyncPullResult> = post(
        "/account/ledger-sync/pull",
        token,
        LedgerSyncJsonContracts.encodePullRequest(
            LedgerSyncPullRequestContract(deviceId, cursor, LEDGER_SYNC_MAX_BATCH_SIZE)
        ),
        { LedgerSyncJsonContracts.parsePullResponse(it).toDomain() }
    )

    override suspend fun resolve(
        token: String,
        conflictId: String,
        expectedVersion: Long,
        choice: LedgerSyncConflictChoice
    ): LedgerSyncRemoteResult<LedgerSyncRecord> = post(
        "/account/ledger-sync/conflicts/resolve",
        token,
        LedgerSyncJsonContracts.encodeResolveRequest(
            LedgerSyncResolveConflictRequestContract(conflictId, expectedVersion, choice.toContract())
        ),
        { LedgerSyncJsonContracts.parseResolveResponse(it).record.toDomain() }
    )

    private suspend fun <T> post(
        path: String,
        token: String,
        body: String,
        parser: (String) -> T
    ): LedgerSyncRemoteResult<T> {
        if (!available) {
            return LedgerSyncRemoteResult.Failure(
                code = "SYNC_TRANSPORT_UNAVAILABLE",
                message = "账户同步需要 HTTPS；局域网 HTTP 仅可通过本地测试开关启用",
                retryable = false
            )
        }
        return try {
            val response = withContext(Dispatchers.IO) { executePost("$baseUrl$path", token, body) }
            if (response.first in 200..299) {
                LedgerSyncRemoteResult.Success(parser(response.second))
            } else {
                val error = runCatching { AccountApiJsonContracts.parseErrorResponse(response.second) }.getOrNull()
                LedgerSyncRemoteResult.Failure(
                    code = error?.error,
                    message = error?.message ?: "同步服务返回异常",
                    retryable = response.first >= 500 || response.first == 429
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            LedgerSyncRemoteResult.Failure(null, "网络连接失败，同步将在联网后重试", true)
        } catch (_: RuntimeException) {
            LedgerSyncRemoteResult.Failure(null, "同步响应无法解析，请稍后重试", true)
        }
    }

    private fun executePost(url: String, token: String, body: String): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            status to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}
