# Sources and decisions — reviewed 2026-10-22
Primary technical references; recheck versions, terms and device-specific behavior before implementation.
- Android eBPF traffic accounting: https://source.android.com/docs/core/data/ebpf-traffic-monitor
- Android SELinux: https://source.android.com/docs/security/features/selinux
- Room migration and exported-schema tests: https://developer.android.com/training/data-storage/room/migrating-db-versions
- AFWall+ official project/wiki: https://github.com/ukanth/afwall and https://github.com/ukanth/afwall/wiki/FAQ
- River streaming ML API reference (algorithm reference, NOT required Android dependency): https://riverml.xyz/0.17.0/api/overview/
- NIST SP 800-94, limits/false positives in anomaly detection: https://csrc.nist.gov/pubs/sp/800/94/final
- HaGeZi: https://github.com/hagezi/dns-blocklists
- URLhaus current community API and commercial/fair use notes: https://urlhaus.abuse.ch/api/
- Spamhaus DROP usage policy: https://www.spamhaus.org/blocklists/drop-fair-use-policy/

Design decisions, not facts guaranteed by sources: Kotlin/Compose architecture; proposed minSdk29; module names; scoring parameters; 14-day learning; retention and budgets; policy precedence. All need implementation-specific validation. Do not copy AFWall+/feed source without meeting actual licenses. App licensing decision remains open pending component selection; this package includes no upstream code or data.
