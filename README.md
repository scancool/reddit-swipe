# Reddit Swipe

An Android app for swiping through Reddit posts Tinder-style — **text only, no images or video**.

## Why
A calmer replacement for Instagram and other image-heavy social apps: something to do with your thumb that doesn't pull you into an endless visual feed.

## Core idea
- One card on screen at a time showing a Reddit text post (title + body, subreddit, score).
- Swipe right: save / like.
- Swipe left: skip.
- Swipe up (or tap) on "Next" between the ✕ and ♥ buttons: move on to the next story.
- Only story-length text posts (300+ characters) from story subreddits like TrueOffMyChest, tifu and AITAH; no question subs where the content is in the comments.
- Quick-read subreddits (Showerthoughts, LifeProTips, YouShouldKnow, Jokes, DadJokes) keep short posts too, shown in larger centered type; add them from the chips on the Subreddits screen (`QUICK_READ_SUBREDDITS` in `data/RedditRepository.kt`).
- A Quick reads / Mixed / Long reads switch above the cards picks which kind of post to deal; it's remembered.
- Pinch the story text with two fingers to zoom it (the title stays put); Settings shows the zoom and can reset it.
- ↶ in the top bar undoes the last swipe (up to 30).
- ☰ menu: Swipe, Saved, Subreddits (add/remove), Stats (stories and minutes read per day), Settings
  (text size, serif font, NSFW stories, download stories for offline reading).
- Saved posts, swiped history (so stories don't repeat), the unread deck (offline cache) and stats are kept on the phone.

## Stack
- Kotlin + Jetpack Compose (Material 3), minSdk 26, targetSdk 35
- Reddit's official API, reached through a small Cloudflare Worker relay (`relay/`) that keeps the Reddit app secret off the phone
- Local storage: JSON files in the app's private folder plus SharedPreferences for settings (`data/LocalStore.kt`)

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
- `app/src/main/java/com/redditswipe/data/RedditRepository.kt`: fetches listings and keeps only story-length text posts, flagging NSFW ones
- `SwipeViewModel.kt`: the card deck, saved list, NSFW setting, and the subreddits to pull from (edit `storySubreddits` / `nsfwSubreddits`)
- `ui/SwipeCard.kt`: the story card (typography, SAVE/SKIP stamps) and `CardSwipeState` (swipe thresholds and decisions)
- `ui/SwipeScreen.kt`: card stack, drag/fling gestures, exit animations, skip/save buttons and the Next zone
- `ui/FeedEndCard.kt`: what sits under the last card: loading placeholder, error, no subreddits, or end of feed
- `ui/SavedScreen.kt`: saved posts; tap one to open it on Reddit

## Reddit relay (`relay/`)
Reddit blocks unauthenticated requests (HTTP 403) and no longer lets new API apps be created without
approval, so the app talks to a Cloudflare Worker at `https://reddit-swipe-relay.abella.workers.dev`.
The Worker uses an existing Reddit "web app" (client credentials) whose ID and secret are stored as
Worker secrets, never in this repo. It only serves subreddit listings (`GET /r/<sub>/hot|new|top|rising`)
and caches responses for 2 minutes.

Redeploy after changing it:
```bash
cd relay && npx wrangler deploy
```

## Ideas for later
- Keep saved posts on the device (Room)
- Let the user choose subreddits in the app
- Daily swipe limit / "take a break" reminder
