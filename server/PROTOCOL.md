# Co-Op-Pixel-Dungeon wire protocol, version 1

This document is the contract between the Cloudflare server in `server/` and its clients
(the web lobby in `web-client/play/session.mjs` and the Windows client in `inn-client/`).
A client can be written from this document alone. Constants live in `server/src/protocol.ts`.
§14 lists what changed after the first published revision of this document.

## 1. Overview

| Endpoint | Kind | Purpose |
| --- | --- | --- |
| `GET /api/v5/inn?layout=<id>[&shard=<n>]` | WebSocket | One inn room: presence, movement, chat, NPC notices, expedition preparation, matchmaking. |
| `GET /api/v5/link?id=<matchId>` | WebSocket | One party run: authenticates the two matched players and relays dungeon payloads. |
| `GET /api/v5/health` | HTTP | `200 {"ok":true,"protocol":1}`. No Durable Object is touched. |
| everything else under the origin | HTTP | Static files (`/` → 302 `/play/`, `/play` → 301 `/play/`; layout in `scripts/stage-web-client.mjs`). Unknown paths return `404 {"error":"not_found"}`. |

The origin is the Worker's own origin (`https://<worker>.<subdomain>.workers.dev` or a custom domain).
The web lobby uses same-origin relative URLs; the Windows client is configured with the origin
(`inn-client/src/main/resources/inn-origin.txt`). The old `/api/v2/inn/*` and `/api/v4/link/*`
HTTP endpoints, WebRTC signalling and the HTTP relay no longer exist.

Use `wss://` for an `https://` origin and `ws://` only for a loopback development origin.

## 2. Transport rules (both sockets)

* **Text frames only**, each one JSON object with a string field `t` (the message type). A binary
  frame closes the socket with `1003`. Invalid JSON, a non-object, or a missing `t` closes with `4000`.
* **Frame size**: inn frames at most **4096 bytes** of UTF-8; link frames at most **148,480 bytes**
  (enough for any valid 24 KiB payload after JSON escaping, §6). A larger frame closes with `1009`.
* **First message must be `hello`** (§4.1, §6). Anything else first closes with `4000`
  (`hello_required`). A socket that has not sent `hello` within **10 s** is closed with `4002` the
  next time the server looks at the room (§2, sweeps).
* **Pipelining is allowed**: a client may send further frames right after `hello` without waiting;
  they are processed after the `hello`, in order. (`welcome`/`link` still arrives first.)
* **Keep-alive and liveness**: send the literal text frame `ping` (not JSON) at least every **25 s**
  (both clients send it every 5 s and show the time to its `pong` as the round trip); the
  server answers the literal text `pong` without waking its Durable Object. **Only this text
  `ping` and real messages count as signs of life.** WebSocket protocol ping frames are answered by
  the runtime with protocol pongs, but they do **not** count: a socket that sends only protocol pings
  is treated as silent. A socket from which neither a message nor a text `ping` arrived for **100 s**
  is closed with `1001` reason `stale`, **without** a preceding `error` frame.
* **Sweeps are lazy in the inn**: an inn room keeps no timers. Hello timeouts and stale sockets are
  found when the room is next used: on every new connection, and on incoming frames at most once
  every 5 s. A silent socket in a room where nobody acts can therefore stay listed longer than 100 s.
  A paired party link also checks every 2 minutes (§7.3).
* **Flood limit (inn)**: more than 30 frames in a burst or 12 frames/s sustained closes the inn socket
  with `4008` (`rate_limited`). Link limits are in §6. Per-message limits are listed with each message.
* **Ordering**: within one socket, frames are processed and delivered in the order sent. The server
  never reorders, merges or drops a client's frames except where stated (rate limits, rejections).
* **Requests and replies (inn only)**: any inn message other than `hello` may carry `ref`, a positive
  safe integer chosen by the client. For such a message the server sends exactly one
  `{"t":"ok","ref":n}` or `{"t":"error","ref":n,"code":"…"}` after it has processed it (and after any
  broadcasts it caused). Without `ref`, success is silent and failures send
  `{"t":"error","code":"…"}` without `ref`. `hello` is answered by `welcome` (or a refusal), never by
  `ok`. **The link socket never sends `ok` and never echoes `ref`.** Ignore unknown fields everywhere.
