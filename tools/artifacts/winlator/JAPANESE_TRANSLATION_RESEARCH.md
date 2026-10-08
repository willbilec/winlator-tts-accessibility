# Japanese-to-English game translation for Winlator

Research date: October 5, 2026. This is a proposal based on source inspection and primary documentation, not an implemented or audibly accepted translation feature. `README.md` remains the current deployment and speech handoff.

## Recommendation

Use the BK1/2 **approach**: capture the game's Unicode NVDA speech requests, translate Japanese text to English, and speak the English text through Winlator's existing native SAPI/RHVoice helper.

The existing bridge provides the capture point. Add an asynchronous translation layer around its NVDA speech submission path. A small Android service inside Winlator should perform translation and maintain a cache, communicating with the Wine helper over localhost. Keep the existing native voice as the output.

For a first implementation that provisions itself without a translation account, use Android ML Kit's downloaded Japanese-to-English model. For better dialogue quality, allow an official online translation provider later. These providers can share the same capture, caching, ordering, and cancellation machinery. The best capture method is clear; the best translation quality must be established using actual game lines rather than inferred from API availability.

## Correction to the initial Audio Game Manager assessment

The local `work/audiogame-manager/audiogame-manager.sh` sets `TRANSLATE=true` for “Bokurano Daibouken” (part 1) and part 2 at lines 570–575. Its startup function logs translation settings and warns if `trans` is missing. These are configuration actions, not a translator implementation.

The original upstream nvda2speechd server's `SpeakText` handler directly sends the received text to Speech Dispatcher. It has no language-translation operation or `TRANSLATE` environment handling in the inspected source:

