# Orbit network protocol (version 2)

One plugin channel, `orbit_cosmetic:sync`. Each payload is a single message: `[id: u8][fields]`, big-endian,
with no Minecraft framing, so Fabric (custom payloads) and Paper (plugin messages) carry identical bytes.
Implementation and validation: `common/.../protocol/OrbitProtocol.java`.

## Principles

* **Opt-in.** A client sends nothing until the server announces the channel. A server sends nothing to a
  player until it receives `Hello`. Vanilla clients and vanilla servers never see Orbit traffic.
* **Identity, data and configuration are separate.** An *asset* is identified by the SHA-256 of its
  canonical bytes (`AssetId`). The bytes travel at most once per server and are cached by hash on both
  sides. What a player *wears* is a small list of asset ids plus animation settings.
* **No continuous streaming.** Animations are evaluated on every client from synchronized player state
  (vanilla movement flags plus Orbit's `State` flags). Only configuration changes are sent.
* **Server is authoritative.** Everything a client sends is re-validated; the policy a client receives only
  explains restrictions.
* **Strict decoding.** Lengths are bounded, enums range-checked, trailing bytes rejected. A bad message is
  dropped and logged; it never affects the connection or other players. Players who keep sending invalid
  traffic are ignored by Orbit until they reconnect.

## Types

| Type | Encoding |
|---|---|
| `string(n)` | u16 byte length + UTF-8, at most n characters |
| `asset` | 32 raw bytes (SHA-256) |
| `uuid` | two i64 |
| `cosmetics` | u8 count (≤ 16), then per cosmetic: `asset`, `string(64)` name, u8 animation count (≤ 64), then per animation: u8 trigger, u8 loop mode, u16 speed×100, u8 priority (0-100), u8 interrupt, u16 min wait×10, u16 max wait×10 (seconds, for the random trigger); then u8 physics link count (≤ 8), then per link: u16 bone index, 6 × (u8 source, f32 strength, f32 max, f32 resistance) for position X/Y/Z and rotation X/Y/Z |
| `policy` | u8 flags (enabled, canWear, uploads, downloads, icons, animations), u8 max equipped, u8 allowed anchors bitmask, 11×i32 model limits, `string(256)` message |
| chunk | `asset`, i32 total size, u16 index, u16 count, u32 length + bytes |

Chunk numbering is validated: `count = ceil(total / chunkSize)`, every chunk but the last is exactly
`chunkSize`. Upload chunks are 24 KiB (vanilla caps serverbound payloads at 32 KiB), download chunks 96 KiB.

## Messages

Client to server:

| Id | Message | Purpose |
|---|---|---|
| 0x01 | `Hello(u16 protocol, string version)` | Marks the player as Orbit-enabled |
| 0x02 | `Equip(cosmetics)` | Full equipped configuration (replaces the previous one) |
| 0x03 | `UploadChunk(chunk)` | Asset bytes, only for assets the server asked for |
| 0x04 | `RequestAsset(asset)` | Download an asset worn by someone else |
| 0x05 | `State(u8 flags)` | Ability flight, which vanilla does not share with other clients |

Server to client:

| Id | Message | Purpose |
|---|---|---|
| 0x81 | `Welcome(u16 protocol, policy)` | Reply to `Hello` |
| 0x82 | `PolicyUpdate(policy)` | Admin changed something affecting this player |
| 0x83 | `PlayerCosmetics(uuid, bool icon, cosmetics)` | What another player visibly wears (accepted assets only) |
| 0x84 | `PlayerRemove(uuid)` | That player left |
| 0x85 | `AssetChunk(chunk)` | Download data |
| 0x86 | `AssetUnavailable(asset, string reason)` | Download refused |
| 0x87 | `UploadRequest(asset)` | The server does not have this asset yet |
| 0x88 | `UploadResult(asset, bool accepted, string message)` | Validation result |
| 0x89 | `PlayerState(uuid, u8 flags)` | Relayed `State` |
| 0x8A | `Notice(u8 level, string message)` | Text to show the player |

## Flows

**Join.** Server announces the channel, client sends `Hello`, server sends `Welcome`, then
`PlayerCosmetics` for every other Orbit player, and announces the new player to everyone. A protocol mismatch
gets a `Welcome` with Orbit disabled and an explanation.

**Equip.** Client sends `Equip`. For each asset the server either knows it (accepted or rejected) or
sends `UploadRequest`. The client uploads only cosmetics the player chose to wear. The server re-hashes the
bytes, parses and validates them with its own limits and allowed anchors, stores accepted assets as
`<sha256>.bbmodel`, replies with `UploadResult`, and broadcasts the wearer's visible cosmetics.

**View.** On `PlayerCosmetics` the client looks for each asset in its own library, then in
`orbit/.cache/<sha256>.bbmodel` (re-verified), and only then sends `RequestAsset`. Downloads are verified
against the hash, validated with the stricter of the client's and server's limits, and cached.
Servers serve only assets that are currently worn by an allowed player or approved by an admin, paced by a
global per-tick bandwidth budget.

**Random animations** need no messages: every client derives the play times from the shared level game
time and a seed of (wearer UUID, asset hash, animation index), with waits drawn from a SplitMix64 hash
(`RandomSchedule`). **Physics** values are recomputed on every client from synchronized movement.

Version 2 added the random wait times and physics links. A client and server with different versions do
not exchange cosmetics; the server explains the mismatch in its `Welcome`.

**Reconnect.** All state is rebuilt from `Hello`; assets are not re-uploaded or re-downloaded because both
sides keep them by hash.
