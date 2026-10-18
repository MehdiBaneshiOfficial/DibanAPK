"""Facade the Android app (Kotlin, via Chaquopy) uses to drive the Diban engine.

The proxy engine itself (core/, proxy/, relay/) is the exact same code as the
desktop app.  This module only replaces what the Tk GUI used to do: reading and
writing config, creating the MITM CA, starting/stopping the engine, probing the
connection and keeping an in-memory log.

Everything returned to Kotlin is a plain str / bool / int, with JSON strings for
anything structured, so no Python objects ever cross the bridge.
"""
from __future__ import annotations

import collections
import json
import logging
import os
import re
import secrets
import shutil
import socket
import threading
import time
import urllib.request

import connect_code
from engine import Engine, EngineError

HTTP_PORT, SOCKS_PORT = 8085, 1080
_KEY_FILE = "pending_key.txt"
_CFG_FILE = "config.json"

_ID_URL = re.compile(r"/macros/s/([A-Za-z0-9_\-]{20,})")
_ID_RAW = re.compile(r"^[A-Za-z0-9_\-]{20,}$")

_lock = threading.RLock()
_data_dir = ""
_engine: Engine | None = None
_log_buf: collections.deque = collections.deque(maxlen=800)
_log_handler: logging.Handler | None = None


# ───────────────────────── setup ─────────────────────────
class _MemHandler(logging.Handler):
    def emit(self, record):
        try:
            _log_buf.append(self.format(record))
        except Exception:  # noqa: BLE001
            pass


