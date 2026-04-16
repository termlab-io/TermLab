# Tunnel Manager Plugin Design

**Goal:** Let users create, manage, and activate SSH tunnels (local and remote port forwarding) through a tool window that mirrors the Hosts manager's UX. Tunnels reference hosts from either the SSH plugin's HostStore or `~/.ssh/config` aliases.

**Driving context:** This is TermLab's last core plugin. It depends on the SSH plugin for host definitions, credential resolution, connection infrastructure, and host-key verification. It does NOT depend on having a terminal session open — tunnels have independent lifecycle.

## Plugin structure

Separate plugin at `plugins/tunnels/` with id `com.termlab.tunnels`. Dependencies: `com.termlab.core`, `com.termlab.ssh`, `com.termlab.vault`. Registered as an essential plugin in `TermLabApplicationInfo.xml` and bundled in `TermLabProperties.kt`.

## Data model

### `SshTunnel` record

```java
public record SshTunnel(
    UUID id,
    String label,           // "prod-db-proxy"
    TunnelType type,        // LOCAL or REMOTE
    TunnelHost host,        // which host to tunnel through
    int bindPort,           // local port (LOCAL) or remote port (REMOTE)
    String bindAddress,     // default "localhost"
    String targetHost,      // destination hostname
    int targetPort,         // destination port
    Instant createdAt,
    Instant updatedAt
)
```

### `TunnelType` enum

`LOCAL` (-L): binds a local port and forwards traffic to a remote target through the SSH connection.
`REMOTE` (-R): binds a port on the remote host and forwards traffic back to a local target.

### `TunnelHost` sealed interface

```java
sealed interface TunnelHost permits InternalHost, SshConfigHost {}
record InternalHost(UUID hostId) implements TunnelHost {}
record SshConfigHost(String alias) implements TunnelHost {}
```

`InternalHost` references an `SshHost` in the SSH plugin's `HostStore` by UUID. `SshConfigHost` references a `Host` alias from `~/.ssh/config` — MINA resolves the actual connection details (hostname, port, identity, proxy) from the config file at connect time.

Gson adapter uses `"type": "internal"` / `"type": "ssh_config"` discriminator, same pattern as `SshAuth`.

### `TunnelState` enum

`DISCONNECTED` — saved but not active.
`CONNECTING` — connection in progress (credential resolution, TCP handshake, auth).
`ACTIVE` — port forwarding established and healthy.
`ERROR` — connection failed or tunnel dropped mid-session.

## Persistence

`~/.config/termlab/tunnels.json` with the same versioned-envelope + atomic-write pattern as `ssh-hosts.json`:

```json
{
  "version": 1,
  "tunnels": [
    {
      "id": "uuid",
      "label": "prod-db-proxy",
      "type": "LOCAL",
      "host": { "type": "internal", "hostId": "uuid" },
      "bindPort": 3307,
      "bindAddress": "localhost",
      "targetHost": "db.internal.example.com",
      "targetPort": 3306,
      "createdAt": "2026-04-12T...",
      "updatedAt": "2026-04-12T..."
    }
  ]
}
```

`TunnelStore` is an application service (same pattern as `HostStore`) with `getHosts()`, `addTunnel()`, `removeTunnel()`, `updateTunnel()`, `save()`, `reload()`.

## Connection engine

### `TunnelConnectionManager` (application service)

Owns all active tunnel connections. Maps `UUID → TunnelConnection`.

### `TunnelConnection`

```java
class TunnelConnection implements AutoCloseable {
    ClientSession session;
    SshdSocketAddress boundAddress;
    TunnelState state;
    String errorMessage;
}
```

### Connect flow

1. **Resolve host.** `InternalHost` → `HostStore.findById(hostId)`. `SshConfigHost` → build a synthetic `SshHost` with the alias as hostname, port 22, username from system, `PromptPasswordAuth` as default auth. Configure MINA's `OpenSshHostConfigEntryResolver.INSTANCE` on the client so MINA resolves the real connection details from `~/.ssh/config`.

2. **Resolve credentials.** Same path as SSH terminal sessions: `SshCredentialResolver.resolve()` for vault-saved credentials, `InlineCredentialPromptDialog` for prompt-password or key-file hosts, `SshCredentialPicker` as fallback.

3. **Connect.** `TermLabSshClient.connect(host, credential, new TermLabServerKeyVerifier())` — reuses the SSH plugin's full connect flow including host-key verification (unknown → prompt, mismatch → hard reject).

4. **Start forwarding.** For LOCAL: `session.startLocalPortForwarding(new SshdSocketAddress(bindAddress, bindPort), new SshdSocketAddress(targetHost, targetPort))`. For REMOTE: `session.startRemotePortForwarding(new SshdSocketAddress(bindAddress, bindPort), new SshdSocketAddress(targetHost, targetPort))`.

