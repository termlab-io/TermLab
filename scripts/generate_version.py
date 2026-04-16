#!/usr/bin/env python3
"""
generate_version.py — Stamp the product version and commit SHA into
customization/resources/idea/TermLabApplicationInfo.xml in place.

Only four attributes are touched, all on the <version> element:

    <version major="X" minor="Y" patch="Z" suffix="<short-sha>"/>

Everything else — the <build> tag, motto, logos, themes, essential-plugin
list, comments, whitespace — is preserved byte-for-byte. The file is
edited as raw text (targeted regex replacement) rather than parsed and
re-serialized, so human edits survive regeneration.

Inputs:
    $VERSION        If set, persisted to customization/version.properties
                    as product.version before the XML is rewritten.

Version format:
    X.Y or X.Y.Z (missing parts default to 0). Examples:
        VERSION=0.1.0  → major=0 minor=1 patch=0
        VERSION=0.1    → major=0 minor=1 patch=0
        VERSION=1.2.3  → major=1 minor=2 patch=3
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
WORKBENCH = HERE.parent

VERSION_PROPS = WORKBENCH / "customization" / "version.properties"
APP_INFO_XML = WORKBENCH / "customization" / "resources" / "idea" / "TermLabApplicationInfo.xml"


def die(msg: str) -> None:
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def read_props(path: Path) -> dict[str, str]:
    props: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        props[key.strip()] = value.strip()
    return props


def write_prop(path: Path, key: str, value: str) -> None:
    """Update `key` in a .properties file in place, preserving comments
    and the order of other entries. Appends the key if missing."""
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    out: list[str] = []
    replaced = False
    for line in lines:
        stripped = line.lstrip()
        if stripped and not stripped.startswith("#") and "=" in stripped:
            existing_key = stripped.split("=", 1)[0].strip()
            if existing_key == key:
                eol = "\n" if line.endswith("\n") else ""
                out.append(f"{key}={value}{eol}")
                replaced = True
                continue
        out.append(line)
    if not replaced:
        if out and not out[-1].endswith("\n"):
            out[-1] += "\n"
        out.append(f"{key}={value}\n")
    path.write_text("".join(out), encoding="utf-8")


def parse_version(raw: str) -> tuple[str, str, str]:
    """'0.1.0' -> ('0', '1', '0'). Missing minor/patch default to '0'."""
    parts = raw.strip().split(".")
    if not (1 <= len(parts) <= 3):
        die(f"version '{raw}' must be X, X.Y, or X.Y.Z")
    for p in parts:
        if not p.isdigit():
            die(f"version '{raw}' component '{p}' is not numeric")
    while len(parts) < 3:
        parts.append("0")
    return parts[0], parts[1], parts[2]


def git_sha() -> str:
    try:
        sha = subprocess.check_output(
            ["git", "-C", str(WORKBENCH), "rev-parse", "--short", "HEAD"],
            stderr=subprocess.DEVNULL,
        ).decode().strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return "unknown"
    dirty = subprocess.call(
        ["git", "-C", str(WORKBENCH), "diff", "--quiet", "--ignore-submodules", "HEAD"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    ) != 0
    return f"{sha}-dirty" if dirty else sha


def _xml_attr_escape(value: str) -> str:
    return (
        value.replace("&", "&amp;")
             .replace("<", "&lt;")
             .replace(">", "&gt;")
             .replace('"', "&quot;")
    )


def set_attribute(text: str, tag: str, attr: str, value: str) -> str:
    """Set `attr="value"` on the first <tag ...> / <tag .../> in `text`.
    Preserves other attributes and surrounding whitespace. Inserts the
    attribute if it isn't already present."""
    tag_re = re.compile(rf"<{re.escape(tag)}\b[^>]*?/?>", re.DOTALL)
    m = tag_re.search(text)
    if not m:
        die(f"<{tag}> not found in {APP_INFO_XML.name}")
    opening = m.group(0)
    escaped = _xml_attr_escape(value)
    attr_re = re.compile(rf'(\b{re.escape(attr)}=")[^"]*(")')
    if attr_re.search(opening):
        new_opening = attr_re.sub(
            lambda _m: f"{_m.group(1)}{escaped}{_m.group(2)}", opening, count=1
        )
    else:
        if opening.endswith("/>"):
            new_opening = opening[:-2].rstrip() + f' {attr}="{escaped}"/>'
        else:
            new_opening = opening[:-1].rstrip() + f' {attr}="{escaped}">'
    return text[: m.start()] + new_opening + text[m.end() :]


def main() -> None:
    if not VERSION_PROPS.exists():
        die(f"{VERSION_PROPS} not found")
    if not APP_INFO_XML.exists():
        die(f"{APP_INFO_XML} not found")

    override = os.environ.get("VERSION", "").strip()
    if override:
        write_prop(VERSION_PROPS, "product.version", override)

    props = read_props(VERSION_PROPS)
    product_version = props.get("product.version")
    if not product_version:
        die("product.version missing from version.properties")

    major, minor, patch = parse_version(product_version)
    sha = git_sha()

    text = APP_INFO_XML.read_text(encoding="utf-8")
    text = set_attribute(text, "version", "major", major)
    text = set_attribute(text, "version", "minor", minor)
    text = set_attribute(text, "version", "patch", patch)
    text = set_attribute(text, "version", "suffix", sha)
    APP_INFO_XML.write_text(text, encoding="utf-8")

    rel = APP_INFO_XML.relative_to(WORKBENCH)
    print(f"[generate_version] updated {rel}")
    print(f"  version : {major}.{minor}.{patch}")
    print(f"  suffix  : {sha}")


if __name__ == "__main__":
    main()
