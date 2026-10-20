"""Android stand-in for pylibyaml.

The real package only patches PyYAML to use its libyaml C loaders for speed. Android has PyYAML
already, and inspect.getsource() on a compiled package is not reliable, so this no-op keeps
splat's `import pylibyaml` working with the pure/regular PyYAML loaders.
"""

__version__ = "0.1.0"
__all__ = ["monkey_patch_pyyaml", "restore_original_pyyaml"]


def monkey_patch_pyyaml(*args, **kwargs):
    return False


def restore_original_pyyaml(*args, **kwargs):
    return None
