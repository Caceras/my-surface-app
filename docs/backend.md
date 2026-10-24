# Ægentica on aegentica.se, from the phone

Connect the phone to Ægentica's back office and the phone agent can use your memory, Inbox and tasks, and hand long jobs to the server agent. That agent keeps working on aegentica.se while the phone sleeps. You watch it on the phone, answer its questions, approve anything that costs money, steer it, and take over its browser.

## Connect

1. Settings → **Connect to Ægentica** (or the sheet's **+** menu) explains what it allows. Tap **Connect**.
2. aegentica.se opens in the app at Settings → Security, with the form named "Pixel". Tap **Make token**.
3. The page hands the token to the app over its private channel. The app keeps it encrypted with Android's Keystore and says "Connected to Ægentica".

Disconnect in the same dialog. To end the access everywhere, remove it on aegentica.se's Settings → Security; the phone then gets "Connect again".

## What the phone agent can do

| Tool | Tier | What happens |
|---|---|---|
| Check memory, read a saved note | Read | Runs on its own |
| Read the Inbox, search it, read a conversation | Read | Runs on its own; never marks anything read |
| List scheduled tasks, read a job | Read | Runs on its own |
| Remember something | Act | Asks first; only what you asked it to remember |
| Draft a reply | Act | Asks first. **Never sent:** the draft waits in Ægentica's Inbox until you send it there |
| Propose a task | Act | Asks first. Saved **turned off** until you turn it on in Ægentica's Tasks |
| Hand a job to the server agent | Act | Asks first, then starts the job and shows it |
| Tell a job what to change | Act | Asks first; refused while the job waits for your approval |

Hands-free applies only to Ægentica hands on the phone; the back office always asks. No tool can approve a job's step or answer its question: only you do, on the phone. The tool names and wording are in the [shared playbook](playbook.md).

## Jobs on the phone

A job handed over shows as a card in the floating bar and in the sheet, and as a notification.

- **The card:** what it is doing, its steps, a question or an approval, tables as they fill in, the newest pictures, files, the cost so far, and when it ends, its answer. **Open chat** opens it on aegentica.se in the app; a file opens there too.
- **Paying:** a step that costs money shows its price ("About $0.96 for 24 images") and the approve button carries the amount. When you asked by voice, Ægentica reads the amount aloud and listens: a short, clear yes approves, a no skips. Before the amount was read in full, or for anything else it heard, nothing is approved and the buttons stay.
- **Steering:** **Tell the job** puts the bar or the sheet's message box on the job; your next words steer it, or answer its question. While an approval waits, it is answered first, because words would cancel it.
- **The live view:** while the job uses Ægentica's browser, the card shows it live, with a ring where the agent is about to act. Tap it for the full screen: **Take over** to tap, scroll and type yourself (a password the agent must never type), **Hand back** when done.
- **The notification:** a live update while it works (Android 16 asks to show it as a status-bar chip): its newest step, **Stop**, **Approve · $0.96 for 24 images** and **Don't** when a step waits, or a reply field. Approving and replying need the phone unlocked. When it ends: "Ægentica is done" with the answer, **Open chat** and the first file.

## Chat history

A phone conversation is copied into Ægentica's chat history when it is over (a new question, closing the sheet or the floating bar), so it continues on the computer. Questions and answers only, as text; screens and screenshots stay on the phone. It is saved again only when it grew. Turn it off in Settings → Connect to Ægentica.

## Safety

- The token is kept with Keystore encryption and sent only to aegentica.se, as a bearer header. Only Ægentica's own site in the app can hand one over; the bar and other pages cannot.
- Everything the back office returns (memory, messages, job results) reaches the model fenced as data, never as instructions. When a job is handed over, the screen you asked about goes with it as `clientContext`, which the server agent also gets fenced as untrusted.
- Job events are checked field by field; only `https` links open, aegentica.se's inside the app, and only aegentica.se's own event and media addresses get the token.
- A notification button sends only an option the job offered, for a request still open.
- Pictures from the live browser are only shown; nothing in them is read or run on the phone.

## Transport

- **Tools:** MCP over Streamable HTTP, stateless with JSON answers: one `POST https://aegentica.se/api/backend/mcp` per tool call (`Backend.kt`). It is what aegentica.se's back office speaks to every app, so the phone needs no SDK and no session.
- **Jobs:** server-sent events from `job_start`'s `events` address, resumed with `Last-Event-ID` after the server's ten-minute limit or a dropped connection, with `job_get` to catch up in between and every few seconds when no stream is open (`ServerJobs.kt`). Events cost nothing while nothing happens, and the job lives on the server, so the phone can sleep.
- **Live browser:** `GET /api/browser/stream` (status, frame, action, handover) only while a live view is on screen, and `POST /api/browser/{control,input}` for taking over, with taps as fractions of the page (`LiveBrowser.kt`).

The contract is aegentica-app's `docs/BACKEND_API.md`.

## Android's limits

- A stream stays open only while the app process lives. When Android stops it, the notification stops updating; the job keeps running on aegentica.se, and the next open of the app catches up.
- The live view opens a stream only while it is on screen, at most a few pictures a second, decoded at most every 120 ms.
- Approving from the lock screen asks to unlock first.

## Code

| File | What it holds |
|---|---|
| `Backend.kt` | Pairing, the Keystore-sealed token, the MCP client, saving chats |
| `BackendSetup.kt` | Settings → Connect to Ægentica |
| `ServerJobs.kt` | Jobs: events, snapshots, answering, steering, priced approvals; `BackendTools`, the agent's back-office tools; `Toolboxes`, the hands and the back office as one |
| `JobCard.kt` | The job card for the sheet and the floating bar, the live picture, and `PriceVoice`, approving a price by voice |
| `LiveBrowser.kt`, `LiveViewActivity.kt` | The server's browser live, and the full-screen view with taking over |
| `AgentNotifications.kt`, `AgentActionReceiver.kt` | The job's live notification and its Stop, Approve, Don't and reply |
| `ShellBridge.kt`, `ShellActivity.kt` | The `access-token` message from aegentica.se, kept only by Ægentica's own site |
