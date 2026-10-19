# High-impact bug fixes: validation

Run the JVM regression suite and build the debug APK:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline
```

The suite tests actual backup parsing and preference normalization, selected-app
target merging, restore failures after partial mutations, rollback failures,
long-running mode mutation suppression, and real child processes with full
stdout/stderr pipes, nonzero exits, UTF-8 input, and timeouts.

## Device checks still required

These scenarios require an Android device with the corresponding permissions;
they have not been executed in this session.

1. On a fresh install, select apps through the Freezer picker. Enable automatic
   freezing, thaw an app, and lock the screen. Confirm it freezes. Confirm the
   freeze-all shortcut and tile target the same selected apps. Check an existing
   installation retains legacy whitelist and tag behavior.
2. Activate a Shizuku-disable mode that needs shell suspension fallback. Stop
   Shizuku, then disable the mode. Confirm the operation reports failure and
   retains ownership. Restart Shizuku and disable again; all owned apps must thaw.
3. While reconciliation adds a fallback-suspended app, terminate Hailstone after
   the shell mutation and before the final journal commit. Relaunch and disable
   the mode. Verify the fallback app thaws. Repeat termination immediately before
   the shell mutation; recovery must also succeed when the intent was recorded
   but no suspension occurred.
4. Exercise a freeze operation lasting more than ten seconds. Deliver package
   change broadcasts during the final batch and just after completion. Confirm
   the mode still owns its apps and thaws them when disabled. Repeat after a
   process restart while an enabling transaction is being recovered.
5. Freeze and thaw a large selected list through a shortcut/API request. Confirm
   the activity remains responsive and finishes only after the operation.
6. Enable skipping notifying apps. Disconnect/revoke notification-listener access
   and request automatic freezing. The worker must retry without freezing apps
   whose notification state is unknown. Reconnect and confirm the retry skips
   notifying apps. Repeat after process restart and service destruction.
7. Restore a backup containing lists, modes, tags, checked apps, and settings.
   Inject a write failure at each section, and a service-start failure after
   preferences commit. Verify old in-memory state is restored and persisted state
   is restored wherever storage remains writable. Unwritable storage cannot
   guarantee durable rollback; the restore must report failure.

## Lint

The full `:app:lintDebug` check currently fails with existing project-wide errors,
including the API-24 `clearProfileOwner` call in `HPolicy.kt` with minSdk 23 and
resource issues. The first lint run reported 693 errors and 135 warnings, with no
errors in the changed Kotlin files. No lint baseline or suppression was added.
