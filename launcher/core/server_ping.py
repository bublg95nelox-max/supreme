"""
Minecraft Server List Ping (SLP) implementation.
Pings Minecraft Java Edition servers to retrieve status and player count.
"""
import json
import struct
import socket
import logging
from typing import Dict, Any

logger = logging.getLogger("lowuncher.ping")


def _pack_varint(val: int) -> bytes:
    """Encodes an integer as a Minecraft VarInt."""
    total = b""
    while True:
        byte = val & 0x7F
        val >>= 7
        if val != 0:
            total += struct.pack("B", byte | 0x80)
        else:
            total += struct.pack("B", byte)
            break
    return total


def _read_varint(sock: socket.socket) -> int:
    """Reads a VarInt from a socket."""
    val = 0
    for i in range(5):
        raw = sock.recv(1)
        if not raw:
            raise IOError("Socket closed prematurely while reading VarInt")
        byte = ord(raw)
        val |= (byte & 0x7F) << (7 * i)
        if not (byte & 0x80):
            break
    return val


def ping_server(host: str, port: int = 25565, timeout: float = 3.0) -> Dict[str, Any]:
    """
    Pings a Minecraft server using the Server List Ping protocol.
    Returns a dict with 'online', 'players_online', 'players_max', 'latency_ms', and 'motd'.
    """
    result = {
        "online": False,
        "players_online": 0,
        "players_max": 0,
        "motd": "Оффлайн",
        "latency_ms": 0,
    }

    if not host:
        return result

    import time
    try:
        start_time = time.perf_counter()
        with socket.create_connection((host, port), timeout=timeout) as s:
            # 1. Handshake Packet (id 0x00)
            host_bytes = host.encode("utf-8")
            handshake = (
                b"\x00"  # Packet ID 0x00
                + _pack_varint(767)  # Protocol version (1.21.1)
                + _pack_varint(len(host_bytes))
                + host_bytes
                + struct.pack(">H", port)
                + _pack_varint(1)  # Next state: 1 (status)
            )
            s.sendall(_pack_varint(len(handshake)) + handshake)

            # 2. Status Request Packet (id 0x00)
            s.sendall(_pack_varint(1) + b"\x00")

            # 3. Read Response
            _packet_len = _read_varint(s)
            packet_id = _read_varint(s)
            if packet_id != 0x00:
                raise IOError(f"Unexpected packet ID: {packet_id}")

            json_len = _read_varint(s)
            data = b""
            while len(data) < json_len:
                chunk = s.recv(min(4096, json_len - len(data)))
                if not chunk:
                    break
                data += chunk

            resp = json.loads(data.decode("utf-8"))
            players = resp.get("players", {})

            result["online"] = True
            result["latency_ms"] = max(1, int((time.perf_counter() - start_time) * 1000))
            result["players_online"] = players.get("online", 0)
            result["players_max"] = players.get("max", 0)

            # Extract MOTD text
            desc = resp.get("description", "")
            if isinstance(desc, dict):
                result["motd"] = desc.get("text", "")
            elif isinstance(desc, str):
                result["motd"] = desc
            else:
                result["motd"] = "Онлайн"

    except Exception as e:
        logger.debug(f"Failed to ping {host}:{port}: {e}")

    return result
