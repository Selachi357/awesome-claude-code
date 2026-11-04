#!/usr/bin/env python3
"""
WiFi Budget - daily data tracker for capped Wi-Fi (ships, planes, hotels).

* Counts every byte your computer sends + receives, per UTC day (resets 00:00 UTC).
* Live dashboard at http://127.0.0.1:8765
* Warnings at 50 / 75 / 90 / 100 % of your daily allowance.
* Data Saver mode: blocks OS/app updates, game launcher downloads, telemetry and
  ad networks, and marks the Wi-Fi as "metered" so the OS pauses background sync.
  Turns on automatically at 80 % (configurable) and off again at the UTC reset.

Usage:
    python wifi_budget.py run              # start tracker + dashboard (keep it running)
    python wifi_budget.py status           # today's usage
    python wifi_budget.py history          # last 14 days
    python wifi_budget.py saver on|off     # needs admin / sudo
    python wifi_budget.py set-used 620MB   # match the ship's portal reading
    python wifi_budget.py blocklist        # show what Data Saver blocks

Requires: pip install psutil
"""
import argparse
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
import webbrowser
from collections import deque
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

try:
    import psutil
except ImportError:
    sys.exit("psutil is required. Install it with:  pip install psutil")

OS = platform.system()  # "Windows" | "Darwin" | "Linux"
MB = 1_000_000  # providers count in decimal units, and decimal is the conservative choice


def _home():
    # When started with sudo, keep using the real user's data folder.
    sudo_user = os.environ.get("SUDO_USER")
    if OS != "Windows" and sudo_user:
        return Path(os.path.expanduser(f"~{sudo_user}"))
    return Path.home()


APP_DIR = Path(os.environ.get("WIFI_BUDGET_HOME", _home() / ".wifi_budget"))
STATE_FILE = APP_DIR / "usage.json"
CONFIG_FILE = APP_DIR / "config.json"

DEFAULT_CONFIG = {
    "daily_limit_mb": 1500,
    "auto_saver_at_percent": 80,      # set to 0 to disable automatic Data Saver
    "warn_at_percent": [50, 75, 90, 100],
    "interfaces": [],                 # e.g. ["Wi-Fi"] or ["en0"]; empty = all real adapters
    "port": 8765,
    "poll_seconds": 3,
    "extra_blocked_domains": [],      # your own additions to the Data Saver blocklist
}

# Interfaces skipped when "interfaces" is empty: loopback, VPN tunnels (their traffic is
# already counted on the physical adapter) and virtual machine / container bridges.
SKIP_IFACE = re.compile(
    r"^(lo\d*|loopback|utun|tun|tap|wg|tailscale|zt|ipsec|gif|stf|awdl|llw|anpi|ap\d|bridge|"
    r"docker|veth|br-|ifb|virbr|vmnet|vboxnet|vethernet|isatap|teredo|npcap|bluetooth)",
    re.I,
)

# ---------------------------------------------------------------- Data Saver blocklist
BLOCKLIST = {
    "Windows Update / Store": [
        "windowsupdate.microsoft.com", "update.microsoft.com", "download.windowsupdate.com",
        "au.download.windowsupdate.com", "ctldl.windowsupdate.com", "fe2.update.microsoft.com",
        "fe3.delivery.mp.microsoft.com", "sls.update.microsoft.com", "dl.delivery.mp.microsoft.com",
        "tlu.dl.delivery.mp.microsoft.com", "emdl.ws.microsoft.com", "officecdn.microsoft.com",
        "msedge.b.tlu.dl.delivery.mp.microsoft.com",
    ],
    "Apple software & App Store updates": [
        "swscan.apple.com", "swdist.apple.com", "swcdn.apple.com", "updates.cdn-apple.com",
        "updates-http.cdn-apple.com", "mesu.apple.com", "osrecovery.apple.com",
        "oscdn.apple.com", "osxapps.itunes.apple.com", "iosapps.itunes.apple.com",
    ],
    "Linux snaps / flatpaks": ["api.snapcraft.io", "dl.flathub.org"],
    "Browser & app auto-updates": [
        "update.googleapis.com", "dl.google.com", "aus5.mozilla.org", "download.mozilla.org",
        "ardownload.adobe.com", "ccmdl.adobe.com", "agsupdate.adobe.com",
    ],
    "Game launcher downloads": [
        "steampipe.akamaized.net", "steamcdn-a.akamaihd.net", "content1.steampowered.com",
        "cdn.cloudflare.steamstatic.com", "download.epicgames.com",
        "epicgames-download1.akamaized.net", "fastly-download.epicgames.com",
        "level3.blizzard.com", "blzddist1-a.akamaihd.net",
    ],
    "Telemetry": [
        "vortex.data.microsoft.com", "v10.events.data.microsoft.com",
        "v20.events.data.microsoft.com", "settings-win.data.microsoft.com",
        "watson.telemetry.microsoft.com", "telemetry.microsoft.com",
        "incoming.telemetry.mozilla.org",
    ],
    "Ads (incl. heavy video ads)": [
        "doubleclick.net", "googleads.g.doubleclick.net", "securepubads.g.doubleclick.net",
        "pagead2.googlesyndication.com", "tpc.googlesyndication.com", "adservice.google.com",
        "imasdk.googleapis.com", "aax.amazon-adsystem.com", "c.amazon-adsystem.com",
        "static.ads-twitter.com", "an.facebook.net", "cdn.taboola.com", "trc.taboola.com",
        "widgets.outbrain.com", "ads.yahoo.com",
    ],
}

