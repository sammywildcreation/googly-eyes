# Googly Eyes: a desktop companion for your Mac and Android phone

A little blueberry character lives on your Android phone, propped up under your Mac's screen. His eyes follow
your mouse. Hold his face and talk, or type, and he looks at your Mac's screen and answers, or does things on
the Mac for you.

This project builds on [rbrown101010/bluey-by-riley](https://github.com/rbrown101010/bluey-by-riley) (public
domain). His project has a Mac app and an iPhone app. This version keeps the Mac app, adds an **Android app**, and adds:

- **Silent mode.** Nothing appears on the Mac: no cursor, speech bubbles, research cards or sounds. He still
  looks at your screen and does what you ask, but every answer shows **only on your phone**. You can type instead
  of talking.
- **Your own name and colour.** Call him anything and pick any colour, from the phone or the Mac menu.
- **Focus mode is off by default.** Turn it on in Settings when you want the focus timer and nudges.
- **He asks before anything important.** He checks with you before sending, posting, replying, deleting, buying
  or submitting anything for you.

---

## What you need

- A Mac running macOS 14 (Sonoma) or newer
- An Android phone running Android 8 or newer, on the **same Wi-Fi** as the Mac
- An **OpenAI API key** with some credit: [platform.openai.com](https://platform.openai.com) → API keys.
  This is billed separately from a ChatGPT subscription. Set a monthly limit under Settings → Billing → Limits.

## Part 1: Set up the Mac app

**1. Install Apple's free developer tools.** Open **Terminal** (press ⌘-Space, type "Terminal", press Return)
and paste:

```bash
xcode-select --install
```

Click **Install** in the window that pops up and wait for it to finish. If it says the tools are already
installed, that's fine.

**2. Get this project onto your Mac.** If you downloaded it as a ZIP, unzip it into your **home folder**, not
Documents or Desktop. iCloud syncing in those folders can break the build. Then, in Terminal:

```bash
cd ~/bluey
```

(Use the folder name you unzipped to.)

**3. Build the app:**

```bash
./scripts/build-mac.sh
```

The first build takes a few minutes. When it finishes it says `Built build/GooglyEyes.app`.

**4. Start it:**

```bash
open build/GooglyEyes.app
```

A small blueberry icon appears in your Mac's **menu bar** (top-right of the screen). There's no window and no
Dock icon; everything happens from that menu.

**5. Add your OpenAI key.** It asks automatically the first time, or use menu bar icon → **OpenAI Key…**. The
key stays private on your Mac and is never sent to the phone.

**6. Allow permissions.** macOS asks for:
- **Screen Recording.** Needed so he can see your screen. Turn it on for GooglyEyes in System Settings →
  Privacy & Security → Screen & System Audio Recording, then quit and reopen the app.
- **Accessibility** (optional). Needed only if you want him to click and type for you. Menu bar icon →
  **Allow Clicking and Typing (Accessibility)…**
- **Local Network.** Click Allow, so the phone can find the Mac.

> Tip: to start it automatically, open System Settings → General → Login Items and add `GooglyEyes.app`
> from the project's `build` folder.

## Part 2: Install the Android app

**1. Install [Android Studio](https://developer.android.com/studio)** (free) on your Mac and open it once so it
finishes downloading its components.

**2. Open the project:** File → Open → choose the **`Android`** folder inside this project. Wait for it to
finish "syncing" (a progress bar at the bottom). The first time can take several minutes.

**3. Turn on developer mode on your phone:** Settings → About phone → tap **Build number** 7 times. Then in
Settings → System → Developer options, turn on **USB debugging**.

**4. Plug the phone into the Mac** with a USB cable and tap **Allow** on the phone when it asks.

**5. Press the green ▶ Run button** in Android Studio (with "app" selected at the top). The app installs and
opens on your phone. After this you can unplug it; the app stays installed.

## Part 3: Pair them

1. On the Mac: menu bar icon → **Pair Phone (QR)…** A QR code appears.
2. On the phone: open **Googly Eyes** → **Scan pairing code** → point it at the QR code.
3. The dot next to his name turns green when they're connected.

Keep the QR code private: it's the key that links your phone to your Mac.

## Using it

| To… | Do this |
|---|---|
| Talk to him | **Hold his face**, speak, then let go |
| Type to him | Tap the **keyboard** button (top right) |
| Wake him / send him to sleep | **Double-tap** his face |
| Turn on silent mode | Settings → **Silent mode**, the **Silent** chip in Chat, or the Mac menu |
| Change his name or colour | Settings → Name / Colour (or the Mac menu) |
| See past conversations | The **clock** button |
| Stop him doing something on the Mac | Press **Control-Option-S** on the Mac |
| Focus timer | Settings → turn on **Focus mode**, then the timer button |

Things to try:
- "What's this error?" (with your mouse over it)
- "Summarise this page."
- "What does this email want from me?"
- "Open Notes and write a shopping list: eggs, milk, bread."
- "Reply to this email saying I'll be there." He drafts it and asks "Send it?" before sending.

### Silent mode in detail

When silent mode is on:
- Nothing is drawn on the Mac: the cursor, speech bubbles and research cards are all hidden.
- No chirp sounds from the phone.
- Answers appear in a card on the phone's face screen and in the Chat screen, and are saved in History.
- He can give longer, more detailed answers, since you're reading them on the phone rather than in a tiny bubble.
- He still clicks and types when you ask. You just won't see his cursor doing it.

## What it costs

You pay OpenAI per use, from your API credit. These are rough estimates, so check your usage at platform.openai.com:

- A question about your screen (one screenshot plus an answer): about **1–3 US cents**
- Talking: a few cents per minute of speech
- Focus mode while a session runs: it checks your screen every 30 seconds, so up to about **$1 per hour**.
  That's why it's off by default.

## Privacy

- Your screen is only sent to OpenAI when you ask about it, or every 30 seconds during a focus session.
  Screenshots aren't saved by the app.
- The phone and Mac talk over your local Wi-Fi with encryption, using the key in the pairing QR code.
- Your OpenAI key stays on the Mac. The phone only ever gets short-lived keys.
- Conversations are saved only on your phone (History). You can delete them there.

## Troubleshooting

- **The phone can't find the Mac.** Make sure both are on the same Wi-Fi (not a guest network) and that the Mac app is running
  (blueberry in the menu bar). Some office or school networks block devices from seeing each other.
- **"Couldn't connect. Check the Mac app…"** Check your OpenAI key (Mac menu → OpenAI Key…) and that your account has credit.
- **"I can't see the screen."** Turn on Screen Recording for GooglyEyes in System Settings, then quit and reopen the app.
  After rebuilding the Mac app you may need to turn it off and on again.
- **He won't click or type.** Allow Accessibility (Mac menu → Allow Clicking and Typing), and make sure
  "Let Him Use the Computer" is ticked.

## Project layout

- `Mac/`: the Mac menu bar app (Swift)
- `Shared/`: code the Mac app shares with the original iPhone app: connection, encryption, colours
- `Android/`: the Android app. `Android/core` is the connection and encryption code (plain Kotlin, with tests);
  `Android/app` is the app itself (Jetpack Compose).
- `iOS/`: the original iPhone app (unchanged, not needed for Android)
- `docs/UPSTREAM-README.md`: the original project's README

To run the Android connection tests: `cd Android && ./gradlew :core:test`

## License

Public domain under [the Unlicense](LICENSE), like the original project.
