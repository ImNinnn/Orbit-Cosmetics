# Deploying the backend at api.orbitcosmetics.qd.je

This puts the backend on the internet at `https://api.orbitcosmetics.qd.je` while it keeps listening only on
`127.0.0.1:8000` of your Fedora machine. A Cloudflare Tunnel connects **out** from your machine to Cloudflare,
so no router port forwarding, no open firewall port and no certificates to manage. `orbitcosmetics.qd.je`
itself stays free for the website.

```
Minecraft client / browser
        │ HTTPS
        ▼
Cloudflare (api.orbitcosmetics.qd.je, certificate, DDoS protection)
        │ tunnel (outbound connection from your PC)
        ▼
cloudflared ──► http://127.0.0.1:8000 ──► uvicorn (ORBIT_MODE=production)
```

What production mode changes compared to the local prototype:

| | development | production |
|---|---|---|
| Upload / publish presence | from this computer only, no account | signed-in Minecraft accounts only (verified with Mojang) |
| Delete over HTTP | from this computer only | never; use `manage.py` |
| Rate limits, upload quota, storage cap | no | yes |
| Visitor IP | socket address | `CF-Connecting-IP` from the tunnel |

Minecraft sign-in works like joining an online-mode server: the game asks Mojang to confirm the player, the
backend asks Mojang "did this player just join?". The Microsoft/Mojang access token never reaches the backend,
and the backend stores no passwords. Cracked/offline accounts can still **see** cosmetics but cannot publish.

---

## Step 1 — Put the domain on Cloudflare

