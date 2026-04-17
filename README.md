# TermLab

TermLab is an Integrated SysOps Environment built on the IntelliJ Product Platform.

It is designed as a one-stop shop for home lab enthusiasts and infrastructure professionals who want terminal-first workflows, remote access tooling, and systems management capabilities in one desktop application. Today, TermLab focuses on SSH access, credential management, SFTP workflows, tunnel management, and encrypted sharing of remote-connection setups. Over time, it is intended to grow into a broader operations workstation with first-class cluster, container, and platform tooling.

## What TermLab Is

TermLab is a stripped-down IntelliJ Platform product tailored for SysOps work instead of general software development.

Compared with a full IntelliJ IDE, TermLab removes large portions of the traditional IDE surface area and keeps the pieces that matter for terminal, remote access, and operator workflows. Under the hood it still benefits from the IntelliJ Product Platform's windowing model, action system, settings infrastructure, keymaps, search UI, plugin architecture, and cross-platform desktop runtime.

## Current Capabilities

- SSH host connection and session launching
- Encrypted credential vault for passwords, keys, and passphrases
- SSH key generation inside the vault
- Dual-pane SFTP UI for local and remote file browsing
- SSH tunnel management for local and remote forwarding
- Encrypted bundle export/import for SSH hosts, tunnels, and vault-backed credentials
- Command Palette and workspace-oriented terminal workflow
- IntelliJ-based settings, actions, shortcuts, and plugin structure

## Planned Direction

The current roadmap includes expanding TermLab beyond the SSH-centric core into broader homelab and infrastructure management workflows, including:

- Proxmox plugin support for managing Proxmox clusters
- A fuller Kubernetes management experience
- Local container management for tools such as Podman and Docker
- Additional systems-management plugins built on the same product foundation

These are forward-looking plans, not features that are complete today.

## Quick Start

### Requirements

- macOS or Linux
- Bash
- Git
- Python 3
- Enough disk space for a shallow `intellij-community` checkout plus build output

### Clone and Bootstrap

```bash
git clone https://github.com/an0nn30/conch_workbench ~/projects/conch_workbench
cd ~/projects/conch_workbench
./setup.sh
```

`setup.sh` does the initial platform wiring:

- reads the pinned upstream IntelliJ ref from `INTELLIJ_REF`
- shallow-clones `intellij-community`
- symlinks this repo into `intellij-community/termlab`
- writes `.intellij-root` so local build commands can find the platform checkout
- installs an IntelliJ run configuration and module registrations for debugging

### Build and Run

```bash
make termlab
```

Useful targets:

| Target | Purpose |
| --- | --- |
| `make termlab` | Build and run TermLab |
| `make termlab-build` | Build without launching |
| `make termlab-clean` | Clean Bazel build artifacts |
| `make termlab-installers` | Build installer artifacts for supported platforms |
| `make termlab-installers-mac` | Build macOS installers |
| `make termlab-installers-linux` | Build Linux installers |
| `make termlab-installers-windows` | Build Windows installers |
| `make termlab-perf-benchmark` | Run the idle-footprint benchmark harness |
| `make termlab-perf-budget` | Check the latest benchmark against budget thresholds |

If your `intellij-community` checkout lives somewhere other than the default sibling directory:

```bash
./setup.sh ~/some/other/path/intellij-community
INTELLIJ_ROOT=~/some/other/path/intellij-community make termlab
```

### Release Automation

Releases are prepared in GitHub Actions with a two-stage flow:

1. Run `Prepare Release` manually from `main` with `suffix`, `codename`, `eap`, and optional `skip_tests`.
2. The workflow stamps `customization/resources/idea/TermLabApplicationInfo.xml` via `scripts/generate_version.py`, commits the version bump, and pushes a tag in the form `Codename-YYYY.M-suffix`.
3. The tag triggers `Build Release`, which runs the test runners, builds platform-native installers for macOS, Linux, and Windows across `aarch64` and `amd64`, generates Windows MSI installers, and creates a draft GitHub release.

Repository setup note:

- Add a `RELEASE_TOKEN` secret with `contents:write`. The prepare workflow uses it instead of `GITHUB_TOKEN` so the pushed tag will trigger the tag-based release build workflow.
- Re-running `Prepare Release` for the same version replaces the existing tag first, then rebuilds the same draft release tag and refreshes its assets.
- Set `skip_tests=true` when you want the release build to bypass the test-runner job and go straight to packaging.

## Getting Started in the App

Once TermLab is running, the usual starting points are:

