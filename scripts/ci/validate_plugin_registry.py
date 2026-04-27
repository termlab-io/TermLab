#!/usr/bin/env python3
"""
Validate that every TermLab plugin in source is registered with the
installer's pluginLayouts list, and that every essential-plugin
declared in TermLabApplicationInfo.xml is packaged. Fails CI if either
property is violated.

Run from repo root:
    python3 scripts/ci/validate_plugin_registry.py
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]


def collect_source_plugin_ids() -> set[str]:
    """Plugin ids declared in <module>/resources/META-INF/plugin.xml files."""
    plugin_xmls = [REPO / "core" / "resources" / "META-INF" / "plugin.xml"]
    plugin_xmls += sorted((REPO / "plugins").glob("*/resources/META-INF/plugin.xml"))

    ids: set[str] = set()
    for plugin_xml in plugin_xmls:
        if not plugin_xml.exists():
            continue
        plugin_id = ET.parse(plugin_xml).getroot().findtext("id")
        if plugin_id:
            ids.add(plugin_id.strip())
    return ids


def collect_layout_plugin_ids() -> set[str]:
    """Plugins listed in TermLabProperties.kt's pluginLayouts list."""
    props_path = REPO / "build/src/org/jetbrains/intellij/build/termlab/TermLabProperties.kt"
    props_text = props_path.read_text(encoding="utf-8")
    matches = re.findall(
        r'PluginLayout\.plugin\(mainModuleName\s*=\s*"intellij\.termlab\.(\w+)"',
        props_text,
    )
    return {f"com.termlab.{m}" for m in matches}


def collect_essential_plugin_ids() -> set[str]:
    """essential-plugin entries in TermLabApplicationInfo.xml."""
    app_info = REPO / "customization/resources/idea/TermLabApplicationInfo.xml"
    root = ET.parse(app_info).getroot()
    ns = {"app": "http://jetbrains.org/intellij/schema/application-info"}
    return {e.text.strip() for e in root.findall("app:essential-plugin", ns) if e.text}


def main() -> int:
    source_plugins = collect_source_plugin_ids()
    layout_plugins = collect_layout_plugin_ids()
    essential_plugins = collect_essential_plugin_ids()

    errors: list[str] = []

    missing_from_layout = source_plugins - layout_plugins
    if missing_from_layout:
        errors.append(
            "Plugins exist in source but are missing from TermLabProperties.pluginLayouts; "
            f"they will NOT ship in the installer: {sorted(missing_from_layout)}"
        )

    phantom = layout_plugins - source_plugins
    if phantom:
        errors.append(
            "TermLabProperties.pluginLayouts references plugins with no matching source: "
            f"{sorted(phantom)}"
        )

    missing_essential = essential_plugins - layout_plugins
    if missing_essential:
        errors.append(
            "essential-plugin entries declared in TermLabApplicationInfo.xml are NOT in pluginLayouts; "
            f"the app will fail to start: {sorted(missing_essential)}"
        )

    if errors:
        for e in errors:
            print(f"::error::{e}", file=sys.stderr)
        return 1

    print(
        f"OK — {len(source_plugins)} source plugins all registered; "
        f"{len(essential_plugins)} essential plugins all packaged."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
