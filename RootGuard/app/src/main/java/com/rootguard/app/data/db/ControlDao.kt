package com.rootguard.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.rootguard.core.model.Phase
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryDao {
    @Query("SELECT * FROM app_installation WHERE user_serial=:user AND removed_ms IS NULL")
    suspend fun activeInstallations(user: Long): List<AppInstallationEntity>

    @Insert
    suspend fun insertInstallation(e: AppInstallationEntity)

    @Query("UPDATE app_installation SET removed_ms=:at WHERE app_id=:appId AND removed_ms IS NULL")
    suspend fun markRemoved(appId: String, at: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVersion(e: AppVersionEntity)

    @Query("SELECT * FROM principal WHERE user_serial=:user AND valid_until_ms IS NULL")
    suspend fun activePrincipals(user: Long): List<PrincipalEntity>

    @Query("SELECT * FROM principal WHERE user_serial=:user AND valid_until_ms IS NULL")
    fun observeActivePrincipals(user: Long): Flow<List<PrincipalEntity>>

    @Insert
    suspend fun insertPrincipal(e: PrincipalEntity)

    @Query("UPDATE principal SET valid_until_ms=:at WHERE principal_id=:id AND valid_until_ms IS NULL")
    suspend fun closePrincipal(id: String, at: Long)

    @Query("UPDATE principal SET attribution=:attribution WHERE principal_id=:id")
    suspend fun setAttribution(id: String, attribution: com.rootguard.core.model.Attribution)

    @Query("SELECT * FROM principal_member WHERE valid_until_ms IS NULL")
    suspend fun activeMembers(): List<PrincipalMemberEntity>

    @Query("SELECT * FROM principal_member WHERE valid_until_ms IS NULL")
    fun observeActiveMembers(): Flow<List<PrincipalMemberEntity>>

    @Insert
    suspend fun insertMember(e: PrincipalMemberEntity)

    @Query("UPDATE principal_member SET valid_until_ms=:at WHERE principal_id=:principalId AND valid_until_ms IS NULL")
    suspend fun closeMembers(principalId: String, at: Long)

    @Query("UPDATE principal_member SET valid_until_ms=:at WHERE app_id=:appId AND valid_until_ms IS NULL")
    suspend fun closeMembership(appId: String, at: Long)

    @Query("SELECT * FROM app_installation WHERE removed_ms IS NULL")
    fun observeInstallations(): Flow<List<AppInstallationEntity>>
}

@Dao
interface PolicyDao {
    @Query("SELECT * FROM profile LIMIT 1")
    suspend fun profile(): ProfileEntity?

    @Upsert
    suspend fun upsertProfile(p: ProfileEntity)

    @Query("SELECT * FROM policy_revision WHERE revision_id=:id")
    suspend fun revision(id: String): PolicyRevisionEntity?

    @Insert
    suspend fun insertRevision(r: PolicyRevisionEntity)

    @Insert
    suspend fun insertRules(rules: List<PolicyRuleEntity>)

    @Transaction
    suspend fun insertRevisionWithRules(r: PolicyRevisionEntity, rules: List<PolicyRuleEntity>) {
        insertRevision(r)
        if (rules.isNotEmpty()) insertRules(rules)
    }

    @Query("SELECT * FROM runtime_state WHERE singleton=1")
    suspend fun runtime(): RuntimeStateEntity?

    @Query("SELECT * FROM runtime_state WHERE singleton=1")
    fun observeRuntime(): Flow<RuntimeStateEntity?>

    @Upsert
    suspend fun upsertRuntime(r: RuntimeStateEntity)

    @Insert
    suspend fun insertTransaction(t: ApplyTransactionEntity)

    @Query("UPDATE apply_transaction SET phase=:phase, updated_ms=:at, helper_receipt_json=:receipt, error_code=:error WHERE transaction_id=:id")
    suspend fun updateTransaction(id: String, phase: Phase, at: Long, receipt: String?, error: String?)

    @Query("SELECT * FROM apply_transaction WHERE phase IN ('PREPARED','APPLYING','VERIFIED') ORDER BY created_ms DESC LIMIT 1")
    suspend fun openTransaction(): ApplyTransactionEntity?

    @Query("SELECT * FROM apply_transaction ORDER BY created_ms DESC LIMIT 20")
    fun observeRecentTransactions(): Flow<List<ApplyTransactionEntity>>

    @Insert
    suspend fun insertCapability(c: CapabilitySnapshotEntity)

    @Query("SELECT * FROM capability_snapshot ORDER BY recorded_ms DESC LIMIT 1")
    suspend fun latestCapability(): CapabilitySnapshotEntity?

    @Insert
    suspend fun insertConsent(c: ConsentEntity)

    @Query("SELECT * FROM consent WHERE scope=:scope AND revoked_ms IS NULL ORDER BY granted_ms DESC LIMIT 1")
    suspend fun activeConsent(scope: String): ConsentEntity?

    @Query("SELECT * FROM consent WHERE scope=:scope AND revoked_ms IS NULL ORDER BY granted_ms DESC LIMIT 1")
    fun observeConsent(scope: String): Flow<ConsentEntity?>

    @Upsert
    suspend fun upsertDraft(d: WizardDraftEntity)

    @Query("SELECT * FROM wizard_draft WHERE draft_id=:id")
    suspend fun draft(id: String): WizardDraftEntity?

    @Insert
    suspend fun insertAudit(a: AuditEventEntity)

    @Query("SELECT * FROM audit_event ORDER BY time_ms DESC LIMIT :limit")
    fun observeAudit(limit: Int): Flow<List<AuditEventEntity>>
}
