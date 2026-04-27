#!/usr/bin/env python3
"""
Validate that each TermLab module's intellij.termlab.<name>.iml file
declares the same set of production module/library deps as its
BUILD.bazel jvm_library target. Fails CI on any drift with a
per-module breakdown.

Only checks production-scoped deps. Test-scoped deps and runtime-scoped
deps are out of scope for this validator (test-deps drift is less
load-bearing and adds parsing complexity).

Run from repo root:
    python3 scripts/ci/validate_iml_build_parity.py
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

# Bazel target path (without leading //) → iml module/library name.
# Targets ending with :name use the explicit suffix; targets without
# explicit suffix default to the directory name.
# Bazel target path (without leading //) → iml project-library name.
# These are targets that surface as <orderEntry type="library" name="..."> in
# the iml (not as module deps). Used for entries like //libraries/junit5-launcher
# whose IDEA representation is a named project library rather than an iml module.
BAZEL_TO_IML_LIBRARY = {
    "libraries/junit5-launcher": "JUnit6Launcher",
}

BAZEL_TO_IML_MODULE = {
    # platform modules
    "platform/analysis-api:analysis": "intellij.platform.analysis",
    "platform/core-api:core": "intellij.platform.core",
    "platform/core-impl": "intellij.platform.core.impl",
    "platform/core-impl:core-impl": "intellij.platform.core.impl",
    "platform/core-ui": "intellij.platform.core.ui",
    "platform/credential-store": "intellij.platform.credentialStore",
    "platform/eel": "intellij.platform.eel",
    "platform/editor-ui-api:editor-ui": "intellij.platform.editor.ui",
    "platform/editor-ui-ex:editor-ex": "intellij.platform.editor.ex",
    "platform/ide-core": "intellij.platform.ide.core",
    "platform/ide-core-impl": "intellij.platform.ide.core.impl",
    "platform/lang-api:lang": "intellij.platform.lang",
    "platform/lang-impl": "intellij.platform.lang.impl",
    "platform/lang-impl:lang-impl": "intellij.platform.lang.impl",
    "platform/platform-api:ide": "intellij.platform.ide",
    "platform/platform-impl:ide-impl": "intellij.platform.ide.impl",
    "platform/platform-util-io:ide-util-io": "intellij.platform.ide.util.io",
    "platform/project-frame": "intellij.platform.projectFrame",
    "platform/projectModel-api:projectModel": "intellij.platform.projectModel",
    "platform/util": "intellij.platform.util",
    "platform/util:util-ui": "intellij.platform.util.ui",
    "platform/util-ex": "intellij.platform.util.ex",
    "platform/util/text-matching": "intellij.platform.util.text.matching",
    "platform/welcome-screen": "intellij.platform.welcomeScreen",
    # library modules
    "libraries/bouncy-castle-provider": "intellij.libraries.bouncy.castle.provider",
    "libraries/jediterm-core:jediterm-core": "intellij.libraries.jediterm.core",
    "libraries/jediterm-ui:jediterm-ui": "intellij.libraries.jediterm.ui",
    "libraries/pty4j": "intellij.libraries.pty4j",
    "libraries/sshd-osgi": "intellij.libraries.sshd.osgi",
    # termlab modules (cross-deps)
    "termlab/core": "intellij.termlab.core",
    "termlab/sdk": "intellij.termlab.sdk",
    "termlab/customization": "intellij.termlab.customization",
    "termlab/plugins/editor": "intellij.termlab.editor",
    "termlab/plugins/proxmox": "intellij.termlab.proxmox",
    "termlab/plugins/runner": "intellij.termlab.runner",
    "termlab/plugins/search": "intellij.termlab.search",
    "termlab/plugins/sftp": "intellij.termlab.sftp",
    "termlab/plugins/share": "intellij.termlab.share",
    "termlab/plugins/ssh": "intellij.termlab.ssh",
    "termlab/plugins/sysinfo": "intellij.termlab.sysinfo",
    "termlab/plugins/tunnels": "intellij.termlab.tunnels",
    "termlab/plugins/vault": "intellij.termlab.vault",
}


def is_lib_target(target: str) -> bool:
    return target.startswith("@lib//:")


def lib_name(target: str) -> str:
    return target.removeprefix("@lib//:")


def normalize_bazel_target(target: str) -> str | None:
    """Strip leading // and return the canonical form."""
    if not target.startswith("//"):
        return None
    return target.removeprefix("//")


def parse_iml(iml_path: Path) -> tuple[set[str], set[str]]:
    """Returns (modules, libraries) — production-scope only."""
    root = ET.parse(iml_path).getroot()
    modules: set[str] = set()
    libraries: set[str] = set()
    for entry in root.iter("orderEntry"):
        scope = entry.get("scope", "")
        if scope == "TEST":
            continue
        # PROVIDED, RUNTIME, and the default (compile) all count as "production"
        # for the parity check; users can refine later if needed.
        entry_type = entry.get("type")
        if entry_type == "module":
            name = entry.get("module-name")
            if name:
                modules.add(name)
        elif entry_type == "library":
            name = entry.get("name")
            if name:
                libraries.add(name)
    return modules, libraries


