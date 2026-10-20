"""Load the rabbitizer C extension built for Android.

Chaquopy can install pure-Python packages and prebuilt Android wheels, but it builds a
local package with the host toolchain (the first attempt produced an x86-64 ``.so`` even
though the APK is arm64). The CI therefore compiles ``librabbitizer.so`` with the NDK
and the Chaquopy target headers, and this module loads it from the app's
``nativeLibraryDir``.

``decompmobile.start`` sets ``RABBITIZER_LIB`` before ``decompnet`` is imported, so by the
time ``spimdisasm`` does ``import rabbitizer`` the path is already known.
"""

from __future__ import annotations

import importlib.util
import os
import sys
from importlib.machinery import ExtensionFileLoader
from pathlib import Path


def _find_library() -> str:
    env = os.environ.get("RABBITIZER_LIB")
    if env and Path(env).is_file():
        return env

    # Chaquopy keeps libpython3.13.so and the other native libraries together. If the
    # environment variable is absent (a desktop test, or a future packaging change),
    # look for the library beside the native libraries on sys.path.
    for entry in sys.path:
        candidate = Path(entry) / "librabbitizer.so"
        if candidate.is_file():
            return str(candidate)

    raise ImportError(
        "librabbitizer.so not found. It must be in the app's nativeLibraryDir "
        "(RABBITIZER_LIB is set by decompmobile.start)."
    )


_path = _find_library()
_loader = ExtensionFileLoader("rabbitizer", _path)
_spec = importlib.util.spec_from_file_location("rabbitizer", _path, loader=_loader)
if _spec is None:  # pragma: no cover - only possible with a broken interpreter
    raise ImportError(f"cannot create an import spec for {_path}")
_module = importlib.util.module_from_spec(_spec)
_loader.exec_module(_module)

# A local C extension is normally imported under its own name. This module is the
# package that pip installs (so spimdisasm's requirement is satisfied); expose the
# extension's API at the top level.
for _name, _value in vars(_module).items():
    if not _name.startswith("__"):
        globals()[_name] = _value
__all__ = getattr(_module, "__all__", [name for name in globals() if not name.startswith("_")])
