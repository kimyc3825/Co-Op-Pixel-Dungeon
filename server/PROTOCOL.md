# Co-Op-Pixel-Dungeon wire protocol, version 1

This document is the contract between the Cloudflare server in `server/` and its clients
(the web lobby in `web-client/play/session.mjs` and the Windows client in `inn-client/`).
A client can be written from this document alone. Constants live in `server/src/protocol.ts`.

## 1. Overview

| Endpoint | Kind | Purpose |
| --- | --- | --- |
| `GET /api/v5/inn?layout=<id>[&shard=<n>]` | WebSocket | One inn room: presence, movement, chat, NPC notices, expedition preparation, matchmaking. |
| `GET /api/v5/link?id=<matchId>` | WebSocket | One party run: authenticates the two matched players and relays dungeon payloads. |
| `GET /api/v5/health` | HTTP | `200 {"ok":true,"protocol":1}`. No Durable Object is touched. |
| everything else under the origin | HTTP | Static files (`/` redirects to `/play/`; layout in `scripts/stage-web-client.mjs`). Unknown paths return `404 {"error":"not_found"}`. |

The origin is the Worker's own origin (`https://<worker>.<account>.workers.dev` or a custom domain).
The web lobby uses same-origin relative URLs; the Windows client is configured with the origin
(see `inn-client/src/main/resources/inn-origin.txt`). The old `/api/v2/inn/*` and `/api/v4/link/*`
HTTP endpoints, WebRTC signalling and the HTTP relay no longer exist.

Use `wss://` for an `https://` origin and `ws://` only for a loopback development origin.

## 2. Transport rules (both sockets)

* **Text frames only**, each one JSON object with a string field `t` (the message type). A binary
  frame closes the socket with `1003`. Invalid JSON, a non-object, or a missing `t` closes with `4000`.
* **Frame size**: inn frames at most **4096 bytes** of UTF-8; link frames at most **32 KiB**. A larger
  frame closes the socket with `1009`.
* **First message must be `hello`** (§4.1, §6.1). Anything else first closes with `4000`
  (`hello_required`). A socket that sends no `hello` within **10 s** is closed with `4002`.