def parse_build_bazel(build_path: Path, target_name: str) -> tuple[set[str], set[str], list[str]]:
    """
    Parses the deps list of the jvm_library named target_name in build_path.
    Returns (resolved_modules, resolved_libraries, unknown_targets).
    """
    text = build_path.read_text(encoding="utf-8")

    # Find the jvm_library block with this name
    pattern = re.compile(
        r'jvm_library\s*\(\s*(?:[^()]*?\n)*?\s*name\s*=\s*"' + re.escape(target_name) + r'"',
        re.DOTALL,
    )
    m = pattern.search(text)
    if not m:
        return set(), set(), [f"<no jvm_library named {target_name} found in {build_path}>"]

    # Find the matching closing parenthesis for this jvm_library
    start_pos = m.start()
    paren_count = 0
    deps_start = -1

    for i, char in enumerate(text[start_pos:], start=start_pos):
        if char == '(':
            paren_count += 1
        elif char == ')':
            paren_count -= 1
            if paren_count == 0:
                block_text = text[start_pos:i]
                break
    else:
        return set(), set(), [f"<malformed jvm_library {target_name} in {build_path}>"]

    # Extract deps block
    deps_match = re.search(r'deps\s*=\s*\[(.*?)\]', block_text, re.DOTALL)
    if not deps_match:
        return set(), set(), []

    deps_block = deps_match.group(1)
    raw_deps = re.findall(r'"([^"]+)"', deps_block)

    modules: set[str] = set()
    libraries: set[str] = set()
    unknown: list[str] = []

    for raw in raw_deps:
        if is_lib_target(raw):
            libraries.add(lib_name(raw))
            continue
        norm = normalize_bazel_target(raw)
        if norm is None:
            unknown.append(raw)
            continue
        if norm in BAZEL_TO_IML_LIBRARY:
            libraries.add(BAZEL_TO_IML_LIBRARY[norm])
        elif norm in BAZEL_TO_IML_MODULE:
            modules.add(BAZEL_TO_IML_MODULE[norm])
        else:
            unknown.append(raw)

    return modules, libraries, unknown


# (module_dir, iml_filename, bazel_target_name)
TERMLAB_MODULES = [
    ("core", "intellij.termlab.core.iml", "core"),
    ("customization", "intellij.termlab.customization.iml", "customization"),
    ("sdk", "intellij.termlab.sdk.iml", "sdk"),
    ("plugins/editor", "intellij.termlab.editor.iml", "editor"),
    ("plugins/proxmox", "intellij.termlab.proxmox.iml", "proxmox"),
    ("plugins/runner", "intellij.termlab.runner.iml", "runner"),
    ("plugins/search", "intellij.termlab.search.iml", "search"),
    ("plugins/sftp", "intellij.termlab.sftp.iml", "sftp"),
    ("plugins/share", "intellij.termlab.share.iml", "share"),
    ("plugins/ssh", "intellij.termlab.ssh.iml", "ssh"),
    ("plugins/sysinfo", "intellij.termlab.sysinfo.iml", "sysinfo"),
    ("plugins/tunnels", "intellij.termlab.tunnels.iml", "tunnels"),
    ("plugins/vault", "intellij.termlab.vault.iml", "vault"),
]


def main() -> int:
    drift_found = False
    unknown_warnings: list[str] = []

    for module_dir, iml_name, bazel_target in TERMLAB_MODULES:
        iml_path = REPO / module_dir / iml_name
        build_path = REPO / module_dir / "BUILD.bazel"

        if not iml_path.exists():
            print(f"::error::{iml_path} missing", file=sys.stderr)
            drift_found = True
            continue
        if not build_path.exists():
            print(f"::error::{build_path} missing", file=sys.stderr)
            drift_found = True
            continue

        iml_modules, iml_libraries = parse_iml(iml_path)
        bazel_modules, bazel_libraries, unknown = parse_build_bazel(build_path, bazel_target)

        if unknown:
            unknown_warnings.append(
                f"{module_dir}: unmapped Bazel targets (validator's mapping table needs an entry): "
                + ", ".join(unknown)
            )

        only_in_iml_modules = iml_modules - bazel_modules
        only_in_bazel_modules = bazel_modules - iml_modules
        only_in_iml_libraries = iml_libraries - bazel_libraries
        only_in_bazel_libraries = bazel_libraries - iml_libraries

        if only_in_iml_modules or only_in_bazel_modules or only_in_iml_libraries or only_in_bazel_libraries:
            drift_found = True
            print(f"::error::Drift in {module_dir}:", file=sys.stderr)
            if only_in_iml_modules:
                print(f"  modules in iml but not in BUILD.bazel: {sorted(only_in_iml_modules)}", file=sys.stderr)
            if only_in_bazel_modules:
                print(f"  modules in BUILD.bazel but not in iml: {sorted(only_in_bazel_modules)}", file=sys.stderr)
            if only_in_iml_libraries:
                print(f"  libraries in iml but not in BUILD.bazel: {sorted(only_in_iml_libraries)}", file=sys.stderr)
            if only_in_bazel_libraries:
                print(f"  libraries in BUILD.bazel but not in iml: {sorted(only_in_bazel_libraries)}", file=sys.stderr)

    for w in unknown_warnings:
        print(f"::warning::{w}", file=sys.stderr)

    if drift_found:
        return 1

    print(f"OK — {len(TERMLAB_MODULES)} TermLab modules in iml↔BUILD parity.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
