# Permissions and Privacy — OnePlus Care Pro v0.4 RC

The app works with **no permissions at all** (dashboard, battery & thermal, network doctor, repair
shortcuts). Every permission below is optional, requested only from the screen that needs it or from the
Permission Center, and explained before the system prompt.

## Permissions

| Permission | Type | When requested | Used for | If denied |
|---|---|---|---|---|
| `INTERNET` | normal | — | Network Doctor probes, only when the user taps *Run* | — |
| `ACCESS_NETWORK_STATE` | normal | — | Transport, validation, captive portal, VPN, Private DNS mode | — |
| `POST_NOTIFICATIONS` (13+) | runtime | Enabling the daily scan, or Permission Center | Scheduled-scan findings only | Scan still runs; results in History |
| `READ_MEDIA_IMAGES/VIDEO/AUDIO` (13+), `READ_EXTERNAL_STORAGE` (≤12) | runtime | Cleaner / Duplicates / Permission Center | Media listing and hashing | Cleaner shows "storage access is off" |
| `READ_MEDIA_VISUAL_USER_SELECTED` (14+) | runtime | Offered by the system media prompt | Partial ("selected photos") access | — |
| `WRITE_EXTERNAL_STORAGE` (≤10, `maxSdkVersion=29`) | runtime | Same prompt on Android 8–10 | Moving files into Safe Trash on old Android | Nothing is offered for removal |
| `MANAGE_EXTERNAL_STORAGE` (11+) | special (Settings) | Permission Center → "All files access" | Downloads, installers, documents; Safe Trash moves | Media-only mode |
| `PACKAGE_USAGE_STATS` | special (Settings) | App Doctor / Data usage / Permission Center | Last use, screen time, per-package StorageStats, per-app network bytes (NetworkStatsManager) | Metrics shown as "Not available" |
| `QUERY_ALL_PACKAGES` | normal (Play-restricted) | — | Security scan must see apps that hide their icon (stalkerware) | — |
| VPN consent | system dialog | Turning the Firewall or Ad blocking on | Local VPN: drops traffic of apps you blocked on the current network, or filters DNS lookups | Feature stays off |
| Shizuku permission | third-party | Advanced screen | Fixed read-only diagnostics | Advanced screen explains; nothing else changes |

Removed in v0.4: `ACCESS_WIFI_STATE`. `QUERY_ALL_PACKAGES` was removed in rc1 and re-added in rc3 for the
security scan only (App Doctor still lists launchable apps).

### Google Play policy notes
* **All files access** is a restricted permission on Google Play. The app keeps a fully functional media-only
  mode and must declare the core use case ("file management / device cleaner") in the Play Console. If the
  declaration is not accepted, ship a Play flavor without `MANAGE_EXTERNAL_STORAGE`.
* **QUERY_ALL_PACKAGES** must be declared in the Play Console under the "device security / antivirus"
  use case; the scan is the app's core security feature.
* **VpnService** use must be declared (core functionality: device-level firewall, no traffic is
  collected or redirected).
* **Usage access** is a special app-op the user grants in Settings; no Play declaration is needed.

## What the app stores

| Data | Location | Contents | Backed up? |
|---|---|---|---|
| Settings | `shared_prefs/care_settings.xml` | Toggles, thresholds, exclusion folder paths, retention | Yes (only this) |
| Trash journal | `files/trash_journal.json` | Original path and display name of each trashed file, sizes, times, media URIs | **No** |
| Safe Trash files | `Android/data/<pkg>/files/safe_trash/` | The moved files themselves | **No** |
| History | `files/history.json` | Event type, time, count, bytes. **No names or paths.** | **No** |
| Downloaded stalkerware list | `files/stalkerware_ioc.yaml` | Public indicator list (only after you tap Update) | No |
| Firewall lists | `shared_prefs/care_firewall.xml` | Package names blocked on Wi-Fi / on mobile | **No** |
| Ad-block list | `files/adblock_hosts.txt` | Public hosts list (only after you tap Download) | **No** |
| Ad-block exceptions | `shared_prefs/care_adblock.xml` | Host names you chose to always allow | **No** |
| Migration flag | `shared_prefs/care_migrations.xml` | One boolean | No |

Nothing is sent off the device. There are no accounts, analytics, crash reporters, ads or remote
configuration. The only network traffic is the Network Doctor check (gateway TCP port 53, DNS lookup and
an HTTPS GET to `connectivitycheck.gstatic.com/generate_204` — the endpoint Android itself uses) and, only
when you tap *Update list* in Security scan, an HTTPS GET of the public stalkerware list from
`raw.githubusercontent.com/AssoEchap/stalkerware-indicators` (no data about the phone is sent).

The security scan results (which apps are installed and what they can do) stay in memory and are never
written to disk, history or the diagnostic export. The firewall drops packets of blocked apps without
reading them; it keeps no logs.

Ad blocking sees DNS lookups only (never app traffic). Each lookup is answered or forwarded to the current
network's own DNS servers; nothing is written to disk. While it runs, counters and the 20 most-blocked host
names are kept in memory and cleared when it stops. Downloading the hosts list (on tap) fetches
`raw.githubusercontent.com/StevenBlack/hosts`. Data usage figures are read from Android on demand and
never stored.

## Diagnostic export

Opt-in, user-initiated, written through the system file picker to a location the user chooses.

Always included: schema/app version, Android SDK/release, security-patch date, health states, storage
rounded to 0.1 GB, battery level/temperature/state/health/cycle count, thermal status, connection
booleans and transport types, permission booleans.

Optional (toggles in Settings → Diagnostic export): phone manufacturer/model (default on), network layer
statuses (no addresses), history event counts, Shizuku diagnostics (passed through `Redactor`).

Never included: file names or paths, installed-app list or package names, IP/MAC/DNS server addresses,
Private DNS hostnames, Wi-Fi SSID, serial number, IMEI, ANDROID_ID, build fingerprint, account data.

## Notifications

One channel ("Scheduled scan results"), importance LOW, lock-screen visibility PRIVATE with a generic
public version ("Maintenance scan finished"). Levels: Never / Only critical / Important findings.

## Shizuku

Optional. **System Tuner** (added after the RC) uses Shizuku to apply allow-listed, reversible changes:
battery-optimization exemption and background app-ops for apps the user picks, standby bucket, preinstalled
app disable/remove-for-user (protected packages excluded), animation scales, peak/min refresh rate, display
density (bounded), Private DNS presets, and the Android 14 child-process restriction toggle. Every change is
journaled in `files/tweak_journal.json` (not backed up) with its exact undo. The system package list is read
on demand and kept in memory only.

For diagnostics: The UserService can read 10 allow-listed build properties (none is a per-device identifier)
and run three fixed diagnostics (`dumpsys battery`, `dumpsys thermalservice`, `df -h /data`). Output is
truncated and scrubbed of serial/IMEI/MAC patterns. It cannot run arbitrary commands, write files,
change settings or manage packages. See `CYBERSECURITY_AUDIT.md` SEC-08/09/24.

## User controls

* Permission Center shows every capability with its current state and a grant button.
* History can be cleared (confirmation required).
* Safe Trash retention is **off by default**; when enabled, expiry runs only when the app is opened.
* App language can be set independently of the system (Android 13+ per-app language).