- [Original server source](https://raw.githubusercontent.com/RastislavKish/nvda2speechd/main/src/server/src/main.rs)
- [Bridge architecture and usage](https://github.com/RastislavKish/nvda2speechd)

The manager pins a different binary through `.includes/ipfs.sh:9`. I fetched that payload into memory and inspected its strings without running it:

- IPFS CID: `QmPxhoNsoFoJC7bCfioBBCcK8tEoSoYpm342z6u7KjFsVz`
- Size: 1,186,480 bytes
- SHA-256: `d8519c33411edef92a6793dc907e634476d2ae221251fe721165ebc6d2729b0b`
- ELF machine: x86-64; no `UPX` marker found
- It contains `SpeakText`, Speech Dispatcher references, and `NVDA2SPEECHD_HOST`/module/voice settings.
- It contains no `TRANSLATE` string, and no matching `translate`, `-brief`, or `-no-autocorrect` strings were found in the initial printable-string scan.

This static evidence does not prove every possible runtime behavior, but it does **not substantiate** translation support in the pinned daemon. Simply copying `TRANSLATE=true` into Winlator would not implement the feature. My earlier description of BK1/2 as having functioning daemon translation was too definite.

Local Git history shows those launch flags in commit `b0817ddce970ab30e5a547ec67d2b113b3c471bc`. Older Audio Game Manager history used a clipboard translator for BK1/2 and removed their NVDA DLL to force clipboard output. That historical fallback is separate from the current flags. The [older fix](https://git.stormux.org/storm/audiogame-manager/commit/e3289b92cb019b6d0737693a792ff66020606f59) documents this behavior.

## Why capturing NVDA text is a good fit

The Japanese Games Translator author's [user guide](https://blindgamers.com/Home/JGTUserGuide) specifically lists BK1, BK2, BK3, and ShadowRine FullVoice as games that can send Unicode directly to NVDA. Its automatic translation feature demonstrates the intended interaction: moving through menus produces translated speech without a separate translation hotkey. This is documentary evidence of the game interface, not a Winlator acceptance test.

Winlator already serves the stock NVDA Controller API through a 32-bit Wine RPC helper. In `bridge/nvda_android_rpc_service.c:178`, `speakText(const WCHAR *text)` receives the original Unicode text and, in the active `NATIVE_SAPI` build, calls `sapi_backend_submit(text)`. This is the useful insertion point.

In the current route, `bridge/sapi_backend.h` owns a persistent COM worker. It queues speech and purge commands and invokes asynchronous SAPI speech. `NativeSpeechControl.java` independently sends Control cancellation to the helper's UDP listener on localhost port 51235. The helper's main-thread audio warmup and the current native SAPI output must remain part of the route.

Thus the proposed flow is:

```text
Game's Japanese Unicode text
    -> original NVDA Controller DLL
    -> existing Wine RPC helper
    -> translation queue and cache
    -> Android translator over localhost
    -> English Unicode text
    -> existing SAPI/RHVoice voice
```

Installing full NVDA is not required for this proposal. It implements translation at the controller interface already served by the helper. This covers text actually sent to NVDA; it does not translate prerecorded Japanese voice acting, sound effects, or text sent exclusively to another speech system.

## Translation engine choices

| Engine | Fit for Winlator | Main tradeoff |
| --- | --- | --- |
| Android ML Kit on-device Japanese -> English | Recommended first self-contained prototype; supported Java API, downloaded model, local translation afterward | Google describes the models as intended for casual/simple translation; game dialogue and names need evaluation |
| Official online translation API with a persistent cache | Quality-oriented optional provider; same NVDA capture path | Requires service credentials/account configuration, network access for uncached lines, and provider charges/limits |
| `translate-shell` and its default Google backend | Useful reference for Audio Game Manager behavior | Adds shell/runtime dependencies and reliance on a web-facing translation backend; poor fit for an integrated Android feature |
| Existing game localization/dictionary | Prefer when a good game-specific translation is available | Covers only the content supplied by that game's translation package |

ML Kit explicitly supports `ja` and `en`. Its Android API requires API level 23 or later; this Winlator checkout already has `minSdkVersion 26`. Models download on demand, and Google describes language models as approximately 30 MB. Translation runs on the device after the required model is available. Startup should prepare the model before reporting translation as ready.

Google recommends evaluating on-device translation for the actual use case and considering Cloud Translation for higher fidelity. Consequently, offline availability is a reason to prototype ML Kit, not proof that it translates Japanese game dialogue best.

Sources:

- [ML Kit Android implementation](https://developers.google.com/ml-kit/language/translation/android)
- [Supported languages](https://developers.google.com/ml-kit/language/translation/translation-language-support)
- [On-device translation capabilities and quality limits](https://developers.google.com/ml-kit/language/translation)
- [Google translation offerings](https://docs.cloud.google.com/translate/docs/overview)
- [Translate Shell dependencies and engines](https://github.com/soimort/translate-shell)

## Necessary implementation behavior

1. **Explicit mode.** Add a “Translate Japanese screen-reader speech to English” option for a container/session. Enable it before launching the Japanese game. Set source `ja` and target `en` explicitly. Pass existing English text and numbers through without unnecessary translation.
2. **No game-thread waits for translation.** Copy incoming text into a bounded work queue and acknowledge acceptance promptly. Translation/network work must not run on the Android input thread, the RPC caller, or the SAPI COM worker.
3. **Preserve speech semantics.** Keep the order of queued utterances within a speech generation. Do not silently collapse narrative lines. Coalesce duplicate translation work while retaining the game's legitimate repeated speech requests.
4. **Cancel pending work as well as audio.** Both `cancelSpeech` and Control must invalidate earlier translation requests and purge the native voice. A generation counter must be checked atomically with submitting the translated result. Otherwise an old result can restart speech after cancellation.
5. **Cache repeated text.** Keep a memory cache and a persistent cache keyed by source text, language pair, provider/model version, and game identity when available. Keep numbers, punctuation, and full multiline text. Use parameterized database queries. Cache misses may be slower; cached menu responses should avoid repeat translation work.
6. **Visible readiness and failures.** Prepare the Japanese model automatically, expose download/readiness/errors accessibly, and keep failures bounded. Do not silently claim that untranslated Japanese is English. A failed or canceled request must not deadlock the game's next speech request.
7. **Session lifetime.** Close the translator and discard pending results when the guest session ends. Preserve the current launcher detection, audio warmup, voice selection, and Control interruption behavior.

The native RPC helper and native payload would need rebuilding, followed by copying the payload into the matching APK asset. An Android-only change would not be sufficient because the active speech route currently goes straight from Wine RPC to native SAPI.

## Why the clipboard is a fallback

The local `speech/clipboard_translator.sh` polls the X11 clipboard every 50 ms, normalizes text, looks up a SQLite cache, calls `trans` for uncached text, speaks with `spd-say`, and clears the clipboard. It is an actual translation implementation, unlike the launch flag alone.

However, its polling design can miss intermediate updates, handles translation synchronously, changes clipboard contents, and does not receive the game's NVDA cancellation events. Its command also retains only the first line of the translator's output. An Android/Winlator version would still need a translation queue, cache, and interruption handling. The direct Unicode NVDA route supplies a better event interface for the four games in question.

If a particular game version fails to send usable NVDA text, investigate a guest clipboard listener as a fallback. Do not depend on Android's general clipboard synchronization as the only capture path.

For BK3, prefer the publisher's English dictionary where available. For ShadowRine FullVoice, prefer a usable English localization file. A shared NVDA translator can fill gaps or support versions without those packages, but it cannot replace recorded Japanese voice acting.

## What remains to verify

No translation implementation was changed or installed, and no BK1/2 game session was run during this research. The current source route and its Unicode entry point were inspected; direct game support is documented by the JGT author.

Before declaring this feature usable:

- Capture actual BK1 and BK2 menu, status, combat, and dialogue text through the existing bridge. Verify kana, kanji, punctuation, numbers, and multiline messages survive intact.
- Compare the candidate engine's English output against representative game lines, including directions, negation, item names, and counts.
- Measure cold translation, model-loaded translation, and cached translation separately.
- Test rapid menu navigation and canceled in-flight translations; old entries must not speak later.
- Test original game calls to cancel and Android Control cancellation independently.
- Confirm English speech audibly after a fresh guest launch, with normal keyboard input and a clean restart.

The recommendation is therefore specific: **retain the BK1/2 NVDA-text concept, implement a real translator in Winlator, and use its accepted native voice output.** Start with ML Kit for automatic offline provisioning, with a provider interface that can add higher-fidelity online translation without changing game capture or cancellation.
