package com.rootguard.app.data

import androidx.room.withTransaction
import com.rootguard.app.data.db.ApplyTransactionEntity
import com.rootguard.app.data.db.AuditEventEntity
import com.rootguard.app.data.db.CapabilitySnapshotEntity
import com.rootguard.app.data.db.ConsentEntity
import com.rootguard.app.data.db.ControlDatabase
import com.rootguard.app.data.db.PolicyRevisionEntity
import com.rootguard.app.data.db.PolicyRuleEntity
import com.rootguard.app.data.db.ProfileEntity
import com.rootguard.app.data.db.RuntimeStateEntity
import com.rootguard.app.data.db.WizardDraftEntity
import com.rootguard.app.root.PrivilegedGateway
import com.rootguard.core.model.Action
import com.rootguard.core.model.BehaviorResponse
import com.rootguard.core.model.EnforcementHealth
import com.rootguard.core.model.FailureMode
import com.rootguard.core.model.Mode
import com.rootguard.core.model.NetworkScope
import com.rootguard.core.model.Phase
import com.rootguard.core.model.ProbeResult
import com.rootguard.core.policy.Plan
import com.rootguard.core.policy.Policy
import com.rootguard.core.policy.PolicyCompiler
import com.rootguard.core.policy.Preview
import com.rootguard.core.policy.PrincipalRef
import com.rootguard.core.policy.Rule
import com.rootguard.core.protocol.ErrorCode
import com.rootguard.core.protocol.Health
import com.rootguard.core.protocol.Operation
import com.rootguard.core.protocol.Payload
import com.rootguard.core.protocol.Receipt
import com.rootguard.core.protocol.Result
import com.rootguard.core.protocol.RgProtocol
import com.rootguard.core.txn.Decision
import com.rootguard.core.txn.Reconciler
import com.rootguard.core.txn.expectedFamilyHashes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.UUID

/** Networks the per-app editor exposes in v1. */
val EDITABLE_NETWORKS = listOf(NetworkScope.WIFI, NetworkScope.MOBILE, NetworkScope.VPN)

/** The unprivileged, editable draft. Saved in wizard_draft; never applied without preview. */
@Serializable
data class Draft(
    val mode: Mode = Mode.BALANCED,
    /** principalId -> network -> allowed. Missing entries use the mode default. */
    val overrides: Map<String, Map<NetworkScope, Boolean>> = emptyMap(),
) {
    val defaultAction: Action get() = if (mode == Mode.STRICT) Action.DENY else Action.ALLOW

    fun modeDefault(net: NetworkScope): Boolean = when (mode) {
        Mode.STRICT -> false
        Mode.DATA_SAVER -> net != NetworkScope.MOBILE
        else -> true
    }

    fun allowed(principalId: String, net: NetworkScope): Boolean = overrides[principalId]?.get(net) ?: modeDefault(net)

    /** Rules only where an app differs from the profile default action. */
    fun rules(principals: Collection<PrincipalView>): List<Rule> = principals.sortedBy { it.principalId }.flatMap { p ->
        EDITABLE_NETWORKS.mapNotNull { net ->
            val allow = allowed(p.principalId, net)
            when {
                defaultAction == Action.ALLOW && !allow -> Rule("u-${p.principalId}-${net.name}", p.principalId, Action.DENY, network = net)
                defaultAction == Action.DENY && allow -> Rule("u-${p.principalId}-${net.name}", p.principalId, Action.ALLOW, network = net)
                else -> null
            }
        }
    }
}

/** Stored in policy_revision.policy_json: the policy and the exact plan compiled for it. */
@Serializable
data class RevisionDocument(val policy: Policy, val plan: Plan)

data class ControllerState(
    val consented: Boolean = false,
    val probe: ProbeResult? = null,
    val capabilitySnapshotId: String? = null,
    val health: EnforcementHealth = EnforcementHealth.UNKNOWN,
    val reason: String? = null,
    val appliedRevision: String? = null,
    val lastHealth: Health? = null,
    val busy: Boolean = false,
    val lastError: ErrorCode? = null,
    val lastReceipt: Receipt? = null,
)

