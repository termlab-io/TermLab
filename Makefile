# TermLab — Build & Run
#
# This Makefile lives at the root of termlab_workbench. After running setup.sh,
# the workbench is symlinked into intellij-community/termlab, so running `make`
# from the workbench dir delegates Bazel commands up to the intellij root.
#
# Usage:
#   make termlab                    — build and run TermLab
#   make termlab-build              — build only (no run)
#   make termlab-test               — run the full TermLab unit/integration test suite
#   make termlab-clean              — clean build artifacts
#   make termlab-installers         — build all-platform distributions
#   make termlab-installers-fast    — build all-platform distributions reusing existing compiled IDE output
#   make termlab-installers-mac     — build macOS distributions only (.dmg)
#   make termlab-installers-linux   — build Linux distributions only (.tar.gz)
#   make termlab-installers-windows — build Windows distributions only (.win.zip, .exe)
#   make termlab-version            — regenerate TermLabApplicationInfo.xml
#
# Product version: pass VERSION=X.Y.Z on any build/run target to override and
# persist the version in customization/version.properties, e.g.:
#   VERSION=0.1.0 make termlab
#   VERSION=0.2.0 make termlab-installers
# The generated TermLabApplicationInfo.xml is a build artifact (gitignored);
# the template lives at customization/TermLabApplicationInfo.xml.tmpl.
#
# Installer artifacts land in: $(INTELLIJ_ROOT)/out/termlab/artifacts/
#
# INTELLIJ_ROOT resolution order:
#   1. Environment variable / `make INTELLIJ_ROOT=...`
#   2. .intellij-root file (written by setup.sh, contains an absolute path)
#   3. Sibling 'intellij-community' directory (zero-config fallback)
#
# .intellij-root is gitignored — it's local config, not source.

WORKBENCH_DIR  := $(realpath $(dir $(lastword $(MAKEFILE_LIST))))
INTELLIJ_ROOT_FILE := $(WORKBENCH_DIR)/.intellij-root

ifeq ($(origin INTELLIJ_ROOT), undefined)
  ifneq ($(wildcard $(INTELLIJ_ROOT_FILE)),)
    INTELLIJ_ROOT := $(strip $(shell cat $(INTELLIJ_ROOT_FILE)))
  else
    INTELLIJ_ROOT := $(WORKBENCH_DIR)/../intellij-community
  endif
endif

ifeq ($(OS),Windows_NT)
  # On Git Bash / GitHub Actions Windows runners, invoking `bash bazel.cmd`
  # executes the Unix shim in bazel.cmd, which sees `uname` values like
  # MINGW64_NT-* and exits as unsupported. Use the native cmd.exe entrypoint
  # so bazel.cmd follows its Windows code path and downloads the correct
  # bazelisk binary.
  BAZEL := cd "$(INTELLIJ_ROOT)" && cmd.exe //c bazel.cmd
else
  BAZEL := cd $(INTELLIJ_ROOT) && bash bazel.cmd
endif

# Default workspace root for TermLab. Override with:
#   make termlab TERMLAB_WORKSPACE=/path/to/workspace
TERMLAB_WORKSPACE ?= $(HOME)

# Perf harness defaults. Override as needed, for example:
#   make termlab-perf-benchmark TERMLAB_PERF_WARMUP_SEC=120 TERMLAB_PERF_DURATION_SEC=600
TERMLAB_PERF_OUT ?= $(WORKBENCH_DIR)/perf-results
TERMLAB_PERF_WORKSPACE ?= $(WORKBENCH_DIR)/.perf-workspace
TERMLAB_PERF_WARMUP_SEC ?= 90
TERMLAB_PERF_SAMPLE_SEC ?= 5
TERMLAB_PERF_DURATION_SEC ?= 300

TERMLAB_TEST_TARGETS := \
	//termlab/core:core_test_runner \
	//termlab/plugins/vault:vault_test_runner \
	//termlab/plugins/ssh:ssh_test_runner \
	//termlab/plugins/tunnels:tunnels_test_runner \
	//termlab/plugins/share:share_test_runner \
	//termlab/plugins/editor:editor_test_runner \
	//termlab/plugins/runner:runner_test_runner \
	//termlab/plugins/sftp:sftp_test_runner \
	//termlab/plugins/search:search_test_runner

.PHONY: termlab termlab-build termlab-test termlab-clean termlab-installers termlab-installers-fast termlab-installers-mac termlab-installers-linux termlab-installers-windows check-intellij termlab-version termlab-memory-artifacts termlab-sync-idea-config termlab-bootstrap-intellij termlab-perf-benchmark termlab-perf-budget

check-intellij:
	@test -f "$(INTELLIJ_ROOT)/bazel.cmd" || { \
		echo "ERROR: bazel.cmd not found at $(INTELLIJ_ROOT)."; \
		echo "       Run ./setup.sh first, or set INTELLIJ_ROOT explicitly."; \
		exit 1; \
	}