def init(data_dir: str) -> None:
    """Must be called once, before anything from proxy/ is imported."""
    global _data_dir, _log_handler
    _data_dir = data_dir
    os.makedirs(data_dir, exist_ok=True)
    # proxy.mitm reads this at import time to decide where the CA lives.
    os.environ["MHR_DATA_DIR"] = data_dir

    root = logging.getLogger()
    root.setLevel(logging.INFO)
    if _log_handler is None:
        _log_handler = _MemHandler()
        _log_handler.setFormatter(
            logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s", "%H:%M:%S"))
        root.addHandler(_log_handler)


def version() -> str:
    from core.constants import __version__
    return __version__


# ───────────────────────── config ─────────────────────────
def _cfg_path() -> str:
    return os.path.join(_data_dir, _CFG_FILE)


def _load() -> dict | None:
    try:
        with open(_cfg_path(), encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else None
    except (OSError, ValueError):
        return None


def _save(cfg: dict) -> None:
    tmp = _cfg_path() + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    os.replace(tmp, _cfg_path())


def has_config() -> bool:
    return _load() is not None


def server_count() -> int:
    cfg = _load() or {}
    ids = cfg.get("script_ids") or ([cfg["script_id"]] if cfg.get("script_id") else [])
    return len(ids)


def ports() -> str:
    cfg = _load() or {}
    return json.dumps({"http": cfg.get("http_port", HTTP_PORT),
                       "socks": cfg.get("socks5_port", SOCKS_PORT)})


def auth_key() -> str:
    """The secret already shared with the user's relay(s); "" when not configured yet."""
    return str((_load() or {}).get("auth_key", ""))


def code_in_text(text: str) -> bool:
    """True when pasted/clipboard text contains a connection code."""
    return connect_code.find_in_text(text or "") is not None


def import_code(text: str) -> str:
    """Save a ready-made connection code.  Returns {"ok":bool,"error":str,"note":str}."""
    try:
        data = connect_code.decode(text)
    except connect_code.CodeError as exc:
        return json.dumps({"ok": False, "error": str(exc).rstrip(".")}, ensure_ascii=False)
    _save(connect_code.build_config(data, HTTP_PORT, SOCKS_PORT))
    return json.dumps({"ok": True, "note": data.get("note", ""), "name": data.get("name", "")},
                      ensure_ascii=False)


# ───────────────────────── Apps Script relay helpers ─────────────────────────
def extract_deployment_id(text: str) -> str:
    """Bare Deployment ID or the whole .../macros/s/<ID>/exec URL.  "" when invalid."""
    t = (text or "").strip().strip("\"'")
    m = _ID_URL.search(t)
    if m:
        return m.group(1)
    return t if _ID_RAW.match(t) else ""


def pending_key() -> str:
    """Survives a half-finished setup so the pasted script and the app always agree."""
    p = os.path.join(_data_dir, _KEY_FILE)
    try:
        with open(p, encoding="utf-8") as f:
            k = f.read().strip()
        if k:
            return k
    except OSError:
        pass
    k = secrets.token_urlsafe(18)
    with open(p, "w", encoding="utf-8") as f:
        f.write(k)
    return k


def clear_pending_key() -> None:
    try:
        os.remove(os.path.join(_data_dir, _KEY_FILE))
    except OSError:
        pass


def apply_auth_key(template: str, auth_key: str) -> str:
    """Put the user's private key into the bundled Code.gs text."""
    out, n = re.subn(r'const AUTH_KEY = "[^"]*";', lambda _m: f'const AUTH_KEY = "{auth_key}";',
                     template, count=1)
    if n != 1:
        raise RuntimeError("AUTH_KEY line not found in Code.gs")
    return out


def save_deployment(dep_text: str, add: bool) -> str:
    """Finish the wizard.  add=True appends another relay to the existing config."""
    dep = extract_deployment_id(dep_text)
    if not dep:
        return json.dumps({"ok": False,
                           "error": "این مقدار شبیه شناسه نیست و باید یک رشته‌ی طولانی باشد"},
                          ensure_ascii=False)
    cfg = _load()
    if add and cfg:
        ids = list(cfg.get("script_ids") or [cfg["script_id"]])
        if dep not in ids:
            ids.append(dep)
        cfg.pop("script_id", None)
        cfg["script_ids"] = ids
    else:
        cfg = connect_code.build_config({"ids": [dep], "key": pending_key()}, HTTP_PORT, SOCKS_PORT)
    _save(cfg)
    clear_pending_key()
    return json.dumps({"ok": True})


# ───────────────────────── MITM CA ─────────────────────────
def ensure_ca() -> str:
    """Create the CA on first use (RSA keygen, a few seconds on a phone). Returns ca.crt path."""
    from proxy.mitm import CA_CERT_FILE, MITMCertManager
    if not os.path.exists(CA_CERT_FILE):
        MITMCertManager()
    return CA_CERT_FILE


# ───────────────────────── engine ─────────────────────────
def start() -> str:
    """Start the HTTP + SOCKS5 proxy on loopback.  Returns "" on success, else a Persian error."""
    global _engine
    with _lock:
        if _engine is not None and _engine.running:
            return ""
        cfg = _load()
        if cfg is None:
            return "تنظیمات پیدا نشد"
        try:
            ensure_ca()
            eng = Engine(cfg)
            eng.start()
            # Engine.start() returns once the HTTP port is up; wait for SOCKS5 too.
            deadline = time.time() + 10
            while time.time() < deadline:
                if _port_open("127.0.0.1", cfg.get("socks5_port", SOCKS_PORT)):
                    break
                time.sleep(0.1)
            _engine = eng
            return ""
        except EngineError as exc:
            return str(exc)
        except Exception as exc:  # noqa: BLE001
            logging.getLogger("Bridge").exception("start failed")
            return f"{type(exc).__name__}: {exc}"


def stop() -> None:
    global _engine
    with _lock:
        eng, _engine = _engine, None
    if eng is not None:
        try:
            eng.stop()
        except Exception:  # noqa: BLE001
            logging.getLogger("Bridge").exception("stop failed")


def is_running() -> bool:
    eng = _engine
    return bool(eng and eng.running)


def _port_open(host: str, port: int) -> bool:
    try:
        with socket.create_connection((host, port), timeout=0.5):
            return True
    except OSError:
        return False


def probe() -> bool:
    """Real end-to-end check through the local proxy (same URL the desktop app uses)."""
    port = (_load() or {}).get("http_port", HTTP_PORT)
    try:
        opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({"http": f"http://127.0.0.1:{port}"}))
        with opener.open("http://connectivitycheck.gstatic.com/generate_204", timeout=30) as r:
            return r.status in (200, 204)
    except Exception as exc:  # noqa: BLE001
        logging.getLogger("Bridge").warning("probe failed: %s", exc)
        return False


# ───────────────────────── misc ─────────────────────────
def get_log() -> str:
    return "\n".join(_log_buf)


def reset_config() -> None:
    """Re-run the wizard: drop config and pending key, keep the CA the user already installed."""
    stop()
    for name in (_CFG_FILE, _KEY_FILE):
        try:
            os.remove(os.path.join(_data_dir, name))
        except OSError:
            pass


def reset_all() -> None:
    """Forget config, key and CA (the Kotlin side removes the CA from Android's own store)."""
    stop()
    shutil.rmtree(os.path.join(_data_dir, "ca"), ignore_errors=True)
    for name in (_CFG_FILE, _KEY_FILE):
        try:
            os.remove(os.path.join(_data_dir, name))
        except OSError:
            pass
