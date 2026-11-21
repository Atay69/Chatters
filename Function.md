# 🧩 Function.md – how Chatters works (the simplified version)

This file explains **every important part of the project in plain words**: what it does, which files hold it, and how it works step by step.
It is updated every time we change something (the day-by-day history is in `DEVELOPMENT_LOG.md`, how to use the app is in `README.md`).

> 💡 Reading tip: start with **1. The big picture**, then jump to the feature you care about.

---

## 1. The big picture

```
 ClientGUI ─► ChatWindow ─► ChatClient ══ socket (port 5000) ══ ClientHandler ◄─ ChatServer
  (window)    (screen)     (packets)                           (one per client)  (shared data)
```

* **Server** (`src/server/`): runs on one computer, keeps accounts and history on disk (`chatter-data/`), and forwards messages.
* **Client** (`src/client/`): the window every person sees.
* **Shared** (`src/shared/`): the "language" both sides speak (`ChatProtocol`).
* Everything that travels is a **packet**: `type, sender, timestamp, metadata, [id], [bytes]`. The list of types is at the top of `ChatProtocol.java`.

| Folder | Holds |
|---|---|
| `src/shared` | `ChatProtocol` (types + constants), `Frames` (builders), `AppInfo` |
| `src/server` | `ChatServer` (start-up, data, broadcasts), `ClientHandler` (one thread per client), `FileStore`, `UserStore`, `PrivateHistory`, `ChangeLog`, `ProfanityFilter` |
| `src/client` | window, conversation list, message view, composer, connection, icons, animations, dialogs |
| `src/images`, `src/emoji`, `src/wallpapers` | pictures (inside the jar too) |

---

## 2. Signing in

* **Files:** `LoginDialog` (client), `ChatClient.login`, `ClientHandler.signIn`, `UserStore`.
* The client opens a socket and sends one `TYPE_AUTH` packet with `LOGIN` or `REGISTER` + name + password.
* `UserStore` checks it. Passwords are never stored as text, only as a **salted PBKDF2 hash**.
* The server answers `TYPE_AUTH` (ok) or `TYPE_LOGIN_ERROR` (with the reason, e.g. "Wrong account name or password").
* After success the server sends the **saved history**, then announces "X joined" and the user list.

## 3. Sending and receiving messages

* **Files:** `Composer` (the bar at the bottom), `ChatClient.sendText`, `ClientHandler.handleTextMessage / handlePrivateMessage`, `ChatWindow.handlePacket`.
* GroupChat text → `TYPE_TEXT`; private text → `TYPE_PRIVATE` (metadata = recipient).
* The server **saves** the message (`ChatServer.persistGroupMessage` / `PrivateHistory.add`), gives it an **id**, then sends it to everybody (GroupChat) or only the two people (private).
* The client keeps one list per conversation (`ChatWindow.groupEntries`, `privateEntries`) and draws it with `MessageView`.
* `ChatEntry` = one message. It also recognises messages that are really **stored files** (see section 6).

## 4. Edit and delete (your own messages)

* **Files:** `MessageView` (right-click menu), `Composer` (edit mode), `ChatWindow` (`applyEdit/applyDelete`), `ClientHandler.handleMessageEdit/Delete`, `ChangeLog`.
* Every message has an **id** (group and private count separately, never reused). Message packets carry it (`ChatProtocol.hasMessageId`).
* Right-click your message → **Edit** (text only) or **Delete**.
  * *Edit:* the text returns to the message box, Send becomes **Save**, Esc cancels. The client sends `TYPE_MSG_EDIT`.
  * *Delete:* a confirmation, then `TYPE_MSG_DELETE`.
* The server checks **you are the author**, applies the change and tells everybody concerned (`TYPE_MSG_EDITED` / `TYPE_MSG_DELETED`). Deleting a file message also deletes the stored file.
* **Saved for good:** every change is appended to `group-changes.bin` / `private-changes.bin` (`ChangeLog`) and replayed when the server starts.

## 5. Private chats

