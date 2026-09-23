"""DungeonWithin save.es3 Backup / Restore GUI.

- Backup:  device GAME_SAVE -> device Download folder (overwrites)
- Restore: closes the game, copies device Download folder -> device
  GAME_SAVE (overwrites), then relaunches the game.
- Auto-connects adb to localhost:7555 when no device is attached.

Run:  python save_manager.py
Bundled adb (./adb/adb.exe) is preferred, system adb is fallback.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import threading
import time
import tkinter as tk
from tkinter import ttk

ADB_HOST = "localhost:7555"  # historic default, kept for compatibility
DEFAULT_DEVICE = ADB_HOST

# (display name, adb address) for popular Android emulators.
EMULATOR_PRESETS: list[tuple[str, str]] = [
    ("MuMu Player", "127.0.0.1:7555"),
    ("BlueStacks 5", "127.0.0.1:5555"),
    ("LDPlayer", "localhost:5555"),
    ("NoxPlayer", "127.0.0.1:62001"),
    ("MEmu", "127.0.0.1:21503"),
    ("WSA", "127.0.0.1:58526"),
]
CUSTOM_LABEL = "Custom…"

# Apple-inspired palettes. Every theme must define the same keys.
LIGHT_THEME: dict[str, str] = {
    "BG": "#F5F5F7",            # system grouped background
    "CARD": "#FFFFFF",
    "CARD_BORDER": "#E5E5EA",   # hairline separator gray
    "TEXT": "#1D1D1F",
    "SECONDARY": "#86868B",
    "ACCENT": "#007AFF",        # system blue
    "ACCENT_HOVER": "#0A84FF",
    "ACCENT_ACTIVE": "#0066CC",
    "ACCENT_TINT": "#E8F1FF",   # light blue tile behind icons
    "GREEN_TINT": "#E6F7EB",
    "GREEN_DARK": "#1E9E4A",
    "SUCCESS": "#34C759",
    "WARNING": "#FF9F0A",
    "ERROR": "#FF3B30",
    "BTN_DISABLED_BG": "#C7C7CC",
    "BTN_DISABLED_FG": "#FFFFFF",
}
DARK_THEME: dict[str, str] = {
    "BG": "#000000",            # system grouped background, dark
    "CARD": "#1C1C1E",
    "CARD_BORDER": "#38383A",
    "TEXT": "#F5F5F7",
    "SECONDARY": "#98989D",
    "ACCENT": "#0A84FF",        # system blue, dark mode
    "ACCENT_HOVER": "#409CFF",
    "ACCENT_ACTIVE": "#0058B0",
    "ACCENT_TINT": "#1E3049",
    "GREEN_TINT": "#17281E",
    "GREEN_DARK": "#30D158",
    "SUCCESS": "#30D158",
    "WARNING": "#FF9F0A",
    "ERROR": "#FF453A",
    "BTN_DISABLED_BG": "#3A3A3C",
    "BTN_DISABLED_FG": "#8E8E93",
}
THEMES = {"light": LIGHT_THEME, "dark": DARK_THEME}
DEFAULT_THEME = "dark"


def _config_path() -> str:
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(here, "config.json")


def _read_config() -> dict:
    try:
        with open(_config_path(), "r", encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def _write_config(patch: dict) -> None:
    try:
        data = _read_config()
        data.update(patch)
        with open(_config_path(), "w", encoding="utf-8") as fh:
            json.dump(data, fh, indent=2)
    except OSError:
        pass


def _load_device() -> str:
    try:
        device = (_read_config().get("device") or "").strip()
        if device:
            return device
    except AttributeError:
        pass
    return DEFAULT_DEVICE


def _load_theme() -> str:
    theme = (_read_config().get("theme") or "").strip().lower()
    return theme if theme in THEMES else DEFAULT_THEME


_current_device = _load_device()
_current_theme = _load_theme()


def get_device() -> str:
    """Currently selected device address (adb serial)."""
    return _current_device


def set_device(address: str) -> str:
    """Select a device address and persist it. Returns the normalized address."""
    global _current_device
    _current_device = address.strip()
    _invalidate_serial_cache()
    _write_config({"device": _current_device})
    return _current_device


def get_theme() -> str:
    """Currently selected theme name ('light' or 'dark')."""
    return _current_theme


def set_theme(name: str) -> str:
    """Select a theme and persist it. Unknown names fall back to default."""
    global _current_theme
    _current_theme = name.strip().lower() if name else DEFAULT_THEME
    if _current_theme not in THEMES:
        _current_theme = DEFAULT_THEME
    _write_config({"theme": _current_theme})
    return _current_theme
PACKAGE = "com.GameCoaster.DungeonWithin"
GAME_SAVE = f"/sdcard/Android/data/{PACKAGE}/files/save.es3"
BACKUP_DIR = "/sdcard/Download"
BACKUP_SAVE = f"{BACKUP_DIR}/save.es3"
# Some file managers / ROMs expose /sdcard/Downloads (plural). Used as fallback.
BACKUP_SAVE_ALT = "/sdcard/Downloads/save.es3"


def get_adb_exe() -> str:
    """Prefer bundled adb.exe next to this script, fall back to PATH."""
    here = os.path.dirname(os.path.abspath(__file__))
    bundled = os.path.join(here, "adb", "adb.exe")
    if os.path.isfile(bundled):
        return bundled
    return "adb"


def parse_adb_devices(output: str) -> list[str]:
    """Parse `adb devices` output -> serials whose state is exactly 'device'.

    Example input:
        List of devices attached
        localhost:7555\\tdevice
        emulator-5554\\toffline

    Returns ['localhost:7555'].
    """
    devices: list[str] = []
    lines = output.replace("\r", "").split("\n")
    for line in lines[1:]:  # skip "List of devices attached"
        line = line.strip()
        if not line:
            continue
        parts = re.split(r"\s+", line)
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices


def build_backup_shell() -> str:
    """Shell command (run via `adb shell`) that copies game save -> Download.

    `cp -f` overwrites any existing save.es3 in Downloads.
    `mkdir -p` ensures the Download dir exists on odd ROMs.
    """
    return f"mkdir -p {BACKUP_DIR} && cp -f {GAME_SAVE} {BACKUP_SAVE}"


def build_restore_shell() -> str:
    """Shell command that copies Download save -> game folder (overwrites)."""
    return f"cp -f {BACKUP_SAVE} {GAME_SAVE}"


def build_restore_shell_with_fallback() -> str:
    """Restore trying singular Download/ first, then plural Downloads/."""
    return (
        f"if [ -f {BACKUP_SAVE} ]; then cp -f {BACKUP_SAVE} {GAME_SAVE}; "
        f"elif [ -f {BACKUP_SAVE_ALT} ]; then cp -f {BACKUP_SAVE_ALT} {GAME_SAVE}; "
        f"else echo NO_BACKUP_FOUND; exit 1; fi"
    )


def build_force_stop_shell() -> str:
    """Shell command that closes the game (no-op if not running)."""
    return f"am force-stop {PACKAGE}"


def build_launch_shell() -> str:
    """Shell command that cold-launches the game via its launcher entry."""
    return f"monkey -p {PACKAGE} -c android.intent.category.LAUNCHER 1"


def _subprocess_kwargs() -> dict:
    """Extra kwargs for every subprocess call.

    On Windows, CREATE_NO_WINDOW stops each adb invocation from
    flashing a console window open and closed.
    """
    if sys.platform == "win32":
        return {"creationflags": subprocess.CREATE_NO_WINDOW}
    return {}


_SERIAL_TTL_SECONDS = 30.0
_serial_cache: dict = {"value": None, "at": 0.0}


def _invalidate_serial_cache() -> None:
    _serial_cache["value"] = None
    _serial_cache["at"] = 0.0


def _target_serial() -> str | None:
    """Serial to pin shell/pull/push to, so multi-device hosts don't error.

    Prefers the selected device when it is connected; otherwise None
    (adb default, which works when exactly one device is attached).
    Runs `adb devices` directly to avoid recursing into run_adb().
    The result is cached briefly so a backup/restore (which issues
    several adb calls) only queries `adb devices` once.
    """
    now = time.monotonic()
    if _serial_cache["at"] and now - _serial_cache["at"] < _SERIAL_TTL_SECONDS:
        value = _serial_cache["value"]
        return value if isinstance(value, str) else None
    try:
        proc = subprocess.run(
            [get_adb_exe(), "devices"],
            capture_output=True, text=True, timeout=15,
            **_subprocess_kwargs(),
        )
    except (OSError, subprocess.SubprocessError):
        result = None
    else:
        serials = parse_adb_devices(proc.stdout or "")
        configured = get_device()
        result = configured if configured in serials else None
    _serial_cache["value"] = result
    _serial_cache["at"] = now
    return result


def run_adb(*args: str, timeout: int = 30) -> subprocess.CompletedProcess:
    adb = get_adb_exe()
    cmd = [adb]
    serial = _target_serial()
    if serial and args and args[0] in ("shell", "pull", "push"):
        cmd += ["-s", serial]
    cmd += list(args)
    return subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        timeout=timeout,
        **_subprocess_kwargs(),
    )


def is_device_connected() -> bool:
    try:
        proc = run_adb("devices")
    except (OSError, subprocess.SubprocessError):
        return False
    return len(parse_adb_devices(proc.stdout or "")) > 0


def ensure_connected() -> tuple[bool, str]:
    """Connect to the selected device if no device is attached."""
    if is_device_connected():
        return True, "Device already connected."
    return connect_to(get_device())


def connect_to(address: str) -> tuple[bool, str]:
    """Connect to an explicit address and verify it shows up.

    Bare serials that `adb connect` can't dial (e.g. emulator-5554)
    are accepted if they are already visible in `adb devices`.
    """
    address = address.strip()
    if not address:
        return False, "Enter a device address first."
    out = ""
    if re.match(r"^[\w.\-]+:\d+$", address):
        try:
            proc = run_adb("connect", address)
        except (OSError, subprocess.SubprocessError) as exc:
            return False, f"Failed to run adb: {exc}"
        out = ((proc.stdout or "") + (proc.stderr or "")).strip()
    # Connection state just changed: drop the cached serial so the
    # follow-up calls re-resolve which device to pin commands to.
    _invalidate_serial_cache()
    try:
        serials = parse_adb_devices((run_adb("devices").stdout or ""))
    except (OSError, subprocess.SubprocessError) as exc:
        return False, f"Failed to run adb: {exc}"
    if address in serials:
        return True, f"Connected to {address}. {out}".strip()
    hint = "Is the emulator running?" if ":" in address else \
        "For network emulators use host:port (e.g. 127.0.0.1:5555)."
    return False, f"Could not connect to {address}. {(out + ' ' + hint).strip()}"


def device_file_exists(device_path: str) -> bool:
    proc = run_adb("shell", f"test -f {device_path} && echo EXISTS")
    return "EXISTS" in (proc.stdout or "")


def is_package_running(package: str) -> bool:
    """True when the package has at least one live process on device."""
    proc = run_adb("shell", f"pidof {package}")
    return bool((proc.stdout or "").strip())


def do_backup() -> tuple[bool, str]:
    ok, msg = ensure_connected()
    if not ok:
        return False, msg
    if not device_file_exists(GAME_SAVE):
        return False, f"Game save not found on device:\n{GAME_SAVE}"
    proc = run_adb("shell", build_backup_shell())
    if proc.returncode != 0:
        err = (proc.stderr or proc.stdout or "unknown error").strip()
        return False, f"Backup failed: {err}"
    verify = run_adb("shell", f"ls -l {BACKUP_SAVE}")
    if verify.returncode != 0 or "save.es3" not in (verify.stdout or ""):
        return False, "Copy ran but backup file not found in Download."
    detail = (verify.stdout or "").strip()
    return True, f"Backup OK:\n{GAME_SAVE}\n  -> {BACKUP_SAVE}\n{detail}\n({msg})"


def do_restore() -> tuple[bool, str]:
    """Close the game, restore the backup over its save, relaunch the game."""
    ok, msg = ensure_connected()
    if not ok:
        return False, msg
    # 1. Never close the player's game when there is nothing to restore.
    if not device_file_exists(BACKUP_SAVE) and not device_file_exists(BACKUP_SAVE_ALT):
        return False, (
            "No backup found on device.\n"
            f"Looked for:\n{BACKUP_SAVE}\n{BACKUP_SAVE_ALT}\n"
            "Run Backup first."
        )
    # 2. Close the game so it can't overwrite the save while we replace it.
    proc = run_adb("shell", build_force_stop_shell())
    if proc.returncode != 0:
        err = (proc.stderr or proc.stdout or "unknown error").strip()
        return False, f"Could not close the game ({PACKAGE}): {err}"
    # 3. Restore the backup over the live save.
    proc = run_adb("shell", build_restore_shell_with_fallback())
    combined = ((proc.stdout or "") + (proc.stderr or "")).strip()
    if proc.returncode != 0 or "NO_BACKUP_FOUND" in combined:
        return False, (
            "The backup disappeared mid-restore.\n"
            f"Looked for:\n{BACKUP_SAVE}\n{BACKUP_SAVE_ALT}\n"
            "Run Backup first, then relaunch the game manually."
        )
    verify = run_adb("shell", f"ls -l {GAME_SAVE}")
    detail = (verify.stdout or "").strip()
    # 4. Relaunch the game.
    proc = run_adb("shell", build_launch_shell())
    if proc.returncode != 0:
        err = (proc.stderr or proc.stdout or "unknown error").strip()
        return True, (
            "Save restored, but the game could not be relaunched automatically:\n"
            f"{err}\nLaunch {PACKAGE} manually.\n{detail}\n({msg})"
        )
    relaunched = is_package_running(PACKAGE)
    status = ("Game relaunched." if relaunched
              else "Relaunch sent - confirm the game is on screen.")
    return True, (f"Restore OK:\n{BACKUP_SAVE}\n  -> {GAME_SAVE}\n"
                  f"{detail}\n{status}\n({msg})")


# ---------------- GUI (Apple-inspired light/dark themes) ----------------

FONT = "Segoe UI"


def _make_style(root: tk.Tk, T: dict[str, str]) -> ttk.Style:
    style = ttk.Style(root)
    try:
        style.theme_use("clam")
    except tk.TclError:
        pass
    style.configure("App.TFrame", background=T["BG"])
    style.configure("Hairline.TFrame", background=T["CARD_BORDER"])
    style.configure("Card.TFrame", background=T["CARD"])
    style.configure("App.TLabel", background=T["BG"], foreground=T["TEXT"],
                    font=(FONT, 10))
    style.configure("Card.TLabel", background=T["CARD"], foreground=T["TEXT"],
                    font=(FONT, 10))
    style.configure("Title.TLabel", background=T["BG"], foreground=T["TEXT"],
                    font=(FONT, 17, "bold"))
    style.configure("Subtitle.Card.TLabel", background=T["CARD"],
                    foreground=T["SECONDARY"], font=(FONT, 9))
    style.configure("Subtitle.TLabel", background=T["BG"],
                    foreground=T["SECONDARY"], font=(FONT, 10))
    style.configure("CardTitle.TLabel", background=T["CARD"],
                    foreground=T["TEXT"], font=(FONT, 11, "bold"))
    style.configure("CardDesc.TLabel", background=T["CARD"],
                    foreground=T["SECONDARY"], font=(FONT, 9))
    style.configure("Caption.TLabel", background=T["BG"],
                    foreground=T["SECONDARY"], font=(FONT, 8))
    # Primary (blue) buttons
    style.configure("Accent.TButton", background=T["ACCENT"], foreground="white",
                    font=(FONT, 10, "bold"), borderwidth=0, relief="flat",
                    padding=(12, 9), focuscolor=T["ACCENT"])
    style.map("Accent.TButton",
              background=[("disabled", T["BTN_DISABLED_BG"]),
                          ("pressed", T["ACCENT_ACTIVE"]),
                          ("active", T["ACCENT_HOVER"])],
              foreground=[("disabled", T["BTN_DISABLED_FG"])])
    # Borderless link-style button (on cards)
    style.configure("Link.TButton", background=T["CARD"], foreground=T["ACCENT"],
                    font=(FONT, 9, "bold"), borderwidth=0, relief="flat",
                    padding=(8, 4), focuscolor=T["CARD"])
    style.map("Link.TButton",
              background=[("active", T["ACCENT_TINT"])],
              foreground=[("disabled", T["SECONDARY"])])
    # Icon button in the header (sits on the app background)
    style.configure("HeaderLink.TButton", background=T["BG"],
                    foreground=T["SECONDARY"], font=(FONT, 12),
                    borderwidth=0, relief="flat", padding=(6, 2),
                    focuscolor=T["BG"])
    style.map("HeaderLink.TButton",
              background=[("active", T["ACCENT_TINT"])])
    # Slim activity bar
    style.configure("Slim.Horizontal.TProgressbar", background=T["ACCENT"],
                    troughcolor=T["BG"], borderwidth=0, thickness=4)
    # Device dropdown: match the cards, not the OS default.
    style.configure("TCombobox", font=(FONT, 10),
                    fieldbackground=T["CARD"], foreground=T["TEXT"],
                    background=T["CARD"], arrowcolor=T["SECONDARY"],
                    selectbackground=T["ACCENT"], selectforeground="white")
    style.configure("Vertical.TScrollbar", background=T["CARD_BORDER"],
                    troughcolor=T["CARD"], bordercolor=T["CARD"],
                    arrowcolor=T["SECONDARY"])
    # Dropdown popup list (a plain Listbox under the hood).
    root.option_add("*TCombobox*Listbox.background", T["CARD"])
    root.option_add("*TCombobox*Listbox.foreground", T["TEXT"])
    root.option_add("*TCombobox*Listbox.selectBackground", T["ACCENT"])
    root.option_add("*TCombobox*Listbox.selectForeground", "white")
    root.option_add("*TCombobox*Listbox.font", (FONT, 10))
    return style


def _card(parent: tk.Widget) -> ttk.Frame:
    """White card with a 1px hairline border.

    Packs the outer wrapper into `parent` and returns the inner frame
    for content. (Packing only the inner frame would leave the whole
    card invisible, since the unmanaged outer would never be laid out.)
    """
    outer = ttk.Frame(parent, style="Hairline.TFrame", padding=1)
    outer.pack(fill=tk.X, pady=(0, 12))
    inner = ttk.Frame(outer, style="Card.TFrame", padding=16)
    inner.pack(fill=tk.BOTH, expand=True)
    return inner


class SaveManagerApp:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.T: dict[str, str] = THEMES[get_theme()]
        self.tiles: list[tuple[tk.Label, str]] = []
        root.title("Save Manager")
        root.geometry("520x780")
        root.minsize(460, 640)
        _make_style(root, self.T)

        main = ttk.Frame(root, style="App.TFrame", padding=(20, 18))
        main.pack(fill=tk.BOTH, expand=True)

        # ---- Header ----
        header = ttk.Frame(main, style="App.TFrame")
        header.pack(fill=tk.X, pady=(0, 14))
        self.header_tile = tk.Label(header, text="\U0001F4BE", font=(FONT, 22),
                                    highlightthickness=1, padx=10, pady=6)
        self.header_tile.pack(side=tk.LEFT)
        titles = ttk.Frame(header, style="App.TFrame")
        titles.pack(side=tk.LEFT, fill=tk.Y, padx=(12, 0))
        ttk.Label(titles, text="Save Manager",
                  style="Title.TLabel").pack(anchor="w")
        ttk.Label(titles, text="DungeonWithin \u00b7 save.es3",
                  style="Subtitle.TLabel").pack(anchor="w")
        self.theme_btn = ttk.Button(header, text="", width=3,
                                    style="HeaderLink.TButton",
                                    command=self.toggle_theme)
        self.theme_btn.pack(side=tk.RIGHT)

        # ---- Device card ----
        device_card = _card(main)
        dev_top = ttk.Frame(device_card, style="Card.TFrame")
        dev_top.pack(fill=tk.X)
        ttk.Label(dev_top, text="Device", style="CardTitle.TLabel").pack(side=tk.LEFT)
        self.preset_var = tk.StringVar()
        self.preset_box = ttk.Combobox(dev_top, textvariable=self.preset_var,
                                       state="readonly", width=30,
                                       values=self._preset_displays())
        self.preset_box.pack(side=tk.RIGHT)
        self.preset_box.bind("<<ComboboxSelected>>", self._on_preset_selected)
        self.custom_row = ttk.Frame(device_card, style="Card.TFrame")
        self.custom_entry_var = tk.StringVar()
        self.custom_entry = tk.Entry(self.custom_row,
                                     textvariable=self.custom_entry_var,
                                     font=(FONT, 10), width=24,
                                     relief="flat", highlightthickness=1)
        self.custom_entry.pack(side=tk.LEFT, fill=tk.X, expand=True,
                               ipady=4)
        self.custom_entry.bind("<Return>", lambda _e: self._connect_custom())
        ttk.Button(self.custom_row, text="Connect", style="Link.TButton",
                   command=self._connect_custom).pack(side=tk.RIGHT, padx=(8, 0))

        # ---- Connection status card ----
        status_card = _card(main)
        status_left = ttk.Frame(status_card, style="Card.TFrame")
        status_left.pack(side=tk.LEFT, fill=tk.Y)
        self.dot = tk.Canvas(status_left, width=12, height=12,
                             highlightthickness=0)
        self.dot.pack(side=tk.LEFT, pady=2)
        self._draw_dot(self.T["WARNING"])
        self.conn_var = tk.StringVar(value="Connecting\u2026")
        ttk.Label(status_left, textvariable=self.conn_var,
                  style="Card.TLabel",
                  font=(FONT, 10, "bold")).pack(side=tk.LEFT, padx=(8, 0))
        self.connect_btn = ttk.Button(status_card, text="Reconnect",
                                      style="Link.TButton",
                                      command=lambda: self._run_in_thread(
                                          lambda: connect_to(get_device())))
        self.connect_btn.pack(side=tk.RIGHT)

        # ---- Action cards ----
        self.backup_btn = self._action_card(
            main, tile_text="\u2191", tile_role="accent",
            title="Back Up Save",
            desc="Copy the game save to Downloads. Overwrites any backup already there.",
            button_text="Back Up Now",
            action=do_backup)
        self.restore_btn = self._action_card(
            main, tile_text="\u2193", tile_role="green",
            title="Restore Save",
            desc="Closes the game, restores your backup over the current save, then relaunches the game.",
            button_text="Restore",
            action=do_restore)

        # ---- Activity bar (slim, Apple-like) ----
        self.progress = ttk.Progressbar(main, style="Slim.Horizontal.TProgressbar",
                                        mode="indeterminate")
        # Reserved slot so the layout doesn't jump when it appears.
        self.progress.pack(fill=tk.X, pady=(12, 0))
        self.progress.pack_forget()

        # ---- Activity log card ----
        log_head = ttk.Frame(main, style="App.TFrame")
        log_head.pack(fill=tk.X, pady=(10, 4))
        ttk.Label(log_head, text="Activity", style="App.TLabel",
                  font=(FONT, 11, "bold")).pack(side=tk.LEFT)
        log_card_outer = ttk.Frame(main, style="Hairline.TFrame", padding=1)
        log_card_outer.pack(fill=tk.BOTH, expand=True)
        log_card = ttk.Frame(log_card_outer, style="Card.TFrame", padding=4)
        log_card.pack(fill=tk.BOTH, expand=True)
        self.log = tk.Text(log_card, height=10, wrap=tk.WORD, state=tk.DISABLED,
                           font=("Consolas", 9), relief="flat",
                           highlightthickness=0, padx=8, pady=8)
        self.log.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scroll = ttk.Scrollbar(log_card, command=self.log.yview)
        scroll.pack(side=tk.RIGHT, fill=tk.Y)
        self.log.configure(yscrollcommand=scroll.set)

        self.caption_var = tk.StringVar()
        ttk.Label(main, textvariable=self.caption_var,
                  style="Caption.TLabel").pack(pady=(10, 0))

        self._paint_chrome()
        self._refresh_device_ui()
        self._set_buttons(False)
        self._log(f"Using {get_adb_exe()}", "info")
        self._log(f"Checking connection to {get_device()}\u2026", "info")
        self._run_in_thread(ensure_connected)

    def _action_card(self, parent: tk.Widget, *, tile_text: str, tile_role: str,
                     title: str, desc: str,
                     button_text: str, action) -> ttk.Button:
        card = _card(parent)
        top = ttk.Frame(card, style="Card.TFrame")
        top.pack(fill=tk.X)
        tile = tk.Label(top, text=tile_text, font=(FONT, 16, "bold"),
                        width=3, pady=6)
        self._paint_tile(tile, tile_role)
        self.tiles.append((tile, tile_role))
        tile.pack(side=tk.LEFT)
        texts = ttk.Frame(top, style="Card.TFrame")
        texts.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(12, 0))
        ttk.Label(texts, text=title,
                  style="CardTitle.TLabel").pack(anchor="w")
        ttk.Label(texts, text=desc, wraplength=360,
                  style="CardDesc.TLabel").pack(anchor="w", pady=(2, 0))
        btn = ttk.Button(card, text=button_text, style="Accent.TButton",
                         command=lambda: self._run_in_thread(action))
        btn.pack(fill=tk.X, pady=(12, 0))
        return btn

    def _draw_dot(self, color: str) -> None:
        self.dot.delete("all")
        self.dot.create_oval(1, 1, 11, 11, fill=color, outline=color)

    def _theme_icon(self) -> str:
        return "\u2600\uFE0F" if get_theme() == "dark" else "\U0001F319"

    def toggle_theme(self) -> None:
        self.apply_theme("light" if get_theme() == "dark" else "dark")

    def apply_theme(self, name: str) -> None:
        """Switch theme live: persist, restyle ttk, repaint tk widgets."""
        set_theme(name)
        self.T = THEMES[get_theme()]
        _make_style(self.root, self.T)
        self._paint_chrome()

    def _paint_tile(self, label: tk.Label, role: str) -> None:
        if role == "accent":
            label.configure(background=self.T["ACCENT_TINT"],
                            foreground=self.T["ACCENT"])
        else:
            label.configure(background=self.T["GREEN_TINT"],
                            foreground=self.T["GREEN_DARK"])

    def _paint_chrome(self) -> None:
        """Paint every plain-tk widget (ttk widgets follow the style)."""
        T = self.T
        self.root.configure(background=T["BG"])
        self.theme_btn.configure(text=self._theme_icon())
        self.header_tile.configure(background=T["CARD"], foreground=T["TEXT"],
                                   highlightbackground=T["CARD_BORDER"])
        for label, role in self.tiles:
            self._paint_tile(label, role)
        self.dot.configure(background=T["CARD"])
        self.custom_entry.configure(background=T["CARD"], foreground=T["TEXT"],
                                    insertbackground=T["ACCENT"],
                                    selectbackground=T["ACCENT"],
                                    selectforeground="white",
                                    highlightbackground=T["CARD_BORDER"],
                                    highlightcolor=T["ACCENT"])
        self.log.configure(background=T["CARD"], foreground=T["TEXT"],
                           insertbackground=T["TEXT"],
                           selectbackground=T["ACCENT"],
                           selectforeground="white")
        self.log.tag_configure("ok", foreground=T["GREEN_DARK"])
        self.log.tag_configure("fail", foreground=T["ERROR"])
        self.log.tag_configure("info", foreground=T["SECONDARY"])

    @staticmethod
    def _preset_displays() -> list[str]:
        return [f"{name} \u00b7 {addr}" for name, addr in EMULATOR_PRESETS] + [CUSTOM_LABEL]

    def _update_caption(self) -> None:
        self.caption_var.set(f"{get_device()} \u00b7 overwrites existing files")

    def _refresh_device_ui(self) -> None:
        """Sync the preset dropdown / custom row with the selected device."""
        device = get_device()
        match = next((f"{name} \u00b7 {addr}" for name, addr in EMULATOR_PRESETS
                      if addr == device), None)
        if match is not None:
            self.preset_var.set(match)
            self.custom_row.pack_forget()
        else:
            self.preset_var.set(CUSTOM_LABEL)
            if not self.custom_entry_var.get():
                self.custom_entry_var.set(device)
            self.custom_row.pack(fill=tk.X, pady=(10, 0))
        self._update_caption()

    def _on_preset_selected(self, _event=None) -> None:
        selected = self.preset_var.get()
        if selected == CUSTOM_LABEL:
            self.custom_entry_var.set(get_device())
            self.custom_row.pack(fill=tk.X, pady=(10, 0))
            self.custom_entry.focus_set()
            return
        address = selected.split("\u00b7")[-1].strip()
        self._switch_device(address)

    def _switch_device(self, address: str) -> None:
        address = set_device(address)
        self._refresh_device_ui()
        self._run_in_thread(lambda: connect_to(address))

    def _connect_custom(self) -> None:
        address = self.custom_entry_var.get().strip()
        if not address:
            self._log("Enter a device address, e.g. 127.0.0.1:5555.", "info")
            return
        self._switch_device(address)

    def _set_buttons(self, enabled: bool) -> None:
        state = tk.NORMAL if enabled else tk.DISABLED
        self.backup_btn.configure(state=state)
        self.restore_btn.configure(state=state)
        self.connect_btn.configure(state=state)

    def _log(self, msg: str, kind: str = "msg") -> None:
        self.log.configure(state=tk.NORMAL)
        self.log.insert(tk.END, msg.rstrip() + "\n", kind)
        self.log.see(tk.END)
        self.log.configure(state=tk.DISABLED)

    def _run_in_thread(self, func) -> None:
        self._set_buttons(False)
        self._draw_dot(self.T["WARNING"])
        self.conn_var.set("Working\u2026")
        self.progress.pack(fill=tk.X, pady=(12, 0))
        self.progress.start(12)

        def worker() -> None:
            try:
                ok, msg = func()
            except Exception as exc:  # never crash the UI thread
                ok, msg = False, f"Error: {exc}"
            self.root.after(0, lambda: self._done(ok, msg))

        threading.Thread(target=worker, daemon=True).start()

    def _done(self, ok: bool, msg: str) -> None:
        self.progress.stop()
        self.progress.pack_forget()
        self._log(("\u2713 " if ok else "\u2717 ") + msg, "ok" if ok else "fail")
        self._log("---", "info")
        # Refresh true connection state in background without blocking
        def refresh() -> None:
            connected = is_device_connected()
            self.root.after(0, lambda: self._refresh_status(connected))
        threading.Thread(target=refresh, daemon=True).start()
        self._set_buttons(True)

    def _refresh_status(self, connected: bool) -> None:
        self._draw_dot(self.T["SUCCESS"] if connected else self.T["ERROR"])
        device = get_device()
        self.conn_var.set(
            f"Connected \u00b7 {device}" if connected else "Not connected")
        self._update_caption()


def main() -> None:
    root = tk.Tk()
    SaveManagerApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
