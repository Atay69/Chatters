# Chatters – project guide

A chat application written in plain Java (Swing for the windows, sockets for the network).
Several people open the **client**; one computer runs the **server** that connects them.

## Run it (IntelliJ)

1. Run **`Server`** (src/Server.java). Leave it running.
2. Run **`ClientGUI`** (src/ClientGUI.java) – once per person. Sign in or "Create an account".
3. Optional: to plug in your own AI, add the VM option `-Dchat.aiClass=ai.MyAssistant` to the Server.

## What it can do

Accounts (name + password) · GroupChat · private chats with text and voice messages (saved, also for offline people), +1 … +9 notification badges · "Messages" list with previews ·
images, files (any size, with cancel) and voice messages in GroupChat and private chats · emoji · online/offline dots ·
dark/light theme · blurred wallpapers you can add to · upload progress bar · AI helper hooks.

## The folders

```
src/
  Server.java, ClientGUI.java   the two "Run" buttons – they only start the real code
  shared/   what client AND server both need
  server/   the server program
  client/   the window the user sees
  ai/       the place where your own AI plugs in
  images/   icons and the logo          emoji/   the emoji pictures (3 sizes)
  wallpapers/   the default wallpapers (they are inside Chatter.jar, so every computer has them) + list.txt = their names
chatter-data/ everything the server saves (created automatically)
```

### shared/ (the common language)
| File | What it is |
|---|---|
| `ChatProtocol` | The packet format and the list of packet types (read its comment first!) |
| `Frames` | Builders for the special packets (login, AI question, user list...) |
| `AppInfo` | The app name in one place (`NAME = "Chatters"`) |

### server/
| File | What it does |
|---|---|
| `ChatServer` | Starts the server, accepts connections, keeps the saved data, sends things to everybody |
| `ClientHandler` | One thread per connected client: sign in → join → read its messages → leave |
| `UserStore` | Accounts. Passwords are stored only as salted hashes (PBKDF2) |
| `PrivateHistory` | Saves private messages and private voice messages |
| `ProfanityFilter` | A placeholder hook for filtering text |

