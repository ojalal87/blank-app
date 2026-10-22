package com.rootguard.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.rootguard.core.model.Action
import com.rootguard.core.model.Attribution
import com.rootguard.core.model.BehaviorResponse
import com.rootguard.core.model.DestinationType
import com.rootguard.core.model.EnforcementHealth
import com.rootguard.core.model.FailureMode
import com.rootguard.core.model.Layer
import com.rootguard.core.model.Mode
import com.rootguard.core.model.NetworkScope
import com.rootguard.core.model.Phase
import com.rootguard.core.model.Protocol

// Room mapping of handoff/database/control_v1.sql (authoritative logical v1 schema). Table and column names
// are identical. CHECK constraints and partial unique indexes, which Room cannot declare, are enforced by
// the triggers in ControlSchema; ControlSchemaTest proves equivalence against the v1 fixture.

@Entity(
    tableName = "app_installation",
    indices = [Index(value = ["user_serial", "package_name", "install_generation"], unique = true)],
)
data class AppInstallationEntity(
    @PrimaryKey @ColumnInfo(name = "app_id") val appId: String,
    @ColumnInfo(name = "user_serial") val userSerial: Long,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "install_generation") val installGeneration: String,
    @ColumnInfo(name = "signer_lineage_sha256") val signerLineageSha256: String,
    @ColumnInfo(name = "installed_ms") val installedMs: Long,
    @ColumnInfo(name = "removed_ms") val removedMs: Long?,
)

@Entity(
    tableName = "app_version",
    primaryKeys = ["app_id", "observed_ms"],
    foreignKeys = [ForeignKey(entity = AppInstallationEntity::class, parentColumns = ["app_id"], childColumns = ["app_id"])],
)
data class AppVersionEntity(
    @ColumnInfo(name = "app_id") val appId: String,
    @ColumnInfo(name = "version_code") val versionCode: Long,
    @ColumnInfo(name = "observed_ms") val observedMs: Long,
    @ColumnInfo(name = "signer_sha256") val signerSha256: String,
    @ColumnInfo(name = "permissions_json") val permissionsJson: String,
)

@Entity(tableName = "principal")
data class PrincipalEntity(
    @PrimaryKey @ColumnInfo(name = "principal_id") val principalId: String,
    @ColumnInfo(name = "user_serial") val userSerial: Long,
    @ColumnInfo(name = "android_uid") val androidUid: Int,
    @ColumnInfo(name = "valid_from_ms") val validFromMs: Long,
    @ColumnInfo(name = "valid_until_ms") val validUntilMs: Long?,
    @ColumnInfo(name = "attribution") val attribution: Attribution,
)

@Entity(
    tableName = "principal_member",
    primaryKeys = ["principal_id", "app_id", "valid_from_ms"],
    foreignKeys = [
        ForeignKey(entity = PrincipalEntity::class, parentColumns = ["principal_id"], childColumns = ["principal_id"]),
        ForeignKey(entity = AppInstallationEntity::class, parentColumns = ["app_id"], childColumns = ["app_id"]),
    ],
    indices = [Index("app_id")],
)
data class PrincipalMemberEntity(
    @ColumnInfo(name = "principal_id") val principalId: String,
    @ColumnInfo(name = "app_id") val appId: String,
    @ColumnInfo(name = "valid_from_ms") val validFromMs: Long,
    @ColumnInfo(name = "valid_until_ms") val validUntilMs: Long?,
)

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "mode") val mode: Mode,
    @ColumnInfo(name = "default_action") val defaultAction: Action,
    @ColumnInfo(name = "behavior_response") val behaviorResponse: BehaviorResponse,
    @ColumnInfo(name = "failure_mode") val failureMode: FailureMode,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
)

@Entity(
    tableName = "policy_revision",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["profile_id"], childColumns = ["profile_id"]),
        ForeignKey(entity = PolicyRevisionEntity::class, parentColumns = ["revision_id"], childColumns = ["parent_revision_id"]),
    ],
    indices = [Index("profile_id"), Index("parent_revision_id")],
)
data class PolicyRevisionEntity(
    @PrimaryKey @ColumnInfo(name = "revision_id") val revisionId: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "parent_revision_id") val parentRevisionId: String?,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
    /** RevisionDocument JSON: the policy plus the exact plan compiled for it. */
    @ColumnInfo(name = "policy_json") val policyJson: String,
    @ColumnInfo(name = "canonical_sha256") val canonicalSha256: String,
    @ColumnInfo(name = "compiler_version") val compilerVersion: String,
)

@Entity(
    tableName = "policy_rule",
    primaryKeys = ["revision_id", "rule_id"],
    foreignKeys = [
        ForeignKey(entity = PolicyRevisionEntity::class, parentColumns = ["revision_id"], childColumns = ["revision_id"]),
        ForeignKey(entity = PrincipalEntity::class, parentColumns = ["principal_id"], childColumns = ["principal_id"]),
    ],
    indices = [Index(value = ["principal_id", "revision_id"], name = "rules_by_principal")],
)
data class PolicyRuleEntity(
    @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "revision_id") val revisionId: String,
    @ColumnInfo(name = "principal_id") val principalId: String?,
    @ColumnInfo(name = "action") val action: Action,
    @ColumnInfo(name = "layer") val layer: Layer,
    @ColumnInfo(name = "network") val network: NetworkScope,
    @ColumnInfo(name = "destination_type") val destinationType: DestinationType,
    @ColumnInfo(name = "destination_value") val destinationValue: String?,
    @ColumnInfo(name = "protocol") val protocol: Protocol,
    @ColumnInfo(name = "port_from") val portFrom: Int?,
    @ColumnInfo(name = "port_to") val portTo: Int?,
    @ColumnInfo(name = "priority", defaultValue = "0") val priority: Int,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "schedule_json") val scheduleJson: String?,
    @ColumnInfo(name = "expires_ms") val expiresMs: Long?,
)

