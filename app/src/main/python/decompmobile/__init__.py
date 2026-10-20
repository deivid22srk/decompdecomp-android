"""decompdecomp for Android: start the app's own server in this process.

Kotlin starts this once, from a foreground service. It creates the same server the desktop app
creates (``decompnet.app.server.build``), on a port on 127.0.0.1, and returns the port and the
per-launch secret the WebView must put in its User-Agent. Everything else (routes, engine,
interface) is the upstream code.
"""

from __future__ import annotations

import json
import logging
import os
import secrets
import sys
import threading
import time
import traceback
import zipfile
from pathlib import Path

_state: dict = {}
_lock = threading.Lock()
log = logging.getLogger("decompdecomp.android")

# Files that are the user's game, not the decompilation: never put them in an export zip.
_GAME_SUFFIXES = {
    ".z64", ".n64", ".v64", ".rom", ".iso", ".cue", ".bin", ".gba", ".nds", ".gcm", ".wbfs",
    ".cso", ".chd", ".pbp", ".xci", ".nsp", ".7z", ".rar", ".zip",
}
_SKIP_DIRS = {".git", "build", "node_modules", ".decomp-wtf", "__pycache__"}


def _start_server(home: Path, first_port: int) -> dict:
    import uvicorn

    from decompnet.app import cleanup
    from decompnet.app.cli import REQUEST_BODY_LIMIT, _listen, server_config
    from decompnet.app.engine import RomNetwork
    from decompnet.app.server import build
    from decompnet.app.support import file_log

    internal = home / ".decomp"
    internal.mkdir(parents=True, exist_ok=True)
    file_log(internal / "logs" / "decomp.log")
    sock = _listen(first_port)
    port = sock.getsockname()[1]
    secret = secrets.token_urlsafe(24)
    state_file = internal / "server.json"
    state_file.write_text(json.dumps({"port": port, "secret": secret, "pid": os.getpid()}), encoding="utf-8")
    try:
        os.chmod(state_file, 0o600)
    except OSError:
        pass
    cleanup.old_registrations(internal)
    net = RomNetwork(internal / "decomp.db", home)
    mcp, manager, security, quit_app = build(net, internal, port, secret)
    app = mcp.streamable_http_app(transport_security=security, max_request_body_size=REQUEST_BODY_LIMIT,
                                  host="127.0.0.1")
    server = uvicorn.Server(server_config(app))
    failure: list[BaseException] = []

    def serve() -> None:
        try:
            server.run(sockets=[sock])
        except BaseException as e:  # the thread must not die silently: Kotlin shows this to the user
            failure.append(e)
            log.exception("the server stopped")

    threading.Thread(target=serve, daemon=True, name="decomp-server").start()
    deadline = time.monotonic() + 60
    while not server.started:
        if failure:
            raise RuntimeError(f"o servidor não iniciou: {failure[0]}")
        if time.monotonic() > deadline:
            raise RuntimeError("o servidor não respondeu em 60 s (veja o Console)")
        time.sleep(0.1)
    _state.update(home=home, internal=internal, net=net, manager=manager, mcp=mcp, server=server,
                  port=port, secret=secret, quit_app=quit_app)
    log.info("decompdecomp mobile server ready on 127.0.0.1:%s", port)
    return {"port": port, "secret": secret, "home": str(home)}


def start(home_path: str, first_port: int = 6464, native_lib_dir: str = "") -> str:
    """Start decompdecomp's server (idempotent). Returns JSON: {"port", "secret", "home"}.

    Called by Android on a background thread; returns as soon as the server answers.
    `native_lib_dir` is the app's nativeLibraryDir: capstone's libcapstone.so lives there (the
    vendored Python wrapper reads LIBCAPSTONE_PATH).
    """
    with _lock:
        if _state.get("server") is not None:
            return json.dumps({"port": _state["port"], "secret": _state["secret"], "home": str(_state["home"])})
        os.environ["DECOMP_MOBILE"] = "1"
        os.environ["DECOMP_WINDOW"] = "0"  # no GTK window: the WebView is the window
        os.environ["DECOMP_TRAY"] = "0"
        if native_lib_dir:
            os.environ["LIBCAPSTONE_PATH"] = native_lib_dir  # before decompnet imports capstone
            # The rabbitizer C extension is compiled by the CI for arm64 and packaged in
            # jniLibs; the pure-Python shim in third_party/rabbitizer-shim loads it.
            os.environ["RABBITIZER_LIB"] = str(Path(native_lib_dir) / "librabbitizer.so")
        home = Path(home_path).expanduser()
        home.mkdir(parents=True, exist_ok=True)
        os.environ["DECOMP_HOME"] = str(home)
        try:
            return json.dumps(_start_server(home, int(first_port)))
        except BaseException:
            traceback.print_exc()
            raise


def stop() -> None:
    """Stop the HTTP server (the foreground service calls this when it ends)."""
    server = _state.get("server")
    if server is not None:
        server.should_exit = True


def info() -> str:
    return json.dumps({"port": _state.get("port"), "secret": _state.get("secret"),
                       "home": str(_state.get("home") or ""), "running": _state.get("server") is not None})


def log_path() -> str:
    home = _state.get("home")
    return str(Path(home) / ".decomp" / "logs" / "decomp.log") if home else ""


def log_tail(lines: int = 300) -> str:
    """The last `lines` lines of the app's log, for the in-app console."""
    path = Path(log_path())
    try:
        data = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError as e:
        return f"(sem log ainda: {e})"
    return "\n".join(data[-max(1, int(lines)):])


def _skip(path: Path, root: Path) -> bool:
    if path.is_symlink():
        return True
    if path.is_dir():
        return path.name in _SKIP_DIRS
    if path.suffix.lower() in _GAME_SUFFIXES:
        return True
    # a disc image named without an extension is still the user's game: only at the project root
    return path.parent == root and path.stat().st_size > 256 * 1024 * 1024


def export_zip(slug: str, mode: str, out_path: str) -> str:
    """Zip a project for the user to save or share. Returns the zip path.

    `mode` is "project" (the exported tree and the work) or "progress" (only progress/, the part that
    is meant to be shared; see the upstream README). The game image itself is never included.
    """
    home: Path = _state["home"]
    net = _state["net"]
    if not slug or Path(slug).name != slug:
        raise ValueError("projeto inválido")
    root = (home / slug).resolve()
    if not root.is_dir() or home.resolve() not in root.parents:
        raise ValueError("projeto não encontrado")
    if mode not in ("project", "progress"):
        mode = "project"
    if mode == "project":
        try:  # best effort: without the x86 compilers the rebuild verdict will fail, but the tree is written
            net.export_rom(slug, log=lambda line: log.info("export: %s", line))
        except Exception as e:  # noqa: BLE001 - exporting is the point; a failed rebuild must not lose the files
            log.warning("export without a rebuild: %s", e)
    source = root / "progress" if mode == "progress" else root
    if not source.is_dir():
        raise FileNotFoundError("este projeto ainda não tem progresso salvo")
    out = Path(out_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6, allowZip64=True) as z:
        for path in sorted(source.rglob("*")):
            if _skip(path, source):
                continue
            if not path.is_file():
                continue
            z.write(path, path.relative_to(source).as_posix())
            count += 1
    log.info("exported %s files from %s to %s", count, source, out)
    return str(out)


def splash_info() -> str:
    """What the loading screen shows: version and where projects live."""
    import decompnet

    home = _state.get("home") or os.environ.get("DECOMP_HOME") or ""
    return json.dumps({"version": decompnet.__version__, "home": str(home), "python": sys.version.split()[0]})
