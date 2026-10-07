# Audio game dependencies

Winlator exposes an **Install audio game dependencies** action in each
container's popup menu. Choosing a catalog entry installs its Windows runtime
files and per-application Wine settings into that container's prefix. The
installer records the selected game ID in the container metadata so the
session launcher can apply game-specific behavior later.

## Adding a catalog entry

Extend `AudioGameDependencyManager.createCatalog()` with a `GameDefinition`.
Give it a stable lowercase ID, a display name and description, exact Windows
process executable names, and the runtime files and registry overrides it
needs. Place redistributable runtime assets under
`app/src/main/assets/audio_game_dependencies/<game-id>/`; add each file to the
definition with its asset path and destination filename. Keep DLL overrides
under `Software\\Wine\\AppDefaults\\<executable>\\DllOverrides` so they
apply only to the selected game.

Every new catalog entry must support automatic dependency setup on launch.
Adding its exact executable basename(s) to `GameDefinition.processNames`
automatically opts it into that behavior: `getWineStartCommand()` uses
`AudioGameDependencyManager.findByExecutable()` to match the launched filename
case-insensitively and installs missing dependencies before starting the guest.
Do not add a separate filename switch or require the user to visit the picker.
An unrelated executable, installer, or similarly named backup must not match.

This applies to direct executable launches and shortcuts containing an executable
path. Launches through a generic Wine desktop or opaque `.lnk` cannot be identified
by this Android launch path; the manual dependency picker remains available.
Installation status is stored per container. Ordinary installed entries are
skipped on later launches; Breed Memorial refreshes its existing provisioning
on each launch. Keep game-specific updater/audio preparation in its existing
launch hook after the catalog's dependency setup.

When adding an entry, verify its exact executable names and bundled assets,
check first-launch setup in a container where that entry is not installed,
and check subsequent launches preserve the installed status and game arguments.

Runtime files are copied into the container's 32-bit Windows system directory
(`syswow64`, falling back to `system32`). Existing files and `user.reg` are
backed up once under
`C:\\WinlatorAudioGameDependencies\\backup\\<game-id>` in the container.
Configuration presets are optional. A preset specifies candidate game
directories, its executable name, config filename, and a content template.
Templates can substitute `${USER}`, `${HOST}`, and `${COUNTRY}`. The installer
creates the config only when the target executable is found and preserves the
original config backup. Avoid adding installers that require user interaction;
the catalog installer must be usable without a screen reader in the guest.

## Automatic session exit

The session launcher captures a baseline of processes after startup. It also
uses the executable name directly when Winlator starts a shortcut or explicit
executable. Windows executables that appear outside the baseline are tracked
regardless of whether their game is in this dependency catalog. Once tracked
executables have all disappeared for three consecutive one-second samples,
Winlator closes the container session. This avoids ending the session before a
game starts and filters brief process enumeration gaps. The dependency catalog
does not control process tracking. For direct launches, if the game's X11
window is destroyed but Wine leaves its process running, Winlator also closes
the session after confirming the window stays gone for two seconds.

## Light Cars

The initial entry installs `dx8vb.dll` and `msvbvm60.dll`, sets a native
`dx8vb` override for `lightCars.exe`, and prepares `config.dat` for the common
Light Cars install folders. The game was confirmed working by the user in
Container 1; new-container provisioning and automatic session exit still need
confirmation through an actual game session.

## Grizzly Gulch and Chillingham

Both Audio Game Manager recipes install the Visual Basic 6 runtime and MFC 4.2
(`vb6run` and `mfc42`). The catalog installs `msvbvm60.dll`, `mfc42.dll`, and
`mfc42u.dll` into the container's 32-bit Windows system directory. It does not
assume where the game itself is installed; the user's copy can remain on the
container's E: drive. No game-specific configuration file or DLL override is
required by either recipe. The dependency picker has separate entries for
Grizzly Gulch and Chillingham, sharing the same bundled runtime files. Each
entry tracks its own installation status and recognizes its own game process.
Direct launches of `Grizzly Gulch.exe` or `Chillingham.exe` automatically install
their missing dependencies before starting the game, regardless of install folder.

## Breed Memorial

The entry `breed-memorial` installs the recipe's CJK-font equivalents (Unifont
13.0.06 and Source Han Sans TTC), Chinese/Japanese/Korean fallback aliases, and
a game-only Windows 7 version setting. Existing fonts and registry files are
backed up under the usual dependency backup directory.

Direct executable launches, including non-link shortcuts, provision these
dependencies automatically. Before the game starts, Winlator also patches the
verified original `dll/hspinet.dll` to guard its invalid optional HTTP header
query. A SHA-256 check permits only the original version diagnosed on-device;
already-patched copies are retained and unknown/newer versions are preserved.
The original stays beside it as `hspinet.dll.before-bmguard`.

Input Controls exposes **Breed Memorial audio compatibility (next launch)**,
enabled by default only for `breed memorial.exe`. It enables a game-local BASS
keep-output-active wrapper, retaining the original in `bass-original.dll`.
Disabling the option restores that original on the next launch. Unknown custom
BASS replacements are preserved. This does not alter other games' audio.

The dependency installer does not download/install the game or modify its saved
settings. A game launched later from a generic Wine desktop or `.lnk` must first
be launched directly once for automatic game-local provisioning.

Build 91 fixed the logged ALSA shared-buffer overrun (rejecting malformed writes
at the client connection rather than aborting ART), and file-manager restoration
after process death. Automatic container closing remains separately optional.
