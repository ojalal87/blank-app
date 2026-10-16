# Device Test Plan — OnePlus Care Pro v0.4 RC

Run on: (1) OnePlus with OxygenOS 14+ (Android 14/15/16), (2) one Android 11–13 device, (3) optionally an
Android 8–10 device. Use disposable test files only.

## A. First run & dashboard
- [ ] Onboarding shows principles, then capability cards with correct live status; Skip lands on Home.
- [ ] Home shows Healthy/Attention/Critical with the causing factor text; values match Settings → Storage
      and Settings → Battery (temperature within ±1 °C of `adb shell dumpsys battery`).
- [ ] No permission granted: dashboard, Battery & Thermal, Network Doctor work; Cleaner explains access.
- [ ] Rotate on every screen: no state lost, no dialog shown twice.

## B. Storage access modes
- [ ] Android 13+: grant photos/videos only → Cleaner says "Media access"; downloads/APKs not listed.
- [ ] Android 14+: "Select photos" → partial mode label; only picked items appear.
- [ ] Grant All files access → returning to the app updates the mode without restart.
- [ ] Android 10: grant storage → files go to Safe Trash; deny → nothing offered for removal.
- [ ] `Android/data`, `Android/obb`, `.thumbnails`, and the app's own `safe_trash` never appear.
- [ ] Add exclusion `DCIM/Camera` → its files disappear from the next scan; `../x` is rejected.

## C. Safe Trash (All files access)
- [ ] Move a test file → it disappears from its folder, appears in Trash; free space unchanged.
- [ ] Restore → back at the exact original path with identical checksum (`sha256sum`).
- [ ] Create a file with the same name, then restore → saved as `name (restored).ext`; the new file intact.
- [ ] Delete the original folder, then restore → folder recreated.
- [ ] Delete permanently → free space increases by the file size; History "Deleted permanently" shows bytes.
- [ ] Interrupt: start moving a large (2 GB) file and force-stop the app → reopen → file is either in Trash
      or still in place, never missing; no duplicate left in `safe_trash`.
- [ ] Retention 7 days with an old item → item purged on next app open, History shows "Trash auto-expiry".
- [ ] Upgrade from v0.3 with items in its Safe Trash → items appear in v0.4 Trash and restore correctly.

## D. MediaStore system trash (Android 11+, media access)
- [ ] Move a test photo → Android confirmation dialog → photo disappears from Gallery, appears in app Trash
      under "In Android system trash".
- [ ] Cancel the dialog → nothing changes ("Nothing was changed").
- [ ] Restore → Android dialog → photo back in Gallery; entry removed from app Trash.
- [ ] Restore the photo from the Gallery app instead → next app start removes it from app Trash.

## E. Duplicate Cleaner
- [ ] Two identical 5 MB files in different folders → one group; oldest marked "Kept".
- [ ] Same name, different content → not grouped.
- [ ] Select every file in a group → confirmation says copies were kept; one file remains afterwards.
- [ ] Very large library: hashing stops at the budget with a note; Cancel works mid-hash.

## F. App Doctor / Privacy Doctor
- [ ] Without Usage access: metrics read "Not available" (never 0).
- [ ] With Usage access: App size, Data (includes cache), Cache, Screen time, Last used plausible vs.
      Settings → Apps → <app> → Storage.
- [ ] Search, every sort, system toggle, findings filter.
- [ ] Privacy Doctor lists apps per permission; tap opens App info; screen lock/USB debugging correct.

## G. Network Doctor
- [ ] Healthy Wi-Fi → all layers OK, verdict healthy.
- [ ] Router with no WAN (unplug uplink) → "Local network only".
- [ ] Private DNS set to a bogus hostname → "Private DNS is failing".
- [ ] Captive portal Wi-Fi → "Sign-in page required".
- [ ] Airplane mode → "Offline"; no probe traffic (verify with router logs if available).

## H. Battery & Thermal / OnePlus guidance
- [ ] Charging while warm (> 40 °C) → warm or hot-while-charging recommendation.
- [ ] Thermal status changes under load (camera 4K recording) and headroom shows (Android 11+).
- [ ] Cycle count shown on Android 14+ (or "Not available" if OEM omits it).
- [ ] OnePlus device: "OnePlus / OxygenOS guidance" cards; each opens a real Settings screen.
- [ ] Non-OnePlus device: generic guidance; no OxygenOS wording.

## I. Scheduled scan & notifications
- [ ] Enable daily scan → notification permission prompt (13+); WorkManager job visible in
      `adb shell dumpsys jobscheduler | grep onepluscare`.
- [ ] Force run: `adb shell cmd jobscheduler run -f com.bright.onepluscare.debug <jobId>` while charging →
      History "Scheduled scan"; no files moved.
- [ ] Lock screen shows only "Maintenance scan finished".

## J. Advanced / Shizuku
- [ ] Not installed / not running / permission denied states each explained; rest of app unaffected.
- [ ] ADB mode: collect → identity uid=2000, properties and three diagnostics; no serial/MAC visible.
- [ ] Root mode → warning card.
- [ ] Stop Shizuku mid-session → status updates; app does not crash.

## K. Diagnostics export
- [ ] Default export contains no model if toggled off, no file names, packages, IPs, serials.
- [ ] Enabling Shizuku section → output redacted.

## L. Accessibility & locale
- [ ] TalkBack: every control announced; health ring announces state; headings navigable.
- [ ] 200 percent font size: no clipped text on Home, Cleaner, Trash.
- [ ] Arabic: full RTL mirroring, arrows mirrored, numbers/dates localized; per-app language switch.
- [ ] Tablet / unfolded: navigation rail, centered content.
