"""Persian desktop UI.

First run: step-by-step wizard that helps the user create *their own* Google
Apps Script relay (own account -> own quota).  Afterwards: one big connect button.

UI rules (Tk lays out mixed Persian/English lines badly):
  * Persian sentences are right-aligned and carry no trailing punctuation;
  * English names (buttons in Google's page) are shown as separate "chips";
  * no pop-ups for normal feedback, messages appear inline.
"""
import atexit
import collections
import json
import logging
import logging.handlers
import os
import shutil
import sys
import threading
import time
import tkinter as tk
import urllib.request
import webbrowser
from tkinter import messagebox, scrolledtext

from . import connect_code, paths, relay_script, sysproxy
from .engine import Engine, EngineError
from .wizard_steps import OPEN_URL, PRIVACY_IMPORTED, STEPS, TG_STEPS, TG_TIP, TROUBLE

APP_TITLE = "دیبان"
VERSION = "0.5.0"
AUTHOR = "مهدی بانشی"
BALE_URL = "https://ble.ir/moinkarami"
TG_URL = "https://t.me/MehdiBaneshiOfficial"
HTTP_PORT, SOCKS_PORT = 8085, 1080
FONT = "Tahoma" if sys.platform == "win32" else "DejaVu Sans"
MONO = "Consolas" if sys.platform == "win32" else "DejaVu Sans Mono"

# Light "clay" palette: white base, one blue accent
BG, FG, MUTED, CARD = "#f4f7fc", "#0f172a", "#64748b", "#ffffff"
GRAY, SLATE, SHADOW = "#cbd5e1", "#dfe7f5", "#d3dbeb"
AMBER, GREEN, RED, BLUE = "#d97706", "#16a34a", "#dc2626", "#2563eb"
CHIP_BG, CHIP_FG = "#e6efff", "#1d4ed8"

WIN_W, WIN_H, PAD = 440, 720, 22
WRAP = WIN_W - 2 * PAD          # full-width text
WRAP_ROW = WRAP - 40            # text next to a number badge

_FA = str.maketrans("0123456789", "۰۱۲۳۴۵۶۷۸۹")


def fa(n) -> str:
    return str(n).translate(_FA)


def _setup_logging() -> collections.deque:
    buf: collections.deque = collections.deque(maxlen=500)

    class _Mem(logging.Handler):
        def emit(self, record):
            buf.append(self.format(record))

    fmt = logging.Formatter("%(asctime)s %(levelname)s [%(name)s] %(message)s", "%H:%M:%S")
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    for h in (
        _Mem(),
        logging.handlers.RotatingFileHandler(
            paths.path("app.log"), maxBytes=512_000, backupCount=2, encoding="utf-8"),
    ):
        h.setFormatter(fmt)
        root.addHandler(h)
    return buf


def _cfg_path() -> str:
    return paths.path("config.json")


def _load_cfg() -> dict | None:
    try:
        return json.load(open(_cfg_path(), encoding="utf-8"))
    except (OSError, ValueError):
        return None