HOSTS_FILE = (
    Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32/drivers/etc/hosts"
    if OS == "Windows" else Path("/etc/hosts")
)
MARK_START = "# >>> wifi-budget data saver >>>"
MARK_END = "# <<< wifi-budget data saver <<<"


# ---------------------------------------------------------------- helpers
def utcnow():
    return datetime.now(timezone.utc)


def today_key(now=None):
    return (now or utcnow()).strftime("%Y-%m-%d")


def fmt_bytes(n):
    n = float(n)
    if n >= 1000 * MB:
        return f"{n / (1000 * MB):.2f} GB"
    if n >= MB:
        return f"{n / MB:.1f} MB"
    return f"{n / 1000:.0f} KB"


def parse_size(text):
    m = re.fullmatch(r"\s*([\d.]+)\s*(b|kb|mb|gb)?\s*", text.lower())
    if not m:
        raise ValueError(f"Can't read size '{text}'. Use e.g. 620MB or 1.2GB")
    mult = {"b": 1, "kb": 1000, "mb": MB, "gb": 1000 * MB}[m.group(2) or "mb"]
    return int(float(m.group(1)) * mult)


def is_admin():
    if OS == "Windows":
        try:
            import ctypes
            return bool(ctypes.windll.shell32.IsUserAnAdmin())
        except Exception:
            return False
    return os.geteuid() == 0


def run_cmd(args):
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=30)
        return r.returncode, (r.stdout or "") + (r.stderr or "")
    except (OSError, subprocess.SubprocessError) as e:
        return 1, str(e)


def fix_owner(path):
    # Files written under sudo should stay readable/writable by the real user.
    uid, gid = os.environ.get("SUDO_UID"), os.environ.get("SUDO_GID")
    if OS != "Windows" and uid and gid:
        try:
            os.chown(path, int(uid), int(gid))
        except OSError:
            pass


def write_json(path, data):
    APP_DIR.mkdir(parents=True, exist_ok=True)
    fix_owner(APP_DIR)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=1))
    os.replace(tmp, path)
    fix_owner(path)


def load_config():
    cfg = dict(DEFAULT_CONFIG)
    if CONFIG_FILE.exists():
        try:
            cfg.update(json.loads(CONFIG_FILE.read_text()))
        except (OSError, ValueError) as e:
            print(f"Warning: couldn't read {CONFIG_FILE} ({e}); using defaults.")
    else:
        write_json(CONFIG_FILE, cfg)
    return cfg


def load_state():
    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text())
        except (OSError, ValueError):
            pass
    return {"days": {}, "last": {}, "boot_time": 0, "last_day": None,
            "saver": False, "saver_auto": False, "saver_restore": {}, "warned": {}}