* A private chat is just a conversation key = the other person's name.
* Text, images, files, voice messages and emoji all work in private chats; only the two people receive them (the server sends to "me + recipient").
* History is saved in `private-messages.bin`; edits/deletes in `private-changes.bin`.

## 6. Files of any size (upload, cancel, download)

* **Files:** upload in `ChatClient.sendFile/sendBytes` + `Composer.upload`; server in `ClientHandler.handleFile*` + `FileStore`; download in `ChatClient.download` + `FileTransfers`.
* **Upload:** the file is cut into **256 KB chunks**: `FILE_BEGIN` (who gets it, name, size) → many `FILE_CHUNK` → `FILE_END`. Chat messages can slip in between chunks.
* The server writes every chunk **straight to disk** (`chatter-data/files/<id>.part`, renamed to `<id>` when complete). The file is never in memory, so there is no size limit except disk space. (Tested with 2.6 GB while the server had only 64 MB of RAM.)
* The server then posts a normal chat message that starts with a marker character and holds `id|kind|size|name` (`ChatProtocol.fileMessage`). `ChatEntry` recognises it.
* **Cancel:** the **Cancel** button calls `Upload.cancel()`; the client stops after the current chunk and sends `FILE_END = 0`; the server deletes the half file and posts nothing.
* **Download:** `FILE_GET` → the server streams the file back in chunks (`FILE_DATA`, `FILE_DONE`) from its own thread, so chat keeps flowing.
* In the chat: pictures under 4 MB show inline, files have a **Download** button, voice messages a **Play** button.

## 7. The file windows (choose a file / save as)

* **File:** `NativeFileDialog`.
* On Windows it opens the **real modern Windows "Open" / "Save as" window** (the same one Discord, Chrome and Word use). Java's own `FileDialog` is the old Windows 7 look, so we ask Windows itself through a tiny PowerShell script (WinForms `OpenFileDialog` / `SaveFileDialog`).
* The window texts are passed in environment variables (nothing is pasted into the script). The program keeps repainting while the window is open (a "secondary event loop"). Cancel = no file.
* Other systems, or any failure, fall back to Java's `FileDialog`.
* Used by: attach/image buttons (`Composer`), profile picture (`Sidebar`), add wallpaper (`Appearance`), Download (`FileTransfers`).

## 8. Profile pictures

* **Files:** `Sidebar` (click the profile card), `ProfileAvatar`, `ClientHandler.handleAvatar`, `ChatServer.saveAvatar/sendAvatars`.
* Hover your profile card → it darkens and says *Change profile picture*; click → pick an image.
* It is cropped to a square, shrunk to 128 × 128 and sent (`TYPE_AVATAR`, max 256 KB). The server saves it (`chatter-data/avatars/`) and sends it to **everybody**; people who sign in later get all saved pictures.
* `ProfileAvatar` paints the picture (or the grey silhouette if there is none) and the small status symbol.

## 9. Status (Away / Online / Invisible / Do not disturb)

* **Files:** `Sidebar` (menu), `StatusIcon` (symbols), `ClientHandler` (`status` field), `ChatServer.broadcastUserList`.
* Click the symbol on your profile card. The client sends `TYPE_STATUS`; the server stores it and sends the user list to everybody.
* *Invisible* looks **offline** to everyone else. Symbols: green circle, white half moon, grey circle, red circle with a line.

## 10. Look: theme, accent colour, wallpapers (saved per user)

* **Files:** `Appearance` (choices + burger menu), `Theme` (colours, accent, font), `WallpaperManager`, `Settings` (saved values), `Widgets.WallpaperPanel`.
* The burger button (top right) opens: *Add wallpaper…*, *Next wallpaper*, *Color*, *Light/Dark mode*.
* **Saved per user on this computer** (`darkMode.<name>`, `accent.<name>`, `wallpaper.<name>`).
* **Wallpapers:** the default ones are inside the program (`src/wallpapers`, names in `list.txt`), so every computer has them. Your own wallpapers are saved in `<home>/.chatters/wallpapers`. The list is *defaults first, then yours, then "Off"*.
* **Accent colour** (6 choices) colours the Send button, the message-box outline, upload bar, unread badges and the dim outline around every message bubble.
* **Font:** one helper, `Theme.font(...)` (Segoe UI Semibold for text, Bold for names/titles).

