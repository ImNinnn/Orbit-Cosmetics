"""Reads the cosmetics out of an Orbit protocol Equip message (see docs/PROTOCOL.md in the mod project).

The backend stores presence as the client sent it and leaves full validation to the clients. It only needs the
worn cosmetics' asset IDs (SHA-256) and names: to allow banned players featured cosmetics only, and to list what
a reported player wears. Anything that does not parse is treated as invalid.
"""

from __future__ import annotations

from dataclasses import dataclass

EQUIP = 0x02
MAX_EQUIPPED = 16
MAX_ANIMATIONS = 64
MAX_PHYSICS_LINKS = 8
ANIMATION_BYTES = 10  # trigger, loop, speed (2), priority, interrupt, min wait (2), max wait (2)
LINK_BYTES = 2 + 6 * 13  # bone (2), then six axes of source (1) + strength, max, resistance (4 each)


class InvalidEquip(ValueError):
    pass


@dataclass(frozen=True)
class WornCosmetic:
    sha256: str
    name: str


def worn_cosmetics(payload: bytes) -> list[WornCosmetic]:
    reader = _Reader(payload)
    if reader.byte() != EQUIP:
        raise InvalidEquip("not an Equip message")
    count = reader.byte()
    if count > MAX_EQUIPPED:
        raise InvalidEquip("too many cosmetics")
    result = []
    for _ in range(count):
        sha256 = reader.take(32).hex()
        name = reader.take(reader.short()).decode("utf-8", errors="replace")
        animations = reader.byte()
        if animations > MAX_ANIMATIONS:
            raise InvalidEquip("too many animations")
        reader.take(animations * ANIMATION_BYTES)
        links = reader.byte()
        if links > MAX_PHYSICS_LINKS:
            raise InvalidEquip("too many physics links")
        reader.take(links * LINK_BYTES)
        result.append(WornCosmetic(sha256, name[:64]))
    if not reader.done():
        raise InvalidEquip("trailing data")
    return result


class _Reader:
    def __init__(self, data: bytes) -> None:
        self.data = data
        self.position = 0

    def take(self, length: int) -> bytes:
        if self.position + length > len(self.data):
            raise InvalidEquip("truncated")
        chunk = self.data[self.position : self.position + length]
        self.position += length
        return chunk

    def byte(self) -> int:
        return self.take(1)[0]

    def short(self) -> int:
        high, low = self.take(2)
        return high << 8 | low

    def done(self) -> bool:
        return self.position == len(self.data)