def notify(title, msg):
    print(f"[{utcnow():%H:%M} UTC] {title}: {msg}", flush=True)
    if OS == "Darwin":
        run_cmd(["osascript", "-e", f'display notification "{msg}" with title "{title}"'])
    elif OS == "Linux" and shutil.which("notify-send"):
        run_cmd(["notify-send", title, msg])
    elif OS == "Windows":
        title, msg = title.replace("'", "''"), msg.replace("'", "''")
        ps = (
            "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, "
            "ContentType=WindowsRuntime] > $null;"
            "$t=[Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent(1);"
            f"$t.GetElementsByTagName('text')[0].AppendChild($t.CreateTextNode('{title}: {msg}')) > $null;"
            "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('WiFi Budget')"
            ".Show([Windows.UI.Notifications.ToastNotification]::new($t))"
        )
        run_cmd(["powershell", "-NoProfile", "-Command", ps])


# ---------------------------------------------------------------- Data Saver
def blocked_domains(cfg):
    doms = [d for group in BLOCKLIST.values() for d in group] + list(cfg["extra_blocked_domains"])
    return list(dict.fromkeys(d.strip().lower() for d in doms if d.strip()))


def _hosts_without_block(text):
    return re.sub(rf"\n?{re.escape(MARK_START)}.*?{re.escape(MARK_END)}\n?", "\n", text, flags=re.S)


def _flush_dns():
    if OS == "Windows":
        run_cmd(["ipconfig", "/flushdns"])
    elif OS == "Darwin":
        run_cmd(["dscacheutil", "-flushcache"])
        run_cmd(["killall", "-HUP", "mDNSResponder"])
    else:
        run_cmd(["resolvectl", "flush-caches"])


def _windows_wifi_profile():
    _, out = run_cmd(["netsh", "wlan", "show", "interfaces"])
    m = re.search(r"^\s*Profile\s*:\s*(.+?)\s*$", out, re.M)
    return m.group(1) if m else None


def _linux_wifi_connection():
    _, out = run_cmd(["nmcli", "-t", "-f", "NAME,TYPE", "connection", "show", "--active"])
    for line in out.splitlines():
        name, _, typ = line.rpartition(":")
        if "wireless" in typ:
            return name.replace("\\:", ":")
    return None


def saver_enable(cfg, state):
    """Returns a list of human-readable result lines. Raises PermissionError if not admin."""
    if not is_admin():
        raise PermissionError("Data Saver needs admin rights: run as Administrator (Windows) "
                              "or with sudo (macOS/Linux).")
    done, restore = [], state.setdefault("saver_restore", {})

    # 1. Block bandwidth-heavy, non-essential domains in the hosts file.
    text = _hosts_without_block(HOSTS_FILE.read_text(errors="ignore"))
    backup = APP_DIR / "hosts.backup"
    if not backup.exists():
        shutil.copyfile(HOSTS_FILE, backup)
    doms = blocked_domains(cfg)
    block = "\n".join([MARK_START] + [f"0.0.0.0 {d}" for d in doms] + [MARK_END])
    HOSTS_FILE.write_text(text.rstrip("\n") + "\n\n" + block + "\n")
    _flush_dns()
    done.append(f"Blocked {len(doms)} update/telemetry/ad domains")

    # 2. Mark the Wi-Fi as metered so the OS pauses updates and background sync.
    if OS == "Windows":
        prof = _windows_wifi_profile()
        if prof:
            _, out = run_cmd(["netsh", "wlan", "show", "profile", f"name={prof}"])
            m = re.search(r"^\s*Cost\s*:\s*(\w+)", out, re.M)
            restore.setdefault("win_profile", [prof, m.group(1) if m else "Unrestricted"])
            code, _ = run_cmd(["netsh", "wlan", "set", "profileparameter", f"name={prof}", "cost=Fixed"])
            if code == 0:
                done.append(f"Wi-Fi '{prof}' set to metered (pauses Windows Update, OneDrive, Store)")
        for svc in ("wuauserv", "BITS", "DoSvc", "UsoSvc"):
            run_cmd(["sc", "stop", svc])
        done.append("Stopped Windows Update / BITS / Delivery Optimization services")
    elif OS == "Linux":
        conn = _linux_wifi_connection()
        if conn:
            _, prev = run_cmd(["nmcli", "-g", "connection.metered", "connection", "show", conn])
            restore.setdefault("nm_conn", [conn, prev.strip() or "unknown"])
            code, _ = run_cmd(["nmcli", "connection", "modify", conn, "connection.metered", "yes"])
            if code == 0:
                done.append(f"Connection '{conn}' set to metered")
    elif OS == "Darwin":
        prefs = {
            "/Library/Preferences/com.apple.SoftwareUpdate": ["AutomaticDownload",
                                                              "AutomaticallyInstallMacOSUpdates"],
            "/Library/Preferences/com.apple.commerce": ["AutoUpdate"],
        }
        saved = restore.setdefault("mac_defaults", {})
        for domain, keys in prefs.items():
            for key in keys:
                code, out = run_cmd(["defaults", "read", domain, key])
                saved.setdefault(f"{domain}|{key}", out.strip() if code == 0 else None)
                run_cmd(["defaults", "write", domain, key, "-bool", "false"])
        done.append("Turned off automatic macOS & App Store downloads")
        done.append("Tip: also enable Low Data Mode (System Settings > Wi-Fi > Details)")

    state["saver"] = True
    return done


