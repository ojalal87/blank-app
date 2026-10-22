# Intelligence, scanner boundary and security
## Initial adapters
HaGeZi: DNS/domain tracking/threat lists; official https://github.com/hagezi/dns-blocklists . Respect exact license/version before redistribution.
URLhaus: malware distribution URL indicators; https://urlhaus.abuse.ch/api/ . Auth key required; fair-use/commercial conditions apply. A full malicious URL does not justify blocking a whole shared host/IP without an explicit broadened-scope policy.
Spamhaus DROP: IP/CIDR feed; https://www.spamhaus.org/blocklists/do-not-route-or-peer/ . Separate DROP terms from other Spamhaus DNSBL/RPZ products. Record attribution and current usage conditions.
Feeds are intelligence, not an antivirus engine or a calibrated malware probability. Default adapter catalog records license review pending and remains disabled until requirements are met. No bundled third-party dataset in this ZIP.

## Safe ingestion
Unprivileged fetcher. TLS verification, fixed approved origins/redirect policy, ETag/If-Modified-Since, bounded decompressed size, entry count, timeout and retry with jitter/Retry-After. Reject unsupported schemes/local/private fetch targets to prevent accidental SSRF in custom imports. URLhaus auth in endpoint is redacted from all logs and crash reports. Never embed shared commercial API secrets in APK.
Staging -> parse -> normalize IDNA/IP/CIDR -> validate/count/diff -> retain provenance -> activate immutable generation. A missing upstream digital signature must be labeled; SHA-256 alone proves no source authenticity. If operator publishes its own bundles, sign with pinned Ed25519 key plus key-rotation/revocation plan, expiry and monotonic generation; keep last known good. No remote executable scripts or arbitrary executable model objects. Feed failure leaves prior verified list active with visible age.
Do not silently replace a huge list change with empty protection. Large entry-count/range shifts go to review. Every list category has false-positive exception and TTL/review workflow. Refresh cadence per source policy; proposed app check 6 hours subject to provider limits, offline catch-up and battery scheduling. Membership deactivation is generation-based so stale source indicators cannot remain accidentally active.

## Matching
Local domain trie/suffix boundary checks; IP radix/prefix tree or sets supported by backend. Bloom filters can prefilter only; confirm exact match before blocking. Deduplicate value storage but preserve per-source generation/expiry. Cross-source confidence is not independent evidence when lists copy each other. No broad shared-IP auto-block from domain reputation. Full URL records used only with actual full URL observations/user-submitted link scans.

## Scanner extension
User selects APK/files or approved root scan scope. Scanner interface returns KNOWN_MALICIOUS / SUSPICIOUS / NO_KNOWN_MATCH / INCONCLUSIVE with engine/version/signature age and evidence. No scan engine supplied here. Hash reputation lookup opt-in; default no file upload. Directory scanner disallows symlink escape, special device nodes and unbounded recursion/archives; supports cancellation, size limits and read-only operations. Root file scanning, if later implemented, needs separate narrow capability, authenticated scope tokens and independent review; root helper v1 exposes no arbitrary file reads. Antivirus rules/signatures require license and engine compatibility review.

## Security review gates
IPC spoofing/replay, confused-deputy calls, command/path injection, parser resource exhaustion, malformed feed/IDNA, rollback tampering, database theft, logs containing secrets, unauthorized export, symlink/TOCTOU, insecure backups and incompatible root-manager components. Privileged helper never trusts UI/LLM strings. Recovery affects own rules only. Equal-root attacker limitation visible in product docs.
