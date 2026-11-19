# Chatters – development log

Everything that was built, fixed and changed, in the order it happened, followed by a reference of
what every feature does and where its code lives.

Project: a chat application in plain Java (Swing windows, sockets). One computer runs the **server**,
everybody else runs the **client** (`Chatter.jar` or `ClientGUI`).
Repository: https://github.com/Atay69/Chatters (branch `main`).

---

## 1. Timeline

### Phase A – first version (older project "Nova Chat", `Desktop\Chat`)
Before the work moved to the current `Chatters` project, the same ideas were built once in an older code base:
themed line icons, a custom title bar, four statuses, profile pictures shared through the server, private
images/files/folders/voice messages, a smoke test of the server. That code base was then replaced by the
`Chatters` project below; all of those ideas were redone there in the project's own style.

### Phase B – `Chatters` (`IdeaProjects\Chat`)

| # | What was done |
|---|---|
| 1 | **Git repository.** Initialised the repo, wrote `.gitignore` (accounts and history in `chatter-data/`, IDE files, build output, logs), removed private data from the first commit, first commit `3fa12d1`, remote `origin` = Atay69/Chatters. |
| 2 | **New icons** for file, image, emoji, microphone and AI, drawn in code (`LineIcon`) in the theme colour. The five old PNGs were deleted. |
| 3 | **Custom title bar** (`TitleBar`) instead of the Windows one, in the sign-in window and the chat window. |
| 4 | **Profile picture**: click your profile card to choose a picture (first stored on your own computer). |
| 5 | **Minimize / full-screen buttons** on the title bar, and a **hover effect** on the profile card (darkens and asks "Change profile picture"). |
| 6 | **Bug fix – invisible buttons.** The three title-bar buttons were drawn only 2 px tall: inside a button the name `HEIGHT` meant Java's built-in `ImageObserver.HEIGHT` (= 2). Renamed to `BAR_HEIGHT`; buttons redrawn as circles. |
| 7 | **Bigger transfers (first attempt):** limit raised from 50 MB to about 2 GB, bigger buffers. (Replaced by real streaming in step 14.) |
| 8 | **Profile pictures visible to everybody**: sent to the server, saved there, broadcast to all (`TYPE_AVATAR`). The local `profile-pictures/` folder was dropped. |
| 9 | **Clearer font** for the whole app (`Theme.font`), **accent colour** option (6 colours) for the Send button, message-box outline and badges, and **theme, accent colour and wallpaper saved per user**. Header and message spacing fixed after looking at screenshots of the real window. |
| 10 | **Statuses**: Away (white half moon), Online (green), Invisible (grey), Do not disturb (red circle with a line), shown to everybody (`TYPE_STATUS`, `StatusIcon`). |
| 11 | **Burger menu** in the chat header holding Add wallpaper / Next wallpaper / Color / Light-Dark mode, and a **dim accent-coloured outline** around every message bubble. |
| 12 | **`Chatter.jar`** (client only) built. First build targeted Java 17 and failed to open by double-click on a computer whose default Java is 8 ("A Java Exception has occurred"). Rebuilt for **Java 8** (without the `ai` package, which uses a Java 16 `record` and is not needed by the client). |
| 13 | Pushed to GitHub (commits `7889421`, `ef4b133`). |
| 14 | **Streamed files of any size** (stored on the server's disk, downloaded on demand), **Cancel for uploads**, and **images, files, voice messages and emoji in private chats**. Commit `f90a4e7`. |

Commits on `main`: `3fa12d1` initial · `7889421` icons/title bar/avatars/statuses/colour/menu/jar · `ef4b133` Java 8 jar · `f90a4e7` streamed files + private attachments.

---

## 2. What the app can do

### Accounts and connection
* Sign in or create an account (name + password; passwords are stored only as salted PBKDF2 hashes).
* The sign-in window asks for the server IP (port 5000) and can remember the account name.
* Everything travels over one TCP connection per client (`ChatClient` ↔ `ClientHandler`).

### Conversations
* **GroupChat** (everybody) and **private chats** (one person), listed in "Messages" with the last message, its time and a "+1 … +9" badge for unread private messages.
* Text, emoji (picture emoji, picker), images, files and voice messages – **in both GroupChat and private chats**.
* The history is saved on the server; people who were offline get their messages when they sign in.
* Optional AI helper hooks (reply suggestions, finish the sentence, ask a question) – `ai/` package.

### Window and appearance
* **Custom title bar** (`TitleBar`): app icon, title, and three round buttons – minimize, full screen (fills the screen but leaves the taskbar; click again to restore) and close (red on hover). Drag the bar to move the window. The window has no edge-resizing because it has no Windows frame.
* **Icons** (`LineIcon`): file (paperclip), image, emoji, microphone, AI sparkles and the burger menu are drawn with lines in the current theme's text colour, so they match dark and light mode.
* **Font** (`Theme.font`): Segoe UI Semibold for normal text (clear, not too thin, not too bold) and Segoe UI Bold for names, titles and buttons; falls back to the standard sans-serif where Segoe UI does not exist.
* **Burger menu** (button at the top right of the chat): *Add wallpaper…*, *Next wallpaper (name)*, *Color: …* (cycles Green, Blue, Purple, Pink, Orange, Teal), *Light mode / Dark mode*.
* **Accent colour** drives the Send button, the outline of the message box while typing, the upload progress bar, the unread badges and the **dim outline around every message bubble** (bubble and tail).
* **Dark/light theme, accent colour and wallpaper are remembered per account on each computer** (`Settings`, keys `darkMode.<name>`, `accent.<name>`, `wallpaper.<name>`), so two people sharing a PC keep their own look.
* Spacing: message rows, bubble padding, the time under a message, and the message-box padding were tuned after checking screenshots.

### Profile picture
* Click your **profile card** (bottom left). While the mouse is over it the card darkens and the line under your name says "Change profile picture".
* Pick an image: it is cropped to a centred square, shrunk to 128 × 128 and sent to the server (`TYPE_AVATAR`, max 256 KB).
* The server saves it in `chatter-data/avatars/` and sends it to everybody; people who sign in later receive all saved pictures. It appears in the Messages list, the chat header and your profile card. People without a picture keep the grey silhouette (GroupChat has a bubble icon).

### Status
* The small symbol on the right of your profile card opens a menu: **Away** (white half moon), **Online** (green circle), **Invisible** (grey circle), **Do not disturb** (red circle with a grey line).
* It is sent to the server (`TYPE_STATUS`) and shown to everybody on the avatars (Messages list and chat header) and as text under the name.
* *Invisible* looks **offline** to everybody else (the server reports "offline" for you). *Do not disturb* and *Away* are labels only (no sound/notification behaviour exists to switch off).
* Everyone starts as Online each time they sign in (the status is not saved).

### Files, images and voice messages (any size)
How an upload works (`ChatClient.sendFile` / `sendBytes`, `ClientHandler.handleFile*`, `FileStore`):
1. The client sends `FILE_BEGIN` (recipient or "" for GroupChat, name, kind, size), then the file in **256 KB chunks** (`FILE_CHUNK`), then `FILE_END`.
2. The server writes each chunk straight to `chatter-data/files/<id>.part`; the file is never held in memory (tested: a 400 MB file with the server limited to 64 MB of memory). When complete it is renamed to `<id>`.
3. The server posts a normal chat message that starts with a marker character and holds `id|kind|size|name`. It is saved in the history like text and goes to everybody (GroupChat) or only to the two people (private chat).
4. Chat messages keep flowing during an upload, because each chunk is sent under a short lock and the server reads any packet type at any time.

Receiving (`ChatEntry`, `MessageView`, `ChatWindow`):
* **Images** under 4 MB are downloaded automatically and shown inside the bubble; bigger ones show only a Download button.
* **Files** show a card with name, size and a **Download** button (asks where to save; streams to disk with a progress line).
* **Voice messages** show a **Play** button (downloads the message, then plays it).
* Old attachments that were saved inside the history by the first version still display.

**Cancel:** while an upload runs, a **Cancel** button appears next to the progress bar. It stops the upload after the current chunk and sends `FILE_END = 0`; the server deletes the unfinished file and no message is posted. If a client disconnects mid-upload, the half-file is deleted too.

Limits: no size limit (only the server's free disk space); one upload per client at a time; a stored file can be requested by anyone who knows its random 63-bit id (only people who got the message know it).

---

## 3. Network protocol (packet types)

Every packet is `type, sender, timestamp, metadata, [int length + bytes]`, except where noted. Constants are in `shared/ChatProtocol.java`.

| Type | Number | Direction | Meaning |
|---|---|---|---|
| TEXT, IMAGE, FILE, AUDIO, SYSTEM | 1–5 | both | text / old attachments / "X joined" |
| TRANSFER_STATUS | 7 | server → client | "Transfer accepted", errors |
| PRIVATE | 9 | both | private text (also carries private stored-file messages) |
| LOGIN_ERROR, AUTH | 10, 11 | both | sign-in |
| USER_PRESENCE, USER_LIST | 12, 13 | server → client | who exists / is online, with each person's status |
| AI_QUERY, AI_REPLY | 14, 15 | both | AI helper |
| AVATAR | 16 | both | client: `int length + PNG`; server: sender = owner, bytes = PNG |
| STATUS | 17 | client → server | one byte: online 1, away 2, do-not-disturb 3, invisible 4 (offline 0 is only ever sent by the server) |
| FILE_BEGIN | 18 | client → server | recipient, name, kind, `long` size |
| FILE_CHUNK | 19 | client → server | `int length` + bytes (≤ 4 MB, normally 256 KB) |
| FILE_END | 20 | client → server | `1` = finished, `0` = cancelled |
| FILE_GET | 21 | client → server | `long` file id, `long` request number |
| FILE_DATA | 22 | server → client | request number, `int length`, bytes |
| FILE_DONE | 23 | server → client | request number, `1` = complete / `0` = failed |

Statuses sent by the server in the user list: 0 offline, 1 online, 2 away, 3 do not disturb (invisible users are sent as 0).

---

## 4. Where everything lives

```
src/
  Server.java, ClientGUI.java        the two "Run" buttons
  shared/   ChatProtocol (all constants + the file-message format), Frames, AppInfo
  server/   ChatServer       start-up, saved data, broadcasts, user list with statuses, avatars
            ClientHandler   one thread per client: sign-in, messages, uploads, downloads, status, picture
            FileStore       where uploaded files are stored (chatter-data/files/<id>)
            UserStore, PrivateHistory, ProfanityFilter
  client/   ChatWindow      main window, conversations, burger menu, downloads
            Sidebar         Messages list + profile card (picture, status)
            ConversationList, MessageView (bubbles, image/file/voice), ChatEntry (one message, stored-file detection)
            Composer        message bar, upload progress + Cancel, voice recording
            ChatClient      the connection: sending, chunked upload, download, status, picture
            TitleBar        custom title bar and its round buttons
            LineIcon        the line icons       StatusIcon  the four status symbols
            ProfileAvatar   round avatar (picture or silhouette + status dot)
            Theme           colours, accent colour, font
            Widgets         bubble (with dim outline), buttons, popup menu helpers
            Settings        per-user choices saved on this computer
            LoginDialog, WallpaperManager, EmojiPicker, Emoji, VoiceRecorder, SharpIcon ...
  ai/       AiAssistant, MyAssistant (template), AiService
  images/, emoji/                    app icon and emoji pictures
wallpapers/                          pictures for the wallpaper option
chatter-data/                        created by the server: users, history, avatars/, files/   (never committed)
Chatter.jar                          client only, built for Java 8
PROJECT_GUIDE.md                     the beginner guide;  DEVELOPMENT_LOG.md = this file
```

## 5. How to run it

* **Server:** run `Server` in IntelliJ (needs a modern JDK; the project uses 27). Leave it running. Allow it through the Windows firewall. The IP it prints is what clients type; port 5000.
* **Client:** run `ClientGUI`, or give people `Chatter.jar` (Java 8 or newer; double-click or `java -jar Chatter.jar`). They need the server IP; other networks need port forwarding or a VPN (the connection is not encrypted).
* **Rebuild the jar:** compile `client`, `shared` and `ClientGUI.java` (no tests, no `ai`) with `--release 8`, add the `images/` and `emoji/` folders, and use `Main-Class: ClientGUI` in the manifest.

## 6. Known limits and ideas

* Everyone must run the same version: the protocol changed (statuses, pictures, files).
* Windows shows the plain Java icon on the `.jar` file itself; the app icon appears on the windows and the taskbar. A Windows `.exe` or shortcut would be needed for a file icon.
* A profile picture can be replaced but not removed again (the silhouette is the default only until the first picture is chosen).
* Status is not remembered between sign-ins; *Away* and *Do not disturb* are labels (no automatic away, no muted notifications).
* No pause/resume for uploads and downloads (cancel only); one upload at a time per client.
* The window cannot be resized by dragging its edges (custom title bar) – use the full-screen button.
* The saved history is read completely into memory when the server starts; stored files are not, but they are never deleted (the files folder grows).
* Test servers started by the assistant during development may still be running on ports 5077–5082; they use temporary data and can be closed in Task Manager.
