"""One JSON shape for every error: {"error": {"code": ..., "message": ..., "details": ...}}."""

from __future__ import annotations

import logging

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

logger = logging.getLogger("orbit.api")


class ApiError(Exception):
    def __init__(self, status_code: int, code: str, message: str, details: list | dict | None = None):
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message
        self.details = details


def error_response(status_code: int, code: str, message: str, details: list | dict | None = None, headers: dict | None = None) -> JSONResponse:
    body: dict = {"code": code, "message": message}
    if details is not None:
        body["details"] = details
    return JSONResponse(status_code=status_code, content={"error": body}, headers=headers)


_STATUS_CODES = {404: "not_found", 405: "method_not_allowed", 413: "too_large", 429: "too_many_requests"}


def install_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def api_error(_: Request, error: ApiError) -> JSONResponse:
        return error_response(error.status_code, error.code, error.message, error.details)

    @app.exception_handler(StarletteHTTPException)
    async def http_error(_: Request, error: StarletteHTTPException) -> JSONResponse:
        code = _STATUS_CODES.get(error.status_code, "http_error")
        message = error.detail if isinstance(error.detail, str) else "Request failed."
        return error_response(error.status_code, code, message)

    @app.exception_handler(RequestValidationError)
    async def validation_error(_: Request, error: RequestValidationError) -> JSONResponse:
        details = [{"location": list(e.get("loc", [])), "message": e.get("msg", "")} for e in error.errors()]
        return error_response(422, "invalid_request", "The request is missing fields or has invalid values.", details)

    @app.exception_handler(Exception)
    async def unexpected_error(request: Request, error: Exception) -> JSONResponse:
        logger.exception("Unhandled error on %s %s", request.method, request.url.path)
        return error_response(500, "internal_error", "Something went wrong on the server. See the server log.")