5. **State → ACTIVE.** Store the `TunnelConnection` handle in `TunnelConnectionManager`.

### Disconnect

Stop forwarding, close the session, state → DISCONNECTED.

### Error handling

Same patterns as the SSH plugin's `SshSessionProvider`:
- `HOST_UNREACHABLE` → error notification + state ERROR
- `AUTH_FAILED` → re-prompt once via picker/inline dialog, then error
- `HOST_KEY_REJECTED` → MITM warning dialog, state ERROR
- `CHANNEL_OPEN_FAILED` / `UNKNOWN` → error notification + state ERROR

### Health monitoring

Each active tunnel spawns a daemon thread (same pattern as `TermLabTerminalEditor.startExitWatcher()`) that watches `session.waitFor(ClientSession.CLOSED)`. When the session closes unexpectedly, the thread fires a disconnect notification on the EDT and sets state → ERROR.

### Session isolation

Each tunnel gets its own `ClientSession`. Tunnel lifetime ≠ terminal lifetime. A user might close a terminal to a host but keep a database tunnel through that same host alive for hours. Independent sessions mean independent lifecycle.

## Tool window UI

### `TunnelsToolWindow`

Right sidebar, below Hosts (higher weight number = lower position). Toolbar with Add (+) and Refresh (⟳) buttons. `JBList<SshTunnel>` with `TunnelCellRenderer`.

### `TunnelCellRenderer`

Two-line layout:
- **Line 1:** status icon + label. Icon: ● green (ACTIVE), ○ gray (DISCONNECTED), ⚠ yellow (ERROR).
- **Line 2:** `L`/`R` prefix + `bindAddress:bindPort → targetHost:targetPort`.

### Interactions

- **Double-click** → toggle connect/disconnect
- **Right-click** → context menu: Connect, Disconnect, Edit, Duplicate, Delete
- **+ toolbar** → `TunnelEditDialog` in add mode
- **⟳ toolbar** → `store.reload()` from disk

### `TunnelEditDialog`

Modal `DialogWrapper`:
- Label (text, required)
- Type (radio: Local / Remote)
- Host (combo: HostStore entries under "Saved Hosts" separator + `~/.ssh/config` aliases under "SSH Config" separator)
- Bind address (text, default "localhost")
- Bind port (spinner, 1-65535, required)
- Target host (text, required)
- Target port (spinner, 1-65535, required)

Validation: label required, host selected, bind port in range, target host non-empty, target port in range.

## `~/.ssh/config` parsing

### `SshConfigParser`

```java
public static List<String> parseHostAliases(Path configPath)
public static List<String> parseHostAliases()  // default ~/.ssh/config
```

Reads `Host` lines, extracts alias tokens, skips wildcards (`*`, `?`, `!`-prefixed), skips `Match` blocks. Returns deduplicated sorted list. Returns empty on missing file or parse error.

At connect time, MINA resolves the full connection details from `~/.ssh/config` via `OpenSshHostConfigEntryResolver.INSTANCE`. We don't reimplement hostname/port/identity/proxy resolution.

## Notifications

- **Tunnel activated** — status icon change in tool window (no balloon).
- **Tunnel failed** — balloon notification with error details + error state in tool window.
- **Tunnel dropped** — balloon notification "Tunnel 'X' disconnected unexpectedly" + error state.
- **Host key unknown / auth** — reuse SSH plugin's `HostKeyPromptDialog` and `InlineCredentialPromptDialog`.

## Search Everywhere

`TunnelsSearchEverywhereContributor` registered via `<searchEverywhereContributor>` in plugin.xml. Tab name "Tunnels", weight 45 (between Actions and Hosts). Selecting a tunnel toggles connect/disconnect. Same pattern as `HostsSearchEverywhereContributor`.

`"TermLabTunnels"` added to `TermLabTabsCustomizationStrategy.ALLOWED_TAB_IDS`.

## Scope

### In scope (v1)

- Tunnel CRUD with JSON persistence at `~/.config/termlab/tunnels.json`
- Local (-L) and Remote (-R) port forwarding
- Tool window with status indicators
- Host picker from HostStore + `~/.ssh/config` aliases
- Full auth flow (vault, key files, passphrase prompts)
- Host key verification via `TermLabServerKeyVerifier`
- Health monitoring + disconnect notifications
- Search Everywhere contributor
- Essential plugin + bundled plugin registration

### Out of scope (v2+)

- Dynamic SOCKS proxy (-D)
- Auto-connect on startup
- Tunnel groups/folders
- Multiple forwarding rules per tunnel
- SSH multiplexing (sharing sessions across tunnels to same host)
- Import from `~/.ssh/config` LocalForward/RemoteForward directives
- Traffic statistics/monitoring
