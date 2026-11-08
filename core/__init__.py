"""Lowuncher core module."""
import sys
from pathlib import Path

_core_dir = Path(__file__).resolve().parent
_root_dir = _core_dir.parent

for candidate_libs in [
    _root_dir / "libraries" / "python",
    _core_dir.parent / "libraries" / "python",
    _root_dir / "build" / "win-python" / "Lib" / "site-packages",
    Path(sys.executable).parent.parent / "libraries" / "python",
]:
    if candidate_libs.is_dir() and str(candidate_libs) not in sys.path:
        sys.path.insert(0, str(candidate_libs))

