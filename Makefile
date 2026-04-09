# Conch Terminal Workstation — Build & Run
#
# Usage:
#   make conch       — build and run Conch
#   make conch-build — build only (no run)
#   make conch-clean — clean Conch build artifacts
#   make idea        — build and run IntelliJ IDEA CE (for reference)

BAZEL := bash bazel.cmd

.PHONY: conch conch-build conch-clean idea

# Build and run Conch
conch:
	$(BAZEL) run //conch:conch_run --

# Build Conch without running
conch-build:
	$(BAZEL) build //conch:conch_run

# Clean Conch build artifacts
conch-clean:
	$(BAZEL) clean

# Build and run IntelliJ IDEA CE (for comparison/reference)
idea:
	$(BAZEL) run :main_run --
