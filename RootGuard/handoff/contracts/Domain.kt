package rootguard.contracts

// Reference domain contracts only; no Android/Room/root-library implementation.
// Use Kotlin UUID/opaque value wrappers and serialization validation in implementation.
enum class Support { SUPPORTED, UNSUPPORTED, DENIED, UNVERIFIED }
enum class EnforcementHealth { OFF, ACTIVE, DEGRADED, UNKNOWN }
enum class Mode { OBSERVE, BALANCED, PRIVACY, STRICT, DATA_SAVER, CUSTOM }
enum class Action { ALLOW, DENY }
enum class Attribution { EXACT, SHARED, UNKNOWN }
data class Capability(val name: String, val support: Support, val reason: String)
data class ProbeResult(val bootId: String, val probeVersion: String, val capabilities: List<Capability>)
data class Principal(val id: String, val userSerial: Long, val androidUid: Int,
                     val appIds: Set<String>, val attribution: Attribution)
data class Rule(val id: String, val principalId: String?, val action: Action,
                val network: String, val destinationType: String, val destinationValue: String?,
                val protocol: String, val portFrom: Int?, val portTo: Int?,
                val layer: String, val priority: Int, val expiresMs: Long?)
data class Policy(val revisionId: String, val parentRevisionId: String?,
                  val defaultAction: Action, val rules: List<Rule>)
data class Plan(val revisionId: String, val expectedRevision: String?, val canonicalHash: String,
                val compilerVersion: String, val capabilitySnapshotId: String,
                val operations: List<BackendOperation>)
// Opaque typed backend operations are compiled/validated locally; never a shell string.
sealed interface BackendOperation {
    data class ReplaceOwnedPolicy(val namespace: String, val ipFamily: Int,
                                 val rules: List<Rule>) : BackendOperation
}
data class Preview(val plan: Plan?, val warnings: List<String>, val unsupported: List<String>)
data class ApplyReceipt(val transactionId: String, val revisionId: String?,
                        val verifiedHash: String?, val phase: String, val errorCode: String?)
interface PolicyCompiler { fun preview(policy: Policy, probe: ProbeResult): Preview }
interface PrivilegedGateway {
    suspend fun probe(): ProbeResult
    suspend fun apply(transactionId: String, plan: Plan): ApplyReceipt
    suspend fun verify(revisionId: String): ApplyReceipt
    suspend fun rollback(transactionId: String): ApplyReceipt
    suspend fun recoverOwnRules(): ApplyReceipt
}
data class FeatureWindow(val id: String, val principalId: String, val contextKey: String,
                         val startMs: Long, val endMs: Long, val schemaVersion: Int,
                         val values: Map<String, Double?>, val completeness: Double)
data class Baseline(val snapshotId: String, val trainedThroughMs: Long,
                    val observations: Int, val featureSchemaVersion: Int)
data class Assessment(val windowId: String, val anomalyScore: Double?, val riskScore: Double?,
                      val dataConfidence: Double, val reasonCodes: List<String>,
                      val malwareProbability: Double? = null)
interface BehaviorEngine {
    // Must enforce historical causality and schema matching before scoring.
    fun score(window: FeatureWindow, baseline: Baseline?): Assessment
    // Separate step; caller must quarantine suspicious/incomplete data first.
    suspend fun learnAccepted(windowId: String, expectedSnapshotId: String?)
}
data class FeedMetadata(val sourceId: String, val generationId: String,
                        val expiresMs: Long?, val signatureStatus: String)
interface ThreatRepository {
    suspend fun matchObservedIp(canonicalAddress: ByteArray, nowMs: Long): List<FeedMetadata>
    suspend fun matchObservedDomain(domainAscii: String, nowMs: Long): List<FeedMetadata>
}
enum class ScanVerdict { KNOWN_MALICIOUS, SUSPICIOUS, NO_KNOWN_MATCH, INCONCLUSIVE }
data class ScanResult(val verdict: ScanVerdict, val engineVersion: String,
                      val signatureAgeMs: Long?, val reasons: List<String>)
interface ScannerPlugin { suspend fun scanApprovedArtifact(scopeToken: String): ScanResult }