1. Open the Command Palette to discover available TermLab actions.
2. Open or unlock the Credential Vault.
3. Add or select an SSH host.
4. Start an SSH session, open SFTP, or create a tunnel.
5. Use `Tools | Export TermLab Bundle...` or `Tools | Import TermLab Bundle...` to share or restore connection setups.

### Core Product Shortcuts

These are the product-specific shortcuts currently wired into the app:

| Action | macOS | Linux / Windows |
| --- | --- | --- |
| Command Palette | `Cmd+Shift+P` | `Ctrl+Shift+P` |
| New SSH Session | `Cmd+K` | `Ctrl+K` |
| New SFTP Session | `Cmd+Shift+K` | `Ctrl+Shift+K` |
| Open Vault | `Cmd+Shift+V` | `Ctrl+Shift+V` |
| New Terminal Tab | `Cmd+T` | `Ctrl+Shift+T` |
| Close Terminal Tab | `Cmd+W` | `Ctrl+Shift+W` |
| Split Terminal Right | `Cmd+D` | `Ctrl+Shift+E` |
| Split Terminal Down | `Cmd+Shift+D` | `Ctrl+Shift+O` |
| Pop Out Terminal Tab | `Cmd+Option+Shift+Enter` | `Ctrl+Shift+Alt+Enter` |
| New Terminal Window | `Cmd+Option+Shift+T` | `Ctrl+Shift+Alt+T` |
| Save Workspace As | `Cmd+Shift+S` | `Ctrl+Alt+S` |
| Rename Tab | `F2` | `F2` |

## JetBrains Help and Shortcut References

Because TermLab is built on the IntelliJ Product Platform, many interaction patterns, settings screens, and keyboard behaviors follow JetBrains conventions.

Useful JetBrains documentation:

- Keyboard shortcuts: https://www.jetbrains.com/help/idea/mastering-keyboard-shortcuts.html
- Search Everywhere and action search concepts: https://www.jetbrains.com/help/idea/searching-everywhere.html
- Configuring keymaps: https://www.jetbrains.com/help/idea/configuring-keyboard-and-mouse-shortcuts.html
- IntelliJ IDEA help index: https://www.jetbrains.com/help/idea/discover-intellij-idea.html

TermLab intentionally reshapes parts of that experience, but those docs are still the best reference for general platform behaviors, navigation patterns, and shortcut customization.

## Development Layout

This repository contains the TermLab source tree, not a full IntelliJ fork.

TermLab is developed alongside a separate `intellij-community` checkout:

```text
~/projects/
├── conch_workbench/            # this repository
└── intellij-community/         # JetBrains platform checkout
    └── termlab -> ../conch_workbench
```

That split keeps the product code isolated while still letting Bazel and the IntelliJ project model treat TermLab as an in-tree platform product.

High-level layout:

```text
.
├── core/             # core product behavior, actions, themes, workspace flow
├── customization/    # branding, application info, product identity
├── sdk/              # shared extension interfaces for TermLab plugins
├── plugins/          # product features such as SSH, Vault, SFTP, Tunnels, Editor
├── docs/             # plans, specs, and design notes
├── scripts/          # setup, IDE integration, perf, and build helpers
├── setup.sh          # bootstrap script
└── Makefile          # main build and run entry points
```

## Running and Debugging from IntelliJ IDEA

After `./setup.sh`, open the `intellij-community` checkout in IntelliJ IDEA, not this repo by itself.

```bash
# Open this directory in IntelliJ IDEA:
~/projects/intellij-community
```

The bootstrap script installs:

- a `TermLab` run configuration under `.idea/runConfigurations/`
- the TermLab module registrations in `.idea/modules.xml`

That gives you normal run and debug flows with breakpoints in the TermLab sources.

If you need to reinstall the IDE wiring:

```bash
./scripts/install-idea-config.sh
```

## Updating the Pinned IntelliJ Platform

To move TermLab to a newer `intellij-community` ref:

```bash
echo "<sha-or-tag>" > INTELLIJ_REF
cd ../intellij-community
git fetch --depth 1 origin "$(cat ../conch_workbench/INTELLIJ_REF)"
git checkout FETCH_HEAD
cd ../conch_workbench
make termlab-build
```

If the platform update introduces API breakage, fix the TermLab code in this repo and commit the code changes together with the `INTELLIJ_REF` bump.

## Status

TermLab is actively evolving. The current product already provides a usable foundation for SSH-driven systems work, but the longer-term vision is broader: a focused operations workstation built on top of the IntelliJ Product Platform rather than a general-purpose IDE.

## License

TBD.
