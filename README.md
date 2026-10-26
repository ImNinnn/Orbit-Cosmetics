# Orbit Cosmetic

**Website: [orbitcosmetics.qd.je](https://orbitcosmetics.qd.je)** · [Creator guide](https://orbitcosmetics.qd.je/guide.html)

Wear your own Blockbench cosmetics in Minecraft Java Edition **26.3**, and share them with other players on
servers that run the Orbit Cosmetic server mod or plugin.

* Put `.bbmodel` files in `<minecraft instance>/orbit/` and load them in the Cosmetic Manager. No resource
  packs, no restarts, no converting.
* Attach geometry to the head, body, arms or legs with named groups. Cosmetics follow the player's real
  animated body, including custom player models from Entity Model Features packs such as Fresh Animations.
* Animated cosmetics: assign each animation to idle, walk, sprint, jump, fall, sneak, crouch, swim, fly or
  "always", with loop mode, speed, priority and interruption.
* Multiplayer: other Orbit players see your cosmetics; players without the mod join and play normally.

| Component | File | Install on |
|---|---|---|
| Client mod (Fabric) | `Mod (client side)/build/libs/orbit-cosmetic-<version>.jar` | each player's `mods/` |
| Server mod (Fabric) | `Plugin and Mod (server side)/fabric/build/libs/orbit-cosmetic-server-fabric-<version>.jar` | Fabric server `mods/` |
| Server plugin (Paper) | `Plugin and Mod (server side)/paper/build/libs/orbit-cosmetic-server-paper-<version>.jar` | Paper server `plugins/` |

Requirements: Minecraft 26.3, Java 25. Fabric builds need Fabric Loader 0.20.1+ and Fabric API. The Paper
plugin was tested on Paper 26.3 build 222.

## For players

1. Install Fabric Loader, Fabric API and `orbit-cosmetic-<version>.jar`.
2. Start the game once; the `orbit` folder is created in your instance folder.
3. Press **O** (or click **Cosmetics** in the pause or title menu) to open the **Cosmetic Manager**.
4. Drag `.bbmodel` files onto the window (or copy them into the folder), select one, and click **Load**.
   Double-clicking an entry also loads or unloads it. Several cosmetics can be loaded at once.

The manager shows each cosmetic's status (loaded, animated, warnings, errors), a live preview on your
player, the body parts it uses and any problems found. **Remove** deletes the file after confirmation.
**Reload** rescans the folder after you edit files in Blockbench.

### Making a cosmetic

Click **Create**. Orbit writes `orbit/templates/orbit_template.bbmodel` (plus `README_TEMPLATE.txt`) and
opens it with your default `.bbmodel` application, normally Blockbench. The template contains:

* `REFERENCE_PLAYER`: a locked player body for orientation. It is never rendered.
* `HEAD`, `BODY`, `LEFT_ARM`, `RIGHT_ARM`, `LEFT_LEG`, `RIGHT_LEG`: put your geometry inside these.
* `example_top_hat` under `HEAD`, with Export turned off so the template itself renders nothing.
* An example `IDLE` animation and a blank `cosmetic_texture.png`.

Geometry outside the anchor groups follows only the player's position and body rotation. Save the finished
file into the `orbit` folder (not `templates`). `examples/orbit_demo.bbmodel` is a complete example with
something on every body part.

See [docs/COORDINATES.md](docs/COORDINATES.md) for exactly how Blockbench coordinates, pivots,
rotations, UVs and animations map to the game, and what is not supported.

### Animations

Select a cosmetic and click **Animations**. Animations named `IDLE`, `WALK`, `SPRINT`, `JUMP`, `FALL`,
`SNEAK`, `CROUCH`, `SWIM`, `FLY` or `RANDOM` (also `animation.x.walk`, `run`, `glide`, `fidget`, and
similar) are assigned automatically. Others start as *Never* and can be assigned by hand.

| Setting | Meaning |
|---|---|
| Plays | The player state that triggers it, or *Always*, *Random* or *Never* |
| Mode | *Loop*; *Once* (then back to the default pose); *Hold* (stay on the last frame) |
| Min / Max wait | For *Random*: the pause between plays is a random time in this range (0 to 120 s). Random animations always play once |
| Speed | 0.1x to 4x |
| Priority | When animations move the same part, the higher priority wins; equal priorities add up |
| Interrupt | While playing, pauses this cosmetic's lower-priority animations |

Settings are saved in the cosmetic's own settings file (see below) and sent to the server, so everyone sees
the same animation. *Random*
animations are scheduled from the game time the server shares with every client and a seed made from the
player, the cosmetic and the animation, so every player sees them start at the same moment without any
extra network traffic.

#### Advanced: movement physics

**Advanced** (in the Animations screen) makes parts react to how the wearer moves, for example a cape that
lifts while running or a tail that swings when turning. It also works for cosmetics without animations.
Click **Add part...**, pick a group from the tree of the cosmetic's parts, and set any of position X/Y/Z
and rotation X/Y/Z (Blockbench axes):

| Setting | Meaning |
|---|---|
| Moves with | *Forward* (backward is negative), *Sideways* (right is positive), *Vertical* (up is positive), *Any speed*, or *Turning* (body turning right is positive) |
| Strength | Pixels or degrees per block/second of movement (Turning: per quarter turn per second). Negative reverses the direction |
| Maximum | The largest movement, in pixels or degrees |
| Resistance | 0 reacts instantly; close to 1 reacts late and settles smoothly |

Up to 8 parts per cosmetic. Physics is added on top of animations, runs once per game tick (so it does not
depend on frame rate) and is synchronized like the animation settings. Servers that disable animations also
disable physics. Players further away than the *Animation distance* setting (32 blocks by default) are shown
in their rest pose, without animations or physics.

### Sharing a configured cosmetic

Each cosmetic's settings are stored in a JSON file with the same name next to the model:
`orbit/top_hat.bbmodel` has `orbit/top_hat.json`. It is created the first time you change a setting and
holds the animation settings (by animation name) and the physics (by group, with the group's name for
readability). To share a cosmetic exactly as you configured it, send both files. Dropping a `.bbmodel` onto
the Cosmetic Manager also imports the `.json` next to it.

You can add details by editing the file; they are shown in the manager:

```json
{
  "orbitCosmetic": 1,
  "model": "top_hat.bbmodel",
  "name": "Top hat",
  "author": "Your name",
  "description": "A tall hat with a swinging brim.",
  "animations": { "IDLE": { "trigger": "IDLE", "loop": "LOOP", "speed": 1.0, "priority": 10, "interrupt": false } },
  "physics": { "<group uuid>": { "part": "brim", "rotation_x": { "source": "FORWARD", "strength": 8, "max": 40, "resistance": 0.6 } } }
}
```

Click **Reload** after editing it by hand. Shared files are treated as untrusted (size-limited, every value
range-checked). A same-named `.json` without the `"orbitCosmetic"` key (for example a geometry file exported
by Blockbench) is never overwritten; the manager shows a warning and settings changes are not saved for
that cosmetic until the file is renamed. Removing a cosmetic also deletes its settings file.
`orbit/orbit-state.json` only remembers which cosmetics are loaded; settings saved there by earlier versions
are moved into the per-cosmetic files automatically.

### Servers without Orbit (Orbit Cloud)

On a server that runs the Orbit server mod or plugin, cosmetics are shared through that server, and nothing
else is used. On a server **without** Orbit, the client falls back to the Orbit backend (the FastAPI service in
`backend/`) a few seconds after joining:

1. Your worn cosmetics are uploaded to the backend if it does not have them yet (checked by SHA-256; files up
   to 1 MiB).
2. What you wear (cosmetics plus their animation and physics settings) is reported as your *presence*,
   refreshed every 30 seconds and removed when you leave.
3. Every 5 seconds the client asks what the players in the tab list wear, downloads missing cosmetics from the
   backend (verified against their SHA-256 and cached like downloads from Orbit servers) and shows them.

If an Orbit server identifies itself later, the fallback stops immediately. In singleplayer it is only used
while the world is open to LAN.

Sharing needs you to be signed in. The client signs in to the backend with your Minecraft account the same
way joining an online-mode server does: the game asks Mojang to confirm you, and the backend asks Mojang
whether you did. Your Microsoft/Mojang login never reaches the backend. With an offline or cracked account the
sign-in fails (one warning in the log), and you can still **see** other players' cosmetics but yours aren't
shared.

Settings: **Backend fallback** in the Settings screen turns it on or off (`useBackend` in
`config/orbit_cosmetic.json`), and `backendUrl` sets the address (default `https://api.orbitcosmetics.qd.je`).
If the backend is not reachable, a warning is logged and everything else works as before.

To test against a local development backend instead, set `backendUrl` to `http://127.0.0.1:8000`; it accepts
uploads and presence from the same computer without signing in. Config files written by earlier test builds
still contain that local address, so change it to the public one on those instances. See
`backend/README.md` for running the backend, and `backend/deploy/DEPLOYMENT.md` for the public setup.

### Featured catalog

**Catalog** in the Cosmetic Manager lists the featured cosmetics on Orbit Cloud: cosmetics the Orbit team
checked by hand. **Get** downloads one into `orbit/featured/` and loads it. Downloads are checked against the
catalog's SHA-256 and by the same model validator as your own files. Featured cosmetics stay shared even while
you are banned from Orbit Cloud.

### Players menu

On a server where Orbit players can see each other (an Orbit server, or Orbit Cloud), the pause menu gets a
**Players** button next to **Cosmetics**. It lists the other Orbit players there:

- **Hide cosmetics** hides that player's cosmetics for you only, on every server, until you show them again
  (`hiddenPlayers` in `config/orbit_cosmetic.json`).
- **Report…** sends a report with a reason, an optional note and the cosmetics they wear to the Orbit Cloud
  moderators. Reporting needs a Minecraft account verified by Mojang. Reports go to Orbit Cloud on any server,
  including Orbit servers.

On Orbit Cloud, a player reported by 3 different players within a week is brought to the moderators' attention;
at 6 they are banned from Orbit Cloud for a week: their uploads stop and only their featured cosmetics are
shared. Server bans (`/orbit block`, `/orbit ban`) are separate and up to each server.

### Notifications

Orbit shows its own notifications (top right) when a cosmetic is loaded or unloaded, fails to load, is uploaded
to Orbit Cloud or a server, is rejected (too big, wrong format) or banned; when you connect to Orbit Cloud
("Connected to Orbit Cloud") or an Orbit server ("Connected to Server"); when you are banned or unbanned; and
for messages from the server. Failures of cosmetics already in the folder at startup are only shown in the
manager. Turn them off with **Notifications** in Settings.

### Settings

**Settings** in the manager: show cosmetics, show other players' cosmetics, first-person arm cosmetics,
animations, nameplate icons, your own nameplate in third person, downloading from servers, notifications, render and animation distance, a per-player face
budget for performance, download cache size (with **Clear download cache**), and *Attach to*: the animated
model (default, follows EMF/Fresh Animations) or the plain vanilla model. Stored in
`config/orbit_cosmetic.json`. Server restrictions always win over these settings.

### Nameplate icon

Players using Orbit on an Orbit server get a small Orbit icon at the left of their name, inside the
nameplate background (if the server allows it). With Essential also installed, Essential's icon comes first
and Orbit's second. The icon only means the player has Orbit installed. It does **not** mean their
cosmetics are safe, verified or endorsed.

*Own nameplate* in Settings shows your own nameplate in third-person view, as others see it (including
the icon when the server allows it). It is never shown in first person or in inventory previews.

## For server administrators

Install the Fabric server mod (with Fabric API) **or** the Paper plugin. Players without the client mod are
unaffected and are never sent Orbit data. Disabling Orbit never affects gameplay.

Files: `config/orbit_cosmetic/` on Fabric, `plugins/OrbitCosmetic/` on Paper:

* `orbit-server.json`: configuration (created on first start).
* `assets/`: accepted cosmetics, named by SHA-256.
* `security.log`: rejected uploads, malformed traffic, rate-limit hits (rotated at 1 MiB).

### Configuration (`orbit-server.json`)

| Key | Default | Meaning |
|---|---|---|
| `enabled` | true | Master switch |
| `allowUploads` | true | Accept cosmetics uploaded by players |
| `allowDownloads` | true | Let clients download cosmetics worn by others |
| `requireApprovedAssets` | false | Only show cosmetics approved with `/orbit approve` |
| `showNameplateIcons` | true | Allow the Orbit nameplate icon |
| `allowAnimations` | true | Allow animated cosmetics to animate |
| `maxEquippedPerPlayer` | 4 | Visible cosmetics per player (max 16) |
| `allowedAnchors` | all | Allowed body parts: `HEAD BODY LEFT_ARM RIGHT_ARM LEFT_LEG RIGHT_LEG ROOT`. Models using others are refused |
| `limits` | see file | Max asset size, elements, groups, nesting, faces, textures, texture size and bytes, animations, keyframes |
| `maxStoreMegabytes` | 256 | Disk space for stored cosmetics; unused, unapproved ones are evicted first |
| `downloadKilobytesPerTick` | 256 | Total download bandwidth, shared by all players |
| `maxUploadKilobytesPerMinute` | 4096 | Per-player upload budget |
| `maxMessagesPerSecond` | 40 | Per-player Orbit message rate |
| `blockedPlayers`, `approvedAssets`, `bannedAssets` | empty | Managed by the commands below |
| `disabledMessage`, `blockedMessage` | text | Shown to players when restricted |

Hard limits built into Orbit (8 MiB per asset, 1024 px textures, 24576 faces and so on) apply whatever
the file says.

### Commands and permissions

`/orbit` requires operator level 3 or the `orbit_cosmetic.admin` permission on Fabric, and `orbit.admin`
(default: op) on Paper.

| Command | |
|---|---|
| `/orbit status` | Overview |
| `/orbit enable` / `disable` | Turn Orbit on or off for everyone, immediately |
| `/orbit block <player or uuid>` / `unblock` | Stop a player wearing cosmetics (survives reconnects; client settings cannot bypass it) |
| `/orbit players` | Online Orbit players and what they wear |
| `/orbit assets` | Stored cosmetics, with status and wearers |
| `/orbit approve <hash>` / `unapprove` | Manage approved cosmetics (8+ characters of the hash are enough) |
| `/orbit ban <hash>` / `unban` | Forbid a cosmetic for everyone (players wearing it are told) |
| `/orbit set <setting> <value>` | `uploads`, `downloads`, `icons`, `animations`, `requireApproval`, `maxEquipped`, `maxAssetKilobytes`, `maxTextureSize`, `maxFaces`, `maxAnimations` |
| `/orbit log [count]` | Recent errors and security events |
| `/orbit purge` | Delete stored cosmetics that are neither worn nor approved |
| `/orbit reload` | Re-read `orbit-server.json` |

Who may *wear* cosmetics: everyone by default. Deny `orbit_cosmetic.use` (Fabric, via a permission mod
such as LuckPerms) or `orbit.use` (Paper) to restrict it. Permission changes are picked up within five
seconds.

## Security

All cosmetic files, local or received, are treated as untrusted:

* Models are parsed as data only. Nothing is executed. Molang is a small sandboxed expression evaluator
  without loops or access to anything but the player's animation state.
* Sizes and counts are checked before work proportional to them is done (JSON nesting, elements, groups,
  faces, textures, texture dimensions read from the PNG header before decoding, animations, keyframes,
  expression length).
* File names on disk always come from hashes or from the player's own import. Textures referenced by a
  model are only read from the model's own folder, so a model cannot pull other files from your disk into
  an upload.
* The server never trusts client claims: it re-hashes uploads, re-validates them with its own limits,
  checks permissions itself, rate-limits every player and only uploads assets it asked for.
* Malformed packets are dropped and logged, never fatal. Only data URLs embedded in the model are used;
  Orbit never downloads from external URLs.

## Building

```
./gradlew build
```

builds all three jars and runs the unit tests (parser and coordinate mapping, Molang, interpolation,
triggers, protocol fuzzing, and server flows: upload, download, blocking, approval, malicious traffic).
Gradle provisions Java 25 automatically if needed.

Development runs:

* `./gradlew :client:runClient`, `:server-fabric:runServer` (run directories are under `run/`).
* `./gradlew :client:runTestClient`: development-only harness (`src/testmod`, never shipped) that joins
  `localhost:25599`, takes screenshots and logs diagnostics; `ORBIT_TEST_ROLE=wearer|viewer`.

Project layout:

```
common/                         Pure Java, shared by everything: .bbmodel parser and validation,
                                animation engine and Molang, protocol, server core and admin commands
Mod (client side)/              Fabric client mod: library, manager UI, rendering, networking
Plugin and Mod (server side)/
  fabric/                       Fabric server mod (dedicated servers)
  paper/                        Paper plugin
docs/                           COORDINATES.md, PROTOCOL.md
examples/                       orbit_demo.bbmodel and its generator
```

Client-only code lives only in the client mod, which is marked `environment: client`. Dedicated servers
never load it, and the server mod never references client classes.

## How the EMF / Fresh Animations compatibility works

Entity Model Features applies its custom player animations to the vanilla `head`, `body`, arm and leg
parts at the end of `PlayerModel.setupAnim`, and its parts apply custom pivots in `translateAndRotate`.
Vanilla runs `setupAnim` just before player render layers. Orbit's render layer therefore walks the parts
of the model that is actually being rendered, from the root to the body part, and applies each part's own
transform. Cosmetics follow custom animations, changed proportions and moved pivots without depending on
an EMF API. If a part cannot be found in the hierarchy, Orbit falls back to that part's own vanilla
transform. *Attach to: Vanilla model* in Settings ignores replaced models entirely. EMF is optional.

## Known limitations

* Importing works by drag and drop or the folder; Minecraft 26.3 has no native file picker.
* Cosmetics on slim (Alex) arms use the same anchor pivot, which sits half a pixel lower on slim arms.
* A cosmetic's preview needs a loaded world (the preview is drawn on your player).
* Animation transitions switch instantly; there is no cross-fading between animations.
* Animated textures, sound/particle keyframes, animation controllers and quaternion interpolation are not
  supported (see docs/COORDINATES.md).
* Cosmetics are not hidden by armor or held items; design them with that in mind.
* Singleplayer: you see your own cosmetics only. On servers without Orbit, other players are seen only
  through the backend fallback, which is currently a local development prototype (same computer only).
* The EMF integration was verified by reading EMF 3.3.12's code paths; it has not been tested in game with
  Fresh Animations installed.

## Website

The website in [`website/`](website/) is plain HTML and CSS (no build step, no API calls). Pushing changes to
`website/` on `main` publishes it to GitHub Pages at https://orbitcosmetics.qd.je through
[`.github/workflows/pages.yml`](.github/workflows/pages.yml). To preview it locally:
`python3 -m http.server --directory website 8080`, then open http://127.0.0.1:8080.
