#!/usr/bin/env python3
"""
generate_updates_xml.py — Synthesize the JetBrains-format updates.xml for
TermLab from product-info.json files in a freshly-built artifact directory.

The platform's UpdateChecker fetches this file from
ExternalProductResourceUrls.updateMetadataUrl, parses it via parseUpdateData
(platform-impl/UpdateInfo.kt), and compares <build number=...> against the
running app's BuildNumber. Required attributes per that parser:
  <product name=...>          (mandatory)
    <code>TL</code>           (matched against ApplicationInfo.productCode)
    <channel id=...           (mandatory)
             status=...       (release/eap/beta/milestone)
             licensing=...    (release/eap; defaults to release)>
      <build number=... or fullNumber=...   (one mandatory)
             version=...                    (display string)
             releaseDate=YYYYMMDD>
        <message><![CDATA[...]]></message>
        <button url=... download=true name=Download/>

Inputs are read from the artifact directory's *.product-info.json files
(emitted by the IntelliJ installer build alongside each binary). Every
product-info.json for the same build carries the same buildNumber /
versionSuffix / productCode; we read one and assert the rest match.

The output is a single-build updates.xml suitable for hosting at the URL
ExternalProductResourceUrls.updateMetadataUrl points at. Patch entries are
deliberately omitted — the planned hosting model (release-asset) doesn't
support cross-version patches yet, so the dialog directs users at the
download page via the <button>.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from xml.dom import minidom
from xml.etree import ElementTree as ET


def die(msg: str) -> None:
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def read_product_infos(artifact_dir: Path) -> dict:
    """Read and validate every product-info.json in the artifact dir.

    All files for one build must agree on buildNumber / productCode /
    versionSuffix; if not, something has drifted and we refuse to publish
    rather than ship an updates.xml that points at inconsistent assets."""
    files = sorted(artifact_dir.glob("*.product-info.json"))
    if not files:
        die(f"no *.product-info.json files in {artifact_dir} — has the build run?")

    canonical: dict | None = None
    for path in files:
        data = json.loads(path.read_text(encoding="utf-8"))
        keys = ("buildNumber", "productCode", "versionSuffix", "version", "name")
        snapshot = {k: data.get(k) for k in keys}
        if canonical is None:
            canonical = snapshot
            canonical["_source"] = path.name
        elif snapshot != {k: canonical[k] for k in keys}:
            die(
                f"product-info.json drift between {canonical['_source']} and {path.name}: "
                f"{snapshot} vs {{k: canonical[k] for k in keys}}"
            )

    for key in ("buildNumber", "productCode", "name"):
        if not canonical.get(key):
            die(f"{canonical['_source']} missing required field '{key}'")

    return canonical


def verify_filename_consistency(artifact_dir: Path, build_number: str) -> None:
    """Sanity check: every installer file (termlab-*.dmg/.tar.gz/.win.zip/
    .exe/.sit/.zip) should embed the build number. Catches stale binaries
    sitting next to fresh product-info.json. Build-side metadata files
    (content-report.zip, dependencies.txt, etc.) are deliberately ignored
    via the `termlab-*` prefix."""
    patterns = ("termlab-*.dmg", "termlab-*.tar.gz", "termlab-*.win.zip",
                "termlab-*.exe", "termlab-*.sit", "termlab-*.zip")
    suspects = []
    for pattern in patterns:
        for f in artifact_dir.glob(pattern):
            name = f.name
            if name.endswith(".product-info.json"):
                continue
            if build_number not in name:
                suspects.append(name)
    if suspects:
        die(
            f"installer filenames do not contain build number {build_number}:\n  "
            + "\n  ".join(suspects)
            + "\nartifact dir likely has stale files; clean and rebuild."
        )


def derive_repo() -> str:
    """Parse owner/repo from git remote origin URL.

    Handles both https (https://github.com/o/r[.git]) and ssh
    (git@github.com:o/r[.git]) forms."""
    try:
        url = subprocess.check_output(
            ["git", "remote", "get-url", "origin"],
            stderr=subprocess.DEVNULL,
        ).decode().strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        die("could not read git remote origin — pass --repo owner/repo explicitly")

    m = re.match(r"^(?:https?://github\.com/|git@github\.com:)([^/]+)/(.+?)(?:\.git)?/?$", url)
    if not m:
        die(f"unexpected git remote origin url: {url} — pass --repo explicitly")
    return f"{m.group(1)}/{m.group(2)}"


def fetch_release_date(tag: str) -> str | None:
    """Ask gh for the release's publishedAt and return YYYYMMDD, or None
    if the release isn't published yet (still a draft) or gh isn't on PATH."""
    try:
        published = subprocess.check_output(
            ["gh", "release", "view", tag, "--json", "publishedAt", "--jq", ".publishedAt"],
            stderr=subprocess.DEVNULL,
        ).decode().strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return None
    if not published:
        return None
    try:
        return datetime.fromisoformat(published.replace("Z", "+00:00")).strftime("%Y%m%d")
    except ValueError:
        return None


def clean_product_version(version_suffix: str) -> str:
    """'0.1.1 (048dc6d-dirty)' -> '0.1.1'. Release builds set suffix to
    the bare version with no parenthetical, in which case this is a no-op."""
    return version_suffix.split(" ", 1)[0].strip()


def build_xml(
    *,
    product_name: str,
    product_code: str,
    channel_id: str,
    channel_status: str,
    channel_licensing: str,
    channel_url: str,
    build_number: str,
    display_version: str,
    release_date: str,
    download_url: str,
    message: str,
) -> str:
    products = ET.Element("products")
    product = ET.SubElement(products, "product", {"name": product_name})
    code = ET.SubElement(product, "code")
    code.text = product_code
    channel = ET.SubElement(
        product,
        "channel",
        {
            "id": channel_id,
            "status": channel_status,
            "licensing": channel_licensing,
            "url": channel_url,
        },
    )
    build = ET.SubElement(
        channel,
        "build",
        {
            "number": build_number,
            "fullNumber": build_number,
            "version": display_version,
            "releaseDate": release_date,
        },
    )
    msg = ET.SubElement(build, "message")
    # ElementTree doesn't support CDATA natively; minidom prettify will
    # emit the text correctly XML-escaped, which is fine for a short blurb.
    msg.text = message
    ET.SubElement(
        build,
        "button",
        {"name": "Download", "url": download_url, "download": "true"},
    )

    raw = ET.tostring(products, encoding="utf-8")
    # Pretty-print so the file is reviewable in PRs / on GitHub.
    return minidom.parseString(raw).toprettyxml(indent="  ", encoding="utf-8").decode("utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    parser.add_argument("--artifact-dir", required=True, type=Path,
                        help="directory containing *.product-info.json + installer artifacts")
    parser.add_argument("--tag", required=True,
                        help="GitHub release tag, used to construct the download URL")
    parser.add_argument("--repo", default=None,
                        help="owner/repo (default: derived from git remote origin)")
    parser.add_argument("--output", type=Path, default=None,
                        help="default: <artifact-dir>/updates.xml")
    parser.add_argument("--channel-id", default="TL-eap",
                        help="default: TL-eap (matches eap=true in ApplicationInfo)")
    parser.add_argument("--channel-status", default="eap",
                        help="release|eap|beta|milestone (default: eap)")
    parser.add_argument("--channel-licensing", default="eap",
                        help="release|eap (default: eap)")
    parser.add_argument("--release-date", default=None,
                        help="YYYYMMDD; default: gh release publishedAt, falling back to today UTC")
    args = parser.parse_args()

    if not args.artifact_dir.is_dir():
        die(f"artifact dir does not exist: {args.artifact_dir}")

    info = read_product_infos(args.artifact_dir)
    build_number = info["buildNumber"]
    verify_filename_consistency(args.artifact_dir, build_number)

    display_version = clean_product_version(info["versionSuffix"] or "")
    if not display_version:
        die("versionSuffix is empty in product-info.json — cannot derive display version")

    repo = args.repo or derive_repo()
    release_date = (
        args.release_date
        or fetch_release_date(args.tag)
        or datetime.now(timezone.utc).strftime("%Y%m%d")
    )

    download_url = f"https://github.com/{repo}/releases/tag/{args.tag}"
    channel_url = f"https://github.com/{repo}/releases"
    message = f"{info['name']} {display_version} is available."

    xml = build_xml(
        product_name=info["name"],
        product_code=info["productCode"],
        channel_id=args.channel_id,
        channel_status=args.channel_status,
        channel_licensing=args.channel_licensing,
        channel_url=channel_url,
        build_number=build_number,
        display_version=display_version,
        release_date=release_date,
        download_url=download_url,
        message=message,
    )

    output = args.output or (args.artifact_dir / "updates.xml")
    output.write_text(xml, encoding="utf-8")

    print(f"[generate_updates_xml] wrote {output}")
    print(f"  product code   : {info['productCode']}")
    print(f"  build number   : {build_number}")
    print(f"  display version: {display_version}")
    print(f"  channel        : {args.channel_id} (status={args.channel_status}, licensing={args.channel_licensing})")
    print(f"  release date   : {release_date}")
    print(f"  download url   : {download_url}")


if __name__ == "__main__":
    main()
