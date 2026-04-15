#!/usr/bin/env python3
"""Regenerate branded app assets (mac, windows, splash) from source images.

Examples:
  python3 scripts/update_brand_assets.py \
    --mac-src icons/term-lab-icon-mac-102.png

  python3 scripts/update_brand_assets.py \
    --mac-src icons/term-lab-icon-mac-102.png \
    --win-src icons/termlab-icon100.png \
    --splash-src icons/termlab-splash100.png
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

try:
    from PIL import Image
except Exception as exc:  # pragma: no cover
    print(
        "error: Pillow is required. Install with `python3 -m pip install Pillow`.",
        file=sys.stderr,
    )
    raise SystemExit(2) from exc


WINDOWS_ICO_SIZES = [
    (16, 16),
    (20, 20),
    (24, 24),
    (32, 32),
    (40, 40),
    (48, 48),
    (64, 64),
    (128, 128),
    (256, 256),
]

MAC_ICNS_SIZES = [
    (16, 16),
    (32, 32),
    (64, 64),
    (128, 128),
    (256, 256),
    (512, 512),
    (1024, 1024),
]


def require_file(path: Path) -> None:
    if not path.is_file():
        raise FileNotFoundError(f"missing input file: {path}")


def ensure_parent(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def crop_cover_resize(src: Image.Image, width: int, height: int) -> Image.Image:
    src_w, src_h = src.size
    src_ratio = src_w / src_h
    dst_ratio = width / height

    if src_ratio > dst_ratio:
        new_w = int(src_h * dst_ratio)
        left = (src_w - new_w) // 2
        cropped = src.crop((left, 0, left + new_w, src_h))
    else:
        new_h = int(src_w / dst_ratio)
        top = (src_h - new_h) // 2
        cropped = src.crop((0, top, src_w, top + new_h))

    return cropped.resize((width, height), Image.Resampling.LANCZOS)


def generate_windows_ico(src_path: Path, out_path: Path) -> None:
    require_file(src_path)
    ensure_parent(out_path)
    src = Image.open(src_path).convert("RGBA")
    src.save(out_path, format="ICO", sizes=WINDOWS_ICO_SIZES)


def generate_mac_assets(
    src_path: Path,
    icns_out: Path,
    dock_png_out: Path,
    inset_fraction: float,
) -> None:
    require_file(src_path)
    ensure_parent(icns_out)
    ensure_parent(dock_png_out)

    if not (0.0 < inset_fraction <= 1.0):
        raise ValueError(f"--mac-inset must be in (0, 1], got {inset_fraction}")

    canvas_size = 1024
    inner_size = int(round(canvas_size * inset_fraction))

    src = Image.open(src_path).convert("RGBA")
    inner = src.resize((inner_size, inner_size), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    offset = (canvas_size - inner_size) // 2
    canvas.paste(inner, (offset, offset), inner)

    canvas.save(dock_png_out)
    canvas.save(icns_out, format="ICNS", sizes=MAC_ICNS_SIZES)


def generate_splash_assets(src_path: Path, out_1x: Path, out_2x: Path) -> None:
    require_file(src_path)
    ensure_parent(out_1x)
    ensure_parent(out_2x)

    src = Image.open(src_path).convert("RGBA")
    crop_cover_resize(src, 640, 400).save(out_1x)
    crop_cover_resize(src, 1280, 800).save(out_2x)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mac-src", type=Path, help="Source PNG for mac icon.")
    parser.add_argument("--win-src", type=Path, help="Source PNG for Windows .ico.")
    parser.add_argument("--splash-src", type=Path, help="Source PNG for splash.")

    parser.add_argument(
        "--mac-icns-out",
        type=Path,
        default=Path("customization/resources/conch.icns"),
        help="Output macOS ICNS path.",
    )
    parser.add_argument(
        "--mac-dock-out",
        type=Path,
        default=Path("core/resources/conch_dock_icon.png"),
        help="Output macOS dock PNG path.",
    )
    parser.add_argument(
        "--mac-inset",
        type=float,
        default=0.8,
        help="Fraction of 1024 canvas occupied by icon art. Default: 0.8",
    )

    parser.add_argument(
        "--win-ico-out",
        type=Path,
        default=Path("customization/resources/conch.ico"),
        help="Output Windows ICO path.",
    )

    parser.add_argument(
        "--splash-out",
        type=Path,
        default=Path("customization/resources/conch_logo.png"),
        help="Output splash 1x (640x400).",
    )
    parser.add_argument(
        "--splash-2x-out",
        type=Path,
        default=Path("customization/resources/conch_logo@2x.png"),
        help="Output splash 2x (1280x800).",
    )

    args = parser.parse_args(argv)
    if not (args.mac_src or args.win_src or args.splash_src):
        parser.error("provide at least one source: --mac-src, --win-src, or --splash-src")
    return args


def main(argv: list[str]) -> int:
    args = parse_args(argv)

    try:
        if args.mac_src:
            generate_mac_assets(
                src_path=args.mac_src,
                icns_out=args.mac_icns_out,
                dock_png_out=args.mac_dock_out,
                inset_fraction=args.mac_inset,
            )
            print(
                f"mac: {args.mac_src} -> {args.mac_icns_out}, {args.mac_dock_out} "
                f"(inset={args.mac_inset:.2f})"
            )

        if args.win_src:
            generate_windows_ico(args.win_src, args.win_ico_out)
            print(f"win: {args.win_src} -> {args.win_ico_out}")

        if args.splash_src:
            generate_splash_assets(args.splash_src, args.splash_out, args.splash_2x_out)
            print(f"splash: {args.splash_src} -> {args.splash_out}, {args.splash_2x_out}")

    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
