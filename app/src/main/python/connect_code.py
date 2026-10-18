"""Connection code: one pasteable string that carries everything a user needs.

Format:  MHR1-<base64url(json)>
JSON:    {"v":1, "name":"...", "ids":["<deployment id>", ...], "key":"<auth key>",
          "ip":"<optional google ip>", "note":"<optional message shown to user>"}
"""
import base64
import json
import re

PREFIX = "MHR1-"
_RE = re.compile(r"MHR1-[A-Za-z0-9_\-]+={0,2}")


class CodeError(ValueError):
    pass


def encode(ids, key, name="", ip="", note="") -> str:
    if isinstance(ids, str):
        ids = [ids]
    payload = {"v": 1, "ids": [i.strip() for i in ids if i.strip()], "key": key.strip()}
    if name:
        payload["name"] = name
    if ip:
        payload["ip"] = ip
    if note:
        payload["note"] = note
    raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return PREFIX + base64.urlsafe_b64encode(raw).decode().rstrip("=")


def find_in_text(text: str) -> str | None:
    """Pull a code out of a pasted chat message (extra words/newlines are fine)."""
    m = _RE.search((text or "").replace("\u200c", "").replace("\u200f", ""))
    return m.group(0) if m else None


def decode(text: str) -> dict:
    code = find_in_text(text)
    if not code:
        raise CodeError("کد اتصال معتبر پیدا نشد.")
    body = code[len(PREFIX):]
    body += "=" * (-len(body) % 4)
    try:
        data = json.loads(base64.urlsafe_b64decode(body).decode("utf-8"))
    except Exception as exc:
        raise CodeError("کد اتصال خراب یا ناقص است (کامل کپی نشده؟).") from exc
    if not isinstance(data, dict) or data.get("v") != 1:
        raise CodeError("نسخه‌ی کد اتصال پشتیبانی نمی‌شود؛ برنامه را به‌روز کنید.")
    if not data.get("ids") or not data.get("key"):
        raise CodeError("کد اتصال ناقص است.")
    return data


def build_config(data: dict, http_port: int = 8085, socks_port: int = 1080) -> dict:
    """Turn a decoded code into the full config the proxy engine expects."""
    cfg = {
        "google_ip": data.get("ip") or "216.239.38.120",
        "front_domain": "www.google.com",
        "front_domains": ["www.google.com", "mail.google.com", "accounts.google.com"],
        "auth_key": data["key"],
        "listen_host": "127.0.0.1",
        "http_port": http_port,
        "socks5_port": socks_port,
        "verify_ssl": True,
        "lan_sharing": False,
        "relay_timeout": 25,
        "tls_connect_timeout": 15,
        "tcp_connect_timeout": 10,
        "parallel_relay": 1,
        "h2_connections": 2,
        "enable_sub_batch": True,
        # Iranian sites keep working natively (and faster) without the relay.
        "direct_hosts": [".ir"],
        "block_hosts": [],
        "youtube_via_relay": False,
        "exit_node": {"enabled": False},
        "log_level": "INFO",
    }
    ids = data["ids"]
    if len(ids) == 1:
        cfg["script_id"] = ids[0]
    else:
        cfg["script_ids"] = ids
    return cfg
