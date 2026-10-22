# UX / wizard / configuration
Arabic RTL and English. No fake '100% secure' badge. Dashboard shows enforcement ACTIVE/DEGRADED/OFF separately from telemetry coverage and model readiness.

## Wizard state machine
WELCOME -> OWNER_CONSENT -> CAPABILITY_PROBE -> GOAL -> IMPORTANT_APPS -> DATA_SCOPE -> FEEDS -> POLICY_PREVIEW -> APPLY_TEST -> READY.
Back navigation never applies rules. Save draft unprivileged. Root denial leads to explicit unsupported root enforcement screen, not pretend activation. Resume from draft; capability results expire across reboot/ROM changes. Apply/test failure -> RESTORE_LAST_GOOD -> troubleshoot, with retry and local recovery. No permanent lockout if user exits wizard.

Screens:
- Consent explains root helper operations and optional metadata/file scopes separately.
- Probe shows actual kernel backend, IPv6, VPN/tethering and unavailable sensors with reason.
- Goal selects OBSERVE / BALANCED / PRIVACY / STRICT / DATA_SAVER / CUSTOM; behavior response and network default are independent controls.
- Important apps explicitly approved; no silently trusting banks/system packages by display name.
- Learning defaults initial 14 days, raw metadata 48h, budget 300MiB, local-only analysis. User sees sampling limits.
- Feed choices separate malware/IP threats from tracking/ads, show license/auth/freshness and commercial restrictions.
- Preview explains each rule change, shared-UID group impact, encrypted DNS coverage and failure behavior.
- Test checks resolver, controlled IPv4/IPv6 endpoints, selected app access and recovery. Network outage != failed firewall automatically.

## Mode semantics
OBSERVE: no behavioral actions; firewall policy unchanged unless separately previewed/changed. BALANCED: threat feed enforcement enabled when configured, behavior alerts only by default. PRIVACY: balanced + opted-in tracker lists with breakage support. STRICT: explicit deny-by-default application policy and optional time-limited behavioral containment after consent. DATA_SAVER: user-selected mobile allowlist. CUSTOM: editable deterministic policy. Switching mode always previews delta and retains rollback. Observation mode is not a universal allow rule.

## Incident and troubleshooting
Show facts and times, comparisons to baseline, source freshness, coverage, uncertainty and reason codes. Display 'unknown' where attribution/DNS is unknown. Action buttons: details, scoped temporary exception, temporary block, expected behavior, report false alarm. Troubleshooter chooses app/time range, ranks own recent blocks and proposes smallest expiring exception; never disables all protection as first step.

## Optional assistant
Explain existing evidence locally or with explicitly opted-in redacted cloud summaries. No packet content, credentials or private file content sent. Assistant returns typed proposed policy changes; same validator/preview path as manual UI. It cannot grant consent, modify model evidence, fetch arbitrary root paths, or execute privileged commands.
