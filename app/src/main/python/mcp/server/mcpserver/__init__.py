"""The part of `mcp.server.mcpserver` decompdecomp touches, without the MCP protocol.

`MCPServer.custom_route` is how every HTTP route in `decompnet/app/routes/` is registered. Routes
are collected here and handed to a Starlette app when `streamable_http_app()` is called (after
`server.build` has registered all of them). Tool and prompt decorators are accepted and ignored:
the API agent gets its schemas from `app/tools.py:api_tool_schemas`, not from MCP.
"""

from __future__ import annotations

from typing import Callable

from starlette.applications import Starlette
from starlette.routing import Route


class Context:  # noqa: D101 - only a type annotation in the upstream code
    pass


class _LowLevelServer:
    def add_request_handler(self, *args, **kwargs) -> None:  # noqa: D102 - MCP-only feature
        return None


class MCPServer:
    def __init__(self, name: str = "", title: str = "", instructions: str = "", version: str = "", **kwargs):
        self.name = name
        self.title = title
        self.instructions = instructions
        self.version = version
        self._routes: list[Route] = []
        self._lowlevel_server = _LowLevelServer()

    def tool(self, *args, **kwargs) -> Callable:
        """Accept `@mcp.tool(...)` and leave the function as it is."""
        def decorate(fn: Callable) -> Callable:
            return fn
        return decorate

    def prompt(self, *args, **kwargs) -> Callable:
        """Accept `@mcp.prompt(...)` and leave the function as it is."""
        def decorate(fn: Callable) -> Callable:
            return fn
        return decorate

    def custom_route(self, path: str, methods: list[str] | None = None, **kwargs) -> Callable:
        """Register an HTTP route on the app that `streamable_http_app()` will return."""
        def decorate(fn: Callable) -> Callable:
            self._routes.append(Route(path, fn, methods=list(methods or ["GET"])))
            return fn
        return decorate

    async def list_tools(self) -> list:
        return []

    def streamable_http_app(self, **kwargs) -> Starlette:
        """The app's HTTP server. There is no MCP endpoint on Android (see the package docstring)."""
        return Starlette(routes=self._routes)
