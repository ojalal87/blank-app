# Root firewall and recovery contract
## Backends
Start with capability-probed iptables/ip6tables implementation. nftables is a later optional adapter, enabled only when tested on that kernel/ROM. Own namespaced chains/sets (e.g. RG_*) and hooks only; never flush global OUTPUT/INPUT/FORWARD, alter netd-owned maps/chains, or overwrite another firewall. Detect conflicting rule managers and report coexistence limits. Android VPN/tether traffic may follow different paths: mark support independently.

## Policy model
Rule matches principal/network/destination/protocol/ports/time with AND semantics; null wildcard. Destination types: ANY, IP, CIDR, DOMAIN_SUFFIX. Domain rules require a resolver/mapping subsystem and may be approximate or unavailable with DoH/DoT/ECH/shared IPs; do not silently treat them as exact IP rules. Show uncovered encrypted DNS paths. LAN broadcast/mDNS, DHCP, captive portals and DNS/bootstrap flows need explicit scoped treatment. No broad implicit allow for UID 0, system apps, updater or DNS.

Precedence is explicit:
1. user-enabled emergency isolation, honoring only its displayed approved recovery exceptions;
2. approved expiring override (may override feed block only with explicit warning and scope);
3. enabled high-confidence threat deny rules;
4. explicit user rules (descending priority, deny wins ties; stable rule ID final tie-break);
5. selected profile default action.
Overrides of threat rules must be explicit. Reject unexpressible backend combinations during preview. Score engine cannot mutate precedence or allow rules. Established flows: choose and test whether drop requires conntrack revocation; no claim existing sessions terminate without verification. DNS connection reuse and QUIC need tests.

## Apply protocol
1. Acquire single-writer lock; validate expected current revision, app identity mappings, capability snapshot and policy limits.
2. Persist immutable compiled plan, previous verified plan and PREPARED journal with unique transaction ID.
3. Helper authenticates caller, checks plan version/replay, current revision, limits and own policy constraints; stage replacement chains/sets.
4. Switch hooks/sets in backend-supported atomic units. IPv4 and IPv6 are a coordinated transaction with compensation, NOT a single guaranteed atomic operation. If strict consistency requested and unsupported, reject or enter user-approved temporary isolation strategy.
5. Read back owned rules and canonical hash, test scoped connectivity and coverage. Move to VERIFIED then commit desired/applied revision.
6. On any failure restore previous owned plan and verify both families. If restoration fails, enter visible DEGRADED state and offer local recovery. Watchdog never removes a healthy last-known-good policy merely because a public connectivity check times out.

Crash after kernel change before DB commit: query helper's durable journal/revision/hash, reconcile deterministically; if inconsistent revert to previous verified plan. Persist sanitized recovery state before switching hooks. Boot reconciliation expires overrides, resolves identities and restores verified policy; no inherited rules on UID reassignment.

## Recovery UX
Selectable failure behavior explained at setup: preserve last-known-good default; strict fail-closed only after explicit choice and successful recovery test. A persistent local recovery action removes only RG-owned hooks and stops its helper, preserving Android/other rules. On-device authenticated recovery must work without Internet. Optional root-manager module has documented disable procedure and independent tested rollback; not implemented by this handoff.

## Helper attack surface
Typed operations only: PROBE, PREVIEW, APPLY, VERIFY, ROLLBACK, HEALTH, RECOVER_OWN_RULES, SUBSCRIBE_METADATA. No shell string/path/script from UI/network/LLM. Validate port ranges, addresses, UID assignments, limits and schema. Use authenticated Binder/root-library IPC with caller UID/package signer verification and protocol handshake; do not deploy unauthenticated localhost ports or world-writable UNIX sockets. Use binary argv or netlink/backend library; no string shell concatenation. Limit request size/time/concurrency. Requests carry ID, expected revision and bounded monotonic expiry. Replays return original outcome or reject. Helper has no feed downloader or arbitrary file-read endpoint.

Temporary exceptions use monotonic expiry during the current boot. Conservative v1 default: expire all temporary overrides at reboot. Never extend an override because wall time moved backward. Persisted scheduled user rules use explicit timezone policy and are revalidated separately.
