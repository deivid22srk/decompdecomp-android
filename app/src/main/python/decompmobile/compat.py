"""Android-only replacements for what the Android runtime cannot do.

The upstream app shells out to a second Python (`python -m splat`) to split a game. Chaquopy runs
Python inside the app process: there is no separate interpreter to exec. This module runs splat's
CLI in-process instead, with a lock, a clean module state and the output captured exactly like the
subprocess call did.
"""

from __future__ import annotations

import contextlib
import io
import os
import runpy
import sys
import threading
import traceback

_SPLAT_LOCK = threading.Lock()


def _fresh_splat_modules() -> None:
    """Forget every splat module, so the next run starts with the module state a new process would
    have. splat keeps options in module globals; without this, a second import inherits the first
    game's settings."""
    for name in [n for n in list(sys.modules) if n == "splat" or n.startswith("splat.")]:
        sys.modules.pop(name, None)


def splat_in_process(args: list[str], cwd) -> str:
    """Run `splat <args>` in this process and return its combined output.

    Raises the same `RomError` the subprocess version raised (with `details`), so callers do not
    need to know which path ran.
    """
    out = io.StringIO()
    code = 0
    with _SPLAT_LOCK:
        old_cwd = os.getcwd()
        old_argv = sys.argv[:]
        try:
            os.chdir(cwd)
            _fresh_splat_modules()
            sys.argv = ["splat", *args]
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
                try:
                    runpy.run_module("splat", run_name="__main__", alter_sys=False)
                except SystemExit as e:  # argparse and splat's own errors
                    if isinstance(e.code, int) or e.code is None:
                        code = e.code or 0
                    else:
                        code = 1
                        out.write(str(e.code) + "\n")
        except BaseException:  # never leave the server thread without the story
            code = 1
            traceback.print_exc(file=out)
        finally:
            sys.argv = old_argv
            try:
                os.chdir(old_cwd)
            except OSError:
                pass
    text = out.getvalue().strip()
    if code != 0:
        from decompnet.rom import RomError

        lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
        reason = next((ln for ln in reversed(lines) if "error" in ln.lower() or "exception" in ln.lower()),
                      lines[-1] if lines else "")
        err = RomError(f"splat could not analyse this ROM: {reason[:300]}")
        err.details = text[-6000:]
        raise err
    return text