def _save_cfg(cfg: dict) -> None:
    with open(_cfg_path(), "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    if os.name == "posix":
        os.chmod(_cfg_path(), 0o600)


def _shade(hexc: str, f: float) -> str:
    """f < 1 darker, f > 1 lighter."""
    r, g, b = (int(hexc[i:i + 2], 16) for i in (1, 3, 5))
    if f < 1:
        r, g, b = (int(v * f) for v in (r, g, b))
    else:
        r, g, b = (int(v + (255 - v) * (f - 1)) for v in (r, g, b))
    return "#%02x%02x%02x" % (r, g, b)


def _rrect(c, x1, y1, x2, y2, r, fill, tags=()):
    """Exact rounded rectangle (4 circles + 2 rectangles); r = h/2 gives a pill."""
    r = min(r, (x2 - x1) / 2, (y2 - y1) / 2)
    kw = dict(fill=fill, outline="", tags=tags)
    for ox in (x1, x2 - 2 * r):
        for oy in (y1, y2 - 2 * r):
            c.create_oval(ox, oy, ox + 2 * r, oy + 2 * r, **kw)
    c.create_rectangle(x1 + r, y1, x2 - r, y2, **kw)
    c.create_rectangle(x1, y1 + r, x2, y2 - r, **kw)


class Clay(tk.Canvas):
    """Soft 3D ("clay") button: raised body, darker lip below, small highlight."""

    def __init__(self, parent, text, cmd, color, font, width=150, height=50):
        super().__init__(parent, width=width, height=height, bg=parent["bg"],
                         highlightthickness=0, bd=0, cursor="hand2")
        self._text, self._cmd, self._color, self._font = text, cmd, color, font
        self._enabled, self._down = True, False
        self.bind("<Configure>", lambda e: self._draw())
        self.bind("<ButtonPress-1>", self._press)
        self.bind("<ButtonRelease-1>", self._release)

    def _press(self, _e):
        if self._enabled:
            self._down = True
            self._draw()

    def _release(self, e):
        was, self._down = self._down, False
        self._draw()
        if was and 0 <= e.x <= self.winfo_width() and 0 <= e.y <= self.winfo_height():
            self._cmd()

    def _draw(self):
        w = max(self.winfo_width(), 40) if self.winfo_width() > 1 else int(self.cget("width"))
        h = int(self.cget("height"))
        self.delete("all")
        body = self._color if self._enabled else "#e6ebf4"
        r = (h - 8) // 2
        top = 5 if self._down else 2
        if not self._down:
            _rrect(self, 3, top + 5, w - 3, h - 1, r, fill=_shade(body, 0.78))
        _rrect(self, 3, top, w - 3, h - 6 + (3 if self._down else 0), r, fill=body)
        mid = (top + h - 6 + (3 if self._down else 0)) / 2
        if self._enabled and not self._down:
            self.create_line(r + 6, top + 4, w - r - 6, top + 4, fill=_shade(body, 1.3),
                             width=2, capstyle="round")
        lum = sum(int(body[i:i + 2], 16) * k for i, k in ((1, .3), (3, .59), (5, .11)))
        fg = "#94a3b8" if not self._enabled else ("#1e293b" if lum > 190 else "white")
        self.create_text(w / 2, mid, text=self._text, fill=fg, font=self._font)

    def configure(self, cnf=None, **kw):
        for k in ("text", "bg", "activebackground", "state"):
            if k in kw:
                v = kw.pop(k)
                if k == "text":
                    self._text = v
                elif k == "bg":
                    self._color = v
                elif k == "state":
                    self._enabled = v == "normal"
        if kw or cnf:
            super().configure(cnf, **kw)
        self._draw()

    config = configure

    def __getitem__(self, key):
        if key == "state":
            return "normal" if self._enabled else "disabled"
        return super().__getitem__(key)


class App:
    def __init__(self):
        self.log_buf = _setup_logging()
        self.log = logging.getLogger("App")
        self.engine: Engine | None = None
        self.state = "idle"  # idle | connecting | connected
        self.busy = False
        self.step = 0
        self.wizard_mode = "new"  # new | add
        self.action_btns: dict = {}
        self._spin_job = None
        self._spin_angle = 0

        self.root = tk.Tk()
        self.root.title(f"Diban {VERSION}")
        x = max((self.root.winfo_screenwidth() - WIN_W) // 2, 0)
        y = max((self.root.winfo_screenheight() - WIN_H) // 3, 0)
        self.root.geometry(f"{WIN_W}x{WIN_H}+{x}+{y}")
        self.root.configure(bg=BG)
        self.root.resizable(False, False)
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        self.root.bind("<Return>", self._on_enter)
        self.root.bind("<FocusIn>", lambda e: self._auto_paste() if e.widget is self.root else None)
        atexit.register(self._cleanup)

        if sysproxy.restore_if_stale():
            self.log.warning("Restored proxy settings left over from a previous crash")

        self._build()
        self._refresh()
        # Importing cryptography / the proxy takes a second or two: do it now,
        # while the user reads the screen, so "connect" feels instant.
        threading.Thread(target=self._prewarm, daemon=True).start()

    def _prewarm(self):
        try:
            from core import cert_installer  # noqa: F401
            from proxy import mitm, proxy_server  # noqa: F401
        except Exception:  # noqa: BLE001
            self.log.exception("prewarm failed")

    # ───────────── small widget helpers ─────────────
    def _label(self, parent, text="", size=11, color=FG, bold=False, wrap=0, center=False, bg=BG, **kw):
        """Persian text: right-aligned unless center=True."""
        return tk.Label(parent, text=text, bg=bg, fg=color, wraplength=wrap,
                        justify="center" if center else "right",
                        anchor="center" if center else "e",
                        font=(FONT, size, "bold" if bold else "normal"), **kw)

    def _btn(self, parent, text, cmd, color=BLUE, width=150, height=50):
        return Clay(parent, text, cmd, color, (FONT, 11, "bold"), width=width, height=height)

    def _link(self, parent, text, cmd, size=9, color=MUTED, bg=BG):
        return tk.Button(parent, text=text, command=cmd, bg=bg, fg=color, bd=0,
                         relief="flat", activebackground=bg, activeforeground=FG,
                         cursor="hand2", highlightthickness=0, font=(FONT, size, "underline"))

    def _card(self, parent, padx=PAD, pady=(0, 0)):
        """White raised card with a soft lip; returns the inner frame to fill."""
        lip = tk.Frame(parent, bg=SHADOW)
        lip.pack(fill="x", padx=padx, pady=pady)
        card = tk.Frame(lip, bg=CARD)
        card.pack(fill="x", pady=(0, 4))
        inner = tk.Frame(card, bg=CARD)
        inner.pack(fill="x", padx=14, pady=12)
        return inner

    def _style(self, btn, text=None, color=None, enabled=True):
        if text is not None:
            btn.config(text=text)
        if color is not None:
            btn.config(bg=color, activebackground=color)
        btn.config(state="normal" if enabled else "disabled",
                   cursor="hand2" if enabled else "arrow")

    def _chips(self, parent, chips, bg=BG):
        """English names, left-to-right, as little tags."""
        f = tk.Frame(parent, bg=bg)
        for i, c in enumerate(chips):
            if i:
                tk.Label(f, text="→", bg=bg, fg=MUTED, font=(MONO, 10)).pack(side="left", padx=4)
            tk.Label(f, text=c, bg=CHIP_BG, fg=CHIP_FG, font=(MONO, 9, "bold"),
                     padx=7, pady=2).pack(side="left")
        return f

    def _rows(self, parent, items, numbered=True, bg=BG, wrap=WRAP_ROW, pad=5):
        """Numbered list: badge on the right, Persian sentence, optional chips below."""
        for i, (text, chips) in enumerate(items, 1):
            row = tk.Frame(parent, bg=bg)
            row.pack(fill="x", pady=pad)
            badge = tk.Canvas(row, width=26, height=28, bg=bg, highlightthickness=0)
            badge.pack(side="right", anchor="n", padx=(10, 0))
            if numbered:
                badge.create_oval(1, 3, 25, 27, fill=_shade(BLUE, 0.78), outline="")
                badge.create_oval(1, 1, 25, 25, fill=BLUE, outline="")
                badge.create_text(13, 13, text=fa(i), fill="white", font=(FONT, 10, "bold"))
            else:
                badge.create_oval(9, 9, 17, 17, fill=GRAY, outline="")
            body = tk.Frame(row, bg=bg)
            body.pack(side="right", fill="x", expand=True)
            self._label(body, text, 11, wrap=wrap, bg=bg).pack(fill="x", pady=(2, 0))
            if chips:
                self._chips(body, chips, bg).pack(anchor="e", pady=(4, 0))

    def _draw_logo(self, c, size=44):
        k = size / 44
        _rrect(c, 2 * k, 6 * k, 42 * k, 46 * k, 13 * k, fill=_shade(BLUE, 0.7))
        _rrect(c, 2 * k, 2 * k, 42 * k, 42 * k, 13 * k, fill=BLUE)
        pts = [22, 9, 33, 13.5, 33, 23, 22, 34, 11, 23, 11, 13.5]
        c.create_polygon([v * k for v in pts], fill="white", outline="")
        c.create_oval(18.5 * k, 17 * k, 25.5 * k, 24 * k, fill=BLUE, outline="")

    # ───────────── layout ─────────────
    def _build(self):
        r = self.root
        head = tk.Frame(r, bg=BG)
        head.pack(pady=(16, 0))
        logo = tk.Canvas(head, width=44, height=50, bg=BG, highlightthickness=0)
        logo.pack(side="right", padx=(12, 0))
        self._draw_logo(logo)
        tf = tk.Frame(head, bg=BG)
        tf.pack(side="right")
        self._label(tf, APP_TITLE, 20, bold=True).pack(anchor="e")
        tk.Label(tf, text="DIBAN", bg=BG, fg=BLUE, font=(FONT, 8, "bold")).pack(anchor="e")
        self.sub = self._label(r, "", 10, MUTED, wrap=WRAP, center=True)
        self.sub.pack(pady=(6, 4), fill="x")

        # bottom area first so it always stays visible: credit, then the menu above it
        self.credit = tk.Frame(r, bg=BG)
        self.credit.pack(side="bottom", pady=(0, 10))
        self._label(self.credit, f"ساخته شده توسط {AUTHOR}", 9, MUTED, center=True).pack()
        links = tk.Frame(self.credit, bg=BG)
        links.pack()
        self._link(links, "کانال بله", lambda: webbrowser.open(BALE_URL), color=BLUE).pack(side="right", padx=8)
        self._link(links, "کانال تلگرام", lambda: webbrowser.open(TG_URL), color=BLUE).pack(side="right", padx=8)
        self.foot = tk.Frame(r, bg=BG)
        self.foot.pack(side="bottom", pady=6)

        # ── wizard ──
        self.wiz = tk.Frame(r, bg=BG)
        self.w_nav = tk.Frame(self.wiz, bg=BG)  # packed first: always visible at the bottom
        self.w_nav.pack(side="bottom", fill="x", padx=PAD, pady=(8, 10))
        # RTL: "next" on the left, "back" on the right
        self.w_back = self._link(self.w_nav, "قبلی", self.prev_step, 11)
        self.w_next = self._btn(self.w_nav, "بعدی", self.next_step)

        self.dots = tk.Canvas(self.wiz, width=WRAP, height=14, bg=BG, highlightthickness=0)
        self.dots.pack(padx=PAD, pady=(6, 0))
        self.w_count = self._label(self.wiz, "", 9, MUTED, wrap=WRAP)
        self.w_count.pack(fill="x", padx=PAD, pady=(8, 0))
        self.w_title = self._label(self.wiz, "", 16, bold=True, wrap=WRAP)
        self.w_title.pack(fill="x", padx=PAD, pady=(0, 8))
        self.w_content = self._card(self.wiz)
        self.w_entry = tk.Text(self.wiz, height=3, font=(MONO, 10), bg=CARD, fg=FG,
                               insertbackground=FG, relief="flat", wrap="char", padx=8, pady=6,
                               highlightthickness=1, highlightbackground=SHADOW, highlightcolor=BLUE)
        self.w_entry.bind("<<Paste>>", lambda e: self.root.after_idle(self._check_entry))
        self.w_entry.bind("<KeyRelease>", self._check_entry)
        self.w_entry.bind("<Return>", lambda e: (self._on_enter(), "break")[1])
        self.w_msg = self._label(self.wiz, "", 10, MUTED, wrap=WRAP)

        # ── main ──
        self.main = tk.Frame(r, bg=BG)
        self.canvas = tk.Canvas(self.main, width=220, height=130, bg=BG, highlightthickness=0,
                                cursor="hand2")
        self.canvas.pack(pady=(14, 4))
        self.canvas.bind("<Button-1>", lambda e: self.toggle())
        self._tp = 0.0  # toggle knob position 0..1
        self.state_lbl = self._label(self.main, "", 22, bold=True, center=True)
        self.state_lbl.pack(fill="x", padx=PAD)
        self.hint = self._label(self.main, "", 10, MUTED, wrap=WRAP, center=True)
        self.hint.pack(fill="x", padx=PAD, pady=(2, 0))
        self.uptime = self._label(self.main, "", 12, BLUE, center=True)
        self.uptime.pack(fill="x", padx=PAD, pady=(4, 0))
        self.status_lip = None
        st = self._card(self.main, padx=PAD + 6, pady=(14, 0))
        self.status_lip = st.master.master
        self.status = tk.Label(st, text="", bg=CARD, fg=FG, wraplength=WRAP - 60,
                               justify="center", font=(FONT, 10))
        self.status.pack(fill="x")
        self.status_lip.pack_forget()
        tg = self._card(self.main, padx=PAD + 6, pady=(14, 0))
        self.tg_lip = tg.master.master
        self._label(tg, "تلگرام", 12, bold=True, bg=CARD).pack(fill="x")
        self._label(tg, "با یک کلیک پروکسی را به تلگرام اضافه کنید", 10, MUTED,
                    wrap=WRAP - 60, bg=CARD).pack(fill="x", pady=(2, 10))
        self._btn(tg, "افزودن پروکسی به تلگرام", self.add_telegram, BLUE, width=300,
                  height=46).pack(fill="x")
        self._link(tg, "راهنمای دستی", self.show_tg, 9, BLUE, CARD).pack(pady=(8, 0))
        self.help = tk.Frame(r, bg=BG)  # full-page help, shown on demand
        self.help_open = False
        self._t0 = None

        # ── loading overlay (shown while connecting) ──
        self._ov_shown, self._ov_angle, self._ov_t0 = False, 0, 0.0
        self.ov = tk.Frame(r, bg=BG)
        cred = tk.Frame(self.ov, bg=BG)
        cred.pack(side="bottom", pady=(0, 36))
        self._label(cred, "ساخته شده توسط", 10, MUTED, center=True).pack()
        self._label(cred, AUTHOR, 20, BLUE, bold=True, center=True).pack(pady=(0, 14))
        row = tk.Frame(cred, bg=BG)
        row.pack()
        self._btn(row, "کانال بله", lambda: webbrowser.open(BALE_URL), SLATE,
                  width=150, height=44).pack(side="right", padx=6)
        self._btn(row, "کانال تلگرام", lambda: webbrowser.open(TG_URL), SLATE,
                  width=150, height=44).pack(side="right", padx=6)
        self.ov_cv = tk.Canvas(self.ov, width=150, height=150, bg=BG, highlightthickness=0)
        self.ov_cv.pack(pady=(130, 14))
        self.ov_cv.create_oval(15, 15, 135, 135, outline=SHADOW, width=12)
        self.ov_arc = self.ov_cv.create_arc(15, 15, 135, 135, start=0, extent=100, style="arc",
                                            width=12, outline=BLUE)
        self.ov_cv.create_polygon(75, 48, 97, 57, 97, 77, 75, 102, 53, 77, 53, 57,
                                  fill=BLUE, outline="")
        self.ov_cv.create_oval(70, 66, 80, 76, fill="white", outline="")
        self._label(self.ov, "در حال اتصال", 18, bold=True, center=True).pack(fill="x")
        self.ov_status = self._label(self.ov, "", 10, MUTED, wrap=WRAP - 40, center=True)
        self.ov_status.pack(fill="x", padx=PAD + 20, pady=(8, 0))

    def _set_footer(self, items):
        for w in self.foot.winfo_children():
            w.destroy()
        for text, cmd in items:
            self._link(self.foot, text, cmd).pack(side="right", padx=7)

    def _set_footer_menu(self):
        """One quiet 'more' menu instead of four scary links."""
        for w in self.foot.winfo_children():
            w.destroy()
        mb = tk.Menubutton(self.foot, text="بیشتر", bg=BG, fg=MUTED, activebackground=BG,
                           activeforeground=FG, bd=0, cursor="hand2", highlightthickness=0,
                           font=(FONT, 10))
        m = tk.Menu(mb, tearoff=0, font=(FONT, 10))
        m.add_command(label="اگر وصل نمی‌شود", command=self.show_help)
        m.add_command(label="راهنمای تنظیم از اول", command=self.rerun_wizard)
        m.add_command(label="افزودن حساب گوگل دیگر برای سهمیه‌ی بیشتر", command=self.add_server)
        m.add_command(label="کپی کد اتصال برای گوشی", command=self.copy_phone_code)
        m.add_command(label="گزارش فنی", command=self.show_log)
        m.add_separator()
        m.add_command(label="حذف کامل برنامه از سیستم", command=self.uninstall)
        mb.config(menu=m)
        mb.pack()

    # ───────────── screen switching ─────────────
    def _refresh(self):
        self.wiz.pack_forget()
        self.main.pack_forget()
        self.hide_help(restore=False)
        if _load_cfg() is None:
            self.wizard_mode = "new"
            self.step = 0
            self._show_wizard()
            self._set_footer([("کد اتصال آماده دارم", self.import_code)])
            return
        self.main.pack(fill="both", expand=True)
        self._paint()
        self._set_footer_menu()

    # ───────────── wizard ─────────────
    def _draw_dots(self):
        c = self.dots
        c.delete("all")
        if not self.step:
            return
        x = WRAP - 6
        for n in range(1, len(STEPS)):
            w = 26 if n == self.step else 10
            c.create_line(x - w + 4, 7, x - 4, 7, width=8, capstyle="round",
                          fill=BLUE if n <= self.step else GRAY)
            x -= w + 6

    def _show_wizard(self):
        self.wiz.pack(fill="both", expand=True)
        s = STEPS[self.step]
        last = self.step == len(STEPS) - 1
        self.sub.config(text="" if self.step else "فقط یک بار لازم است")
        self.w_count.config(text=f"مرحله {fa(self.step)} از {fa(len(STEPS) - 1)}" if self.step else "")
        self.w_title.config(text=s["title"])
        self._draw_dots()

        for w in self.w_content.winfo_children():
            w.destroy()
        if self.wizard_mode == "add" and self.step == 1:
            self._label(self.w_content,
                        "برای سهمیه‌ی بیشتر همین مراحل را با یک حساب گوگل دیگر انجام دهید و رمز همان رمز فعلی است",
                        10, AMBER, wrap=WRAP - 28, bg=CARD).pack(fill="x", pady=(0, 6))
        self._rows(self.w_content, s["items"], numbered=bool(self.step), bg=CARD, wrap=WRAP_ROW - 28)
        if s["tip"]:
            self._label(self.w_content, s["tip"], 9, MUTED, wrap=WRAP - 28, bg=CARD).pack(fill="x", pady=(8, 0))
        self.action_btns = {}
        for label, method in s["actions"]:
            b = self._btn(self.w_content, label, getattr(self, method), BLUE)
            b.pack(fill="x", pady=(16, 0), ipady=4)
            self.action_btns[method] = b

        self.w_entry.delete("1.0", "end")  # never keep a stale ID from an earlier run
        self.w_entry.pack_forget()
        self.w_msg.pack_forget()
        self.w_msg.config(text="", fg=MUTED)
        if s["entry"]:
            self.w_entry.pack(fill="x", padx=PAD, pady=(12, 4))
            self.w_msg.pack(fill="x", padx=PAD)

        first = 1 if self.wizard_mode == "add" else 0
        self.w_back.pack_forget()
        self.w_next.pack_forget()
        self.w_next.pack(side="left")
        if self.step > first:
            self.w_back.pack(side="right")
        label = "ذخیره و اتصال" if last else ("شروع" if self.step == 0 else "بعدی")
        # Steps with an action button: "next" stays quiet until the action is done.
        self._style(self.w_next, label, SLATE if s["actions"] else BLUE, enabled=not last)
        if last:
            self.root.after(100, self._auto_paste)

    def next_step(self):
        if self.step == len(STEPS) - 1:
            return self.finish_wizard()
        self.step += 1
        self._show_wizard()

    def prev_step(self):
        self.step -= 1
        self._show_wizard()

    def _on_enter(self, _e=None):
        if self.wiz.winfo_ismapped() and str(self.w_next["state"]) == "normal":
            self.next_step()

    def _done(self, method, text):
        """Mark an action button as done and let the user move on."""
        b = self.action_btns.get(method)
        if b:
            self._style(b, text, GREEN)
        self._style(self.w_next, color=BLUE)

    def open_script(self):
        webbrowser.open(OPEN_URL)
        self._done("open_script", "صفحه باز شد ✓")

    def _key(self) -> str:
        if self.wizard_mode == "add":
            cfg = _load_cfg()
            if cfg:
                return cfg["auth_key"]
        return relay_script.pending_key()

    def copy_script(self):
        try:
            code = relay_script.code_gs(self._key())
        except Exception as exc:  # noqa: BLE001
            self.log.exception("code_gs failed")
            self.w_msg.pack(fill="x", padx=PAD)
            self.w_msg.config(text=f"خواندن کد آماده ناموفق بود\n{exc}", fg=RED)
            return
        self.root.clipboard_clear()
        self.root.clipboard_append(code)
        self.root.update()
        self._done("copy_script", "کد کپی شد ✓")

    def _entry_text(self) -> str:
        return self.w_entry.get("1.0", "end").strip()

    def _check_entry(self, _e=None):
        """Live validation: the Save button only lights up for a valid ID."""
        if self.step != len(STEPS) - 1:
            return
        txt = self._entry_text()
        if relay_script.extract_deployment_id(txt):
            self.w_msg.config(text="شناسه معتبر است ✓", fg=GREEN)
            self._style(self.w_next, color=GREEN, enabled=True)
        else:
            self.w_msg.config(text="این مقدار شبیه شناسه نیست و باید یک رشته‌ی طولانی باشد" if txt else "",
                              fg=AMBER)
            self._style(self.w_next, color=SLATE, enabled=False)

    def paste_id(self):
        try:
            txt = self.root.clipboard_get()
        except tk.TclError:
            txt = ""
        if not txt.strip():
            self.w_msg.config(text="کلیپ‌بورد خالی است و ابتدا شناسه را در گوگل کپی کنید", fg=AMBER)
            return
        self.w_entry.delete("1.0", "end")
        self.w_entry.insert("1.0", txt.strip())
        self._check_entry()

    def _auto_paste(self):
        """When the user comes back from the browser, fill the ID by itself."""
        if not self.wiz.winfo_ismapped() or self.step != len(STEPS) - 1 or self._entry_text():
            return
        try:
            txt = self.root.clipboard_get().strip()
        except tk.TclError:
            return
        # Only auto-fill things that clearly look like a deployment ID / web-app link.
        if (txt.startswith("AKfycb") or "/macros/s/" in txt) and relay_script.extract_deployment_id(txt):
            self.w_entry.insert("1.0", txt)
            self._check_entry()

    def finish_wizard(self):
        dep = relay_script.extract_deployment_id(self._entry_text())
        if not dep:
            self.w_msg.config(text="این مقدار شبیه شناسه نیست و باید یک رشته‌ی طولانی باشد", fg=RED)
            return
        key = self._key()
        cfg = _load_cfg()
        if self.wizard_mode == "add" and cfg:
            ids = cfg.get("script_ids") or [cfg["script_id"]]
            if dep not in ids:
                ids.append(dep)
            cfg.pop("script_id", None)
            cfg["script_ids"] = ids
        else:
            cfg = connect_code.build_config({"ids": [dep], "key": key}, HTTP_PORT, SOCKS_PORT)
        _save_cfg(cfg)
        relay_script.clear_pending_key()
        self.state = "idle"
        self._refresh()
        self._status_cfg(text="تنظیم کامل شد و در حال اتصال", fg=GREEN)
        self.root.after(400, self.toggle)  # connect right away: one click less

    def _busy_note(self) -> bool:
        if self.state != "idle":
            self._status_cfg(text="ابتدا اتصال را قطع کنید", fg=AMBER)
            return True
        return False

    def rerun_wizard(self):
        if self._busy_note():
            return
        if not messagebox.askyesno(APP_TITLE, "مراحل تنظیم از اول انجام شود؟\n(تنظیمات فعلی جایگزین می‌شود)"):
            return
        try:
            os.remove(_cfg_path())
        except OSError:
            pass
        relay_script.clear_pending_key()
        self._refresh()

    def add_server(self):
        """Extra Apps Script on another Google account = extra daily quota."""
        if self._busy_note():
            return
        self.wizard_mode = "add"
        self.step = 1
        self.main.pack_forget()
        self.hide_help(restore=False)
        self._show_wizard()
        self._set_footer([("انصراف", self._refresh)])

    def import_code(self):
        w = tk.Toplevel(self.root)
        w.title("کد اتصال آماده")
        w.configure(bg=BG)
        w.resizable(False, False)
        w.transient(self.root)
        w.geometry(f"+{self.root.winfo_x() + 30}+{self.root.winfo_y() + 120}")
        self._label(w, "کدی که شخص مورد اعتماد برایتان فرستاده را اینجا بچسبانید",
                    11, wrap=380).pack(fill="x", padx=20, pady=(16, 8))
        t = tk.Text(w, height=5, width=46, font=(MONO, 9), bg=CARD, fg=FG,
                    insertbackground=FG, relief="flat", wrap="char", padx=8, pady=6,
                    highlightthickness=1, highlightbackground=SHADOW, highlightcolor=BLUE)
        t.pack(padx=20)
        try:
            clip = self.root.clipboard_get()
            if connect_code.find_in_text(clip):
                t.insert("1.0", clip)
        except tk.TclError:
            pass
        self._label(w, PRIVACY_IMPORTED, 9, AMBER, wrap=380).pack(fill="x", padx=20, pady=(10, 0))
        msg = self._label(w, "", 10, RED, wrap=380)
        msg.pack(fill="x", padx=20, pady=(6, 0))

        def ok():
            try:
                data = connect_code.decode(t.get("1.0", "end"))
            except connect_code.CodeError as exc:
                msg.config(text=str(exc).rstrip("."))
                return
            _save_cfg(connect_code.build_config(data, HTTP_PORT, SOCKS_PORT))
            w.destroy()
            self._refresh()

        self._btn(w, "متوجه شدم و ثبت کد", ok).pack(pady=14)

    # ───────────── main screen ─────────────
    def _draw_toggle(self, p):
        c = self.canvas
        c.delete("t")
        base = {"idle": "#dbe3f1", "connecting": "#86adf6", "connected": BLUE}[self.state]
        _rrect(c, 20, 32, 200, 104, 36, fill=_shade(base, 0.8), tags="t")   # lip
        _rrect(c, 20, 26, 200, 98, 36, fill=base, tags="t")                  # track
        c.create_line(56, 35, 164, 35, fill=_shade(base, 1.35), width=3, capstyle="round", tags="t")
        x, cy = 56 + 108 * p, 62
        c.create_oval(x - 29, cy - 23, x + 29, cy + 35, fill=_shade(base, 0.62), outline="", tags="t")
        c.create_oval(x - 29, cy - 29, x + 29, cy + 29, fill="#eef2f9", outline="", tags="t")
        c.create_oval(x - 22, cy - 25, x + 6, cy - 9, fill="white", outline="", tags="t")
        if self.state == "connecting":
            c.create_arc(x - 38, cy - 38, x + 38, cy + 38, start=self._spin_angle, extent=90,
                         style="arc", width=4, outline="white", tags="t")

    def _loop(self):
        """Slides the knob and spins the ring while connecting."""
        t = 0.0 if self.state == "idle" else 1.0
        self._tp += (t - self._tp) * 0.3
        if abs(t - self._tp) < 0.01:
            self._tp = t
        self._spin_angle = (self._spin_angle - 16) % 360
        self._draw_toggle(self._tp)
        if self.state == "connecting" or self._tp != t:
            self._spin_job = self.root.after(30, self._loop)
        else:
            self._spin_job = None

    def _paint(self):
        color, title, hint = {
            "idle": (FG, "قطع است", "برای وصل شدن شافل را بکشید"),
            "connecting": (AMBER, "در حال اتصال", "چند لحظه صبر کنید"),
            "connected": (BLUE, "متصل است", "برای قطع کردن شافل را بکشید"),
        }[self.state]
        self.state_lbl.config(text=title, fg=color)
        self.hint.config(text=hint)
        self.sub.config(text="")
        if self.state == "connecting":
            self._show_overlay()
        else:
            self._hide_overlay()
        if self._spin_job is None:
            self._loop()
        if self.state == "connected":
            if self._t0 is None:
                self._t0 = time.time()
                self._tick()
        else:
            self._t0 = None
            self.uptime.config(text="")

    def _show_overlay(self):
        if self._ov_shown:
            return
        self._ov_shown, self._ov_t0 = True, time.time()
        self.ov.place(x=0, y=0, relwidth=1, relheight=1)
        self.ov.lift()
        self._ov_tick()

    def _hide_overlay(self):
        """Keep the loading screen up for a moment so the credit is readable."""
        if self._ov_shown:
            wait = int(max(0.0, 2.6 - (time.time() - self._ov_t0)) * 1000)
            self.root.after(wait, self._do_hide_overlay)

    def _do_hide_overlay(self):
        if self.state != "connecting":
            self._ov_shown = False
            self.ov.place_forget()

    def _ov_tick(self):
        if not self._ov_shown:
            return
        self._ov_angle = (self._ov_angle - 14) % 360
        self.ov_cv.itemconfig(self.ov_arc, start=self._ov_angle)
        self.root.after(30, self._ov_tick)

    def _tick(self):
        if self._t0 is None:
            return
        m, sec = divmod(int(time.time() - self._t0), 60)
        h, m = divmod(m, 60)
        self.uptime.config(text=fa(f"{h:02d}:{m:02d}:{sec:02d}" if h else f"{m:02d}:{sec:02d}"))
        self.root.after(1000, self._tick)

    def _status_cfg(self, text="", fg=FG):
        """Show the status card only while there is a message."""
        self.status.config(text=text, fg=fg)
        if text:
            self.status_lip.pack(fill="x", padx=PAD + 6, pady=(14, 0), before=self.tg_lip)
        else:
            self.status_lip.pack_forget()

    def set_status(self, text, color=FG):
        def apply():
            self._status_cfg(text, color)
            self.ov_status.config(text=text)
        self.root.after(0, apply)

    def set_state(self, state):
        self.state = state
        self.root.after(0, self._paint)

    def _page(self, title, items, tip=None, action=None):
        """Full-page checklist (the main screen has no room for it)."""
        if _load_cfg() is None:
            return
        self.hide_help(restore=False)
        self.main.pack_forget()
        box = self.help
        self._label(box, title, 14, bold=True, wrap=WRAP).pack(fill="x", pady=(4, 6))
        if action:
            self._btn(box, action[0], action[1], BLUE, height=42).pack(fill="x", pady=(0, 8))
        inner = self._card(box, padx=0)
        self._rows(inner, items, bg=CARD, wrap=WRAP_ROW - 28, pad=2)
        if tip:
            self._label(inner, tip, 9, MUTED, wrap=WRAP - 28, bg=CARD).pack(fill="x", pady=(8, 0))
        self._btn(box, "بازگشت", self.hide_help, SLATE, height=42).pack(fill="x", pady=(10, 0))
        box.pack(fill="both", expand=True, padx=PAD)
        self.help_open = True

    def copy_phone_code(self):
        """One pasteable code that carries the relay ID(s) and secret key to the Android app."""
        cfg = _load_cfg()
        if not cfg:
            return
        ids = cfg.get("script_ids") or [cfg["script_id"]]
        code = connect_code.encode(ids, cfg["auth_key"])
        self.root.clipboard_clear()
        self.root.clipboard_append(code)
        self.root.update()
        self._status_cfg(text="کد اتصال کپی شد و آن را فقط برای خودتان بفرستید چون رمز شما داخل آن است", fg=GREEN)

    def show_help(self):
        self._page("اگر وصل نشد اینها را بررسی کنید", TROUBLE)

    def show_tg(self):
        self._page("راهنمای دستی تلگرام", TG_STEPS, TG_TIP,
                   ("افزودن خودکار به تلگرام", self.add_telegram))

    def add_telegram(self):
        """tg:// link: Telegram opens its own 'add proxy' dialog, one click to confirm."""
        port = (_load_cfg() or {}).get("socks5_port", SOCKS_PORT)
        webbrowser.open(f"tg://socks?server=127.0.0.1&port={port}")
        if self.help_open:
            self.hide_help()
        if self.state == "connected":
            self._status_cfg(text="در پنجره‌ی تلگرام دکمه‌ی اتصال به پروکسی را بزنید", fg=FG)
        else:
            self._status_cfg(text="پروکسی به تلگرام اضافه می‌شود و برای کار کردن شافل باید روشن باشد", fg=AMBER)

    def hide_help(self, restore=True):
        for w in self.help.winfo_children():
            w.destroy()
        self.help.pack_forget()
        was_open, self.help_open = self.help_open, False
        if restore and was_open:
            self.main.pack(fill="both", expand=True)

    def toggle(self):
        if self.busy or not self.main.winfo_ismapped():
            return
        if self.state == "idle":
            self.busy = True
            threading.Thread(target=self._connect, daemon=True).start()
        elif self.state == "connected":
            self.busy = True
            threading.Thread(target=self._disconnect, daemon=True).start()

    def _connect(self):
        try:
            self.set_state("connecting")
            cfg = _load_cfg()
            if cfg is None:
                raise EngineError("تنظیمات پیدا نشد")

            self.set_status("در حال آماده‌سازی گواهی امنیتی", MUTED)
            from core.cert_installer import install_ca, is_ca_trusted
            from proxy.mitm import CA_CERT_FILE, MITMCertManager
            if not os.path.exists(CA_CERT_FILE):
                MITMCertManager()
            if not is_ca_trusted(CA_CERT_FILE):
                self.set_status("ویندوز یک بار اجازه می‌خواهد و باید روی دکمه‌ی بله بزنید", AMBER)
                if not install_ca(CA_CERT_FILE):
                    raise EngineError("نصب گواهی امنیتی انجام نشد")

            self.set_status("در حال راه‌اندازی", MUTED)
            self.engine = Engine(cfg)
            self.engine.start()
            sysproxy.enable("127.0.0.1", cfg["http_port"])

            self.set_status("در حال آزمایش اتصال", MUTED)
            ok = self._probe(cfg["http_port"])
            self.set_state("connected")
            if ok:
                self.set_status("اینترنت آزاد وصل است ✓\nمرورگر را یک بار ببندید و دوباره باز کنید", GREEN)
            else:
                self.set_status("وصل شد اما سرور گوگل شما پاسخ نداد", AMBER)
                self.root.after(0, self.show_help)
        except Exception as exc:  # noqa: BLE001
            self.log.exception("connect failed")
            self._teardown()
            self.set_state("idle")
            self.set_status(f"اتصال ناموفق بود\n{str(exc).rstrip('.')}", RED)
        finally:
            self.busy = False

    def _probe(self, port: int) -> bool:
        try:
            opener = urllib.request.build_opener(
                urllib.request.ProxyHandler({"http": f"http://127.0.0.1:{port}"}))
            with opener.open("http://connectivitycheck.gstatic.com/generate_204", timeout=30) as r:
                return r.status in (200, 204)
        except Exception as exc:  # noqa: BLE001
            self.log.warning("probe failed: %s", exc)
            return False

    def _teardown(self):
        try:
            sysproxy.disable()
        except Exception:  # noqa: BLE001
            self.log.exception("restoring proxy failed")
        if self.engine:
            try:
                self.engine.stop()
            except Exception:  # noqa: BLE001
                pass
            self.engine = None

    def _disconnect(self):
        self._teardown()
        self.set_state("idle")
        self.set_status("قطع شد", MUTED)
        self.busy = False

    def _cleanup(self):
        if self.state != "idle" or os.path.exists(paths.path(sysproxy.BACKUP)):
            self._teardown()

    # ───────────── extras ─────────────
    def show_log(self):
        w = tk.Toplevel(self.root)
        w.title("گزارش فنی")
        w.geometry("640x400")
        w.configure(bg=BG)
        text = "\n".join(self.log_buf)
        bar = tk.Frame(w, bg=BG)
        bar.pack(fill="x", padx=10, pady=8)
        btn = self._btn(bar, "کپی گزارش برای ارسال به پشتیبان", lambda: copy(), SLATE, width=270)
        btn.pack(side="right")

        def copy():
            self.root.clipboard_clear()
            self.root.clipboard_append(text)
            self._style(btn, "کپی شد ✓", GREEN)

        t = scrolledtext.ScrolledText(w, font=(MONO, 9), bg=CARD, fg=FG, relief="flat")
        t.pack(fill="both", expand=True, padx=10, pady=(0, 10))
        t.insert("end", text)
        t.see("end")
        t.config(state="disabled")

    def uninstall(self):
        if self._busy_note():
            return
        if not messagebox.askyesno(APP_TITLE, "گواهی امنیتی و همه‌ی تنظیمات برنامه از سیستم پاک شود؟"):
            return
        try:
            from core.cert_installer import uninstall_ca
            from proxy.mitm import CA_CERT_FILE
            uninstall_ca(CA_CERT_FILE)
        except Exception:  # noqa: BLE001
            self.log.exception("uninstall_ca failed")
        shutil.rmtree(paths.path("ca"), ignore_errors=True)
        for n in ("config.json", "pending_key.txt"):
            try:
                os.remove(paths.path(n))
            except OSError:
                pass
        self._refresh()
        self._status_cfg(text="پاک‌سازی انجام شد", fg=MUTED)

    def on_close(self):
        if self.state == "connected" and not messagebox.askyesno(
                APP_TITLE, "با بستن برنامه اتصال قطع می‌شود. ادامه می‌دهید؟"):
            return
        self._cleanup()
        self.root.destroy()

    def run(self):
        self.root.mainloop()


def main():
    App().run()
