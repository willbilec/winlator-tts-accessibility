# Build 94 — early work in progress

Winlator TTS Accessibility is an unofficial Winlator fork adding text-to-speech
for Windows audio games on Android. This is a very early work in progress.

This prerelease provides the latest existing local build:

- Version: `11.2-tts55-auto-game-dependencies`
- Version code: `94`
- Package ID: `com.winlator`
- Target: ARM64 Android devices, Android 8.0 or later
- Build variant: debug, for early testing

Build 94 makes automatic game-dependency installation catalog-driven for direct
executable launches. It retains native SAPI/RHVoice speech, NVDA controller
interface speech, separate speech-rate settings, Control cancellation, keyboard
reset, and Bavisoft quick-tap compatibility.

The development handoff records twelve passing tests and a successful Android
build for Build 94. Chillingham dependency provisioning was checked on its first
direct launch and retained on a subsequent fresh launch. For publication, the
APK's speech, keyboard, and game-dependency assets were verified against the
current local assets. The APK was not rebuilt for the publication commit, which
also includes documentation and native-script layout updates.

Game compatibility is incomplete. This does not provide full Windows screen
reading, general Wine menu/dialog accessibility, or braille. Some installer
focus and long-session keyboard issues need more testing; Arcadelux still has
loading delays. A completely fresh container has not had full live acceptance.

Back up containers and saves before installing. This uses the original Winlator
package ID, so an in-place update requires a matching signing certificate. Do not
uninstall an existing installation without backing up its data.

APK SHA-256:
`f42a9f26dabb9a12f1176a0dc37c2aa00313956efa64c9bdd9d4ebcfd66016af`

The separate `breed_memorial_sourcehansans.ttc` asset is needed only for building
from source. Run `python tools/fetch-build-font.py` after cloning; it downloads
and verifies the font. APK users do not need to download it separately.

Font SHA-256:
`e7d21914e6426157fda3f40dbd53df643dd2e5b9bce6e58790d76d666d89a538`

Upstream: https://github.com/brunodev85/winlator and
https://github.com/brunodev85/winlator-app
