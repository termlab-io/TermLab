#!/usr/bin/env python3
"""
generate_version.py — Stamp TermLab version metadata into
customization/resources/idea/TermLabApplicationInfo.xml in place.

The script always updates the platform-coupled attributes on <version>:

    <version major="2026" minor="2" patch="0" suffix="0.1.0 (abc1234)"/>

major and minor are derived from $(INTELLIJ_ROOT)/build.txt so the
installer build's platform assertion passes. patch is derived from the
third segment of customization/version.properties' product.version.

By default, the script writes a developer-facing suffix that includes
the short git SHA. In release mode it writes the plain product version
instead so tagged builds stay stable and reproducible.

Optional release metadata can also be updated in place:

    <version eap="false" codename="Uplink"/>

Everything else — the <build> tag, motto, logos, themes, essential-plugin
list, comments, whitespace — is preserved byte-for-byte.

The script also emits the installer build number to out/build-number so
the Makefile can pass it to the IntelliJ installer build via
-Dbuild.number. The build number is `<intellij-branch>.<major*100+minor>.<patch>`
(e.g. 262.1.1 for product version 0.1.1). Three-segment form is required
because BuildContextImpl.pluginBuildNumber SemVer-checks it — 4-segment
values fail. The middle segment packs major*100+minor so the sequence stays
monotonic across both minor and major bumps: 0.1.1 → 262.1.1, 0.2.0 →
262.2.0, 1.0.0 → 262.100.0, 1.2.3 → 262.102.3. The leading segment must
match the base from intellij-community/build.txt per SnapshotBuildNumber.

Inputs:
    $VERSION               If set, persisted to customization/version.properties
                           as product.version before the XML is rewritten.
    $INTELLIJ_ROOT         Override for upstream build.txt location. Falls back
                           to .intellij-root, then to a sibling
                           intellij-community directory (matches Makefile).
    $TERMLAB_VERSION_MODE  'dev' (default) or 'release'. Release mode writes
                           suffix=product.version without the git SHA.
    $CODENAME              Optional override for the XML version@codename.
    $EAP                   Optional boolean override for the XML version@eap.
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
BUILD_NUMBER_OUT = WORKBENCH / "out" / "build-number"


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


def resolve_intellij_root() -> Path:
    env = os.environ.get("INTELLIJ_ROOT", "").strip()
    if env:
        return Path(env)
    root_file = WORKBENCH / ".intellij-root"
    if root_file.exists():
        return Path(root_file.read_text(encoding="utf-8").strip())
    return WORKBENCH.parent / "intellij-community"


def decode_platform_version(build_raw: str) -> tuple[str, str]:
    """'262.SNAPSHOT' -> ('2026', '2')."""
    prefix = build_raw.split(".", 1)[0]
    if not prefix.isdigit() or len(prefix) < 3:
        die(f"cannot decode platform version from build.txt prefix '{prefix}'")
    return f"20{prefix[:2]}", prefix[2:]


def intellij_branch(build_raw: str) -> str:
    """'262.SNAPSHOT' -> '262'. The first segment is IntelliJ's branch
    number; SnapshotBuildNumber.BASE in the platform requires any
    supplied -Dbuild.number to start with it."""
    prefix = build_raw.split(".", 1)[0]
    if not prefix.isdigit():
        die(f"cannot extract intellij branch from build.txt prefix '{prefix}'")
    return prefix


def decode_product_version(version: str) -> tuple[str, str, str]:
    parts = version.split(".")
    if len(parts) > 3 or any(not part.isdigit() for part in parts):
        die(f"product.version must look like X, X.Y, or X.Y.Z (got '{version}')")
    padded = parts + ["0"] * (3 - len(parts))
    return padded[0], padded[1], padded[2]


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


def get_attribute(text: str, tag: str, attr: str) -> str | None:
    tag_re = re.compile(rf"<{re.escape(tag)}\b[^>]*?/?>", re.DOTALL)
    m = tag_re.search(text)
    if not m:
        return None
    attr_re = re.compile(rf'\b{re.escape(attr)}="([^"]*)"')
    match = attr_re.search(m.group(0))
    return match.group(1) if match else None


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


def parse_bool(name: str) -> bool | None:
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return None
    value = raw.strip().lower()
    if value in {"1", "true", "yes", "on"}:
        return True
    if value in {"0", "false", "no", "off"}:
        return False
    die(f"{name} must be a boolean value (got '{raw}')")


def resolve_version_mode() -> str:
    mode = os.environ.get("TERMLAB_VERSION_MODE", "dev").strip().lower() or "dev"
    if mode not in {"dev", "release"}:
        die(f"TERMLAB_VERSION_MODE must be 'dev' or 'release' (got '{mode}')")
    return mode


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
    product_major, product_minor, patch = decode_product_version(product_version)

    intellij_root = resolve_intellij_root()
    build_txt = intellij_root / "build.txt"
    if not build_txt.exists():
        die(f"build.txt not found at {build_txt} — set INTELLIJ_ROOT or run ./setup.sh")
    build_raw = build_txt.read_text(encoding="utf-8").strip()
    platform_major, platform_minor = decode_platform_version(build_raw)
    branch = intellij_branch(build_raw)
    # Pack major+minor into one segment so the build number stays at 3
    # segments (required by BuildContextImpl.pluginBuildNumber's SemVer
    # check) while remaining monotonic across major bumps. Allots 100
    # minors per major, which is far more than we'll ever ship.
    packed_mid = int(product_major) * 100 + int(product_minor)
    build_number = f"{branch}.{packed_mid}.{patch}"

    version_mode = resolve_version_mode()
    suffix = product_version if version_mode == "release" else f"{product_version} ({git_sha()})"
    codename = os.environ.get("CODENAME", "").strip() or None
    eap = parse_bool("EAP")

    text = APP_INFO_XML.read_text(encoding="utf-8")
    text = set_attribute(text, "version", "major", platform_major)
    text = set_attribute(text, "version", "minor", platform_minor)
    text = set_attribute(text, "version", "patch", patch)
    text = set_attribute(text, "version", "suffix", suffix)
    if codename is not None:
        text = set_attribute(text, "version", "codename", codename)
    if eap is not None:
        text = set_attribute(text, "version", "eap", "true" if eap else "false")
    APP_INFO_XML.write_text(text, encoding="utf-8")

    BUILD_NUMBER_OUT.parent.mkdir(parents=True, exist_ok=True)
    BUILD_NUMBER_OUT.write_text(build_number + "\n", encoding="utf-8")

    final_codename = get_attribute(text, "version", "codename")
    release_tag = (
        f"{final_codename}-{platform_major}.{platform_minor}-{product_version}"
        if final_codename
        else None
    )

    rel = APP_INFO_XML.relative_to(WORKBENCH)
    print(f"[generate_version] updated {rel}")
    print(f"  product version : {product_version}")
    print(f"  platform        : {platform_major}.{platform_minor}  (build.txt={build_raw})")
    print(f"  patch          : {patch}")
    print(f"  mode           : {version_mode}")
    print(f"  suffix          : {suffix}")
    if final_codename:
        print(f"  codename        : {final_codename}")
    if eap is not None:
        print(f"  eap             : {'true' if eap else 'false'}")
    if release_tag:
        print(f"  release tag     : {release_tag}")
    print(f"  build number    : {build_number}  (written to {BUILD_NUMBER_OUT.relative_to(WORKBENCH)})")


if __name__ == "__main__":
    main()
