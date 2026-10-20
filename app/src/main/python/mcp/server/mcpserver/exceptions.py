"""The one exception decompdecomp imports from the MCP SDK."""


class ToolError(Exception):
    """A tool failed; the message goes back to the agent."""
