"""
Launcher self-update checker for Lowuncher.
Checks remote GitHub Releases or manifest for newer launcher versions.
"""
import logging
from typing import Optional, Tuple
import requests

logger = logging.getLogger("lowuncher.updater")


def parse_version(v_str: str) -> Tuple[int, ...]:
    """Parses semantic version string '1.2.3' into tuple (1, 2, 3)."""
    cleaned = v_str.lstrip("v").strip()
    parts = []
    for p in cleaned.split("."):
        try:
            parts.append(int(p))
        except ValueError:
            break
    return tuple(parts) if parts else (0, 0, 0)


def check_for_update(current_version: str, repo_owner_and_name: str) -> Optional[Tuple[str, str]]:
    """
    Checks GitHub Releases for a newer version of the launcher.
    Returns (latest_version_tag, download_url) if an update is available, else None.
    """
    if not repo_owner_and_name or "/" not in repo_owner_and_name:
        return None

    api_url = f"https://api.github.com/repos/{repo_owner_and_name}/releases/latest"
    headers = {"User-Agent": "Mozilla/5.0 Lowuncher-Updater"}

    try:
        resp = requests.get(api_url, headers=headers, timeout=5)
        if resp.status_code != 200:
            return None

        data = resp.json()
        latest_tag = data.get("tag_name", "")
        if not latest_tag:
            return None

        if parse_version(latest_tag) > parse_version(current_version):
            html_url = data.get("html_url", "")
            return latest_tag, html_url

    except Exception as e:
        logger.debug(f"Update check failed: {e}")

    return None