def saver_disable(cfg, state):
    if not is_admin():
        raise PermissionError("Turning Data Saver off needs admin rights too.")
    done, restore = [], state.get("saver_restore", {})
    try:
        text = HOSTS_FILE.read_text(errors="ignore")
        if MARK_START in text:
            HOSTS_FILE.write_text(_hosts_without_block(text).rstrip("\n") + "\n")
            _flush_dns()
            done.append("Removed domain blocks")
    except OSError as e:
        done.append(f"Couldn't edit hosts file: {e}")

    if OS == "Windows" and "win_profile" in restore:
        prof, cost = restore["win_profile"]
        cost = "Unrestricted" if cost.lower() in ("unrestricted", "default") else "Fixed"
        run_cmd(["netsh", "wlan", "set", "profileparameter", f"name={prof}", f"cost={cost}"])
        done.append(f"Wi-Fi '{prof}' cost restored to {cost}")
    elif OS == "Linux" and "nm_conn" in restore:
        conn, prev = restore["nm_conn"]
        prev = prev if prev in ("yes", "no", "unknown", "guess-yes", "guess-no") else "unknown"
        run_cmd(["nmcli", "connection", "modify", conn, "connection.metered", prev.replace("guess-", "")])
        done.append(f"Connection '{conn}' metered setting restored")
    elif OS == "Darwin":
        for k, prev in restore.get("mac_defaults", {}).items():
            domain, key = k.split("|")
            if prev is None:
                run_cmd(["defaults", "delete", domain, key])
            else:
                run_cmd(["defaults", "write", domain, key, "-bool", "true" if prev == "1" else "false"])
        done.append("Restored macOS update settings")

    state["saver"], state["saver_auto"], state["saver_restore"] = False, False, {}
    return done


