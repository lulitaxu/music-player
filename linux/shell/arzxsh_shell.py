import json
import os
import secrets
import subprocess
import sys
import urllib.request
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("WebKit", "6.0")
from gi.repository import Gio, GLib, Gtk, WebKit

APP_ID = "me.arafatarzu.arzxsh"
PORT = 8077
BASE = f"http://127.0.0.1:{PORT}"
SPLASH = (
    "<html><body style='background:#0c0c0e;color:#e8e8ea;"
    "font-family:sans-serif;display:flex;align-items:center;"
    "justify-content:center;height:100vh;margin:0'>"
    "<p>Starting arzxsh...</p></body></html>"
)
FAILED = (
    "<html><body style='background:#0c0c0e;color:#e8e8ea;"
    "font-family:sans-serif;display:flex;align-items:center;"
    "justify-content:center;height:100vh;margin:0'>"
    "<p>The arzxsh player could not start. Please close and reopen it.</p>"
    "</body></html>"
)


def share_root() -> Path:
    env = os.environ.get("ARZXSH_SHARE_DIR")
    if env:
        return Path(env)
    app_share = Path("/app/share/arzxsh")
    if app_share.exists():
        return app_share
    return Path(__file__).resolve().parent.parent.parent


def backend_dir() -> Path:
    env = os.environ.get("ARZXSH_BACKEND_DIR")
    if env:
        return Path(env)
    return share_root() / "ytmusic-backend"


def user_css() -> str:
    root = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    try:
        return (root / "arzxsh" / "user.css").read_text()
    except OSError:
        return ""


def data_dir() -> Path:
    root = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share"))
    d = root / "arzxsh"
    d.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(d, 0o700)
    except OSError:
        pass
    return d


def healthy() -> bool:
    try:
        with urllib.request.urlopen(BASE + "/health", timeout=1) as r:
            return json.loads(r.read()).get("app") == "arzxsh-ytm"
    except Exception:
        return False


class App(Gtk.Application):
    def __init__(self):
        super().__init__(application_id=APP_ID,
                         flags=Gio.ApplicationFlags.FLAGS_NONE)
        self.backend_proc = None
        self.view = None
        self.polls = 0

    def start_backend(self):
        token = secrets.token_hex(16)
        token_path = data_dir() / "api-token"
        fd = os.open(token_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(token)
        env = dict(os.environ, ARZXSH_API_TOKEN=token)
        self.backend_proc = subprocess.Popen(
            [sys.executable, "-m", "uvicorn", "app:app",
             "--app-dir", str(backend_dir()),
             "--host", "127.0.0.1", "--port", str(PORT)],
            env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def poll_backend(self):
        if healthy():
            self.view.load_uri(BASE + "/")
            return GLib.SOURCE_REMOVE
        if self.backend_proc is not None and self.backend_proc.poll() is not None:
            self.view.load_html(FAILED)
            return GLib.SOURCE_REMOVE
        self.polls += 1
        if self.polls > 90:
            self.view.load_html(FAILED)
            return GLib.SOURCE_REMOVE
        return GLib.SOURCE_CONTINUE

    def do_activate(self):
        win = Gtk.ApplicationWindow(application=self)
        win.set_title("arzxsh")
        win.set_default_size(1120, 720)
        win.set_size_request(860, 540)
        self.view = WebKit.WebView()
        css = user_css()
        if css.strip():
            script = WebKit.UserScript(
                "window.__arzxshUserCss = " + json.dumps(css) + ";",
                WebKit.UserContentInjectedFrames.TOP_FRAME,
                WebKit.UserScriptInjectionTime.START, None, None)
            self.view.get_user_content_manager().add_script(script)
        settings = self.view.get_settings()
        settings.set_property("media-playback-requires-user-gesture", False)
        settings.set_property("hardware-acceleration-policy",
                              WebKit.HardwareAccelerationPolicy.ALWAYS)
        settings.set_property("enable-page-cache", True)
        self.view.load_html(SPLASH)
        win.set_child(self.view)
        win.present()
        if healthy():
            self.view.load_uri(BASE + "/")
            return
        self.start_backend()
        GLib.timeout_add(400, self.poll_backend)

    def do_shutdown(self):
        if self.backend_proc is not None:
            self.backend_proc.terminate()
            try:
                self.backend_proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.backend_proc.kill()
        Gtk.Application.do_shutdown(self)


if __name__ == "__main__":
    sys.exit(App().run(sys.argv))
