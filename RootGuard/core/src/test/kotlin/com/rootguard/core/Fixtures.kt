package com.rootguard.core

import com.rootguard.core.model.Action
import com.rootguard.core.model.Attribution
import com.rootguard.core.model.Capability
import com.rootguard.core.model.CapabilityId
import com.rootguard.core.model.Mode
import com.rootguard.core.model.ProbeResult
import com.rootguard.core.model.Support
import com.rootguard.core.policy.Policy
import com.rootguard.core.policy.PrincipalRef
import com.rootguard.core.policy.Rule

object Fx {
    fun probe(v6: Boolean = true, vararg missing: CapabilityId) = ProbeResult(
        bootId = "boot-1",
        probeVersion = "probe-1",
        recordedMs = 1,
        capabilities = CapabilityId.entries.map { id ->
            val sup = id !in missing && (v6 || id !in setOf(CapabilityId.IPTABLES_V6, CapabilityId.OWNER_MATCH_V6, CapabilityId.RESTORE_NOFLUSH_V6, CapabilityId.REJECT_V6))
            Capability(id, if (sup) Support.SUPPORTED else Support.UNSUPPORTED, "fixture")
        },
        wifiInterfaces = listOf("wlan+"),
        mobileInterfaces = listOf("rmnet_data+", "ccmni+"),
    )

    val browser = PrincipalRef("p-browser", 0, 10123, Attribution.EXACT, listOf("org.example.browser"))
    val shared = PrincipalRef("p-shared", 0, 10200, Attribution.SHARED, listOf("com.vendor.a", "com.vendor.b"))
    val principals = listOf(browser, shared)

    fun policy(vararg rules: Rule, default: Action = Action.ALLOW, emergency: Boolean = false) =
        Policy("rev-2", "rev-1", "prof", Mode.CUSTOM, default, 0, emergency, rules.toList())
}
