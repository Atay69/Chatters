# 💬 Chatters

A chat app written in **plain Java** (Swing windows + sockets). One computer runs the **server** 🖥️, everybody else opens the **client** 🙋 and chats – in a group or privately.

---

## ✨ What it can do

| | Feature |
|---|---|
| 👥 | **GroupChat** for everybody and **private chats** between two people |
| 🔐 | Accounts with name + password (passwords are stored only as salted hashes) |
| 😀 | Emoji, with a picker |
| 🖼️ | Images – shown right inside the chat |
| 📎 | Files of **any size** (even over 2 GB), sent in small chunks, with a **Cancel** button |
| 🎤 | Voice messages |
| 🧑 | **Profile pictures** that everybody sees – click your profile card to change yours |
| 🟢 | **Status**: 🌙 Away · 🟢 Online · ⚪ Invisible · ⛔ Do not disturb |
| 🎨 | Dark / light theme, **6 accent colours**, soft **wallpapers** (defaults included + add your own) |
| 🔔 | Unread badges (+1 … +9) and a Messages list with previews |
| 🤖 | Optional AI helper hooks (reply suggestions, finish a sentence, ask a question) |

Everything in the 🎨 row (theme, colour, wallpaper) is remembered **per user, per computer**.

---

## 🚀 Quick start

### 1️⃣ Start the server (one computer only)
1. Open the project in **IntelliJ IDEA** (JDK 17 or newer – the project uses 27).
2. Run **`Server`** (`src/Server.java`) and leave it running.
3. It prints your **Local IP Address** 📍 – share it with the others. The port is **5000**.
4. If Windows asks, allow Java through the **firewall** 🔥.

### 2️⃣ Start a client (everybody)
* **From IntelliJ:** run **`ClientGUI`** (`src/ClientGUI.java`).
* **Or with the jar:** double-click **`Chatter.jar`** (needs **Java 8 or newer** ☕) or run:
  ```
  java -jar Chatter.jar
  ```
* In the sign-in window type your **account name**, **password** and the **server IP** 📍, then press **Sign in** (or **Create account** the first time).

> 💡 Running the server and a client on the same computer? Use `127.0.0.1` as the server IP.

---

## 🌍 Chatting from other computers

| Situation | What to do |
|---|---|
| 🏠 Same Wi-Fi / network | Just type the server's local IP in the sign-in window |
| 🌐 Different networks | Forward **port 5000** on the server's router, or use a VPN such as **Tailscale** / **ZeroTier** |
| ⚠️ Safety | The connection is **not encrypted** – prefer a VPN over opening the port to the whole internet |

Everybody must use the **same version** of the app (server and clients). The easiest way: share the repo or the newest `Chatter.jar`.

---

## 🧭 Using the app

* 💬 **Send a message:** type in the bar at the bottom, press **Enter** or **Send**.
* 😀 **Emoji / 🖼️ image / 📎 file / 🎤 voice / ✨ AI:** the icons inside the message bar.
  * 🎤 click once to record, click again to send.
  * 📎🖼️ pick a file – a progress bar appears with a **Cancel** ❌ button.
  * Received files show **Download**, voice messages show **Play**, pictures appear inside the bubble.
* 🙋 **Private chat:** click a person in the **Messages** list on the left.
* 🧑 **Change your picture:** hover over your profile card (bottom left) – it darkens and says *Change profile picture* – then click it.
* 🟢 **Change your status:** click the small status symbol on the right of your profile card.
* ☰ **Appearance menu** (burger button, top right): *Add wallpaper…* · *Next wallpaper* · *Color* · *Light/Dark mode*.
* 🪟 **Window buttons** (top right): ➖ minimize · ⬜ full screen (click again to restore) · ✖ close. Drag the top bar to move the window.

---

## 🖼️ Wallpapers

* 🎁 The **default** wallpapers are inside the app (`src/wallpapers/`, names in `list.txt`), so every computer has them.
* ➕ **Add wallpaper** copies your picture to `<your home folder>/.chatters/wallpapers/` – they stay on your computer.
* To make a new **default** for everybody: put the picture in `src/wallpapers/`, add its name to `src/wallpapers/list.txt`, then rebuild the jar.

---

## 📦 How big files travel

1. 📤 The file is cut into **256 KB chunks** and sent one after another.
2. 💾 The server writes every chunk straight to disk (`chatter-data/files/`) – it never holds the whole file in memory.
3. 📨 Everybody (or just the private chat partner) gets a message pointing to the file.
4. 📥 They press **Download** and the file streams back in chunks.

There is no size limit except free disk space ✅, and chat keeps working during a transfer. A 2.6 GB test file went up and came back byte-for-byte identical.

---

## 🗂️ Project map

```
src/
  Server.java · ClientGUI.java   ▶️ the two "Run" buttons
  shared/    📡 packet format and constants (ChatProtocol, Frames)
  server/    🖥️ ChatServer, ClientHandler, FileStore, UserStore, PrivateHistory
  client/    🪟 the window: ChatWindow, Sidebar, MessageView, Composer, ChatClient, TitleBar, Theme ...
  ai/        🤖 where your own AI plugs in
  images/ emoji/ wallpapers/     🎨 pictures
Chatter.jar          📦 ready-to-run client (Java 8+)
chatter-data/        🔒 created by the server: accounts, history, avatars, files (never committed)
PROJECT_GUIDE.md     📖 beginner guide to the code
DEVELOPMENT_LOG.md   📝 everything that was built and why
```

---

## 🛠️ Build the jar yourself

Compile the **client**, **shared** and **ClientGUI** code for Java 8, add the `images/`, `emoji/` and `wallpapers/` folders and use `Main-Class: ClientGUI`:

```
javac --release 8 -encoding UTF-8 -d build <client + shared + ClientGUI sources, without *Test.java>
(copy src/images, src/emoji, src/wallpapers into build/)
jar cfm Chatter.jar manifest.txt -C build .
```

`manifest.txt` contains one line: `Main-Class: ClientGUI`

---

## 🩺 Troubleshooting

| Problem | Fix |
|---|---|
| ❌ "A Java Exception has occurred" when double-clicking the jar | Use the latest `Chatter.jar` (built for Java 8+) and make sure Java is installed |
| 🚫 "Address already in use" when starting the server | Another server is still running on port 5000 – close it (Task Manager → `java.exe`) |
| 🔌 "Could not connect" | Check the IP, that the server is running, and the firewall / router port 5000 |
| 👤 "That display name is already in use" | That account is already signed in somewhere else |
| 🖼️ No wallpaper | Pick one in the ☰ menu – the first start uses *graphite* |

---

## 🧰 Good to know

* 🔁 Uploads can be cancelled but not resumed after a lost connection.
* 🌙 Status isn't remembered between sign-ins; *Away* and *Do not disturb* are labels.
* 🗄️ Files stay on the server's disk until you delete them from `chatter-data/files/`.
* 📝 Full details, protocol table and history of changes: see **DEVELOPMENT_LOG.md**.
