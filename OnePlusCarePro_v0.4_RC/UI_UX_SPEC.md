# UI / UX Specification — OnePlus Care Pro v0.4 RC

Jetpack Compose + Material 3, AMOLED-first. Original design language inspired by OxygenOS restraint;
no OnePlus artwork, fonts or screen layouts are copied, and the app states it is not affiliated.

## 1. Principles → concrete rules

| Brief principle | Rule in the implementation |
|---|---|
| Health first, cleaning second | Home shows state + 3 areas + ≤ 3 recommendations; tools are one level down. |
| Explain before acting | Every recommendation expands to *evidence · expected benefit · risk · reversible · needs*. |
| Show measured evidence | Values come from `Reading<T>`; unavailable values render "Not available", never 0. |
| Risky actions visually distinct | Only irreversible actions use the critical color, a "cannot be undone" line and a haptic tick. |
| Prefer reversible actions | Primary removal action is always "Move to Trash"; permanent delete lives only in Trash. |
| No fear-based urgency | Copy is factual; no countdowns, no red badges for non-problems; offline is not "unhealthy". |
| One primary action per screen | `PrimaryAction` (full-width, 52 dp) appears once per screen state. |
| Advanced separated | Shizuku lives at the bottom of Tools with its own scope notice. |

## 2. Visual system

* **Color** (`ui/theme/Theme.kt`): background/surface `#000000`; containers `#0C0C0E → #222226` in five
  steps; text `#F2F2F3` / secondary `#B4B4BA`. Accent `#EB0029` (white-on-accent ≈ 4.6:1) used only for the
  primary action and selection. Status: Healthy `#3DDC97`, Attention `#FFB547`, Critical `#FF6B6B`,
  Info `#8AB4F8`. All status uses icon + text (`StatusPill`, `NoticeCard`) — never color alone.
* **Type:** Material 3 scale on the system font (OnePlus Sans on OxygenOS), SemiBold titles, `displaySmall`
  / `displayMedium` for the one key number per screen. All sizes in `sp` → follows the user's font scale.
* **Shape & spacing:** 20 dp cards, 16 dp buttons, 16 dp screen gutter, 12 dp vertical rhythm.
* **Motion:** 700 ms ring fill on the dashboard; fade between screens; `animateContentSize` for progress
  blocks; expand/collapse for recommendation details. Nothing loops.
* **Haptics:** only on confirming an irreversible action.

## 3. Information architecture

```
Bottom bar (phones) / Navigation rail (≥ 600 dp)
├── Home            dashboard
├── Clean           Smart Scan → Cleaner, Duplicate Cleaner, Trash
├── Apps            App Doctor → Privacy Doctor
└── Tools           Battery & Thermal · Network Doctor · Privacy Doctor
                    Repair Center · Trash & Restore · Maintenance History
                    Permission Center · Settings · Advanced (Shizuku)
First run: Onboarding (principles → optional permissions, skippable)
```

Back stack is a saveable list (survives rotation/process death). Content width is capped at 840 dp
(tablets, landscape, foldables).

## 4. Screens

**Onboarding.** Step 1: three principles (explain, reversible, private). Step 2: capability cards with live
status (storage, all-files, usage access, notifications) and "Skip for now".

**Home.** (1) Ring gauge with *Healthy / Attention / Critical* and the factor(s) that caused it, plus the
single primary action *Run Smart Scan*. (2) Free storage + battery tiles and a network tile, each tappable.
(3) "Recommended next" — top 3 rule results, or an empty state. (4) Last maintenance event and trash size.

**Smart Scan.** Intro → staged progress (Device health → Storage → Duplicates → Apps) with determinate bars
where totals are known and a Cancel button → results grouped exactly as the brief requires:
*Safe to clean · Review recommended · Diagnostic findings · No action needed*. Links to Cleaner, Duplicates,
Trash.

