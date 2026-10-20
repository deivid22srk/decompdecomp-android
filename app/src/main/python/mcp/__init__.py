"""A small stand-in for the `mcp` package, for the Android build only.

decompdecomp uses `mcp` for two things: serving its tools to CLI agents over MCP, and hosting the
web routes on the MCP server's Starlette app (`mcp.custom_route`). The MCP SDK depends on
pydantic-core, a Rust extension with no Android wheel; CLI agents cannot run on Android anyway.

This shim keeps the part the interface needs (an ASGI app whose `custom_route` collects the app's
routes) and drops the MCP transport. The built-in API agent does not use MCP at all: it calls
`app/tools.py:call` directly.
"""

from .server.mcpserver import Context, MCPServer

__all__ = ["Context", "MCPServer"]