## 11. Window parts

* **Title bar** (`TitleBar`): our own bar (no Windows frame) with title, minimize, full screen, close; drag to move.
* **Icons** (`LineIcon`): file, image, emoji, microphone, AI, burger – drawn with lines in the theme colour.
* **Messages list** (`Sidebar`, `ConversationList`): GroupChat + people with preview, time, unread badge, status dot.
* **Message bubbles** (`MessageView`, `Widgets.Bubble`): own messages right, others left; text, emoji pictures, images, files, voice.

## 12. Animations (smooth feel)

* **Files:** `Animation` (helpers), `Widgets.AnimatedRow`, `MessageView`.
* New messages **slide up and fade in**; the chat **scrolls down smoothly**; a deleted message **fades out** while the others slide up.
* Hover effects **glide** (`Animation.HoverFade`) on buttons, list rows, title-bar buttons and the profile card.
* Saved history and conversation switches appear instantly (no animation) to stay fast.

## 13. Data on the server (`chatter-data/`, never committed)

| File | Holds |
|---|---|
| `users.bin`, `accounts.bin` | who exists, password hashes |
| `group-history.bin` + `group-changes.bin` | GroupChat messages + edits/deletes |
| `private-messages.bin` + `private-changes.bin` | private messages + edits/deletes |
| `avatars/` | profile pictures |
| `files/` | uploaded files (`<id>`) |

## 14. Build and run

* **Server:** run `Server` in IntelliJ (port 5000). **Client:** run `ClientGUI` or double-click `Chatter.jar` (Java 8+).
* **Jar:** compile `client` + `shared` + `ClientGUI` (no tests, no `ai`) with `--release 8`, add `images/`, `emoji/`, `wallpapers/`, manifest `Main-Class: ClientGUI`.

---

## 15. The AI assistant

* **Files:** `src/ai/` (`AiAssistant`, `AiContext`, `AiService`, `GroqAssistant`), `ClientHandler.submitAi` (server), and `ChatWindow`, `Composer`, `ConversationList` (client).
* **Brain:** `GroqAssistant`, which calls Groq's free hosted models. It is the default: no start-up option is needed, only the key.

### Three ways to use it
| Where | What happens |
|---|---|
| **"AI Assistant" row in the sidebar** (right under GroupChat) | A private-style chat with the AI. Your messages are on the right, its answers on the left like a person. The AI remembers the last 20 lines of the talk. |
| `/ai question` or `@ai question` in any chat, or the sparkle button | One-off question; the answer appears as a grey notice line in that chat. |
| Reply chips above the box, and TAB in the message box | Three suggested replies to the last message; TAB finishes your sentence. |

### How the "AI Assistant" conversation works
```
 you type in the AI chat                         server                       Groq
   Composer.sendText ─► ChatWindow.askAi ─► TYPE_AI_QUERY ('T') ─► ClientHandler.submitAi ─► GroqAssistant.ask
        (shows your message at once)                                         │ answer
   ChatWindow.showAiReply ◄─ TYPE_AI_REPLY ('T') ◄──────────────────────────┘
        (shows it as a message from "AI Assistant")
```
* The AI chat is **not a real user**: it is a name constant (`ChatProtocol.AI_NAME`) that `ConversationList` shows as a row and `ChatWindow` stores like a private chat (`privateEntries`). Nothing is saved in `private-messages.bin`.
* **Memory:** the server keeps this client's talk in `ClientHandler.aiChat` (lines `name: text`) and sends the last 20 to the AI with each question. It lasts as long as you stay connected; messages are not kept after you close the window.
* Text only: the file, image and voice buttons are switched off there (`Composer.setTextOnly`). You cannot edit or delete these messages (they have no server id).
* The row appears only when the server has an AI key (`ChatClient.aiAvailable`).