@Entity(
    tableName = "runtime_state",
    foreignKeys = [
        ForeignKey(entity = PolicyRevisionEntity::class, parentColumns = ["revision_id"], childColumns = ["desired_revision"]),
        ForeignKey(entity = PolicyRevisionEntity::class, parentColumns = ["revision_id"], childColumns = ["applied_revision"]),
    ],
    indices = [Index("desired_revision"), Index("applied_revision")],
)
data class RuntimeStateEntity(
    @PrimaryKey @ColumnInfo(name = "singleton") val singleton: Int = 1,
    @ColumnInfo(name = "desired_revision") val desiredRevision: String?,
    @ColumnInfo(name = "applied_revision") val appliedRevision: String?,
    @ColumnInfo(name = "verified_hash") val verifiedHash: String?,
    @ColumnInfo(name = "health") val health: EnforcementHealth,
    @ColumnInfo(name = "updated_ms") val updatedMs: Long,
)

@Entity(
    tableName = "apply_transaction",
    foreignKeys = [
        ForeignKey(entity = PolicyRevisionEntity::class, parentColumns = ["revision_id"], childColumns = ["from_revision"]),
        ForeignKey(entity = PolicyRevisionEntity::class, parentColumns = ["revision_id"], childColumns = ["to_revision"]),
    ],
    indices = [Index("from_revision"), Index("to_revision")],
)
data class ApplyTransactionEntity(
    @PrimaryKey @ColumnInfo(name = "transaction_id") val transactionId: String,
    @ColumnInfo(name = "from_revision") val fromRevision: String?,
    @ColumnInfo(name = "to_revision") val toRevision: String,
    @ColumnInfo(name = "phase") val phase: Phase,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
    @ColumnInfo(name = "updated_ms") val updatedMs: Long,
    @ColumnInfo(name = "helper_receipt_json") val helperReceiptJson: String?,
    @ColumnInfo(name = "error_code") val errorCode: String?,
)

@Entity(
    tableName = "temporary_override",
    foreignKeys = [
        ForeignKey(entity = PolicyRevisionEntity::class, parentColumns = ["revision_id"], childColumns = ["revision_id"]),
        ForeignKey(entity = PolicyRuleEntity::class, parentColumns = ["revision_id", "rule_id"], childColumns = ["revision_id", "rule_id"]),
    ],
    indices = [Index(value = ["revision_id", "rule_id"])],
)
data class TemporaryOverrideEntity(
    @PrimaryKey @ColumnInfo(name = "override_id") val overrideId: String,
    @ColumnInfo(name = "revision_id") val revisionId: String,
    @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
    @ColumnInfo(name = "expires_ms") val expiresMs: Long,
    @ColumnInfo(name = "boot_id") val bootId: String,
    @ColumnInfo(name = "expires_elapsed_ms") val expiresElapsedMs: Long,
    @ColumnInfo(name = "overrides_threat") val overridesThreat: Boolean,
)

@Entity(tableName = "capability_snapshot")
data class CapabilitySnapshotEntity(
    @PrimaryKey @ColumnInfo(name = "snapshot_id") val snapshotId: String,
    @ColumnInfo(name = "boot_id") val bootId: String,
    @ColumnInfo(name = "recorded_ms") val recordedMs: Long,
    @ColumnInfo(name = "probe_version") val probeVersion: String,
    @ColumnInfo(name = "capabilities_json") val capabilitiesJson: String,
)

@Entity(tableName = "consent")
data class ConsentEntity(
    @PrimaryKey @ColumnInfo(name = "consent_id") val consentId: String,
    @ColumnInfo(name = "scope") val scope: String,
    @ColumnInfo(name = "scope_json") val scopeJson: String,
    @ColumnInfo(name = "granted_ms") val grantedMs: Long,
    @ColumnInfo(name = "revoked_ms") val revokedMs: Long?,
    @ColumnInfo(name = "policy_version") val policyVersion: String,
)

@Entity(tableName = "wizard_draft")
data class WizardDraftEntity(
    @PrimaryKey @ColumnInfo(name = "draft_id") val draftId: String,
    @ColumnInfo(name = "step") val step: String,
    @ColumnInfo(name = "draft_json") val draftJson: String,
    @ColumnInfo(name = "updated_ms") val updatedMs: Long,
)

@Entity(tableName = "audit_event", indices = [Index(value = ["time_ms"], name = "audit_by_time")])
data class AuditEventEntity(
    @PrimaryKey @ColumnInfo(name = "audit_id") val auditId: String,
    @ColumnInfo(name = "time_ms") val timeMs: Long,
    @ColumnInfo(name = "actor") val actor: String,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "subject_id") val subjectId: String?,
    @ColumnInfo(name = "details_json") val detailsJson: String,
)

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey @ColumnInfo(name = "job_id") val jobId: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "attempts", defaultValue = "0") val attempts: Int,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
    @ColumnInfo(name = "next_attempt_ms") val nextAttemptMs: Long,
)
