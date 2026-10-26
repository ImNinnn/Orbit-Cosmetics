"""Pydantic models describing the JSON the API returns."""

from __future__ import annotations

from pydantic import BaseModel, Field

from models import Cosmetic


class ErrorBody(BaseModel):
    code: str = Field(examples=["not_found"])
    message: str = Field(examples=["No cosmetic with this ID exists."])
    details: list | dict | None = None


class ErrorResponse(BaseModel):
    """Every error response has this shape."""

    error: ErrorBody


class RootResponse(BaseModel):
    name: str
    version: str
    status: str
    environment: str = Field(description="development, production or read-only", examples=["production"])
    docs: str


class HealthResponse(BaseModel):
    status: str = Field(examples=["ok"])
    database: str = Field(examples=["ok"])
    storage: str = Field(examples=["ok"])
    mode: str = Field(examples=["production"])
    auth: str = Field(description='"mojang" when writes need a player session, otherwise "none"', examples=["mojang"])


class CosmeticResponse(BaseModel):
    id: str = Field(examples=["3f1c9a0e5b6d4e2f8a7b6c5d4e3f2a1b"])
    display_name: str
    original_filename: str
    size_bytes: int
    sha256: str
    uploaded_at: str = Field(description="UTC timestamp in ISO 8601 format")
    validation_status: str = Field(examples=["approved"])
    format_version: str | None
    model_format: str | None
    element_count: int
    texture_count: int
    animation_count: int
    owner_uuid: str | None = Field(None, description="Player who uploaded it (production only)")
    featured: bool = Field(False, description="Checked by hand and listed in the in-game catalog")
    author: str | None = None
    description: str | None = None
    download_url: str | None = Field(description="Present only when the cosmetic can be downloaded")

    @classmethod
    def from_model(cls, cosmetic: Cosmetic, api_prefix: str) -> "CosmeticResponse":
        data = {key: value for key, value in cosmetic.__dict__.items() if key not in ("stored_filename", "featured_at")}
        data["featured"] = bool(cosmetic.featured)
        downloadable = cosmetic.validation_status == "approved"
        data["download_url"] = f"{api_prefix}/cosmetics/{cosmetic.id}/download" if downloadable else None
        return cls(**data)


class CosmeticListResponse(BaseModel):
    items: list[CosmeticResponse]
    total: int
    limit: int
    offset: int


class DeleteResponse(BaseModel):
    id: str
    deleted: bool


class PresenceUpdate(BaseModel):
    """What an Orbit client reports about its own player."""

    player_name: str = Field(min_length=1, max_length=16, examples=["Steve"])
    protocol: int = Field(ge=1, le=1000, description="Orbit protocol version of the equip data", examples=[2])
    equip: str = Field(
        max_length=48_000,
        description="Base64 of an Orbit protocol Equip message: worn cosmetics (by SHA-256) and their animation "
        "and physics settings. The backend stores it as-is; clients decode and validate it.",
    )


class PresenceEntry(BaseModel):
    player_uuid: str
    player_name: str
    protocol: int
    equip: str
    updated_at: float = Field(description="Unix time of the last report")


class PresenceQuery(BaseModel):
    players: list[str] = Field(default_factory=list, max_length=200, description="UUIDs of the players to look up")
    names: list[str] = Field(
        default_factory=list,
        max_length=200,
        description="Player names to look up as well, for offline-mode servers where UUIDs differ from the real ones",
    )


class PresenceList(BaseModel):
    players: list[PresenceEntry]
    ttl_seconds: int = Field(description="Entries not refreshed within this time are no longer returned")


class PresenceStored(BaseModel):
    player_uuid: str
    ttl_seconds: int


class ChallengeResponse(BaseModel):
    server_id: str = Field(description="Pass this to Mojang's join endpoint, then to POST /v1/auth/session")
    expires_in: int


class SessionRequest(BaseModel):
    username: str = Field(min_length=1, max_length=16)
    server_id: str = Field(min_length=1, max_length=64)


class SessionResponse(BaseModel):
    token: str | None = Field(description="Send as 'Authorization: Bearer <token>'. Only returned when signing in.")
    player_uuid: str
    player_name: str
    expires_at: float
    banned: bool = Field(False, description="Banned from Orbit Cloud: no uploads, featured cosmetics only")
    banned_until: float | None = Field(None, description="Unix time the ban ends; null for permanent bans")


class ReportRequest(BaseModel):
    player_uuid: str = Field(description="The reported player")
    player_name: str = Field(min_length=1, max_length=16)
    reason: str = Field(description="inappropriate, offensive, lag, impersonation or other", examples=["inappropriate"])
    note: str = Field("", max_length=200, description="Optional details for moderators")
    cosmetics: list[str] = Field(default_factory=list, max_length=16, description="SHA-256 of the cosmetics the reporter saw")
    reporter_uuid: str | None = Field(None, description="Development mode only (no accounts there): who reports")


class ReportResponse(BaseModel):
    received: bool
    new: bool = Field(description="False if this player had already been reported by you this week (the report was updated)")


class FeaturedListResponse(BaseModel):
    items: list[CosmeticResponse]
    total: int
    limit: int
    offset: int
