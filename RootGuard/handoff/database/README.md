# Applying the schemas
Run `python3 tests/test_handoff.py` from the package root. Only standard Python libraries are required. Tests create disposable in-memory DBs, never touch host networking.
The three included .sqlite files are empty v1 schema fixtures generated from corresponding .sql files. Do NOT pass them straight to Room.createFromAsset: Room identity/metadata and migration history must first be generated/verified by the Android build.

JSON columns are typed at the application boundary; SQLite schema intentionally avoids JSON1-dependent CHECKs for portability. Validate shape/version/size in repositories. SQL enum, FK, uniqueness and range checks remain mandatory. Immutable revision/model/evidence fields must be protected by repository write APIs. Use immutable replacements instead of in-place historical edits.

Additional application invariants: principal lifetimes cannot overlap (including closed intervals), rule schedules/expiry validation; generation activation requires license approval and parser validation; active lookup always filters both expiry columns using now_ms (or explicitly marks stale policy); model trained_through < window.start; graph policy parents acyclic; known probabilities require real calibration artifacts. Not all cross-row/cross-store invariants can be expressed as SQLite CHECKs.
