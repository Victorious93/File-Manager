# Secure folder (Phase 9)

Tools → **Secure folder**. Select files in Files, open the secure folder, unlock, and
**Move in**. Entries can be **Moved out** (decrypted into the focused folder) or deleted.

## Protection

- **Encryption at rest:** AES-256-GCM in 64 KB chunks (`VaultCrypto`). Each chunk has its
  own IV and tag; the chunk index and an authenticated end marker are bound in as AAD, so
  tampering, reordering, or truncation fails decryption.
- **Key:** a non-exportable Android Keystore AES key (`fm_secure_folder_v1`) that requires
  user authentication — biometric (strong) or device credential on API 30+, any lock-screen
  auth on API 26–29 — valid for 300 s after an unlock.
- **Unlock:** AndroidX `BiometricPrompt` with device-credential fallback. A screen lock is
  required; without one the screen says so.
- **Names hidden:** blobs use random names; real names and paths live in an encrypted
  index (`index.vlt`).
- **Location:** `noBackupFilesDir/secure_vault` — app-private, not readable by other apps,
  excluded from backup, never indexed by the media scanner (plus a `.nomedia` marker).
- **Move-in** encrypts first and deletes the original only after success. Deletion is a
  normal file delete, not a secure wipe (flash storage can't guarantee that).

## Auto-lock

`VaultLock` (pure, unit-tested) locks when:

- the app has been in the background longer than the timeout, or
- the folder has been idle longer than the timeout, or
- immediately on leaving the app when the timeout is "Immediately",
- or you tap **Lock now**.

Timeout: Settings → Secure folder (Immediately / 30 s / 1 min / 5 min; default 1 min).
The open screen polls the lock and hides the listing as soon as it locks.

## Limits

- If the device lock screen is removed or biometrics change, Android may invalidate the
  key and existing vault files can no longer be decrypted. Move files out before changing
  the lock-screen setup.
- Root users can see the encrypted blobs (but not decrypt them without the Keystore key).

## Code

`core/…/secure/SecureVault.kt` (`VaultCrypto`, `SecureVault`, `VaultLock`),
`app/…/secure/SecureFolderManager.kt`, `app/…/ui/Phase9Screens.kt`.
Tests: `SecureVaultTest` (round-trips incl. empty and multi-chunk, wrong key / tamper /
truncation, move-in/out with no plaintext names or content on disk, lock timeout logic).
