#!/usr/bin/env python3
"""RCON probe — verify a password works against a running Minecraft RCON
endpoint, independent of the Conch Minecraft Admin plugin."""
import os
import socket
import struct
import sys

HELP = """RCON probe — verifies a password against a running Minecraft RCON endpoint.

Env vars:
  RCON_HOST     RCON host           (default: localhost)
  RCON_PORT     RCON port           (default: 25575)
  RCON_PASSWORD RCON password       (required)
  RCON_COMMAND  command to run      (default: list)

Usage:
  RCON_PASSWORD=secret ./scripts/rcon-probe.py
  RCON_HOST=mc.example.com RCON_PORT=25575 RCON_PASSWORD=secret RCON_COMMAND='tps' ./scripts/rcon-probe.py
"""

TYPE_AUTH = 3
TYPE_COMMAND = 2
TYPE_AUTH_RESPONSE = 2
TYPE_RESPONSE_VALUE = 0

def hex_dump(data: bytes) -> str:
    if not data:
        return "<empty>"
    return " ".join(f"{b:02x}" for b in data)

def redact_password(pw: str) -> str:
    n = len(pw)
    if n == 0:
        return "<empty>"
    if n < 4:
        return f"<len={n}>"
    return f"len={n} first={pw[0]}{pw[1]} last={pw[-2]}{pw[-1]}"

def build_packet(pid: int, ptype: int, body: str) -> bytes:
    body_bytes = body.encode("utf-8")
    length = 4 + 4 + len(body_bytes) + 2
    packet = struct.pack("<iii", length, pid, ptype) + body_bytes + b"\x00\x00"
    return packet

def read_packet(sock: socket.socket) -> tuple[int, int, bytes]:
    """Returns (id, type, body_bytes). Raises on protocol errors."""
    length_bytes = read_exact(sock, 4)
    (length,) = struct.unpack("<i", length_bytes)
    if length < 10 or length > 4106:
        raise IOError(f"invalid rcon packet length: {length}")
    payload = read_exact(sock, length)
    pid, ptype = struct.unpack_from("<ii", payload, 0)
    body = payload[8:-2]  # strip trailing two nulls
    return pid, ptype, body

def read_exact(sock: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise IOError(f"unexpected EOF after {len(buf)} / {n} bytes")
        buf.extend(chunk)
    return bytes(buf)

def main():
    host = os.environ.get("RCON_HOST", "localhost")
    port = int(os.environ.get("RCON_PORT", "25575"))
    password = os.environ.get("RCON_PASSWORD")
    command = os.environ.get("RCON_COMMAND", "list")

    if not password:
        print("error: RCON_PASSWORD env var is required", file=sys.stderr)
        print(HELP, file=sys.stderr)
        sys.exit(2)

    print(f"RCON probe target:    {host}:{port}")
    print(f"Password fingerprint: {redact_password(password)}")
    print(f"Command:              {command}")
    print()

    # Connect
    print(f"======================================================================")
    print(f"[1/3] TCP connect {host}:{port}")
    print(f"----------------------------------------------------------------------")
    try:
        sock = socket.create_connection((host, port), timeout=10)
    except Exception as e:
        print(f"error: could not connect: {type(e).__name__}: {e}", file=sys.stderr)
        sys.exit(1)
    print(f"connected (local={sock.getsockname()} remote={sock.getpeername()})")
    print()

    try:
        # Auth
        auth_id = 1
        auth_packet = build_packet(auth_id, TYPE_AUTH, password)
        print(f"======================================================================")
        print(f"[2/3] send TYPE_AUTH (id={auth_id}, type={TYPE_AUTH}, password.len={len(password)})")
        print(f"----------------------------------------------------------------------")
        print(f"packet bytes ({len(auth_packet)}): {hex_dump(auth_packet)}")
        sock.sendall(auth_packet)
        print()

        # Read first reply
        try:
            first_id, first_type, first_body = read_packet(sock)
        except Exception as e:
            print(f"error: failed to read auth reply: {type(e).__name__}: {e}", file=sys.stderr)
            sys.exit(1)
        print(f"first reply: id={first_id} type={first_type} bodyLen={len(first_body)} bodyHex={hex_dump(first_body)}")

        # Minecraft sometimes emits an empty RESPONSE_VALUE first, then the real AUTH_RESPONSE
        if first_type != TYPE_AUTH_RESPONSE:
            print(f"first reply was not TYPE_AUTH_RESPONSE ({TYPE_AUTH_RESPONSE}); reading next packet")
            try:
                auth_reply_id, auth_reply_type, auth_reply_body = read_packet(sock)
            except Exception as e:
                print(f"error: failed to read second reply: {type(e).__name__}: {e}", file=sys.stderr)
                sys.exit(1)
            print(f"auth reply:  id={auth_reply_id} type={auth_reply_type} bodyLen={len(auth_reply_body)} bodyHex={hex_dump(auth_reply_body)}")
        else:
            auth_reply_id, auth_reply_type, auth_reply_body = first_id, first_type, first_body

        if auth_reply_id == -1:
            print()
            print("✗ RCON auth rejected: the server returned id=-1 which means wrong password.")
            print("  Things to check:")
            print("    1. server.properties rcon.password matches the password you passed.")
            print("    2. The Minecraft server has been RESTARTED since the password was changed.")
            print("    3. RCON is actually enabled (rcon.enabled=true in server.properties).")
            print(f"    4. You're hitting the right port — confirm with: `nc -z {host} {port}`.")
            sys.exit(1)

        if auth_reply_id != auth_id:
            print(f"✗ auth reply id mismatch: sent {auth_id}, got {auth_reply_id}")
            sys.exit(1)

        print("✓ auth succeeded")
        print()

        # Run command
        cmd_id = 2
        cmd_packet = build_packet(cmd_id, TYPE_COMMAND, command)
        print(f"======================================================================")
        print(f"[3/3] send TYPE_COMMAND (id={cmd_id}, type={TYPE_COMMAND}, command='{command}')")
        print(f"----------------------------------------------------------------------")
        print(f"packet bytes ({len(cmd_packet)}): {hex_dump(cmd_packet)}")
        sock.sendall(cmd_packet)

        try:
            reply_id, reply_type, reply_body = read_packet(sock)
        except Exception as e:
            print(f"error: failed to read command reply: {type(e).__name__}: {e}", file=sys.stderr)
            sys.exit(1)
        print(f"reply: id={reply_id} type={reply_type} bodyLen={len(reply_body)}")
        print(f"body:  {reply_body.decode('utf-8', errors='replace')}")
        print()
        print("✓ probe complete")

    finally:
        sock.close()

if __name__ == "__main__":
    if "-h" in sys.argv or "--help" in sys.argv:
        print(HELP)
        sys.exit(0)
    main()