### The flow of the other features (`/ai`, sparkle, chips, TAB)
`Composer` ─► `TYPE_AI_QUERY` (kind `A`, `S` or `C`) ─► `ClientHandler.submitAi` ─► `AiService` (background thread, profanity filter) ─► `AiAssistant` ─► `TYPE_AI_REPLY` back to the same client. Errors become "The assistant failed: …"; the server never crashes. The packet kinds are listed in `ChatProtocol` (`AI_SUGGEST`, `AI_COMPLETE`, `AI_ASK`, `AI_CHAT`).

### Turning it on (server computer only)
1. Free account at `console.groq.com` → **API Keys → Create API Key**.
2. IntelliJ → **Run → Edit Configurations → Server → Environment variables:** `GROQ_API_KEY=your-key`. Never put the key in a source file (it would end up in git).
3. Run the **Server** configuration (not "Current File"; it has no variables). Restart the client.
* No key = the AI row and all AI buttons are hidden, and `/ai` answers "AI not configured yet."

### How `GroqAssistant` works inside (every step is commented in the file)
1. Each method builds a short **prompt** (recent chat + the job).
2. `callGroq` writes a small JSON body (`model`, `max_tokens`, `reasoning_effort`, `messages`) and POSTs it to `https://api.groq.com/openai/v1/chat/completions` with `Authorization: Bearer <key>`.
3. Groq answers `{"choices":[{"message":{"content":"…"}}]}`; `extractText` reads the text. `quote` / `extractText` are the only JSON code (no library).

### Things you may want to change (top of `GroqAssistant.java`)
* `MODEL` – **Groq retires model names now and then** (`API error 404 model_not_found`). Current list: `https://console.groq.com/docs/models`, or `GET https://api.groq.com/openai/v1/models` with your key. Now: `openai/gpt-oss-20b` (fast) / `openai/gpt-oss-120b` (smarter).
* `SYSTEM` – the AI's personality ("answer in Spanish", "be funny" …).
* `maxTokens` per method (800 / 400 / 300): the thinking of these models counts toward it, so keep it generous.
* How many lines of memory the AI gets: the `20` in `ClientHandler.submitAi`.

### Using a different brain
Implement `AiAssistant` (3 methods, public no-argument constructor) and start the server with the VM option `-Dchat.aiClass=your.package.YourClass`.

### Troubleshooting
| Symptom | Cause |
|---|---|
| No "AI Assistant" row, no sparkle | `GROQ_API_KEY` not set for the **Server** run configuration, or the server was not restarted |
| `API error 401` | wrong key |
| `API error 404 model_not_found` | Groq renamed the model: change `MODEL` |
| `API error 429` | free limit reached: wait a minute |
| `timed out` | no internet or Groq is slow (30 s limit) |

---

## 16. What was simplified (organisation changes)

* `ChatWindow` used to do everything (about 630 lines). It now only owns the **conversations and packets** (about 490 lines). Two new small classes took over:
  * **`Appearance`** – theme, accent colour, wallpaper and the burger menu.
  * **`FileTransfers`** – showing/playing stored files and the Download button.
* **One file window for everything** (`NativeFileDialog`): the four copies of "open a Java FileDialog and filter pictures" are gone.
* Removed dead code (an unused "remember token" stub in `UserStore`).
* Every class has a comment at its top saying what it is for.
* **Code-only clean-up (nothing visual changed):**
  * `ChatClient.send(...)` is the one place that writes a packet (lock + flush); the eight copies of that code are gone.
  * `Composer.bind(...)` turns a key into an action in one line (Esc, Enter, Shift+Enter, Tab); the four separate key-handler classes are gone.
  * The repeated "tell the client it failed" packets in `ClientHandler` all use `sendStatus(...)`.
  * The unused `serialVersionUID` lines were removed, and the three extra AI classes (`NoAssistant`, `MyAssistant`, `ClaudeAssistant`) were deleted.
