# Calm Read

An Android app for swiping through news and Reddit stories Tinder-style — **text only, no images or video**.
(It started as "Reddit Swipe"; the repo, package name and APK files keep that name so updates keep installing.)

## Why
A calmer replacement for Instagram and other image-heavy social apps: something to do with your thumb that doesn't pull you into an endless visual feed.

## Core idea
- One card on screen at a time showing a Reddit text post (title + body, subreddit, score).
- Swipe right: like (liked cards are kept under Liked in the menu).
- Swipe left: skip. Until the first swipe, a hint under the card says which way does what.
- Only story-length text posts (300+ characters) from story subreddits like TrueOffMyChest, tifu and AITAH; no question subs where the content is in the comments.
- Quick-read subreddits (Showerthoughts, LifeProTips, YouShouldKnow, Jokes, DadJokes) keep short posts too, shown in larger centered type; add them from the chips on the Subreddits screen (`QUICK_READ_SUBREDDITS` in `data/RedditRepository.kt`).
- News articles too, from about 35 sites grouped into Local (Mazatlán: Mazatlán Interactivo, Línea Directa and a Google
  News search for the city), Center, Liberal, Conservative, World, Business, Tech,
  Science & health and Sports. The News sources screen switches a whole category or single sites on and off
  (`NEWS_FEEDS` in `data/NewsRepository.kt`). Sites marked "full" send whole articles; the rest send a headline and
  summary with a "Hold for full article ↗" link (a long press, so a stray tap never leaves the app). Feeds are read straight from the publishers.
- A News / Stories switch above the cards picks what to deal; the ☰ menu picks the story length (All, Quick or Long).
- Text size is one setting: pinch a card with two fingers or use A−/A+ in Settings.
- Removing a saved post shows an Undo.
- ↶ at the top left undoes the last swipe (up to 30).
- Menu screens (Liked, News sources, Subreddits, Stats, Settings) swipe away to the right to go back to the cards
  (`ui/SwipeBack.kt`); Android's back gesture slides them the same way.
- ☰ menu, top right: Swipe, Liked, News sources, Subreddits, Stats (stories and minutes read per day), Settings
  (text size, serif font, NSFW stories, download stories for offline reading).
- Liked cards, swiped history (so stories don't repeat, crossposts and shared articles included), the unread deck (offline cache) and stats are kept on the phone.

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
Calm Read install apps; after that each update is one confirm tap. (Fully silent updates aren't possible
for apps outside the Play Store.)

Install v0.1 from the GitHub Release (not the debug artifact): updates only install over an app signed with
the same release key. The key lives outside this repo in `../reddit-swipe-signing/`. Back it up.

## Running it from Android Studio
1. Install [Android Studio](https://developer.android.com/studio) (it bundles Java and the Android SDK).
2. **File → Open** and pick this `reddit-swipe` folder. Let the Gradle sync finish.
3. Plug in your phone with USB debugging on (or start an emulator) and press **Run**.

## Code map
- `app/src/main/java/com/redditswipe/data/RedditRepository.kt`: fetches listings and keeps only story-length text posts, flagging NSFW ones
- `data/NewsRepository.kt`: reads the news sites' RSS/Atom feeds and turns articles into plain-text cards
- `SwipeViewModel.kt`: the card deck, saved list, NSFW setting, and the subreddits to pull from (edit `storySubreddits` / `nsfwSubreddits`)
- `ui/SwipeCard.kt`: the story card (typography, SAVE/SKIP stamps) and `CardSwipeState` (swipe thresholds and decisions)
- `ui/SwipeScreen.kt`: card stack, read-mode switch, drag/fling gestures and exit animations
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