1. Create a free account at <https://dash.cloudflare.com>.
2. **Add a domain** → enter `orbitcosmetics.qd.je` → choose the **Free** plan.
3. Cloudflare scans for records (there are none yet, that's fine) and shows two nameservers, e.g.
   `ada.ns.cloudflare.com` and `bob.ns.cloudflare.com`.
4. In the DigitalPlat dashboard (where you registered the domain), open `orbitcosmetics.qd.je` and replace its
   nameservers with the two Cloudflare ones. Save.
5. Wait until Cloudflare shows the domain as **Active** (minutes to a few hours). You can check with:

   ```bash
   dig +short NS orbitcosmetics.qd.je
   ```

> **If Cloudflare refuses the domain** (for example "this is a subdomain" or "not a registered domain"),
> it's because `qd.je` is a free subdomain service, and Cloudflare's free plan only accepts some of those.
> Use the [fallback without Cloudflare](#fallback-without-cloudflare) at the end of this file instead.

## Step 2 — Cloudflare settings that matter for an API

In the Cloudflare dashboard for `orbitcosmetics.qd.je`:

- **Security → Bots → Bot Fight Mode: Off.** The Minecraft client isn't a browser and can't solve challenges;
  with this on, players would randomly fail to load cosmetics.
- **SSL/TLS → Edge Certificates → Always Use HTTPS: On.**
- Leave **SSL/TLS encryption mode** on its default; the tunnel doesn't use it.
- Optional: **Security → WAF → Rate limiting rules**, one rule for `api.orbitcosmetics.qd.je` at e.g.
  300 requests / minute per IP. The backend limits too (240/min, 10/min for sign-in), but Cloudflare stops
  floods before they reach your PC.

## Step 3 — Install cloudflared on Fedora

```bash
sudo dnf config-manager addrepo --from-repofile=https://pkg.cloudflare.com/cloudflared.repo
sudo dnf install cloudflared
cloudflared --version
```

(On older Fedora with dnf4 the first command is `sudo dnf config-manager --add-repo https://pkg.cloudflare.com/cloudflared.repo`.)

## Step 4 — Create the tunnel

All as your normal user (no sudo):

```bash
cloudflared tunnel login                     # opens a browser; pick orbitcosmetics.qd.je
cloudflared tunnel create orbit              # prints the tunnel ID and writes ~/.cloudflared/<ID>.json
cloudflared tunnel route dns orbit api.orbitcosmetics.qd.je
```

The last command creates the DNS record `api` → `<ID>.cfargotunnel.com` in Cloudflare for you.

Then create the tunnel configuration from the template:

```bash
cd "/path/to/Orbit Cosmetics/backend"
cp deploy/cloudflared-config.example.yml ~/.cloudflared/config.yml
nano ~/.cloudflared/config.yml               # replace TUNNEL_ID (twice) and YOUR_USER
cloudflared tunnel ingress validate          # should print "OK"
```

`~/.cloudflared/<ID>.json` and `~/.cloudflared/cert.pem` are the tunnel's credentials: don't share them or copy
them into the project folder.

## Step 5 — Install the backend as a service

If you haven't set up the virtual environment yet:

```bash
cd "/path/to/Orbit Cosmetics/backend"
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python -m pytest                   # all tests should pass
```

Then:

```bash
bash deploy/install-service.sh
```

This creates `~/.config/orbit-backend.env` from [orbit-backend.env.example](orbit-backend.env.example)
(`ORBIT_MODE=production`, data in `~/orbit-data`, readable by you only), the `orbit-backend` user service, and a
daily backup timer (`~/orbit-backups`, last 14 days kept). Review the env file, and if you change it:
`systemctl --user restart orbit-backend`.

Check it locally:

```bash
curl http://127.0.0.1:8000/v1/health
# {"status":"ok", ..., "mode":"production","auth":"mojang"}
```

## Step 6 — Run the tunnel as a service

```bash
bash deploy/install-tunnel-service.sh
```

Then make both services keep running when you're logged out and after a reboot:

```bash
sudo loginctl enable-linger "$USER"
```

## Step 7 — Check it from the internet

```bash
curl https://api.orbitcosmetics.qd.je/v1/health
curl https://api.orbitcosmetics.qd.je/v1/cosmetics
# Writing without signing in must be refused:
curl -i -X PUT https://api.orbitcosmetics.qd.je/v1/presence/00000000-0000-0000-0000-000000000000 \
     -H 'Content-Type: application/json' -d '{"player_name":"Steve","protocol":2,"equip":"AgA="}'
# → HTTP/2 401  {"error":{"code":"not_authenticated", ...}}
```

Try it from your phone on mobile data too, so you know it's not only working on your own network.

The client mod already uses `https://api.orbitcosmetics.qd.je` as its default backend URL. Join a server
**without** Orbit installed with a real (Microsoft) account: the log should show
`Signed in to the Orbit backend as <name>`, and another player with the mod should see your cosmetics.

---

## Running it

| Task | Command |
|---|---|
| Backend logs | `journalctl --user -u orbit-backend -f` |
| Tunnel logs | `journalctl --user -u orbit-tunnel -f` |
| Restart | `systemctl --user restart orbit-backend` |
| Stop everything public | `systemctl --user stop orbit-tunnel` (the API is then offline, data stays) |
| Make the API read-only | set `ORBIT_MODE=read-only` in `~/.config/orbit-backend.env`, restart |

Moderation and maintenance use [manage.py](../manage.py), run on the server machine. Load the same settings as
the service first:

```bash
cd "/path/to/Orbit Cosmetics/backend"
set -a; source ~/.config/orbit-backend.env; set +a
.venv/bin/python manage.py stats
.venv/bin/python manage.py list --limit 20
.venv/bin/python manage.py show <cosmetic_id>        # includes owner_uuid (who uploaded it)
.venv/bin/python manage.py delete <cosmetic_id>
.venv/bin/python manage.py block <player_uuid> --reason "spam"   # also signs them out
.venv/bin/python manage.py unblock <player_uuid>
.venv/bin/python manage.py blocked
.venv/bin/python manage.py backup ~/orbit-backups    # manual backup (the timer does this daily)
```

**Restore a backup:** stop the service, copy `orbit.sqlite3` and the `cosmetics/` folder from the backup folder
back to the paths in the env file, start the service.

**Update the backend:** replace the code, run `.venv/bin/pip install -r requirements.txt` and
`.venv/bin/python -m pytest`, then `systemctl --user restart orbit-backend`. The database upgrades itself on start.

**Keep Fedora updated** (`sudo dnf upgrade`), which also updates cloudflared.

## Production checklist

- [ ] `curl https://api.orbitcosmetics.qd.je/v1/health` shows `"mode":"production"`. **Never** run
      `development` mode behind the tunnel.
- [ ] Unauthenticated PUT/upload returns 401; `DELETE /v1/cosmetics/...` returns 403.
- [ ] Bot Fight Mode is off.
- [ ] `systemctl --user is-enabled orbit-backend orbit-tunnel orbit-backup.timer` → all `enabled`;
      linger is on (`loginctl show-user "$USER" -p Linger` → `Linger=yes`).
- [ ] `ls ~/orbit-backups` shows a backup after the first day. Copy one to another disk now and then.
- [ ] The machine doesn't sleep (Settings → Power → Automatic Suspend off). The API is offline while the PC is.

## The website (orbitcosmetics.qd.je)

The tunnel only answers for `api.`; requests for any other name get a 404. Two easy options for the site:

- **Cloudflare Pages** (free, static sites): create a Pages project, then add `orbitcosmetics.qd.je` under
  *Custom domains*. Cloudflare adds the DNS record itself since the domain is already there.
- **Same machine**: add a second rule to `~/.cloudflared/config.yml` above the 404 line
  (`- hostname: orbitcosmetics.qd.je` / `service: http://127.0.0.1:8080`), run
  `cloudflared tunnel route dns orbit orbitcosmetics.qd.je`, and serve the site on port 8080.

The API already allows the website to read it from the browser (`ORBIT_CORS_ORIGINS=https://orbitcosmetics.qd.je`).
Browser JavaScript can only *read* (GET); it can't upload or delete.

---

## Fallback without Cloudflare

If Cloudflare won't accept `orbitcosmetics.qd.je`, use another free DNS host plus Caddy (which gets HTTPS
certificates automatically). This needs your router to forward ports.

1. Create a free account on a DNS host that accepts any domain, e.g. [deSEC](https://desec.io) or
   [Hurricane Electric DNS](https://dns.he.net). Add `orbitcosmetics.qd.je` and put its nameservers in DigitalPlat.
2. Add an `A` record `api.orbitcosmetics.qd.je` → your public IP (<https://ifconfig.me>). If your IP changes,
   use the DNS host's dynamic-DNS updater.
3. On the router, forward TCP ports 80 and 443 to your Fedora PC. (Some ISPs use CGNAT, which makes this
   impossible; then a small VPS is the way.)
4. Install and configure Caddy:

   ```bash
   sudo dnf install caddy
   sudo tee /etc/caddy/Caddyfile >/dev/null <<'EOF'
   api.orbitcosmetics.qd.je {
       reverse_proxy 127.0.0.1:8000
   }
   EOF
   sudo firewall-cmd --permanent --add-service=http --add-service=https && sudo firewall-cmd --reload
   sudo systemctl enable --now caddy
   ```

5. Steps 5 and 7 above stay the same; skip steps 2, 3, 4 and 6. Caddy sends `X-Forwarded-For`, which the backend
   uses for rate limiting the same way as Cloudflare's header.
