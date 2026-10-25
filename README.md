# Reddit Swipe

An Android app for swiping through Reddit posts Tinder-style — **text only, no images or video**.

## Why
A calmer replacement for Instagram and other image-heavy social apps: something to do with your thumb that doesn't pull you into an endless visual feed.

## Core idea
- One card on screen at a time showing a Reddit text post (title + body, subreddit, score).
- Swipe right: save / like.
- Swipe left: skip.
- Image, video and link posts are filtered out; only self/text posts are shown.

## Stack
- Kotlin + Jetpack Compose (Material 3), minSdk 26, targetSdk 35
- Reddit's public JSON listings (`https://www.reddit.com/r/<sub>/hot.json`), no login
- No database yet: saved posts live in memory and are lost when the app is closed

## Building on GitHub
- Every push to `main` builds a debug APK (Actions → Build → artifact `RedditSwipe-debug`).
- **Releasing a new version:** bump nothing in code, just tag and push:
  ```bash
  git tag v0.2 && git push origin v0.2
  ```
  The Release workflow builds a signed APK with that version and attaches it to a GitHub Release.

## Auto-update
On launch the app checks the latest release of `scancool/reddit-swipe`. If its version is newer, it offers
**Update**, downloads the APK, and opens Android's installer. The first time, Android asks you to let
Reddit Swipe install apps; after that each update is one confirm tap. (Fully silent updates aren't possible
for apps outside the Play Store.)

Install v0.1 from the GitHub Release (not the debug artifact): updates only install over an app signed with
the same release key. The key lives outside this repo in `../reddit-swipe-signing/`. Back it up.

## Running it from Android Studio
1. Install [Android Studio](https://developer.android.com/studio) (it bundles Java and the Android SDK).
2. **File → Open** and pick this `reddit-swipe` folder. Let the Gradle sync finish.
3. Plug in your phone with USB debugging on (or start an emulator) and press **Run**.

## Code map
- `app/src/main/java/com/redditswipe/data/RedditRepository.kt`: fetches listings and keeps only text posts (no images, videos, links, NSFW or pinned posts)
- `SwipeViewModel.kt`: the card deck, saved list, and the subreddits to pull from (edit `subreddits` to change them)
- `ui/SwipeCard.kt`: draggable card with tilt, SAVE/SKIP labels and fly-out animation
- `ui/SwipeScreen.kt`: card stack plus skip/save buttons
- `ui/SavedScreen.kt`: saved posts; tap one to open it on Reddit

## Known issue: Reddit may block the requests
When this was set up, Reddit answered a test request from the dev Mac with **HTTP 403**. Reddit restricts
unauthenticated API access, and whether a phone on a home or mobile network gets through isn't known yet.
If the app shows "returned HTTP 403", the fix is Reddit's official OAuth API: register an app at
reddit.com/prefs/apps (this may need approval from Reddit) and switch the repository to `oauth.reddit.com`.

## Ideas for later
- Keep saved posts on the device (Room)
- Let the user choose subreddits in the app
- Daily swipe limit / "take a break" reminder