* **Keep-alive**: send the literal text frame `ping` (not JSON) every **25 s**; the server answers
  the literal text `pong` without waking its Durable Object. WebSocket protocol ping frames are
  also answered automatically (Java's `WebSocket.sendPing` works). A socket from which nothing
  (message or `ping`) arrived for **100 s** may be closed with `1001` (`stale`).
* **Flood limit (inn)**: more than 40 frames in a burst or 20 frames/s sustained closes the inn socket
  with `4008` (`rate_limited`). Per-message limits are listed with each message.
* **Ordering**: within one socket, frames are processed and delivered in the order sent. The server
  never reorders, merges or drops a client's frames except where stated (rate limits, rejections).
* **Requests and replies**: any client message may carry `ref`, a positive safe integer chosen by the
  client. For a message with `ref` the server sends exactly one `{"t":"ok","ref":n}` or
  `{"t":"error","ref":n,"code":"…"}` after it has processed the message (and after any broadcasts
  it caused). Without `ref`, success is silent and failures send `{"t":"error","code":"…"}` (no ref).
  `ok`/`error` frames may carry extra fields; ignore unknown fields everywhere.
* **Unknown message types** from a client get `error` `unknown_type`; clients must ignore unknown
  server message types and unknown fields (forward compatibility).
* **Refusals**: when the server closes a socket on purpose it first sends
  `{"t":"error","code":"<reason>"}` and then closes with the code in §8. Browser clients cannot read
  HTTP status codes of a failed handshake, so all refusals after a successful upgrade use this form.
  Two refusals happen before the upgrade and are plain HTTP: `426 {"error":"websocket_required"}`
  (no `Upgrade: websocket` / not `GET`) and `403 {"error":"origin_not_allowed"}` (§10).

## 3. Identity

The client generates a **key**: 32 random bytes, base64url without padding (43 characters; accepted
pattern `^[A-Za-z0-9_-]{32,128}$`). It is a bearer secret: send it only inside `hello` frames, never
in URLs, never show it to other players. Keep it for the life of the client session (the web client
keeps it in memory for the page's lifetime; the Windows client for the process lifetime).

The server derives the public **player id** from the key:
`id = base64url(SHA-256("inn-player\n" + key))[0..22]` (22 characters). Other players only ever see
the id. Reconnecting with the same key yields the same id. The same key is used on the party link.

## 4. Inn socket: client → server

Connect to `/api/v5/inn?layout=<layoutId>`; add `&shard=<n>` with the `shard` of your previous
`welcome` when reconnecting, so you return to the same room when it has room.

* `layoutId`: 64 lowercase hex characters, the SHA-256 that both clients compute from the inn layout
  (`inn-layout-v1\n` + map rows joined by `\n` + `\n\n` + sorted `"<npcId> <x> <y>"` lines, each
  followed by `\n`). Rooms are separated by layout id: players with different layouts never see
  each other. An invalid id is refused with `4000` `invalid_layout`.
* **Shards**: a room holds at most **32 sockets** (all states, including away and not-yet-hello).
  When a room is full the server places the connection in the next shard of the same layout
  (up to **8** shards, i.e. 256 sockets per layout). Shards are separate rooms. When all are full
  the socket is refused with `4013` `inn_full`.

Coordinates are integers `0..127`. The server does **not** know the map: walls and furniture are
checked by the clients (§5.3).

### 4.1 `hello` (required, first)

```json
{"t":"hello","v":1,"key":"<key>","name":"앨리스",
 "appearance":{"job":"mage","hairColor":"natural","outfitColor":"blue","hairStyle":"short","accessory":"none"},
 "dungeonProtocol":11,"x":10,"y":14,
 "present":true,"queued":false,"friendlyFire":false,"theme":"sewers"}
```

| Field | Type | Rule |
| --- | --- | --- |
| `v` | int | Wire version, must be `1` (§11). |
| `key` | string | §3. |
| `name` | string | Trimmed, 1–20 code points, no characters in Unicode categories C (control, format, unassigned, private use, surrogate), Zl or Zp. |
| `appearance` | object | Optional fields `job`, `hairColor`, `outfitColor`, `hairStyle`, `accessory`; each an option id matching `^[a-z0-9][a-z0-9_-]{0,31}$`. Missing fields are omitted in what others receive; clients normalise unknown ids to their catalogue defaults. |
| `dungeonProtocol` | int | 1..1000000. Matching only pairs equal values. Current clients that run the original engine send **11**; the legacy mini-battle used **9**. |
| `x`, `y` | int | Starting position. A fresh entry uses a random `spawns` point of the layout; a reconnect sends the last known position. |
| `present` | bool, default `true` | `false` reconnects without appearing in the room (the player is in a dungeon, see `away`). |
| `queued` | bool, default `false` | `true` recruits again right after entering (restores the queue after a reconnect). |
| `friendlyFire`, `theme` | bool / `"sewers"`\|`"prison"` | Match options used if `queued` is true (§4.5). |

Invalid fields close with `4000` `invalid_hello`. A wrong `v` closes with `4001` (§11).

If another socket of the **same player id** is open in this room, that older socket receives
`error` `replaced` and is closed with `4009`; others see no leave/join, only a `person` update.
If the older socket was matched (§4.5), the match carries over to the new socket.

The server answers with `welcome` (§5.1), then broadcasts `person` to the others.

### 4.2 Movement

`{"t":"move","x":11,"y":13}` — one step to an adjacent cell (Chebyshev distance exactly 1 from the
server's current position, diagonals allowed). Send each step as its own message, at most one per
**150 ms** (the walk cadence); the server accepts bursts of 6 and 8 steps/s sustained. No reply
on success unless `ref` is given. A rejected step (not adjacent, out of range, not present, too
fast) gets `error` `move_rejected`, followed (while present) by `you` with the authoritative position.

Clients predict their own steps locally and only correct on `you`.

### 4.3 Chat, drinks, NPC notices

| Message | Effect | Errors |
| --- | --- | --- |
| `{"t":"chat","text":"…"}` | Event `chat` to everyone in the room, including the sender. `text` is trimmed; 1–160 code points; same character rule as names (so one line). | `invalid_message`, `not_in_inn`, `activity_rate_limited` (bucket of 4, refills one per 3 s) |
| `{"t":"drink"}` | Event `drink`. | `not_in_inn`, `activity_rate_limited` |
| `{"t":"interact","npcId":"innkeeper"}` | Event `interact` (an NPC conversation notice; the dialogue itself is local). `npcId` matches `^[a-z0-9][a-z0-9_-]{0,47}$`. Fire and forget; clients ignore failures. | `invalid_message`, `not_in_inn`, `activity_rate_limited` |

`drink`, `interact`, `prepare` and `use_preparation` share one bucket of 6 that refills one per 2 s.

### 4.4 Profile and expedition preparation

| Message | Effect |
| --- | --- |
| `{"t":"profile","name":"…","appearance":{…}}` | Rename / change appearance (same rules as `hello`). Broadcast as `person` to everyone including the sender. Errors: `invalid_profile`, `busy` (matched), `activity_rate_limited`. |
| `{"t":"prepare"}` | If the player has no preparation, the server rolls one: `hp` ∈ {−2, 0, +2} (maximum HP change) and `food` ∈ {−1, 0, +1} (ration change), each uniformly, with a new UUID `id`. Replies `preparation` with the current one (an existing preparation is not re-rolled). Only while present. |
| `{"t":"use_preparation","id":"<uuid>"}` | Consumes that preparation (solo expeditions call this when the dungeon has started). Replies `preparation` (now `null`). Unknown ids are not an error. |

A preparation belongs to the key (stored hashed with `SHA-256("inn-preparation\n"+key)`), survives
reconnects to the same shard and expires after **6 hours**. A match consumes both players'
preparations and hands them to the link (`selfPreparation`/`partnerPreparation`, §7.1).

### 4.5 Matchmaking

| Message | Effect | Errors |
| --- | --- | --- |
| `{"t":"queue","friendlyFire":false,"theme":"sewers"}` | Start recruiting (show the sign). Everyone gets a `person` with `queued:true`. If another present, recruiting player with the same `dungeonProtocol` exists, the two are matched at once; the one who has been recruiting longer is **host**. Re-sending while queued only updates the options. | `not_in_inn`, `busy`, `invalid_message`, `activity_rate_limited` |
| `{"t":"join","targetId":"<id>","friendlyFire":false,"theme":"sewers"}` | Join a specific recruiting player (clicking their sign). The target hosts, the sender is guest. | `partner_unavailable` (not present, not recruiting, already matched, gone, self), `protocol_mismatch`, plus the above |
| `{"t":"cancel"}` | Stop recruiting. Always `ok`. | `activity_rate_limited` |

`queue`, `join`, `cancel`, `away`, `enter` and `profile` share a bucket of 8 refilling one per second.

On a match both players receive `preparation` (`null`, consumed) and then
`{"t":"matched","match":{"id":"<uuid>","role":"host"|"guest","partnerId":"…","partnerName":"…"}}`;
their `person` shows `queued:false`. The requester's `ok` comes after its `matched`. The run settings
are the **host's** `friendlyFire` and `theme`; the server picks the `seed`. A matched player stays
visible in the room but cannot queue, join or rename (`busy`) until it sends `away` or `enter`.
If the server cannot create the link it sends `error` `match_failed` (no ref) and both return to
the present, not-queued state.

### 4.6 Leaving and returning

| Message | Effect |
| --- | --- |
| `{"t":"away"}` | Leave the room view but keep the socket (entering a dungeon, solo or party). Others get `gone`. Clears queue and match. Always `ok`. |
| `{"t":"enter","x":3,"y":4}` | Appear again at the given position (a fresh spawn). Clears any match. The server broadcasts `person`, sends a fresh `welcome`, then `ok`. |
| close the socket (code 1000) | Leave the inn entirely. Others get `gone`. |

While away a client receives no room traffic. Keeping the socket open costs nothing while idle;
send `ping`s as usual.

## 5. Inn socket: server → client

### 5.1 `welcome`

Sent after `hello` and after `enter`.

```json
{"t":"welcome","v":1,"selfId":"…","layoutId":"<64 hex>","shard":0,"now":1764212345678,
 "present":true,"people":[Person…],"events":[Event…],"after":1764212345678000,
 "queued":false,"match":null,"preparation":null,"limits":{"name":20,"chat":160}}
```

* `people`: every visible player in the room, including yourself (when `present`).
* `events`: up to the 30 most recent room events from the last 15 minutes, oldest first.
  `after` is the id of the last one (0 if none).
* `now`: server time in ms since the Unix epoch.
* `queued` is always `false` in the welcome that answers a `hello` with `queued:true`; the
  following `person` frame for yourself carries `queued:true`.
* `match`: the pending match if a replaced socket was matched, else `null`.
* `preparation`: `{"id","hp","food"}` or `null`.
* When `present` is `false` (reconnected while away) `people` is still filled but should be ignored.

A client must replace its whole room state with each `welcome`.

**Person**: `{"id","name","x","y","appearance":{…},"queued":bool,"dungeonProtocol":int}`.

**Event**: `{"id":int,"kind":"chat"|"drink"|"interact","playerId","name","time":ms,"text"?:string,"npcId"?:string}`.
`id` is unique and increasing (time-based: about `time × 1000`, safe integer); `text` only for
`chat`, `npcId` only for `interact`. `name` is the player's name at the time of the event.

### 5.2 Room updates

| Message | Meaning |
| --- | --- |
| `{"t":"person","person":Person}` | A player appeared or changed (name, appearance, queued, or a re-entry). Insert or replace by id. For yourself keep your locally predicted `x`,`y`. |
| `{"t":"gone","id":"…"}` | A player left the room (closed, away, dropped). |
| `{"t":"moved","id":"…","x":…,"y":…}` | Another player's accepted step (never sent for your own steps). |
| `{"t":"you","x":…,"y":…}` | Your authoritative position after a rejected step. |
| `{"t":"event","now":ms,"event":Event}` | A new room event; `now` lets clients animate fresh drinks (the web client shows a drink when `now - event.time < 1700`). |
| `{"t":"preparation","preparation":Preparation\|null}` | Your preparation changed. |
| `{"t":"matched","match":{…}}` | §4.5. Open the party link next (§6). |

### 5.3 Client-side filtering

The server does not validate walls. Clients drop or ignore people whose position is not walkable
on their own layout (`.` or `D` cells), and only step onto walkable cells; diagonal steps need at
least one of the two orthogonal neighbours walkable. Clients of the same layout therefore see the
same thing.

## 6. Party link socket: client → server

After `matched`, connect to `/api/v5/link?id=<match.id>` (a lowercase UUID). Unknown or ended links
are refused with `4004` `link_not_found` (also for a malformed id). A link must have both players
connected within **45 s** of the match or it ends with `pair_timeout`.

| Message | Meaning |
| --- | --- |
| `{"t":"hello","v":1,"key":"<key>"}` | First message. The key must belong to one of the two matched players, else `4003` `forbidden`. A second socket for the same side replaces the first (`4009`). |
| `{"t":"ready"}` | This side's dungeon has started and can receive payloads. Forwarded to the partner as `peer {ready:true}` and remembered by the server. Idempotent; resend after a reconnect. |
| `{"t":"data","d":"<string>"}` | An opaque game payload. Non-empty string of at most **24 KiB** (24576 bytes of UTF-8, which also bounds it to 24576 UTF-16 units). Larger payloads get `error` `payload_too_large` and are dropped; the run continues. |
| `{"t":"leave"}` | End the run for both. The partner gets `ended` `partner_left`; this socket is closed with 1000. Send it before closing the socket on purpose. |

Rate limit per socket: bursts of 120 frames, 60 frames/s sustained, and 2 MiB burst / 1 MiB/s of
frame text. Exceeding it ends the run: the offender gets `error` `rate_limited` and close `4008`,
the partner `ended` `rate_limited`. An invalid frame from a joined player ends the run the same way
with reason `protocol_error`.

## 7. Party link socket: server → client

### 7.1 `link`

Sent right after a valid `hello` (also after a reconnect):

```json
{"t":"link","v":1,"id":"<match id>","role":"host",
 "selfId":"…","selfName":"…","selfAppearance":{…},"selfPreparation":{"id":"…","hp":2,"food":-1},
 "partnerId":"…","partnerName":"…","partnerAppearance":{…},"partnerPreparation":null,
 "seed":3050392115,"friendlyFire":false,"theme":"sewers","dungeonProtocol":11,
 "paired":false,"partnerConnected":false,"partnerReady":false}
```

`seed` is an unsigned 32-bit integer (0..4294967295) chosen by the server, the same for both.
`friendlyFire` and `theme` are the host's match options. Preparations are what the match consumed.

### 7.2 Other messages

| Message | Meaning |
| --- | --- |
| `{"t":"paired"}` | Both players are connected for the first time. Sent once to each side (a side that connects second gets `link` with `paired:true` and then `paired`). |
| `{"t":"peer","ready":true}` | The partner sent `ready`. |
| `{"t":"peer","connected":false}` / `{"t":"peer","connected":true}` | The partner's socket dropped / came back. Informational. |
| `{"t":"data","d":"…"}` | The partner's payload, unchanged. |
| `{"t":"ended","reason":"…"}` | The run is over; the socket is then closed with `4010`. Reasons: `partner_left`, `partner_lost` (the partner stayed disconnected 20 s, or its buffer overflowed), `pair_timeout`, `rate_limited`, `protocol_error`, `expired` (12 h). |
| `{"t":"error","code":"…"}` | `payload_too_large`, `unknown_type`; refusals before a close (§8). |

### 7.3 Delivery guarantees

* Each `data` and `ready` is forwarded the moment it arrives, in the order the sender sent it.
  There is no "latest packet only" behaviour and no polling.
* While a side is disconnected (up to 20 s), frames for it are queued in order (at most 512 frames
  or 2 MiB, else the run ends with `partner_lost`) and delivered after its `link` on reconnect.
* Frames that were already written to a connection that then died can be lost; the server has no
  acknowledgements. Game protocols must tolerate a gap at a reconnect. `InnOriginalParty` does:
  the host sends full snapshots every 0.12 s and the guest resends its pending command until
  acknowledged.
* After `ended` the link and all its storage are deleted; reconnecting gets `4004`.

### 7.4 Starting the original-engine party (dungeonProtocol 11)

`d` is exactly the `InnOriginalParty` packet string (`S|…` snapshots from the host, `C|…` commands
from the guest). **There is no wrapper**: the former `{"kind":"original","type":…,"seq":…}` envelope
and its `hello`/`ready`/`ping`/`leave` packets are replaced by the server messages above. Sequence:

1. Both open the link; on `paired` (or `link.paired`) both leave the inn view (`away` on the inn socket).
2. The **guest** starts its dungeon immediately with `seed`, `friendlyFire` and the partner profile
   from `link`. When its engine is ready it sends `ready`.
3. The **host** starts its dungeon when it has seen `paired` and `peer {ready:true}` (or
   `link.partnerReady`), then sends `ready` when its engine is ready.
4. A side sends `data` only after it is ready **and** has seen the partner's `ready`.
5. Either side ends the run with `leave`; the other gets `ended`.

Client-side timeouts used by the web client: 20 s from the match to `paired`, then 150 s until
both sides are ready; after a dropped link socket it reconnects with back-off for up to 15 s.

### 7.5 Legacy mini-battle (dungeonProtocol 9)

The pre-original `HostBattle` path carries JSON strings in `d`: the host sends
`{"type":"state","sequence":n,"state":{…snapshot…}}` on every change and at least every 650 ms; the
guest sends `{"type":"input","sequence":n,"commands":[{"sequence":k,"action":{"type","dx","dy","target"}}…]}`
and repeats unacknowledged commands every 275 ms until `state.commandAck` covers them. The host
builds the battle from `link` (ids, names, appearances, preparations, `seed`, `theme`,
`friendlyFire`). The `ping`/`leave` packets of the old transport are gone. No current web UI mode
reaches this path (see the report in the change that introduced this protocol).

## 8. Close codes

| Code | Reason text / `error.code` | Client reaction |
| --- | --- | --- |
| 1000 | normal | Expected (your own close or `leave`). |
| 1001 | `stale` | Reconnect. |
| 1003 | `text_only` | Bug: binary frame. |
| 1006 | (no close frame: network) | Reconnect with back-off. |
| 1009 | `too_big` | Bug: frame too large. |
| 1011 | internal error | Reconnect with back-off. |
| 4000 | `invalid_json`, `invalid_frame`, `hello_required`, `invalid_hello`, `invalid_layout` | Bug; do not retry in a loop. |
| 4001 | `unsupported_version` | Do not reconnect; tell the user to update (§11). |
| 4002 | `hello_timeout` | Reconnect. |
| 4003 | `forbidden` (link: not a member) | End the run. |
| 4004 | `link_not_found` | End the run (it already ended). |
| 4008 | `rate_limited` | Inn: wait 10 s, reconnect. Link: the run ended. |
| 4009 | `replaced` | Another connection with the same key took over; do not reconnect. |
| 4010 | link ended (after `ended`) | Return to the inn. |
| 4013 | `inn_full` / `link_full` | Inn: retry after 5 s. |

## 9. Reconnect behaviour

* **Inn**: on any close except 4000, 4001, 4009 reconnect with back-off (about 0.5, 1, 2, 4, 8 s,
  then 8 s; ±25 % jitter), passing `shard` and a `hello` with the same key, the last known position,
  `present` according to whether the player is in a dungeon, and `queued` if it was recruiting.
  Then replace the room state with the new `welcome`. The web client also reconnects at once when
  the tab becomes visible again.
* **Link**: reconnect with the same back-off for at most 15 s, then give up (the server ends the run
  20 s after the drop). The new `link` frame reports `paired`, `partnerConnected`, `partnerReady`.
  Resend `ready` if your side was ready.
* Matching state is not preserved across a full disconnect of the inn socket: a player who drops
  out of the room (`gone`) is no longer recruiting until `queued:true`/`queue` is sent again.

## 10. Origin check

Browser handshakes carry `Origin`. The Worker accepts a socket when `Origin` is absent (non-browser
clients such as the Windows client must not send one), equals the Worker's own origin, or is listed
in the `ALLOWED_ORIGINS` variable (comma-separated, `server/wrangler.jsonc`). Others get HTTP 403.

## 11. Versioning

* `v` in `hello` is the wire version. The server accepts exactly the versions it lists; on mismatch
  it sends `{"t":"error","code":"unsupported_version","supported":[1]}` and closes with `4001`.
  Clients must not reconnect after 4001 and should ask the user to update (the web client reloads).
* A future incompatible change uses a new version number and, if needed, new paths
  (`/api/v6/…`); the server may serve several versions side by side.
* Compatible additions (new optional fields, new message types) do not change `v`; clients ignore
  what they do not know.
* `dungeonProtocol` versions the game payloads, not this protocol: the server only uses it to match
  equal values, so incompatible game builds never share a run.

## 12. Data the server keeps

Per room shard (SQLite in the Durable Object): the last 30 room events from the last 15 minutes
(including player names and chat text), and pending preparations keyed by a hash of the key, for
at most 6 hours. Per link: the run settings and ready flags until the run ends, then everything is
deleted. Presence lives only in socket attachments. No IP addresses, accounts or other personal data
are stored, and the server writes no logs of message content.

## 13. Mapping from the old HTTP API

| Old | New |
| --- | --- |
| `PUT /api/v2/inn/session` (enter/rename) | open inn socket + `hello`; rename: `profile` |
| `POST /api/v2/inn/sync` (poll, 700 ms) | pushed `welcome`/`person`/`gone`/`moved`/`event`/`preparation`/`matched` |
| `POST /api/v2/inn/move` | `move` |
| `POST /api/v2/inn/chat`, `drink`, `interact` | `chat`, `drink`, `interact` |
| `POST`/`DELETE /api/v2/inn/preparation` | `prepare` / `use_preparation` |
| `PUT /api/v2/inn/match {queued,targetId?}` | `queue` / `join` / `cancel` |
| `DELETE /api/v2/inn/session` | `away` (keep socket) or close the socket |
| `POST /api/v4/link/enter`, `sync` | link socket + `hello` → `link`, `paired`, `peer` |
| `POST /api/v4/link/signal` (WebRTC) | removed |
| `POST /api/v4/link/relay` (latest packet, 165 ms poll) | `data` (every packet, in order, immediately) |
| `POST /api/v4/link/leave` | `leave` |
| `GET /api/v4/health` | `GET /api/v5/health` |
