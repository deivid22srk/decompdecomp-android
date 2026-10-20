"""Android stand-in for crunch64 (Yaz0/MIO0 N64 compression).

splat imports it at import time; decompdecomp's config never marks a segment as yay0/mio0, so
decompression is only reached for ROMs whose data was already split that way. If that happens, the
error says why instead of a bare ImportError.
"""

from __future__ import annotations


class _Codec:
    def __init__(self, name: str):
        self._name = name

    def decompress(self, data: bytes) -> bytes:
        raise RuntimeError(
            f"este jogo usa dados comprimidos ({self._name}); a descompressão crunch64 não está "
            "disponível nesta edição Android"
        )

    def compress(self, data: bytes) -> bytes:
        return self.decompress(data)


mio0 = _Codec("MIO0")
yay0 = _Codec("Yay0")