# ---------------------------------------------------------------- tracker
class Tracker:
    def __init__(self, cfg):
        self.cfg = cfg
        self.lock = threading.Lock()
        self.state = load_state()
        self.primed = False
        self.recent = deque()  # (timestamp, bytes) for rate calculation
        self.message = ""

    @property
    def limit(self):
        return int(self.cfg["daily_limit_mb"] * MB)

    def _counters(self):
        out = {}
        for name, c in psutil.net_io_counters(pernic=True).items():
            if self.cfg["interfaces"]:
                if name not in self.cfg["interfaces"]:
                    continue
            elif SKIP_IFACE.match(name):
                continue
            out[name] = [c.bytes_recv, c.bytes_sent]
        return out

    def _day(self, key):
        return self.state["days"].setdefault(key, {"down": 0, "up": 0, "hours": [0] * 24})

    def poll(self):
        now = utcnow()
        key, hour = today_key(now), now.hour
        cur = self._counters()
        boot = psutil.boot_time()
        with self.lock:
            st = self.state
            same_boot = abs(boot - st.get("boot_time", 0)) < 5
            down = up = 0
            for nic, (r, s) in cur.items():
                prev = st["last"].get(nic)
                if prev and same_boot:
                    down += r - prev[0] if r >= prev[0] else r
                    up += s - prev[1] if s >= prev[1] else s
                elif self.primed:  # adapter appeared while we were running: counters start fresh
                    down, up = down + r, up + s
            # Usage that happened while the app was closed is assigned to the day it was
            # last running, so yesterday's leftovers never eat into today's budget.
            book_key = key if self.primed or not st.get("last_day") else st["last_day"]
            day = self._day(book_key)
            day["down"] += down
            day["up"] += up
            if book_key == key:
                day["hours"][hour] += down + up
            self._day(key)

            if st.get("last_day") and st["last_day"] != key:
                self._new_day(key)
            st.update(last=cur, boot_time=boot, last_day=key)
            self.primed = True

            t = time.time()
            self.recent.append((t, down + up))
            while self.recent and t - self.recent[0][0] > 300:
                self.recent.popleft()
            self._check_thresholds(key)
            keep = sorted(st["days"])[-90:]
            st["days"] = {k: st["days"][k] for k in keep}
            write_json(STATE_FILE, st)

    def _new_day(self, key):
        notify("WiFi Budget", f"New UTC day - {fmt_bytes(self.limit)} available again.")
        if self.state.get("saver") and self.state.get("saver_auto"):
            try:
                saver_disable(self.cfg, self.state)
                notify("WiFi Budget", "Data Saver turned off for the new day.")
            except Exception as e:
                self.message = f"Couldn't turn Data Saver off: {e}"

    def _check_thresholds(self, key):
        day = self.state["days"][key]
        pct = (day["down"] + day["up"]) * 100 / self.limit
        warned = self.state.setdefault("warned", {})
        done = warned.setdefault(key, [])
        for w in sorted(self.cfg["warn_at_percent"]):
            if pct >= w and w not in done:
                done.append(w)
                left = max(self.limit - day["down"] - day["up"], 0)
                notify("WiFi Budget", f"{w}% of today's data used - {fmt_bytes(left)} left.")
        for k in list(warned):
            if k != key:
                del warned[k]
        auto = self.cfg["auto_saver_at_percent"]
        if auto and pct >= auto and not self.state.get("saver") and not self.state.get("saver_auto_failed") == key:
            try:
                saver_enable(self.cfg, self.state)
                self.state["saver_auto"] = True
                notify("WiFi Budget", f"Data Saver switched on automatically at {pct:.0f}%.")
            except Exception as e:
                self.state["saver_auto_failed"] = key
                self.message = f"Auto Data Saver couldn't start: {e}"
                notify("WiFi Budget", self.message)

    def rate(self):
        if len(self.recent) < 2:
            return 0.0
        span = self.recent[-1][0] - self.recent[0][0]
        return sum(b for _, b in list(self.recent)[1:]) / span if span > 0 else 0.0

    def status(self):
        with self.lock:
            now = utcnow()
            key = today_key(now)
            day = self._day(key)
            used = day["down"] + day["up"]
            reset = (now + timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
            rate = self.rate()
            left = max(self.limit - used, 0)
            runout = None
            if rate > 500 and left > 0:
                eta = now + timedelta(seconds=left / rate)
                if eta < reset:
                    runout = eta.strftime("%H:%M UTC")
            history = [{"date": k, "used": v["down"] + v["up"]}
                       for k, v in sorted(self.state["days"].items())[-14:]]
            return {
                "date": key, "used": used, "down": day["down"], "up": day["up"],
                "limit": self.limit, "left": left, "percent": used * 100 / self.limit,
                "rate": rate, "runout": runout, "hours": day["hours"],
                "reset_in": int((reset - now).total_seconds()), "now_utc": now.strftime("%H:%M:%S"),
                "saver": bool(self.state.get("saver")), "saver_auto": bool(self.state.get("saver_auto")),
                "admin": is_admin(), "message": self.message, "history": history,
                "interfaces": sorted(self.state.get("last", {})),
            }

    def set_saver(self, on):
        with self.lock:
            self.message = ""
            if on:
                lines = saver_enable(self.cfg, self.state)
                self.state["saver_auto"] = False
            else:
                lines = saver_disable(self.cfg, self.state)
            write_json(STATE_FILE, self.state)
            return lines

    def set_used(self, nbytes):
        with self.lock:
            day = self._day(today_key())
            day["down"], day["up"] = nbytes, 0
            write_json(STATE_FILE, self.state)


# ---------------------------------------------------------------- web dashboard
DASHBOARD = r"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>WiFi Budget</title>
<style>
:root{--bg:#f6f7f9;--card:#fff;--fg:#1b1f24;--mute:#6b7280;--line:#e5e7eb;--ok:#16a34a;--warn:#d97706;--bad:#dc2626;--acc:#2563eb}
@media (prefers-color-scheme:dark){:root{--bg:#0f1115;--card:#181b21;--fg:#e8eaed;--mute:#9aa0a6;--line:#2a2f37;--acc:#60a5fa}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:760px;margin:0 auto;padding:20px 16px 40px}h1{font-size:20px;margin:0 0 2px}.mute{color:var(--mute);font-size:13px}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:18px;margin-top:14px}
.big{font-size:40px;font-weight:700;font-variant-numeric:tabular-nums}.row{display:flex;gap:12px;flex-wrap:wrap}
.row>div{flex:1 1 140px}.lbl{color:var(--mute);font-size:12px;text-transform:uppercase;letter-spacing:.04em}
.val{font-size:20px;font-weight:600;font-variant-numeric:tabular-nums}
.bar{height:14px;background:var(--line);border-radius:7px;overflow:hidden;margin:12px 0 4px}.bar>i{display:block;height:100%;border-radius:7px;transition:width .4s}
.chart{display:flex;align-items:flex-end;gap:3px;height:110px;margin-top:10px}.chart div{flex:1;background:var(--acc);border-radius:3px 3px 0 0;min-height:1px;position:relative}
.chart div.over{background:var(--bad)}.axis{display:flex;gap:3px;font-size:10px;color:var(--mute)}.axis span{flex:1;text-align:center;overflow:hidden}
button{font:inherit;font-weight:600;border:0;border-radius:8px;padding:10px 16px;cursor:pointer;background:var(--acc);color:#fff}
button.off{background:var(--line);color:var(--fg)}.pill{display:inline-block;padding:2px 10px;border-radius:99px;font-size:12px;font-weight:600}
#msg{white-space:pre-line;font-size:13px;margin-top:10px}
</style></head><body><main>
<h1>WiFi Budget</h1><div class="mute">Daily allowance resets at 00:00 UTC &middot; now <span id="now"></span> UTC</div>
<div class="card"><div class="lbl">Used today</div><div class="big" id="used">-</div>
<div class="bar"><i id="fill"></i></div><div class="mute" id="pct"></div>
<div class="row" style="margin-top:14px">
<div><div class="lbl">Left</div><div class="val" id="left"></div></div>
<div><div class="lbl">Speed now</div><div class="val" id="rate"></div></div>
<div><div class="lbl">Resets in</div><div class="val" id="reset"></div></div></div>
<div class="mute" id="runout" style="margin-top:10px"></div></div>
<div class="card"><div style="display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap">
<div><div class="val">Data Saver <span class="pill" id="sv"></span></div>
<div class="mute">Blocks OS/app updates, game downloads, telemetry &amp; ads, and marks the Wi-Fi as metered.</div></div>
<button id="tog"></button></div><div id="msg" class="mute"></div></div>
<div class="card"><div class="lbl">Today by hour (UTC)</div><div class="chart" id="hours"></div><div class="axis" id="haxis"></div></div>
<div class="card"><div class="lbl">Last 14 days</div><div class="chart" id="days"></div><div class="axis" id="daxis"></div></div>
<div class="mute" style="margin-top:12px" id="ifs"></div>
</main><script>
const $=id=>document.getElementById(id);
const fb=n=>n>=1e9?(n/1e9).toFixed(2)+' GB':n>=1e6?(n/1e6).toFixed(1)+' MB':(n/1e3).toFixed(0)+' KB';
const col=p=>p>=90?'var(--bad)':p>=75?'var(--warn)':'var(--ok)';
let S=null;
function bars(el,ax,vals,labels,limit,tip){
  const m=Math.max(limit||0,...vals,1);
  $(el).innerHTML=vals.map((v,i)=>`<div title="${tip(i)}: ${fb(v)}" class="${limit&&v>limit?'over':''}" style="height:${v/m*100}%"></div>`).join('');
  $(ax).innerHTML=labels.map(l=>`<span>${l}</span>`).join('');
}
async function load(){
  try{S=await (await fetch('/api/status')).json()}catch(e){$('msg').textContent='Tracker not reachable.';return}
  const p=S.percent;$('now').textContent=S.now_utc;
  $('used').textContent=fb(S.used)+' / '+fb(S.limit);
  $('fill').style.width=Math.min(p,100)+'%';$('fill').style.background=col(p);
  $('pct').textContent=p.toFixed(1)+'% used  ·  ↓ '+fb(S.down)+'  ↑ '+fb(S.up);
  $('left').textContent=fb(S.left);$('left').style.color=col(p);
  $('rate').textContent=fb(S.rate)+'/s';
  const h=Math.floor(S.reset_in/3600),m=Math.floor(S.reset_in%3600/60);$('reset').textContent=h+'h '+m+'m';
  $('runout').textContent=S.left<=0?'⚠ Daily allowance used up.':S.runout?'⚠ At the current speed you will run out at '+S.runout+'.':'';
  $('sv').textContent=S.saver?(S.saver_auto?'ON (auto)':'ON'):'OFF';
  $('sv').style.background=S.saver?'var(--ok)':'var(--line)';$('sv').style.color=S.saver?'#fff':'var(--fg)';
  $('tog').textContent=S.saver?'Turn off':'Turn on';$('tog').className=S.saver?'off':'';
  if(S.message)$('msg').textContent=S.message;
  else if(!S.admin)$('msg').textContent='Tracking works without admin. To use Data Saver, restart the app as Administrator / with sudo.';
  bars('hours','haxis',S.hours,S.hours.map((_,i)=>i%3?'':i),0,i=>i+':00');
  bars('days','daxis',S.history.map(d=>d.used),S.history.map(d=>d.date.slice(8)),S.limit,i=>S.history[i].date);
  $('ifs').textContent='Counting adapters: '+(S.interfaces.join(', ')||'none');
}
$('tog').onclick=async()=>{$('tog').disabled=true;
  const r=await (await fetch('/api/saver',{method:'POST',body:JSON.stringify({on:!S.saver})})).json();
  $('msg').textContent=(r.error||r.result.join('\n'));$('tog').disabled=false;load()};
load();setInterval(load,3000);
</script></body></html>"""


def make_handler(tracker):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _send(self, code, body, ctype="application/json"):
            data = body.encode() if isinstance(body, str) else json.dumps(body).encode()
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path == "/api/status":
                self._send(200, tracker.status())
            elif self.path in ("/", "/index.html"):
                self._send(200, DASHBOARD, "text/html; charset=utf-8")
            else:
                self._send(404, {"error": "not found"})

        def do_POST(self):
            # Only accept requests from this page / the CLI, not from other websites.
            origin = self.headers.get("Origin")
            if origin and not re.match(r"^http://(127\.0\.0\.1|localhost)(:\d+)?$", origin):
                return self._send(403, {"error": "forbidden"})
            try:
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or "{}")
                if self.path == "/api/saver":
                    self._send(200, {"result": tracker.set_saver(bool(body.get("on")))})
                elif self.path == "/api/set-used":
                    tracker.set_used(int(body["bytes"]))
                    self._send(200, {"result": ["ok"]})
                else:
                    self._send(404, {"error": "not found"})
            except PermissionError as e:
                self._send(200, {"error": str(e)})
            except Exception as e:
                self._send(500, {"error": f"{type(e).__name__}: {e}"})

    return Handler


# ---------------------------------------------------------------- CLI
def api(cfg, path, payload=None):
    """Talk to a running tracker. Returns None if it isn't running."""
    url = f"http://127.0.0.1:{cfg['port']}{path}"
    try:
        data = json.dumps(payload).encode() if payload is not None else None
        with urllib.request.urlopen(urllib.request.Request(url, data=data), timeout=60) as r:
            return json.loads(r.read())
    except OSError:
        return None


def cmd_run(cfg, args):
    if api(cfg, "/api/status"):
        print(f"Already running: http://127.0.0.1:{cfg['port']}")
        return
    tracker = Tracker(cfg)
    tracker.poll()
    server = ThreadingHTTPServer(("127.0.0.1", cfg["port"]), make_handler(tracker))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    url = f"http://127.0.0.1:{cfg['port']}"
    s = tracker.status()
    print(f"WiFi Budget running - dashboard: {url}")
    print(f"Daily limit {fmt_bytes(tracker.limit)}, resets 00:00 UTC. Used today: {fmt_bytes(s['used'])}")
    print(f"Counting adapters: {', '.join(s['interfaces']) or 'none found'}")
    if not is_admin():
        print("Note: not running as admin/sudo, so Data Saver can't be switched on.")
    print("Press Ctrl+C to stop.")
    if not args.no_browser:
        webbrowser.open(url)
    try:
        while True:
            time.sleep(cfg["poll_seconds"])
            try:
                tracker.poll()
            except Exception as e:  # keep tracking no matter what
                print(f"poll error: {e}", file=sys.stderr)
    except KeyboardInterrupt:
        print("\nStopped. Usage while stopped is still picked up on next start (unless you reboot).")


def cmd_status(cfg, args):
    s = api(cfg, "/api/status")
    if s is None:
        t = Tracker(cfg)
        t.primed = True  # don't book anything; just read
        s = t.status()
        print("(tracker is not running - showing last saved numbers)")
    bar = "#" * int(min(s["percent"], 100) / 4)
    print(f"{s['date']} (UTC)   [{bar:<25}] {s['percent']:.1f}%")
    print(f"Used {fmt_bytes(s['used'])} of {fmt_bytes(s['limit'])}  ->  {fmt_bytes(s['left'])} left")
    print(f"Resets in {s['reset_in'] // 3600}h {s['reset_in'] % 3600 // 60}m   "
          f"Data Saver: {'ON' if s['saver'] else 'off'}")
    if s.get("runout"):
        print(f"At the current speed you run out at {s['runout']}.")


def cmd_history(cfg, args):
    st = load_state()
    limit = cfg["daily_limit_mb"] * MB
    for k, v in sorted(st["days"].items())[-14:]:
        used = v["down"] + v["up"]
        print(f"{k}  {fmt_bytes(used):>10}  {'#' * int(min(used / limit, 1.2) * 30)}"
              f"{'  OVER' if used > limit else ''}")


def cmd_saver(cfg, args):
    on = args.state == "on"
    r = api(cfg, "/api/saver", {"on": on})
    if r is None:  # tracker not running: act directly
        st = load_state()
        try:
            lines = saver_enable(cfg, st) if on else saver_disable(cfg, st)
        except PermissionError as e:
            sys.exit(str(e))
        write_json(STATE_FILE, st)
        r = {"result": lines}
    print(r.get("error") or "\n".join(r["result"]))


def cmd_set_used(cfg, args):
    n = parse_size(args.amount)
    if api(cfg, "/api/set-used", {"bytes": n}) is None:
        st = load_state()
        day = st["days"].setdefault(today_key(), {"down": 0, "up": 0, "hours": [0] * 24})
        day["down"], day["up"] = n, 0
        write_json(STATE_FILE, st)
    print(f"Today's usage set to {fmt_bytes(n)}.")


def cmd_blocklist(cfg, args):
    for group, doms in BLOCKLIST.items():
        print(f"\n{group}:")
        for d in doms:
            print(f"  {d}")
    if cfg["extra_blocked_domains"]:
        print("\nYour extra domains:")
        for d in cfg["extra_blocked_domains"]:
            print(f"  {d}")
    print(f"\nEdit {CONFIG_FILE} -> extra_blocked_domains to add more.")


def main():
    p = argparse.ArgumentParser(description="Daily Wi-Fi data tracker (UTC reset) with Data Saver.")
    sub = p.add_subparsers(dest="cmd")
    r = sub.add_parser("run", help="start tracker + dashboard")
    r.add_argument("--no-browser", action="store_true")
    sub.add_parser("status", help="show today's usage")
    sub.add_parser("history", help="show the last 14 days")
    s = sub.add_parser("saver", help="turn Data Saver on/off (admin)")
    s.add_argument("state", choices=["on", "off"])
    u = sub.add_parser("set-used", help="correct today's total, e.g. set-used 620MB")
    u.add_argument("amount")
    sub.add_parser("blocklist", help="list domains Data Saver blocks")
    p.add_argument("--limit", help="daily allowance, e.g. 1.5GB (saved to config)")
    args = p.parse_args()

    cfg = load_config()
    if args.limit:
        cfg["daily_limit_mb"] = parse_size(args.limit) / MB
        write_json(CONFIG_FILE, cfg)
    {"run": cmd_run, "status": cmd_status, "history": cmd_history, "saver": cmd_saver,
     "set-used": cmd_set_used, "blocklist": cmd_blocklist, None: cmd_run}[args.cmd](cfg, args)


if __name__ == "__main__":
    if len(sys.argv) == 1:
        sys.argv.append("run")
    main()