**Cleaner.** Access-mode notice → summary (total, candidates, files checked, truncation note, "moving does
not free space yet") → two groups with select-all → per-file row (name, category · folder · age, size) →
"Move N items to Trash (size)". Confirmation dialog states counts, bytes and which trash is used.

**Duplicate Cleaner.** Summary (reclaimable, groups, copies, files hashed, method and budget notes) →
"Select extra copies" / "Clear selection" → groups with the keeper marked *Kept* → guard notice
→ move button. Confirmation reports how many keepers were protected automatically.

**App Doctor.** Usage-access notice → link to Privacy Doctor → search, sort chips (size, cache, least
recently used, usage time, name), "Include system apps", "Only with findings" → app cards with flags and
five separately-labelled metrics (*App size · Data size (includes cache) · Cache · Screen time (N days) ·
Last used*). Tap opens system App info.

**Battery & Thermal.** Level + charge state → "hot while charging" alert when applicable → measured table
(temperature with band, health, power source, voltage, current marked OEM-dependent, cycle count with
"Android 14+" note) → thermal status and headroom with API notes → no-placebo statement → OEM guidance
cards (OnePlus/OxygenOS specific when detected).

**Network Doctor.** Intro + privacy note + *Run network check* → verdict pill + advice → 7 layer rows with
timings → transport, metered, Private DNS mode, DNS server count (addresses hidden) → fixes (Internet
panel, Private DNS location).

**Privacy Doctor.** Device security (screen lock, patch date and age, developer options, USB debugging)
→ unused apps with sensitive permissions → per-permission app lists (tap → App info) → scope note.

**Repair Center.** Before/After session (start → live trashed/purged counters → finish → attributed freed,
net change, pending in trash, "other activity" notice) → system tools → OEM guidance.

**Trash & Restore.** Size + count + space and uninstall notes → retention chips (Never default, 7–90 days)
→ Safe Trash rows (Restore · Delete permanently) → "Empty Safe Trash permanently" → system-trash media
section (Restore via system dialog).

**Maintenance History.** Group chips (All / Cleaning / Scans / Other) and range chips (Any / 7 / 30 days)
→ "space actually freed" total → event list → privacy note. Clear needs confirmation.

**Advanced (Shizuku).** Scope notice → status table → state-specific action (install / start / allow /
collect / stop) → root warning → results in monospace → footer about scrubbing.

**Settings.** Scheduled scan + notification level · Cleaner rules (large size, age, exclusions with
validation) · Safe Trash retention link · Diagnostic export toggles + export · App language · version/about.

**Permission Center.** Same cards as onboarding, grouped by Storage / Apps / Notifications.

## 5. States

Every data screen implements: **loading** (`ProgressBlock`, announces its label), **empty** (`EmptyState`
with one next action), **error** (`NoticeCard` critical + Retry), **permission-limited** (`NoticeCard` info
+ grant action), **partial** (truncation / budget notes).

## 6. Accessibility

* Touch targets ≥ 48 dp (rows 56 dp, buttons 48–52 dp).
* Icons that only decorate have `contentDescription = null`; actionable icon buttons are labelled.
* Section headers carry `heading()` semantics; the health ring exposes its state text as content description.
* Checkbox rows use `Role.Checkbox` on the whole row; switch rows use `Role.Switch`.
* Colors meet WCAG AA against `#000000`/`#121214` for body text; status is never color-only.
* Font scaling: no fixed heights on text containers (`heightIn(min=…)` only).

## 7. RTL and localization

* English (`values/`) and Arabic (`values-ar/`) with 624 strings each, generated from one table so they
  cannot drift (`tools/gen_strings.py` fails on missing/unused keys and mismatched `%1$d/%1$s` args).
* `android:supportsRtl="true"`; layouts use start/end; directional icons are `Icons.AutoMirrored.*`.
* Numbers, percentages, dates and sizes are formatted with the current locale (`NumberFormat`,
  `DateUtils`, `Formatter.formatShortFileSize`), so Arabic shows Arabic digits where the locale does.
* Per-app language via `locales_config.xml` (Android 13+), linked from Settings.
* Copy avoids grammatical-number constructions that break in Arabic plurals (neutral "N items" phrasing).

## 8. Responsive

`screenWidthDp ≥ 600` switches the bottom bar to a navigation rail; content is centered with a max width
of 840 dp; onboarding is capped at 640 dp.

## 9. Previews / sample data

`core/src/test/.../Fixtures.kt` provides deterministic device facts used by tests; Compose UI tests in
`app/src/test/.../ComposeScreensTest.kt` render real components with sample recommendations.
