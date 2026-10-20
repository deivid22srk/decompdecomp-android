# SPDX-FileCopyrightText: © 2022-2024 Decompollaborate
# SPDX-License-Identifier: MIT

from setuptools import setup, Extension
import sysconfig
from pathlib import Path
import platform

Py_GIL_DISABLED = sysconfig.get_config_var("Py_GIL_DISABLED")

bindingsPath = Path("rabbitizer")
srcPath = Path("src")

sourcesList = [str(x) for x in bindingsPath.glob("**/*.c")] + [str(x) for x in srcPath.glob("**/*.c")]
headersList = [str(x) for x in bindingsPath.glob("**/*.h")] + [str(x) for x in srcPath.glob("**/*.h")]

extraCompileArgs = ["-std=c11", "-Wall", "-g",]
if platform.system() == "Linux":
    # ANDROID PATCH: the NDK's clang emits warnings (unused parameter, shadowing...) that upstream
    # turns into errors with -Werror. Keep the warnings visible but do not fail the Android build.
    extraCompileArgs += ["-Wno-unknown-warning-option",]
    extraCompileArgs += ["-Os", "-Wextra",]
    extraCompileArgs += ["-Wno-nonnull-compare"]

# Only enable abi3 build if we are not building a free threaded wheel.
define_macros = []
py_limited_api = False
options = {}
if not Py_GIL_DISABLED:
    # ANDROID PATCH: build for Chaquopy's exact Python 3.13 instead of an abi3 wheel.
    define_macros = []
    py_limited_api = False
    options = {}

setup(
    ext_modules=[
        Extension(
            name="rabbitizer",
            sources=sourcesList,
            include_dirs=["include", "rabbitizer", "tables"],
            define_macros=define_macros,
            extra_compile_args = extraCompileArgs,
            depends=headersList,
            py_limited_api=py_limited_api,
        ),
    ],
    options=options,
)
