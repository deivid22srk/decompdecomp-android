"""Types `server.build` imports from the MCP SDK to scope the tool list per agent.

Android exposes no MCP endpoint, so these are only placeholders for the registration call.
"""


class ListToolsResult:
    def __init__(self, *args, **kwargs):
        self.args = args
        self.kwargs = kwargs


class PaginatedRequestParams:
    pass