### client/
| File | What it does |
|---|---|
| `ChatWindow` | The main window: one rounded "glass" card on the wallpaper. Owns the conversations and reacts to the server's packets |
| `LoginDialog` | The sign-in / create-account screen |
| `ChatClient` | The connection to the server (sending and receiving packets) |
| `Sidebar`, `ConversationList` | Left side: the "Messages" list (GroupChat + people, with the last message and time) and your profile card |
| `MessageView` | The scrolling list of messages as bubbles (yours on the right), with text, emoji, files, images, voice |
| `Composer` | The bottom bar: the typing pill (with the file, image, emoji, voice and AI icons) and the green Send button; also the upload progress bar |
| `Widgets`, `Theme` | Small reusable pieces (glass card, bubble, buttons, panels), and all the colours (dark = black glass, light = white glass) |
| `Emoji`, `EmojiPicker` | The emoji pictures, and the popup to pick one |
| `WallpaperManager` | Wallpapers: cycle, add, soften |
| `NativeFileDialog` | The real modern Windows "Open" / "Save as" window (falls back to Java's) |
| `Appearance`, `FileTransfers` | Theme / accent / wallpaper + burger menu; stored-file fetch and Download (split out of `ChatWindow`) |
| `Animation` | Small helpers for smooth movement (slide/fade in, glide on hover) |
| `LineIcon` | The file, image, emoji, microphone and AI icons, drawn in code in the theme colour |
| `StatusIcon` | The status symbols: green circle (online), white half moon (away), grey circle (invisible/offline), red circle with a line (do not disturb) |
| `TitleBar` | Our own title bar (app icon, title, close button; drag it to move the window) |
| `ProfileAvatar`, `SharpIcon`, `VoiceRecorder`, `Settings`, `ChatEntry` | avatar drawing (click yours in the sidebar to pick a picture; the server keeps it in `chatter-data/avatars/` and everybody sees it) · crisp icons · microphone · saved settings · one message |

### ai/ (your future AI)
`AiAssistant` is the interface to implement (3 methods: reply suggestions, complete a sentence,
answer a question). `MyAssistant` is a ready template. `AiService` runs it in the background.

## The big picture

```
 ClientGUI ──► ChatWindow ──► ChatClient ══ socket (port 5000) ══ ClientHandler ◄── ChatServer
   (window)     (UI logic)    (packets)                          (one per client)   (shared data)
```

Everything that travels is a **packet**: `type, sender, timestamp, metadata, [bytes]`.

### Story 1 – signing in
1. `LoginDialog` opens a socket and `ChatClient.login` sends a `TYPE_AUTH` packet (name + password).
2. `ClientHandler.signIn` asks `UserStore` to check it (or create the account).
3. The server answers `TYPE_AUTH` (ok) or `TYPE_LOGIN_ERROR` (with the reason).
4. On success the server sends the saved history, then announces "X joined" and the user list.

### Story 2 – sending a message
1. You press Enter → `Composer.sendText` → `ChatClient.sendText` writes a `TYPE_TEXT` packet.
2. `ClientHandler.handleTextMessage` saves it (`ChatServer.persistGroupMessage`) and
   `broadcastPacket` sends it to **every** client – including you.
3. Each client's `ChatClient` reader thread gets it → `ChatWindow.handlePacket` →
   `MessageView.add` draws it. (A private message does the same, but only to the two people.)

### Story 3 – uploading a file
`Composer.upload` → `ChatClient.sendFile` writes the file in small pieces and reports progress
(that drives the progress bar) → the server saves it and broadcasts it → everybody gets a file card.

## Good to know (for the presentation)

* **Threads:** the server has one thread per client; the client has one reader thread. Swing
  must only be touched from the "event thread", so received packets are handed over with
  `SwingUtilities.invokeLater` (in `ChatClient`).
* **Theme:** widgets read their colour from `Theme` every time they paint, so switching the
  theme is just a repaint. Colours are translucent so the wallpaper shows through the glass.
  Everything is grey, white or black; the only colour is the green Send button (`Theme.ACCENT`).
* **Saved data:** plain binary files in `chatter-data/`. Delete the folder to start fresh.
  Don't create or edit these files by hand.
* **Rename the app:** change `NAME` in `shared/AppInfo.java`.
* **More emoji:** put `<unicode-hex>.png` files into `src/emoji/128`, `44` and `22`
  and list them in `Emoji.java`.
* **Wallpapers:** the default ones live in `src/wallpapers/` and are listed in `src/wallpapers/list.txt` (add a file there and write its name in list.txt to give everybody a new default; rebuild the jar). "Add wallpaper" in the menu copies a picture into `<your home folder>/.chatters/wallpapers/` on that computer, so every user can have their own wallpapers.

## Credits

Emoji pictures: **Noto Emoji** by Google, Apache License 2.0
(https://github.com/googlefonts/noto-emoji). The logo and icons were drawn for this project.

### Story – sending a file (any size, GroupChat or private chat)
1. The Composer calls `ChatClient.sendFile(...)`. A background thread sends `TYPE_FILE_BEGIN` (who gets it, name, size), then the file in 256 KB pieces (`TYPE_FILE_CHUNK`), then `TYPE_FILE_END`. The **Cancel** button calls `Upload.cancel()`, which stops between two pieces and sends `FILE_END` = "cancelled".
2. `ClientHandler.handleFileChunk` writes every piece straight to `chatter-data/files/<id>.part` (`FileStore`), so the server never holds the file in memory. When the file is complete it is renamed to `<id>` and a normal chat message is posted: text that starts with `ChatProtocol.FILE_MARK` followed by `id|kind|size|name` (saved in the history like any text; for GroupChat or only for the two people of a private chat).
3. `ChatEntry` recognises that text as a stored file. The bubble shows a picture (loaded automatically if it is under 4 MB), a "Download" button or a "Play" button. Downloading sends `TYPE_FILE_GET`; the server streams the file back in pieces (`TYPE_FILE_DATA`) from its own thread, so chat messages keep flowing.
