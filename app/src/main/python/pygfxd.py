"""Android stand-in for pygfxd (N64 display-list extraction).

splat imports `pygfxd` as soon as it is imported, but decompdecomp's imports never ask splat to
extract display lists (its config has no `gfx` segments), and pygfxd ships a prebuilt x86_64
`libgfxd.so`. This module keeps the import working and fails with a clear message if a display
list is ever extracted.
"""

from __future__ import annotations

from enum import IntEnum


class GfxdEndian(IntEnum):
    BIG = 0
    LITTLE = 1


# ucode constants splat passes to gfxd_target()
gfxd_f3d = 0
gfxd_f3db = 1
gfxd_f3dex = 2
gfxd_f3dexb = 3
gfxd_f3dex2 = 4


def _unavailable(*args, **kwargs):
    raise RuntimeError(
        "a extração de display lists (pygfxd) não está disponível nesta edição Android; "
        "o jogo foi importado sem os assets gráficos"
    )


def _identity(fn=None):
    """pygfxd's callback decorators only annotate the Python functions splat passes in."""
    if fn is None:
        return lambda f: f
    return fn


# callback decorators/annotations
gfxd_dl_callback = _identity
gfxd_mtx_callback = _identity
gfxd_vtx_callback = _identity
gfxd_tlut_callback = _identity
gfxd_timg_callback = _identity
gfxd_cimg_callback = _identity
gfxd_zimg_callback = _identity
gfxd_vp_callback = _identity
gfxd_light_callback = _identity
gfxd_lookat_callback = _identity
gfxd_macro_fn = _identity
gfxd_macro_dflt = None

# calls
gfxd_endian = _unavailable
gfxd_execute = _unavailable
gfxd_input_buffer = _unavailable
gfxd_output_buffer = _unavailable
gfxd_target = _unavailable
gfxd_printf = _unavailable
gfxd_puts = _unavailable
gfxd_buffer_to_string = _unavailable
