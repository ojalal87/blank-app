package com.rootguard.app.ui

import com.rootguard.app.R
import com.rootguard.core.model.CapabilityId
import com.rootguard.core.model.EnforcementHealth
import com.rootguard.core.model.Support
import com.rootguard.core.policy.PreviewNote
import com.rootguard.core.protocol.ErrorCode

object Labels {
    fun health(h: EnforcementHealth) = when (h) {
        EnforcementHealth.ACTIVE -> R.string.health_active
        EnforcementHealth.DEGRADED -> R.string.health_degraded
        EnforcementHealth.OFF -> R.string.health_off
        EnforcementHealth.UNKNOWN -> R.string.health_unknown
    }

    fun reason(r: String?) = when (r) {
        "VERIFIED" -> R.string.reason_verified
        "HOOK_NOT_FIRST" -> R.string.reason_hook_not_first
        "KERNEL_RULES_ABSENT" -> R.string.reason_rules_absent
        "DRIFT" -> R.string.reason_drift
        "NO_POLICY" -> R.string.reason_no_policy
        "UNEXPECTED_OWNED_RULES" -> R.string.reason_unexpected
        "RECOVERED" -> R.string.reason_recovered
        "RECOVERY_FAILED" -> R.string.reason_recovery_failed
        "RESTORE_FAILED" -> R.string.reason_restore_failed
        "RECOVERED_COMMIT" -> R.string.reason_recovered_commit
        "HELPER_UNAVAILABLE" -> R.string.reason_helper_unavailable
        else -> R.string.reason_none
    }

    fun support(s: Support) = when (s) {
        Support.SUPPORTED -> R.string.support_supported
        Support.UNSUPPORTED -> R.string.support_unsupported
        Support.DENIED -> R.string.support_denied
        Support.UNVERIFIED -> R.string.support_unverified
    }

    fun capability(c: CapabilityId) = when (c) {
        CapabilityId.ROOT -> R.string.cap_root
        CapabilityId.SELINUX_ENFORCING -> R.string.cap_selinux
        CapabilityId.IPTABLES_V4 -> R.string.cap_iptables_v4
        CapabilityId.IPTABLES_V6 -> R.string.cap_iptables_v6
        CapabilityId.RESTORE_NOFLUSH_V4 -> R.string.cap_restore_v4
        CapabilityId.RESTORE_NOFLUSH_V6 -> R.string.cap_restore_v6
        CapabilityId.XTABLES_WAIT -> R.string.cap_wait
        CapabilityId.OWNER_MATCH_V4 -> R.string.cap_owner_v4
        CapabilityId.OWNER_MATCH_V6 -> R.string.cap_owner_v6
        CapabilityId.REJECT_V4 -> R.string.cap_reject_v4
        CapabilityId.REJECT_V6 -> R.string.cap_reject_v6
        CapabilityId.WIFI_INTERFACES -> R.string.cap_wifi
        CapabilityId.MOBILE_INTERFACES -> R.string.cap_mobile
        CapabilityId.VPN_PATH -> R.string.cap_vpn
        CapabilityId.LAN_SCOPE -> R.string.cap_lan
        CapabilityId.TETHER_FORWARD -> R.string.cap_tether
        CapabilityId.DOMAIN_RULES -> R.string.cap_domain
        CapabilityId.NFTABLES -> R.string.cap_nft
        CapabilityId.PER_FLOW_EBPF -> R.string.cap_ebpf
        CapabilityId.FLOW_METADATA -> R.string.cap_metadata
        CapabilityId.NO_CONFLICTING_FIREWALL -> R.string.cap_conflict
    }

    fun note(n: PreviewNote) = when (n) {
        PreviewNote.SHARED_UID -> R.string.note_shared_uid
        PreviewNote.RULE_AFFECTS_ALL_UIDS -> R.string.note_all_uids
        PreviewNote.DEFAULT_DENY_SCOPE -> R.string.note_default_deny
        PreviewNote.ISOLATED_AND_SANDBOX_UIDS_NOT_COVERED -> R.string.note_isolated
        PreviewNote.DNS_VIA_SYSTEM_RESOLVER -> R.string.note_dns
        PreviewNote.ENCRYPTED_DNS_NOT_VISIBLE -> R.string.note_encrypted_dns
        PreviewNote.VPN_PATH_UNVERIFIED -> R.string.note_vpn
        PreviewNote.IPV6_NOT_ENFORCED -> R.string.note_ipv6
        PreviewNote.OVERRIDE_BEATS_THREAT -> R.string.note_override
        PreviewNote.EXPIRED_RULE_SKIPPED -> R.string.note_expired
        PreviewNote.CONFLICTING_FIREWALL -> R.string.note_conflict
        PreviewNote.EXISTING_CONNECTIONS_UNVERIFIED -> R.string.note_existing
        PreviewNote.LOOPBACK_ALWAYS_ALLOWED -> R.string.note_loopback
        PreviewNote.EMERGENCY_ISOLATION_ACTIVE -> R.string.note_emergency
        PreviewNote.DOMAIN_RULES_UNSUPPORTED -> R.string.note_domain_unsupported
        PreviewNote.TETHER_UNSUPPORTED -> R.string.note_tether_unsupported
        PreviewNote.LAN_WITH_DESTINATION_UNSUPPORTED -> R.string.note_lan_dest
        PreviewNote.NO_WIFI_INTERFACES -> R.string.note_no_wifi
        PreviewNote.NO_MOBILE_INTERFACES -> R.string.note_no_mobile
        PreviewNote.OWNER_MATCH_UNAVAILABLE -> R.string.note_no_owner
        PreviewNote.IPV6_REQUIRED_FOR_DENY_DEFAULT -> R.string.note_ipv6_required
        PreviewNote.INVALID_POLICY -> R.string.note_invalid
        PreviewNote.NOT_ROOT -> R.string.note_not_root
    }

    fun error(e: ErrorCode?) = when (e) {
        null -> R.string.err_none
        ErrorCode.ROOT_DENIED -> R.string.err_root_denied
        ErrorCode.UNSUPPORTED_BACKEND -> R.string.err_backend
        ErrorCode.CAPABILITY_STALE -> R.string.err_stale
        ErrorCode.REVISION_CONFLICT -> R.string.err_conflict
        ErrorCode.INVALID_PLAN -> R.string.err_invalid_plan
        ErrorCode.VERIFY_FAILED -> R.string.err_verify
        ErrorCode.ROLLBACK_FAILED -> R.string.err_rollback
        ErrorCode.AUTH_FAILED -> R.string.err_auth
        ErrorCode.UNSUPPORTED_PROTOCOL -> R.string.err_protocol
        else -> R.string.err_internal
    }
}