/**
 * Policy lifecycle: draft -> preview -> journaled apply -> helper verify -> commit, plus startup
 * reconciliation and local recovery. One writer at a time ([lock]); the DB commit is never assumed atomic
 * with the kernel change (see Reconciler).
 */
class FirewallController(
    private val db: ControlDatabase,
    private val gateway: PrivilegedGateway,
    private val inventory: InventoryRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(ControllerState())
    val state: StateFlow<ControllerState> = _state.asStateFlow()

    private val _draft = MutableStateFlow(Draft())
    val draft: StateFlow<Draft> = _draft.asStateFlow()

    private val _principals = MutableStateFlow<List<PrincipalView>>(emptyList())
    val principals: StateFlow<List<PrincipalView>> = _principals.asStateFlow()

    private val json = RgProtocol.json

    val audit: kotlinx.coroutines.flow.Flow<List<AuditEventEntity>> get() = db.policy().observeAudit(100)
    private val dao get() = db.policy()

    suspend fun load() {
        val consent = dao.activeConsent(CONSENT_SCOPE)
        dao.draft(DRAFT_ID)?.let { d -> runCatching { json.decodeFromString(Draft.serializer(), d.draftJson) }.getOrNull()?.let { _draft.value = it } }
        val rt = dao.runtime()
        _state.update { it.copy(consented = consent != null, appliedRevision = rt?.appliedRevision, health = rt?.health ?: EnforcementHealth.UNKNOWN) }
    }

    suspend fun recordConsent() {
        val now = clock()
        dao.insertConsent(ConsentEntity(UUID.randomUUID().toString(), CONSENT_SCOPE, """{"helper_ops":["PROBE","PREVIEW","APPLY","VERIFY","ROLLBACK","HEALTH","RECOVER_OWN_RULES"],"metadata":false,"files":false}""", now, null, CONSENT_VERSION))
        audit("user", "CONSENT_GRANTED", CONSENT_SCOPE, "{}")
        _state.update { it.copy(consented = true) }
    }

    suspend fun refreshInventory(): List<PrincipalView> = inventory.sync(clock()).also { _principals.value = it }

    /** Same reconciliation from an explicit package list (tests, and future package-change events). */
    suspend fun applyInventory(installed: List<InstalledPackage>): List<PrincipalView> = inventory.reconcile(installed, clock()).also { _principals.value = it }

    fun setMode(mode: Mode) = updateDraft { it.copy(mode = mode) }

    fun setAllowed(principalId: String, net: NetworkScope, allowed: Boolean) = updateDraft { d ->
        val per = d.overrides[principalId].orEmpty().toMutableMap()
        if (allowed == d.modeDefault(net)) per.remove(net) else per[net] = allowed
        d.copy(overrides = (d.overrides - principalId) + if (per.isEmpty()) emptyMap() else mapOf(principalId to per))
    }

    private fun updateDraft(f: (Draft) -> Draft) {
        _draft.value = f(_draft.value)
    }

    suspend fun saveDraft(step: String) {
        dao.upsertDraft(WizardDraftEntity(DRAFT_ID, step, json.encodeToString(Draft.serializer(), _draft.value), clock()))
    }

    /** Connects to the helper (asks for root) and records a capability snapshot for this boot. */
    suspend fun probe(): ProbeResult? = lock.withLock { probeLocked() }

    private suspend fun probeLocked(): ProbeResult? {
        if (!gateway.connect()) {
            _state.update { it.copy(lastError = ErrorCode.ROOT_DENIED, probe = null) }
            return null
        }
        val r = gateway.request(Operation.PROBE)
        val probe = (r.result as? Result.Probe)?.probe
        if (probe == null) {
            _state.update { it.copy(lastError = r.error ?: ErrorCode.INTERNAL) }
            return null
        }
        val id = UUID.randomUUID().toString()
        dao.insertCapability(CapabilitySnapshotEntity(id, probe.bootId, clock(), probe.probeVersion, json.encodeToString(ProbeResult.serializer(), probe)))
        _state.update { it.copy(probe = probe, capabilitySnapshotId = id, lastError = null) }
        return probe
    }

    private fun refs(): List<PrincipalRef> = _principals.value.map { PrincipalRef(it.principalId, inventory.userSerial, it.uid, it.attribution, it.packages.map { p -> p.packageName }) }

    fun buildPolicy(revisionId: String, parent: String?): Policy {
        val d = _draft.value
        return Policy(revisionId, parent, PROFILE_ID, d.mode, d.defaultAction, inventory.androidUserId, false, d.rules(_principals.value))
    }

    fun preview(): Preview? {
        val s = _state.value
        val probe = s.probe ?: return null
        return PolicyCompiler.preview(buildPolicy(PREVIEW_REVISION, s.appliedRevision), refs(), probe, s.capabilitySnapshotId ?: "none", s.appliedRevision, clock())
    }

    /** Journaled apply. Returns the helper receipt; the DB is updated to match what the kernel actually has. */
    suspend fun apply(): Receipt? = lock.withLock {
        val s = _state.value
        val probe = s.probe ?: return@withLock null
        _state.update { it.copy(busy = true, lastError = null) }
        try {
            val revisionId = "rev-" + UUID.randomUUID().toString()
            val policy = buildPolicy(revisionId, s.appliedRevision)
            val preview = PolicyCompiler.preview(policy, refs(), probe, s.capabilitySnapshotId ?: "none", s.appliedRevision, clock())
            val plan = preview.plan ?: run {
                _state.update { it.copy(lastError = ErrorCode.INVALID_PLAN) }
                return@withLock null
            }
            val now = clock()
            val txId = UUID.randomUUID().toString()
            db.withTransaction {
                ensureProfile(policy)
                dao.insertRevisionWithRules(
                    PolicyRevisionEntity(revisionId, PROFILE_ID, s.appliedRevision, now, json.encodeToString(RevisionDocument.serializer(), RevisionDocument(policy, plan)), plan.canonicalHash, PolicyCompiler.VERSION),
                    policy.rules.map { r -> PolicyRuleEntity(r.id, revisionId, r.principalId, r.action, r.layer, r.network, r.destinationType, r.destinationValue, r.protocol, r.portFrom, r.portTo, r.priority, r.enabled, null, r.expiresMs) },
                )
                dao.insertTransaction(ApplyTransactionEntity(txId, s.appliedRevision, revisionId, Phase.PREPARED, now, now, null, null))
                val rt = dao.runtime()
                dao.upsertRuntime(RuntimeStateEntity(1, revisionId, rt?.appliedRevision, rt?.verifiedHash, rt?.health ?: EnforcementHealth.UNKNOWN, now))
            }
            dao.updateTransaction(txId, Phase.APPLYING, clock(), null, null)
            val resp = gateway.request(Operation.APPLY, Payload.PlanPayload(txId, plan), s.appliedRevision)
            val receipt = (resp.result as? Result.ReceiptResult)?.receipt
            val receiptJson = receipt?.let { json.encodeToString(Receipt.serializer(), it) }
            if (resp.ok && receipt?.phase == Phase.VERIFIED && receipt.verifiedHash == plan.canonicalHash) {
                db.withTransaction {
                    dao.updateTransaction(txId, Phase.VERIFIED, clock(), receiptJson, null)
                    dao.updateTransaction(txId, Phase.COMMITTED, clock(), receiptJson, null)
                    dao.upsertRuntime(RuntimeStateEntity(1, revisionId, revisionId, plan.canonicalHash, EnforcementHealth.ACTIVE, clock()))
                }
                audit("user", "POLICY_APPLIED", revisionId, """{"from":${jsonStr(s.appliedRevision)},"hash":"${plan.canonicalHash}","rules":${policy.rules.size}}""")
                _state.update { it.copy(health = EnforcementHealth.ACTIVE, reason = "VERIFIED", appliedRevision = revisionId, lastHealth = receipt.health, lastReceipt = receipt) }
            } else {
                val phase = if (receipt?.phase == Phase.ROLLED_BACK) Phase.ROLLED_BACK else Phase.FAILED
                dao.updateTransaction(txId, phase, clock(), receiptJson, (resp.error ?: receipt?.errorCode ?: ErrorCode.INTERNAL).name)
                audit("helper", "POLICY_APPLY_FAILED", revisionId, """{"error":"${resp.error ?: receipt?.errorCode}","compensated":${receipt?.compensated == true}}""")
                _state.update { it.copy(lastError = resp.error ?: receipt?.errorCode ?: ErrorCode.INTERNAL, lastReceipt = receipt) }
                reconcileLocked()
            }
            receipt
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun ensureProfile(policy: Policy) {
        dao.upsertProfile(ProfileEntity(PROFILE_ID, "Default", policy.mode, policy.defaultAction, BehaviorResponse.LOG, FailureMode.PRESERVE_LAST_GOOD, dao.profile()?.createdMs ?: clock()))
    }

    /** Startup/after-failure reconciliation with what the kernel actually has. */
    suspend fun reconcile() = lock.withLock { reconcileLocked() }

    private suspend fun reconcileLocked() {
        if (_state.value.probe == null && probeLocked() == null) return
        val resp = gateway.request(Operation.HEALTH)
        val health = (resp.result as? Result.HealthResult)?.health ?: run {
            _state.update { it.copy(health = EnforcementHealth.UNKNOWN, reason = "HELPER_UNAVAILABLE") }
            return
        }
        val rt = dao.runtime()
        val open = dao.openTransaction()
        suspend fun hashes(rev: String?): Map<Int, String>? {
            val e = rev?.let { dao.revision(it) } ?: return null
            val plan = runCatching { json.decodeFromString(RevisionDocument.serializer(), e.policyJson).plan }.getOrNull() ?: return null
            return expectedFamilyHashes(plan.chains)
        }
        val target = open?.let { hashes(it.toRevision) }
        val previous = open?.let { hashes(it.fromRevision) }
        val applied = hashes(rt?.appliedRevision)
        when (val d = Reconciler.decide(open?.phase, target, previous, applied, health)) {
            Decision.CommitOpen -> {
                val tx = open!!
                db.withTransaction {
                    dao.updateTransaction(tx.transactionId, Phase.COMMITTED, clock(), null, null)
                    dao.upsertRuntime(RuntimeStateEntity(1, tx.toRevision, tx.toRevision, dao.revision(tx.toRevision)?.canonicalSha256, EnforcementHealth.ACTIVE, clock()))
                }
                audit("system", "RECONCILE_COMMITTED", tx.transactionId, "{}")
                setSteady(EnforcementHealth.ACTIVE, "RECOVERED_COMMIT", tx.toRevision, health)
            }
            Decision.MarkOpenRolledBack -> {
                dao.updateTransaction(open!!.transactionId, Phase.ROLLED_BACK, clock(), null, "NOT_APPLIED")
                audit("system", "RECONCILE_ROLLED_BACK", open.transactionId, "{}")
                reconcileSteady(rt?.appliedRevision, applied, health)
            }
            Decision.RestorePrevious -> {
                val tx = open!!
                val prevPlan = tx.fromRevision?.let { dao.revision(it) }?.let { json.decodeFromString(RevisionDocument.serializer(), it.policyJson).plan }
                val ok = if (prevPlan != null) {
                    val r = gateway.request(Operation.APPLY, Payload.PlanPayload(UUID.randomUUID().toString(), prevPlan), health.journalRevision)
                    r.ok
                } else {
                    gateway.request(Operation.RECOVER_OWN_RULES).ok
                }
                dao.updateTransaction(tx.transactionId, Phase.ROLLED_BACK, clock(), null, "RESTORED_PREVIOUS")
                audit("system", "RECONCILE_RESTORED_PREVIOUS", tx.transactionId, """{"ok":$ok}""")
                val h2 = (gateway.request(Operation.HEALTH).result as? Result.HealthResult)?.health ?: health
                if (ok) reconcileSteady(rt?.appliedRevision, applied, h2) else setSteady(EnforcementHealth.DEGRADED, "RESTORE_FAILED", rt?.appliedRevision, h2)
            }
            is Decision.Steady -> setSteady(d.health, d.reason, rt?.appliedRevision, health)
        }
    }

    private suspend fun reconcileSteady(applied: String?, appliedHashes: Map<Int, String>?, health: Health) {
        val d = Reconciler.decide(null, null, null, appliedHashes, health) as Decision.Steady
        setSteady(d.health, d.reason, applied, health)
    }

    private suspend fun setSteady(h: EnforcementHealth, reason: String, applied: String?, health: Health) {
        val rt = dao.runtime()
        dao.upsertRuntime(RuntimeStateEntity(1, rt?.desiredRevision ?: applied, applied, rt?.verifiedHash, h, clock()))
        _state.update { it.copy(health = h, reason = reason, appliedRevision = applied, lastHealth = health) }
    }

    /** Re-applies the last verified revision (e.g. after a reboot cleared the kernel rules). */
    suspend fun reapplyLastGood(): Boolean = lock.withLock {
        val rev = dao.runtime()?.appliedRevision ?: return@withLock false
        val doc = dao.revision(rev)?.let { json.decodeFromString(RevisionDocument.serializer(), it.policyJson) } ?: return@withLock false
        if (_state.value.probe == null && probeLocked() == null) return@withLock false
        val journalRev = (gateway.request(Operation.HEALTH).result as? Result.HealthResult)?.health?.journalRevision
        val r = gateway.request(Operation.APPLY, Payload.PlanPayload(UUID.randomUUID().toString(), doc.plan), journalRev)
        audit("user", "REAPPLY_LAST_GOOD", rev, """{"ok":${r.ok}}""")
        reconcileLocked()
        r.ok
    }

    /** Local recovery: removes only RootGuard's own rules. Works offline; Android's rules stay. */
    suspend fun recover(): Boolean = lock.withLock {
        if (!gateway.connect()) return@withLock false
        val r = gateway.request(Operation.RECOVER_OWN_RULES)
        val receipt = (r.result as? Result.ReceiptResult)?.receipt
        audit("user", "RECOVER_OWN_RULES", null, """{"ok":${r.ok}}""")
        if (r.ok) {
            val rt = dao.runtime()
            dao.upsertRuntime(RuntimeStateEntity(1, rt?.desiredRevision, null, null, EnforcementHealth.OFF, clock()))
            _state.update { it.copy(health = EnforcementHealth.OFF, reason = "RECOVERED", appliedRevision = null, lastHealth = receipt?.health) }
        } else {
            _state.update { it.copy(health = EnforcementHealth.DEGRADED, reason = "RECOVERY_FAILED", lastError = r.error) }
        }
        r.ok
    }

    private suspend fun audit(actor: String, type: String, subject: String?, details: String) {
        dao.insertAudit(AuditEventEntity(UUID.randomUUID().toString(), clock(), actor, type, subject, details))
    }

    private fun jsonStr(s: String?) = if (s == null) "null" else "\"$s\""

    companion object {
        const val CONSENT_SCOPE = "ROOT_FIREWALL"
        const val CONSENT_VERSION = "consent-1"
        const val PROFILE_ID = "profile-default"
        const val DRAFT_ID = "draft-main"
        const val PREVIEW_REVISION = "rev-preview"
    }
}
