"""MCP transport security settings. Android uses no MCP transport; accepted and ignored."""


class TransportSecuritySettings:
    def __init__(self, *args, **kwargs):
        self.args = args
        self.kwargs = kwargs
