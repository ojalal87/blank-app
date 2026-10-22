package com.rootguard.core.protocol

import com.rootguard.core.model.Ids
import com.rootguard.core.model.Phase
import com.rootguard.core.model.ProbeResult
import com.rootguard.core.policy.Plan
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Privileged helper protocol v1 (handoff contracts/IPC.md). Typed payloads only; unknown fields are rejected. */
object RgProtocol {
    const val VERSION = 1
    const val MAX_REQUEST_BYTES = 256 * 1024
    /** A request may not be valid for longer than this (bounded monotonic expiry). */
    const val MAX_TTL_MS = 60_000L

    val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = true
        isLenient = false
        allowSpecialFloatingPointValues = false
        classDiscriminator = "type"
    }
}

enum class Operation { PROBE, PREVIEW, APPLY, VERIFY, ROLLBACK, HEALTH, RECOVER_OWN_RULES, SUBSCRIBE_METADATA }

enum class ErrorCode {
    ROOT_DENIED, UNSUPPORTED_BACKEND, CAPABILITY_STALE, REVISION_CONFLICT, INVALID_PLAN, TOO_LARGE,
    EXPIRED_REQUEST, AUTH_FAILED, VERIFY_FAILED, ROLLBACK_FAILED, ATTRIBUTION_UNCERTAIN,
    UNSUPPORTED_PROTOCOL, MALFORMED, REPLAY, UNSUPPORTED_OPERATION, INTERNAL,
}

@Serializable
sealed interface Payload {
    @Serializable @SerialName("none") data object None : Payload
    @Serializable @SerialName("plan") data class PlanPayload(val transactionId: String, val plan: Plan) : Payload
    @Serializable @SerialName("verify") data class VerifyPayload(val revisionId: String, val expectedHash: String) : Payload
    @Serializable @SerialName("rollback") data class RollbackPayload(val transactionId: String) : Payload
}

@Serializable
data class Request(
    val protocolVersion: Int,
    val requestId: String,
    val operation: Operation,
    val bootId: String,
    val expiresElapsedMs: Long,
    val expectedRevision: String?,
    val payload: Payload,
)

@Serializable
data class FamilyHealth(val family: Int, val chainPresent: Boolean, val hookCount: Int, val hookFirst: Boolean, val ruleCount: Int, val hash: String, val unparsedCount: Int)

@Serializable
data class Health(
    val bootId: String,
    val families: List<FamilyHealth>,
    val foreignChains: List<String>,
    /** Revision the helper's durable journal says is installed (used as expectedRevision for recovery re-applies). */
    val journalRevision: String? = null,
) {
    fun hashOf(family: Int): String? = families.firstOrNull { it.family == family }?.hash
    val anyOwned: Boolean get() = families.any { it.chainPresent || it.hookCount > 0 }
}

@Serializable
data class Receipt(
    val transactionId: String?,
    val revisionId: String?,
    val verifiedHash: String?,
    val phase: Phase,
    val errorCode: ErrorCode? = null,
    /** Present when the helper restored the previous owned rules after a failure. */
    val compensated: Boolean = false,
    val health: Health? = null,
)

@Serializable
sealed interface Result {
    @Serializable @SerialName("probe") data class Probe(val probe: ProbeResult) : Result
    @Serializable @SerialName("receipt") data class ReceiptResult(val receipt: Receipt) : Result
    @Serializable @SerialName("health") data class HealthResult(val health: Health) : Result
    @Serializable @SerialName("valid") data object Valid : Result
}

@Serializable
data class Response(val requestId: String?, val ok: Boolean, val error: ErrorCode? = null, val result: Result? = null) {
    companion object {
        fun error(id: String?, code: ErrorCode) = Response(id, false, code, null)
    }
}

/**
 * Helper-side envelope checks done before any operation runs: size, decoding, version, identity, boot
 * binding, monotonic expiry and replay. Pure so it can be tested off-device.
 */
class RequestGate(private val bootId: String, private val cacheSize: Int = 256) {
    private val seen = object : LinkedHashMap<String, Response>(cacheSize, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Response>?) = size > cacheSize
    }

    sealed interface Outcome {
        data class Accept(val request: Request) : Outcome
        data class Reject(val response: Response) : Outcome
    }

    @Synchronized
    fun admit(raw: String, nowElapsedMs: Long): Outcome {
        if (raw.length > RgProtocol.MAX_REQUEST_BYTES || raw.toByteArray().size > RgProtocol.MAX_REQUEST_BYTES) return reject(null, ErrorCode.TOO_LARGE)
        val req = try {
            RgProtocol.json.decodeFromString(Request.serializer(), raw)
        } catch (_: Exception) {
            return reject(null, ErrorCode.MALFORMED)
        }
        if (req.protocolVersion != RgProtocol.VERSION) return reject(req.requestId, ErrorCode.UNSUPPORTED_PROTOCOL)
        if (!Ids.isUuid(req.requestId)) return reject(null, ErrorCode.MALFORMED)
        seen[req.requestId]?.let { return Outcome.Reject(it) } // replay returns the original outcome
        if (req.bootId != bootId) return reject(req.requestId, ErrorCode.CAPABILITY_STALE)
        if (req.expiresElapsedMs <= nowElapsedMs || req.expiresElapsedMs - nowElapsedMs > RgProtocol.MAX_TTL_MS) return reject(req.requestId, ErrorCode.EXPIRED_REQUEST)
        if (req.expectedRevision != null && !Ids.isSafeId(req.expectedRevision)) return reject(req.requestId, ErrorCode.MALFORMED)
        val payloadOk = when (req.operation) {
            Operation.PROBE, Operation.HEALTH, Operation.RECOVER_OWN_RULES, Operation.SUBSCRIBE_METADATA -> req.payload is Payload.None
            Operation.PREVIEW, Operation.APPLY -> (req.payload as? Payload.PlanPayload)?.let { Ids.isUuid(it.transactionId) } == true
            Operation.VERIFY -> (req.payload as? Payload.VerifyPayload)?.let { Ids.isSafeId(it.revisionId) && Regex("^[0-9a-f]{64}$").matches(it.expectedHash) } == true
            Operation.ROLLBACK -> (req.payload as? Payload.RollbackPayload)?.let { Ids.isUuid(it.transactionId) } == true
        }
        if (!payloadOk) return reject(req.requestId, ErrorCode.MALFORMED)
        return Outcome.Accept(req)
    }

    /** Records the outcome so a replay of the same request ID gets the same answer instead of re-running. */
    @Synchronized
    fun complete(requestId: String, response: Response) {
        seen[requestId] = response
    }

    private fun reject(id: String?, code: ErrorCode): Outcome = Outcome.Reject(Response.error(id, code))
}

fun Response.encode(): String = RgProtocol.json.encodeToString(Response.serializer(), this)
fun Request.encode(): String = RgProtocol.json.encodeToString(Request.serializer(), this)
fun decodeResponse(raw: String): Response? = try {
    RgProtocol.json.decodeFromString(Response.serializer(), raw)
} catch (_: Exception) {
    null
}