# Keep IntelliJ project module registration in sync with TermLab modules.
# The installer build loads the JPS project model from intellij-community/.idea,
# so stale modules.xml entries can make newly added plugins look "missing"
# even though their Bazel targets and .iml files exist.
termlab-sync-idea-config: check-intellij
	@bash "$(WORKBENCH_DIR)/scripts/install-idea-config.sh" "$(INTELLIJ_ROOT)"

# Installer packaging depends on the sibling intellij-community and android
# checkouts matching the SHAs pinned by INTELLIJ_REF / ANDROID_REF. Reuse the
# same bootstrap flow as release.sh so local packaging doesn't silently compile
# against a drifted upstream checkout.
termlab-bootstrap-intellij: check-intellij
	@bash "$(WORKBENCH_DIR)/scripts/ci/bootstrap_intellij.sh" "$(INTELLIJ_ROOT)"

# Stamp version attributes into customization/resources/idea/TermLabApplicationInfo.xml
# in place. Only <version major/minor/patch suffix> are touched; other
# edits in that file (motto, logos, themes, plugins, ...) are preserved.
# Pass VERSION=X.Y.Z to override (and persist) the product version:
#   make termlab-version VERSION=0.2.0
# Runs automatically before every termlab build/run target below.
termlab-version:
	python3 $(WORKBENCH_DIR)/scripts/generate_version.py

termlab-memory-artifacts:
	@mkdir -p "$(WORKBENCH_DIR)/docs/memory-investigation/2026-04-16/dumps" \
		"$(WORKBENCH_DIR)/docs/memory-investigation/2026-04-16/gc" \
		"$(WORKBENCH_DIR)/docs/memory-investigation/2026-04-16/jcmd"

termlab: check-intellij termlab-version termlab-memory-artifacts
	$(BAZEL) run //termlab:termlab_run -- $(TERMLAB_WORKSPACE)

termlab-build: check-intellij termlab-version
	$(BAZEL) build //termlab:termlab_run

termlab-test: check-intellij termlab-version
	@echo "→ Running full TermLab test suite"
	@set -e; \
	for target in $(TERMLAB_TEST_TARGETS); do \
		echo ""; \
		echo "--- $$target"; \
		$(BAZEL) run $$target; \
	done

termlab-clean: check-intellij
	$(BAZEL) clean

termlab-installers: check-intellij termlab-version termlab-bootstrap-intellij
	$(BAZEL) run //termlab/build:termlab_installers
	@echo "→ Installer artifacts in $(INTELLIJ_ROOT)/out/termlab/artifacts/"

termlab-installers-fast: check-intellij termlab-version termlab-bootstrap-intellij
	@echo "→ Building installers from existing compiled IDE output"
	@echo "  Run 'Build Project' in IntelliJ first if outputs are stale or missing."
	TERMLAB_REUSE_COMPILED_CLASSES=true $(BAZEL) run //termlab/build:termlab_installers
	@echo "→ Installer artifacts in $(INTELLIJ_ROOT)/out/termlab/artifacts/"

termlab-installers-mac: check-intellij termlab-version termlab-bootstrap-intellij
	cd $(INTELLIJ_ROOT) && TERMLAB_TARGET_OS=mac bash bazel.cmd run //termlab/build:termlab_installers
	@echo "→ Installer artifacts in $(INTELLIJ_ROOT)/out/termlab/artifacts/"

termlab-installers-linux: check-intellij termlab-version termlab-bootstrap-intellij
	cd $(INTELLIJ_ROOT) && TERMLAB_TARGET_OS=linux bash bazel.cmd run //termlab/build:termlab_installers
	@echo "→ Installer artifacts in $(INTELLIJ_ROOT)/out/termlab/artifacts/"

termlab-installers-windows: check-intellij termlab-version termlab-bootstrap-intellij
	cd $(INTELLIJ_ROOT) && TERMLAB_TARGET_OS=windows bash bazel.cmd run //termlab/build:termlab_installers
	@echo "→ Installer artifacts in $(INTELLIJ_ROOT)/out/termlab/artifacts/"

termlab-perf-benchmark: check-intellij
	mkdir -p "$(TERMLAB_PERF_WORKSPACE)"
	bash $(WORKBENCH_DIR)/scripts/perf/benchmark_idle.sh \
		--intellij-root "$(INTELLIJ_ROOT)" \
		--workspace "$(TERMLAB_PERF_WORKSPACE)" \
		--warmup-sec "$(TERMLAB_PERF_WARMUP_SEC)" \
		--sample-sec "$(TERMLAB_PERF_SAMPLE_SEC)" \
		--duration-sec "$(TERMLAB_PERF_DURATION_SEC)" \
		--out "$(TERMLAB_PERF_OUT)"

termlab-perf-budget:
	bash $(WORKBENCH_DIR)/scripts/perf/check_perf_budget.sh \
		--summary "$(TERMLAB_PERF_OUT)/latest_summary.env"