* **Unknown message types** from a client get `error` `unknown_type`; clients must ignore unknown
  server message types and unknown fields (forward compatibility).
* **Refusals**: when the server rejects a socket on purpose it first sends
  `{"t":"error","code":"<reason>"}` (sometimes with extra fields) and then closes with the code in §8.
  Exceptions without an `error` frame: `1001 stale`, the leaver's own `1000 left` on the link, and
  the `4010` close that follows an `ended` frame. Browsers cannot read HTTP status codes of a failed
  handshake, so refusals happen after a successful upgrade. Two refusals are plain HTTP before the
  upgrade: `426 {"error":"websocket_required"}` (no `Upgrade: websocket` / not `GET`) and
  `403 {"error":"origin_not_allowed"}` (§10).
* After the server has closed or replaced a socket it ignores everything that socket still sends
  (including a new `hello`); such a socket no longer counts toward any cap.

## 3. Identity

The client generates a **key**: 32 random bytes, base64url without padding (43 characters; accepted
pattern `^[A-Za-z0-9_-]{32,128}$`). It is a bearer secret: send it only inside `hello` frames, never
in URLs, never show it to other players. Keep it for the life of the client session (the web client
keeps it in memory for the page's lifetime; the Windows client for the process lifetime), with one
exception: when `use_preparation` fails (§4.4) the web client takes a new key and reconnects the inn
socket with it, so a preparation cannot be carried into another expedition. A client that changes
its key must reconnect: the inn and the party link identify the player by the key sent in `hello`.

The server derives the public **player id** from the key:
`id = base64url(SHA-256("inn-player\n" + key))[0..22]` (22 characters). Other players only ever see
the id. Reconnecting with the same key yields the same id. The same key is used on the party link.

## 4. Inn socket: client → server

Connect to `/api/v5/inn?layout=<layoutId>`; add `&shard=<n>` with the `shard` of your previous
`welcome` when reconnecting, so you return to the same room when it has room.

* `layoutId`: 64 lowercase hex characters, the SHA-256 of this UTF-8 text:

  ```
  "inn-layout-v1\n" + map.join("\n") + "\n\n" + points.join("\n") + "\n"
  ```

  where `map` is the layout's rows and `points` are the strings `"<npcId> <x> <y>"` (decimal), one per
  NPC, sorted by comparing whole strings by UTF-16 code unit (JavaScript's default `sort()`, Java's
  `String.compareTo`). With no NPCs `points.join("\n")` is empty, so the text ends in three
  newlines. Art, names and dialogue are not part of the id.
* Rooms are separated by layout id: players with different layouts never see each other. An
  invalid id is refused with `4000` `invalid_layout`.
* **Shards**: a room holds at most **32 sockets** (present, away and not-yet-hello alike), and at most
  **64 sockets in total** counting sockets the server closed whose close the client never
  acknowledged. A shard that is full by either count passes the connection to the next shard of the
  same layout (up to **8** shards, 256 players per layout). Shards are separate rooms. When every
  shard refused for capacity the socket is refused with
  `{"t":"error","code":"inn_full","retryAfter":30}` and `4013`; wait `retryAfter` seconds (with
  jitter) before trying again, because every attempt probes the shards.
* **Per-address limits** use the client address that Cloudflare's edge puts in `CF-Connecting-IP`,
  keyed as the IPv4 address or the IPv6 **/64**. Loopback addresses, and every request to a loopback
  host (`wrangler dev`, where a client-sent header would pass through), have no per-address limits.
  The key is kept in the socket attachment for the socket's lifetime (so it survives hibernation)
  and in memory; it is never written to storage.
  * **Sockets per address**: at most **8** per room shard, counting every socket of the address the
    shard still holds, including ones the server closed whose close was never acknowledged (so
    filling a shard's 64-socket ceiling takes at least eight addresses). A ninth is passed to the
    next shard like a capacity refusal; only when every shard refused and at least one of them because of this cap
    is the socket refused with `too_many_connections` and `4029`. Households and carrier-grade NAT
    share addresses, hence the generous value.
  * **Connects per address**: bursts of **10** new connections per shard, refilled one per 2 s.
    Beyond that the socket is refused with `rate_limited` and `4008`, and no other shard is tried;
    other addresses are not affected.

Coordinates are integers `0..127`. The server does **not** know the map: walls and furniture are
checked by the clients (§5.3).

### 4.1 `hello` (required, first)

```json
{"t":"hello","v":1,"key":"<key>","name":"앨리스",
 "appearance":{"job":"mage","hairColor":"natural","outfitColor":"blue","hairStyle":"short","accessory":"none"},
 "dungeonProtocol":12,"x":10,"y":14,
 "present":true,"queued":false,"friendlyFire":false,"theme":"sewers"}
```

| Field | Type | Rule |
| --- | --- | --- |
| `v` | int | Wire version, must be `1` (§11). |
| `key` | string | §3. |
| `name` | string | Trimmed, 1–20 code points, no characters in Unicode categories C (control, format, unassigned, private use, surrogate), Zl or Zp. |
| `appearance` | object | Optional fields `job`, `hairColor`, `outfitColor`, `hairStyle`, `accessory`; each an option id matching `^[a-z0-9][a-z0-9_-]{0,31}$`. Missing fields are omitted in what others receive; clients normalise unknown ids to their catalogue defaults. |
| `dungeonProtocol` | int | 1..1000000. Matching only pairs equal values. Current clients that run the original engine send **12** (turn-clock packets); **11** was the ATB build and **9** the legacy mini-battle. Bump it whenever the game payload format changes. |
| `x`, `y` | int | Starting position. A fresh entry uses a random `spawns` point of the layout; a reconnect sends the last known position. |
| `present` | bool, default `true` | `false` reconnects without appearing in the room (the player is in a dungeon, see `away`). |
| `queued` | bool, default `false` | `true` recruits again right after entering (restores the queue after a reconnect). |
| `friendlyFire`, `theme` | bool / `"sewers"`\|`"prison"` | Match options used if `queued` is true (§4.5). |

Invalid fields close with `4000` `invalid_hello`. A wrong `v` closes with `4001` (§11).

**Takeover**: if another socket of the **same player id** is open in this room, that older socket
receives `error` `replaced` and is closed with `4009`. If the older socket was visible and the new
`hello` is `present:true`, the others see only a `person` update; if the new `hello` is
`present:false`, they get `gone`. A pending match of the older socket carries over to a
`present:true` socket; its preparation always carries over.

The server answers with `welcome` (§5.1), then broadcasts `person` to the others.

### 4.2 Movement

`{"t":"move","x":11,"y":13}` — one step to an adjacent cell (Chebyshev distance exactly 1 from the
server's current position, diagonals allowed). Send each step as its own message, at most one per
**150 ms** (the walk cadence); the server accepts bursts of 6 and 8 steps/s sustained. No reply on
success unless `ref` is given. A rejected step (not adjacent, out of range, not present, too fast)
gets `error` `move_rejected`, followed (while present) by `you` with the authoritative position.

Clients predict their own steps locally and only correct on `you`.

### 4.3 Chat, drinks, NPC notices

| Message | Effect | Errors |
| --- | --- | --- |
| `{"t":"chat","text":"…"}` | Event `chat` to everyone in the room, including the sender. `text` is trimmed; 1–160 code points; same character rule as names (so one line). | `invalid_message`, `not_in_inn`, `activity_rate_limited` (per socket: bucket of 4, one more every 3 s), `room_busy` |
| `{"t":"drink"}` | Event `drink`. | `not_in_inn`, `activity_rate_limited` |
| `{"t":"interact","npcId":"innkeeper"}` | Event `interact` (an NPC conversation notice; the dialogue itself is local). `npcId` matches `^[a-z0-9][a-z0-9_-]{0,47}$`. Fire and forget; clients ignore failures. | `invalid_message`, `not_in_inn`, `activity_rate_limited` |

`drink`, `interact`, `prepare` and `use_preparation` share one per-socket bucket of 6 refilling one
per 2 s. On top of the per-socket limits the room has budgets of its own:

* **Chat**: room-wide 40 lines refilling 4 per second, and per client address 8 lines refilling one
  per second (§4). When either is empty the line fails with `room_busy`; the rejection does not
  spend the sender's own token, so the sender may retry as soon as the room has room.
* **Drink and interact**: room-wide 30 refilling 2 per second, separate from chat. Over budget the
  event is silently not broadcast (the sender still gets `ok`).

### 4.4 Profile and expedition preparation

| Message | Effect |
| --- | --- |
| `{"t":"profile","name":"…","appearance":{…}}` | Rename / change appearance (same rules as `hello`). While the player is visible it is broadcast as `person` to everyone including the sender; while away nothing is broadcast. Errors: `invalid_profile`, `busy` (matched), `activity_rate_limited`. |
| `{"t":"prepare"}` | If the player has no preparation, the server rolls one: `hp` ∈ {−2, 0, +2} (maximum HP change) and `food` ∈ {−1, 0, +1} (ration change), each uniformly, with a new UUID `id`. Replies `preparation` with the current one (an existing preparation is not re-rolled). One roll per entry: after `use_preparation` consumed one, `prepare` fails with `preparation_used` until the next `enter`. Errors: `not_in_inn` (not present), `preparation_used`, `activity_rate_limited`. |
| `{"t":"use_preparation","id":"<uuid>"}` | Consumes that preparation (solo expeditions call this when the dungeon has started). Replies `preparation` with what the player holds afterwards: `null` when the id matched, otherwise the unchanged current preparation (an unknown but well-formed id is not an error). Errors: `invalid_message` (not a UUID), `activity_rate_limited`. |

A preparation lives with the socket: it survives `away`/`enter`, hibernation and a takeover by a
reconnect while the old socket is still open, but **not** a full disconnect. A match consumes both
players' preparations and hands them to the link (`selfPreparation`/`partnerPreparation`, §7.1); if
creating the link fails, they are given back.

### 4.5 Matchmaking

| Message | Effect | Errors |
| --- | --- | --- |
| `{"t":"queue","friendlyFire":false,"theme":"sewers"}` | Start recruiting (show the sign). Everyone gets a `person` with `queued:true`. If another present, recruiting player with the same `dungeonProtocol` exists, the two are matched at once; the one who has been recruiting longer is **host**. Re-sending while queued only updates the options. | `not_in_inn`, `busy`, `invalid_message`, `activity_rate_limited` |
| `{"t":"join","targetId":"<id>","friendlyFire":false,"theme":"sewers"}` | Join a specific recruiting player (clicking their sign). The target hosts, the sender is guest. | `invalid_message` (bad options, or `targetId` is yourself), `partner_unavailable` (not present, not recruiting, already matched, gone), `protocol_mismatch`, `not_in_inn`, `busy`, `activity_rate_limited` |
| `{"t":"cancel"}` | Stop recruiting. Always `ok` unless rate limited. | `activity_rate_limited` |

`queue`, `join`, `cancel`, `enter` and `profile` share a per-socket bucket of 8 refilling one per
second. `away` is not limited by it (§4.6).

On a match both players receive `preparation` (`null`, consumed) and then
`{"t":"matched","match":{"id":"<uuid>","role":"host"|"guest","partnerId":"…","partnerName":"…"}}`;
their `person` shows `queued:false`. The requester's `ok` comes after its `matched`. The run settings
are the **host's** `friendlyFire` and `theme`; the server picks the `seed`. A matched player stays
visible in the room but cannot queue, join or rename (`busy`) until it sends `away` or `enter`.
If the server cannot create the link, each side that is still connected gets `preparation` (its
preparation back), then `error` `match_failed` (no ref), and returns to present, not queued; a side
that left in the meantime stays gone. The `queue` that triggered the attempt is still answered
`ok` (it was accepted); a `join` is answered `partner_unavailable`.

### 4.6 Leaving and returning

| Message | Effect |
| --- | --- |
| `{"t":"away"}` | Leave the room view but keep the socket (entering a dungeon, solo or party). Others get `gone`. Clears queue and match. Never refused (only the flood limit of §2 applies): always `ok`. |
| `{"t":"enter","x":3,"y":4}` | Appear again at the given position (a fresh spawn). Clears queue and match. If the player was already visible the others first get `moved`; then everyone, including the sender, gets `person`; then the sender gets a fresh `welcome`, then `ok`. Errors: `invalid_message`, `activity_rate_limited`. |
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
* `events`: up to the 30 most recent room events from the last 15 minutes, oldest first, as far as
  the server still remembers them: the history is kept in memory only and is lost when the room
  object hibernates. `after` is the id of the last one (0 if none).
* `now`: server time in ms since the Unix epoch.
* `queued` is always `false` in the welcome that answers a `hello` with `queued:true`; the
  following `person` frame for yourself carries `queued:true`.
* `match`: a pending match carried over from a socket this `hello` took over, else `null`. **It can
  name a match the client has already finished or abandoned** (the old socket was still open when
  the client gave up on the run). A client that is not running that match must send `enter` (at its
  current position) to clear it; until then the server answers `busy` to queue/join/profile.
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
| `{"t":"gone","id":"…"}` | A player left the room (closed, away, dropped, stale, or reconnected with `present:false`). |
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
are refused with `link_not_found` and `4004` (also for a malformed id). A link must have both players
connected within **45 s** of the match or it ends with `pair_timeout`. A link serves at most 6
sockets; when full, the oldest socket that has not yet sent `hello` is closed with `link_full`
`4013` to make room, so unauthenticated sockets cannot lock a player out. A link also refuses new
sockets with `link_full` while it holds more than 12 in total (counting closed sockets whose close
was never acknowledged).

| Message | Meaning |
| --- | --- |
| `{"t":"hello","v":1,"key":"<key>"}` | First message. The key must belong to one of the two matched players, else `forbidden` `4003`. A second socket for the same side replaces the first (`4009`). Answered by `link` (§7.1). Frames may be pipelined after it. |
| `{"t":"ready"}` | This side's dungeon has started and can receive payloads. Forwarded to the partner as `peer {ready:true}` and remembered by the server. Idempotent; resend after a reconnect. |
| `{"t":"data","d":"<string>"}` | An opaque game payload: a non-empty string of at most **24 KiB** (24,576 bytes of UTF-8, which also bounds it to 24,576 UTF-16 units). The frame limit (§2) admits any such payload whatever its JSON escaping. An empty, non-string or larger `d` gets `error` `payload_too_large` and is dropped; the run continues. |
| `{"t":"leave"}` | End the run for both. The partner gets `ended` `partner_left`; this socket is closed with `1000` reason `left` (no `error` frame). Send it before closing the socket on purpose. |

Rate limit per link socket: bursts of **300** frames, **25 frames/s** sustained, and 2 MiB burst /
1 MiB/s of frame text. Exceeding it after `hello` ends the run: the offender gets `error`
`rate_limited` and close `4008`, the partner `ended` `rate_limited`. A socket that floods before
its `hello` only gets `rate_limited` / `4008` itself; the run continues.

Clients should not send more than they need: the web client sends the host's frames (full-state
snapshots) at most 15 per second, newest wins, and every guest frame (commands) at once and in
order. The choice is by role, not by payload. A Windows client should do the same. An invalid frame (binary, too big, bad JSON, no `t`) from a joined
player ends the run the same way: the offender gets `error` `protocol_error` and close `1003`, `1009`
or `4000` with reason `protocol_error`; the partner `ended` `protocol_error`. Before `hello` the same
faults only close that socket (`1003`/`1009`/`4000` with the specific reason).

## 7. Party link socket: server → client

### 7.1 `link`

Sent right after a valid `hello` (also after a reconnect):

```json
{"t":"link","v":1,"id":"<match id>","role":"host",
 "selfId":"…","selfName":"…","selfAppearance":{…},"selfPreparation":{"id":"…","hp":2,"food":-1},
 "partnerId":"…","partnerName":"…","partnerAppearance":{…},"partnerPreparation":null,
 "seed":3050392115,"friendlyFire":false,"theme":"sewers","dungeonProtocol":12,
 "paired":false,"partnerConnected":false,"partnerReady":false}
```

`seed` is an unsigned 32-bit integer (0..4294967295) chosen by the server, the same for both.
`friendlyFire` and `theme` are the host's match options. Preparations are what the match consumed.

### 7.2 Other messages

| Message | Meaning |
| --- | --- |
| `{"t":"paired"}` | Both players are connected for the first time. Sent once to each side (a side that connects second gets `link` with `paired:true` and then `paired`). |
| `{"t":"peer","ready":true}` | The partner sent `ready`. |
| `{"t":"peer","connected":false}` / `{"t":"peer","connected":true}` | The partner's socket dropped or went stale / came back. Informational. |
| `{"t":"data","d":"…"}` | The partner's payload, unchanged. |
| `{"t":"ended","reason":"…"}` | The run is over; the socket is then closed with `4010`. Reasons: `partner_left`, `partner_lost` (the partner stayed disconnected 20 s, or its buffer overflowed), `pair_timeout`, `rate_limited`, `protocol_error`, `expired` (12 h). |
| `{"t":"error","code":"…"}` | `payload_too_large`, `unknown_type`; refusals before a close (§8). |

### 7.3 Delivery guarantees and loss detection

* Each `data` and `ready` is forwarded the moment it arrives, in the order the sender sent it.
  There is no "latest packet only" behaviour and no polling.
* While a side is not connected — before its first connection as well as while it reconnects
  (up to 20 s) — frames for it are queued in order (at most 512 frames or 2 MiB, else the run ends
  with `partner_lost`) and delivered right after its `link`. The queue is in memory: it is lost if
  the link object restarts.
* Frames that were already written to a connection that then died can be lost; the server has no
  acknowledgements. Game protocols must tolerate a gap at a reconnect (`InnOriginalParty` does:
  state is resent and the guest resends its pending command until acknowledged).
* A side counts as lost when its socket closes, when it is found silent for 100 s (checked whenever
  either side sends a frame, and by an alarm every 2 minutes while paired, so a dead side is found
  within about 4 minutes even when the other side sends nothing), or — after the link object was
  restarted, for example by a deploy, when no close event is delivered — when the object wakes and a
  paired side has no socket. The other side then gets `peer {connected:false}`; after 20 s without a
  reconnect the run ends with `partner_lost`.
* After `ended` the link and all its storage are deleted; reconnecting gets `4004`.

### 7.4 Starting the original-engine party (dungeonProtocol 12)

`d` is exactly the `InnOriginalParty` packet string (`S|…` snapshots from the host, `C|…` commands
from the guest; in dungeonProtocol 12 a command is `C|floor|seq|kind|first|second`, kind `W` meaning
"wait one turn", and a snapshot has 16 fields including the turn and the remaining grace; both
clients produce and parse them with the shared `core` class, the server never looks inside).
**There is no wrapper**: the former `{"kind":"original","type":…,"seq":…}` envelope and its
`hello`/`ready`/`ping`/`leave` packets are replaced by the server messages above. Sequence:

1. Both open the link; on `paired` (or `link.paired`) both leave the inn view (`away` on the inn socket).
2. The **guest** starts its dungeon immediately with `seed`, `friendlyFire` and the partner profile
   from `link`. When its engine is ready it sends `ready`.
3. The **host** starts its dungeon when it has seen `paired` and `peer {ready:true}` (or
   `link.partnerReady`), then sends `ready` when its engine is ready.
4. A side sends `data` only after it is ready **and** has seen the partner's `ready`.
5. Either side ends the run with `leave`; the other gets `ended`.

Client-side timeouts used by the web client: 20 s from the match to `paired`, then 150 s until
both sides are ready; after a dropped link socket it reconnects with back-off for up to 15 s; a
connection attempt that does not open within 10 s is abandoned.

### 7.5 Legacy mini-battle (dungeonProtocol 9)

The pre-original `HostBattle` path of the web client carries JSON strings in `d` (host:
`{"type":"state","sequence":n,"state":{…}}` on change and every 650 ms; guest:
`{"type":"input","sequence":n,"commands":[…]}` repeated every 275 ms until acknowledged). No current
web UI mode reaches it, and the Windows client no longer has a legacy `HostBattle` path; a Windows
client never needs to implement it.

## 8. Close codes

| Code | Reason text / `error.code` | Client reaction |
| --- | --- | --- |
| 1000 | normal; `left` (the link leaver's own socket) | Expected. |
| 1001 | `stale` (no `error` frame) | Reconnect. |
| 1003 | `text_only`; `protocol_error` on a joined link socket | Bug: binary frame. On the link the run has ended. |
| 1006 | (no close frame: network) | Reconnect with back-off. |
| 1009 | `too_big`; `protocol_error` on a joined link socket | Bug: frame too large. On the link the run has ended. |
| 1011 | internal error | Reconnect with back-off. |
| 4000 | `invalid_json`, `invalid_frame`, `hello_required`, `invalid_hello`, `invalid_layout`; `protocol_error` on a joined link socket | Bug: **do not reconnect** (the inn client stops and shows a message). On the link the run has ended. |
| 4001 | `unsupported_version` | Do not reconnect; tell the user to update (§11). |
| 4002 | `hello_timeout` | Reconnect. |
| 4003 | `forbidden` (link: not a member) | End the run. |
| 4004 | `link_not_found` | End the run (it already ended). |
| 4008 | `rate_limited` | Inn: wait 10 s, reconnect (no shard is tried meanwhile). Link: after `hello` the run has ended (the partner got `ended rate_limited`); before `hello` only this socket was refused. |
| 4009 | `replaced` | Another connection with the same key took over; do not reconnect. |
| 4010 | link ended (after `ended`, no `error` frame) | Return to the inn. |
| 4013 | `inn_full` (with `retryAfter` seconds); `link_full` | Inn: retry after `retryAfter` s with jitter. Link: only a surplus socket that has not sent `hello` gets this; reconnect like after a drop. |
| 4029 | `too_many_connections` | Inn: every shard already holds 8 sockets from this address; retry after 30 s. |

## 9. Reconnect behaviour

* **Inn**: on any close except 4000, 4001 and 4009 reconnect with back-off (about 0.5, 1, 2, 4, 8 s,
  then 8 s; ±25 % jitter; the waits after 4008, 4013 and 4029 in §8 are required, and nothing (such as
  the tab becoming visible) may shorten them), passing `shard` and a
  `hello` with the same key, the last known position, `present` according to whether the player is
  in a dungeon, and `queued` if it was recruiting. Reset the back-off only after a usable `welcome`.
  Then replace the room state with the new `welcome` (and clear a stale `match`, §5.1). The web
  client also reconnects at once when the tab becomes visible again. If `enter` fails while the
  socket stays open, the web client closes and reconnects, so the `hello` brings the player back.
* **Link**: reconnect with the same back-off for at most 15 s, then give up (the server ends the run
  20 s after the drop). The new `link` frame reports `paired`, `partnerConnected`, `partnerReady`.
  Resend `ready` if your side was ready. Codes 4003, 4004, 4008, 4009, 4010, 1003, 1009, 4000 and
  4001 on the link mean the run is over: do not reconnect.
* Matching state is not preserved across a full disconnect of the inn socket: a player who drops
  out of the room (`gone`) is no longer recruiting until `queued:true`/`queue` is sent again.

## 10. Origin check

Browser handshakes carry `Origin`. The Worker accepts a socket when `Origin` is absent (non-browser
clients such as the Windows client must not send one), equals the Worker's own origin, or is listed
in the `ALLOWED_ORIGINS` variable (comma-separated, `server/wrangler.jsonc`). Others get HTTP 403.

## 11. Versioning

* `v` in `hello` is the wire version. The server accepts exactly the versions it lists; on mismatch
  it sends `{"t":"error","code":"unsupported_version","supported":[1]}` and closes with `4001`.
  Clients must not reconnect after 4001 and should ask the user to update (the web client shows a
  message asking the user to reload the page; it does not reload by itself).
* A future incompatible change uses a new version number and, if needed, new paths
  (`/api/v6/…`); the server may serve several versions side by side.
* Compatible additions (new optional fields, new message types, new error codes) do not change
  `v`; clients ignore what they do not know.
* `dungeonProtocol` versions the game payloads, not this protocol: the server only uses it to match
  equal values, so incompatible game builds never share a run.

## 12. Data the server keeps and what it costs

* **Inn rooms write nothing.** Presence, position, queue, match and preparation live in the socket
  attachments; the event history lives in memory. No SQLite table, no key, no alarm. When the last
  socket of a room closes, anything an older server version stored there is deleted. Storage rows
  read and written per inn action: **0**.
* **Party links** keep one row (`link`: run settings, pairing time, ready flags, loss times) from
  the match until the run ends, then delete everything. Row writes per run: creation 1, pairing 1,
  each side's first `ready` 1, each drop 1 and each return 1, an alarm write at creation and when a
  deadline moves earlier, and the final delete. While paired the 2-minute check alarm costs one
  alarm invocation (a billed request) and one row write every 2 minutes: 30 requests and 30 row
  writes per hour of play. Relayed `data` frames cost no storage.
* No accounts or chat text are stored, and no IP address is written to storage: the per-address key
  lives only in socket attachments and memory. The Worker's request logs (Workers Logs) are
  disabled in `wrangler.jsonc`.

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

## 14. Changes after the first published revision

For clients written against the earlier text of this document:

1. Liveness counts only text `ping`s and messages, not protocol pings (§2).
2. Frames may be pipelined after `hello` (§2, §6).
3. `welcome.match` may be stale; answer it with `enter` (§5.1).
4. New refusals: `too_many_connections` `4029`, `room_busy` (an `error` for chat; the connect-time
   `room_busy` of this round was removed again in item 11), and `inn_full` now carries
   `retryAfter` (§4, §8). Inn `4000` must stop reconnecting; link `4008`
   means the run ended (§8, §9).
5. A takeover by a `present:false` hello now sends `gone` (§4.1).
6. Preparations no longer survive a full disconnect and no longer expire after 6 h (§4.4).
7. Event history is memory-only (§5.1).
8. `away` is rate limited (§4.6).
9. Link: relay limit 60 burst / 20 frames/s; frame limit 148,480 bytes; surplus unauthenticated
   sockets are evicted with `link_full`; silent sides are dropped lazily and a restarted link notices
   missing sides (§6, §7.3).
10. `dungeonProtocol` is 12 for the turn-clock engine (§4.1).

Second round:

11. No room-wide connection budget any more. Connects are limited per address (10 burst, one per
    2 s, `rate_limited` `4008`, no shard fallthrough); the per-address socket cap (8 per shard)
    now overflows to the next shard, and `4029` only comes when every shard refused. IPv6 is keyed
    by /64; loopback has no per-address limits (§4).
12. Each shard also refuses (with fallthrough) once it holds 64 sockets in total; a link refuses
    beyond 12 (§4, §6).
13. Chat has its own room budget (40, 4/s) plus 8 lines/1 per s per address; `room_busy` no longer
    spends the sender's token. Drink and interact have a separate budget and are silently dropped
    over it, never `room_busy` (§4.3).
14. `away` is never refused (§4.6). After `use_preparation`, `prepare` fails with
    `preparation_used` until the next `enter` (§4.4).
15. Inn flood limit 30 burst / 12 frames/s (§2). Link relay limit 300 burst / 25 frames/s, and a
    flood before `hello` no longer ends the run (§6). Send host snapshots at most 15/s, newest
    wins; guest frames at once (§6).
16. A paired link checks for a silent or vanished side every 2 minutes (§7.3).

Third round:

17. The per-address socket cap also counts the address's closed, never-acknowledged sockets (§4).
