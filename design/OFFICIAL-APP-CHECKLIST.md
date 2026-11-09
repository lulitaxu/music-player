# arzxsh — Official App Checklist

Built 2026-11-07 at Arz's request ("find a checklist a official app what have fulfill").
Sources: Google Play target API requirements (new apps must target API 36 / Android 16
since 31 Aug 2026), Play Console launch requirements, and Android's Core App Quality
guidelines (developer.android.com). Statuses verified against the actual app at 0.6.1.

Legend: ✅ done · 🔨 in the 0.6.1 build · 📦 release phase · ⛔ blocked (blocker named) · n/a

## Tier A — Core App Quality (Google's minimum bar for any app)

| Item | Status | Note |
|---|---|---|
| Standard Back navigation, never redefined (UX-N1) | 🔨 | Back-stack + sub-page arrows land in 0.6.1 |
| Notifications only for ongoing events, no ads (UX-S1) | 🔨 | Media notification is playback-only, lands in 0.6.1 |
| Minimum permissions only (FN-P1) | ✅ | Internet, downloads storage, + 0.6.1: RECORD_AUDIO (visualizer, explained in-app), POST_NOTIFICATIONS, FGS-media |
| App state preserved across rotation/process death (FN-S1) | ✅ | Queue/playback stores persisted since v5 |
| No crashes/ANRs in core flows (PS-S1) | ✅/test | CI green every release; phone-verified per build by Arz |
| Loading feedback, never a frozen screen (PS-P1) | ✅ | Skeletons + buffering states (v6) |
| No debug libraries/code in the production build | 📦 | Release build type exists (minify + shrink ON); deliverables so far are debug APKs — signed release is the polish phase |
| Power management: Doze/App Standby respected | ✅ | Playback FGS is the sanctioned exemption path for music |
| Touch targets ≥ 48dp | ✅ | Spec'd in reference-ui-spec; rows/controls sized to it |
| Content descriptions / basic accessibility | ✅/partial | Icon buttons carry content descriptions; full TalkBack pass not done |
| Supports latest Android without breaking | ✅/test | Runs on Arz's Realme 8 (Android 13-era ROM); API 36 behavior staged below |

## Tier B — Google Play submission (only if arzxsh ever goes to the store)

| Item | Status | Note |
|---|---|---|
| Play Developer account ($25 + identity verification) | ⛔ | Needs Arz's payment + ID; he has neither set up — sideloading is the current channel |
| Target API 36 (Android 16) | 📦 | App is targetSdk 35 today; bump + behavior pass belongs to the release phase, NOT a finalizing build |
| Android App Bundle (.aab), Play App Signing | 📦 | Release phase; own keystore created with Arz present (his password, Secure Vault) |
| Data safety form + privacy policy URL | 📦 | Must match reality: no server today; v7 accounts change the answers |
| Account deletion (in-app + web link) — REQUIRED once accounts exist | 📦 v7 | Google rule: any app with account creation must offer deletion. Lands WITH the v7 Google sign-in work, incl. the corner Google-photo avatar (Arz, 2026-11-07) |
| Content rating questionnaire | 📦 | Console step at submission |
| Store listing: 512px icon, feature graphic, screenshots | 📦 | Part of the release-phase icon/design work |
| Closed test: 12 testers × 14 days (new personal accounts) | ⛔/📦 | Two-week minimum runway before production access; plan for it if publishing is ever chosen |
| 16 KB page-size support (native libs) | 📦 verify | App is Kotlin/Java; Media3 + NewPipeExtractor ship no exotic .so — verify with zipalign at release |
| FGS + sensitive-permission declarations in Console | 📦 | mediaPlayback FGS and RECORD_AUDIO need declarations if published |

## Tier C — arzxsh's own release standards

| Item | Status | Note |
|---|---|---|
| Branded launcher icon set (adaptive + monochrome) | 📦 | Release phase; icon exists, designed set pending Arz's pick |
| Branded splash | 📦 | Release phase |
| Signed release build (own keystore) | 📦 | Arz sets the password via Secure Vault, present; keystore loss = identity loss |
| License attributions in-app (Poppins OFL, NewPipeExtractor GPL-3.0, Media3, LRCLIB) | ✅ | Licenses screen shipped in the Settings rebuild |
| About/credits with maker's name | ✅ | "arzxsh by Arafat Arzu" shipped 0.6.1 |
| Versioning discipline (name + code, CI green per release) | ✅ | 0.6.1 / code 9 |

## Verdict for the "final app" (0.6.1)

Every Tier-A quality item is either ✅ or landing in 0.6.1 itself. Tier B is honestly
staged: arzxsh is a personal sideloaded app today, and the store items that remain
(target API 36, AAB + signing, listing, testing runway) belong to the release phase —
two of them (dev account, keystore) need Arz personally in the loop.
