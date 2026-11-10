"""
Authentication and account management for Lowuncher.
Supports deterministic offline authentication and Microsoft OAuth2.
"""
import uuid
import hashlib
import logging
import re
from urllib.parse import quote
from typing import Dict, Any, Optional

logger = logging.getLogger("lowuncher.auth")


def generate_offline_uuid(username: str) -> str:
    """
    Generates a deterministic offline UUID for a Minecraft username.
    Matches Java's UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(UTF_8)).
    """
    raw_bytes = f"OfflinePlayer:{username}".encode("utf-8")
    md5_hash = bytearray(hashlib.md5(raw_bytes).digest())
    
    # Set version to 3 (name-based MD5)
    md5_hash[6] = (md5_hash[6] & 0x0f) | 0x30
    # Set variant to IETF variant (variant 2)
    md5_hash[8] = (md5_hash[8] & 0x3f) | 0x80
    
    return str(uuid.UUID(bytes=bytes(md5_hash)))


def get_avatar_url(username: str, size: int = 64) -> str:
    """
    Returns an avatar image URL (head with helm overlay) for the username.
    Uses Minotar / Crafatar CDN.
    """
    safe_name = username.strip() or "Steve"
    if not re.fullmatch(r"[A-Za-z0-9_]{1,16}", safe_name):
        safe_name = "Steve"
    return f"https://minotar.net/helm/{quote(safe_name, safe='')}/{int(size)}.png"


class AuthManager:
    """Handles login sessions and options generation for Minecraft."""

    @staticmethod
    def get_offline_session(username: str) -> Dict[str, Any]:
        cleaned_username = username.strip() if username else "Player"
        if not cleaned_username:
            cleaned_username = "Player"
            
        player_uuid = generate_offline_uuid(cleaned_username)
        return {
            "username": cleaned_username,
            "uuid": player_uuid,
            "token": "0",
            "auth_type": "offline",
        }

    @staticmethod
    def format_minecraft_options(
        session: Dict[str, Any],
        allocated_ram_mb: int = 4096,
        custom_jvm_args: str = "",
        server_host: Optional[str] = None,
        server_port: Optional[int] = None,
    ) -> Dict[str, Any]:
        """
        Formats options dictionary compatible with minecraft-launcher-lib.
        """
        requested_ram = max(1024, int(allocated_ram_mb or 4096))
        # Prevent JVM crash on low-RAM systems by clamping heap to available physical memory
        try:
            from core.config import get_total_system_ram_mb
            total_ram = get_total_system_ram_mb()
            if total_ram > 0:
                safe_max = max(2048, total_ram - 1536)
                ram_mb = min(requested_ram, safe_max)
                if ram_mb < requested_ram:
                    logger.info(f"Clamped RAM allocation from {requested_ram}MB to {ram_mb}MB for system stability ({total_ram}MB total)")
            else:
                ram_mb = requested_ram
        except Exception:
            ram_mb = requested_ram

        jvm_arguments = [
            f"-Xmx{ram_mb}M",
            f"-Xms{min(1024, ram_mb)}M",
        ]

        if custom_jvm_args:
            jvm_arguments.extend(custom_jvm_args.strip().split())

        player_name = (session.get("username") or "Player").strip() or "Player"
        raw_uuid = session.get("uuid")
        if not raw_uuid or str(raw_uuid).strip().lower() in ("undefined", "null", ""):
            player_uuid = generate_offline_uuid(player_name)
        else:
            player_uuid = str(raw_uuid).strip()

        token = session.get("token") or "0"
        if str(token).strip().lower() in ("undefined", "null", ""):
            token = "0"

        # Lowuncher verification arguments and branding
        session_token = hashlib.sha256(f"Lowuncher-Verified:{player_name}:{player_uuid}:6233".encode("utf-8")).hexdigest()
        jvm_arguments.extend([
            "-Dminecraft.launcher.brand=Lowuncher",
            "-Dminecraft.launcher.version=1.0.7",
            "-Dlowuncher.active=true",
            f"-Dlowuncher.user={player_name}",
            f"-Dlowuncher.session={session_token}",
        ])

        options = {
            "username": player_name,
            "uuid": player_uuid,
            "token": str(token).strip(),
            "jvmArguments": jvm_arguments,
        }

        # Auto-connect to community server if specified
        if server_host:
            options["server"] = server_host
            if server_port and server_port != 25565:
                options["port"] = str(server_port)

        return options
