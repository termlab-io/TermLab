#!/usr/bin/env python3
"""AMP REST probe — see if the plugin's endpoints return what it expects."""
import json
import os
import sys
import urllib.error
import urllib.request

HELP = """AMP REST probe — verifies the Minecraft Admin plugin's endpoints work against a live AMP panel.

Env vars:
  AMP_URL      AMP panel URL       (default: http://localhost:8080)
  AMP_USERNAME AMP panel username  (required)
  AMP_PASSWORD AMP panel password  (required)
  AMP_INSTANCE Instance friendly name to query (optional; GetInstances still runs)

Usage:
  AMP_USERNAME=admin AMP_PASSWORD=secret AMP_INSTANCE=survival ./scripts/amp-probe.py"""

SEP_HEAVY = "=" * 70
SEP_LIGHT = "-" * 70


def redact_secrets(body: dict) -> dict:
    copy = dict(body)
    if "password" in copy:
        copy["password"] = "<redacted>"
    if "SESSIONID" in copy:
        copy["SESSIONID"] = "<redacted>"
    return copy


def post_json(url: str, body: dict) -> tuple:
    """Returns (status, headers_as_dict, body_text). Raises for connection errors."""
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            status = resp.status
            headers = dict(resp.headers)
            body_text = resp.read().decode("utf-8")
            return status, headers, body_text
    except urllib.error.HTTPError as e:
        status = e.code
        headers = dict(e.headers) if e.headers else {}
        try:
            body_text = e.read().decode("utf-8")
        except Exception:
            body_text = "<could not read error body>"
        return status, headers, body_text


def print_request_section(step_label: str, url: str, body: dict) -> None:
    print(SEP_HEAVY)
    print(f"{step_label} POST {url}")
    print(SEP_LIGHT)
    print("request body:")
    redacted = redact_secrets(body)
    print("  " + json.dumps(redacted, indent=2).replace("\n", "\n  "))


def print_response_section(status: int, headers: dict, body_text: str,
                            redact_session: bool = False) -> None:
    print(SEP_LIGHT)
    print(f"response status: {status}")
    print("response headers:")
    for k, v in sorted(headers.items()):
        print(f"  {k}: {v}")
    print(SEP_LIGHT)

    content_type = headers.get("Content-Type", headers.get("content-type", ""))
    if "json" not in content_type:
        print("WARNING: Content-Type does not contain 'json'")
        print("response body (raw):")
        print("  " + body_text)
        print(SEP_HEAVY)
        return

    try:
        parsed = json.loads(body_text)
        if redact_session and isinstance(parsed, dict) and "sessionID" in parsed:
            session_id = parsed["sessionID"]
            truncated = session_id[:8] + "…"
            display = dict(parsed)
            display["sessionID"] = truncated + "  (truncated for safety)"
        else:
            display = parsed
        pretty = json.dumps(display, indent=2)
        print("response body (prettyprinted):")
        print("  " + pretty.replace("\n", "\n  "))
    except json.JSONDecodeError:
        print("response body (raw — could not parse as JSON):")
        print("  " + body_text)

    print(SEP_HEAVY)


def check_status(status: int, body_text: str, api_call: str) -> None:
    if status < 200 or status >= 300:
        print(f"\nerror: {api_call} returned non-2xx status {status}")
        print(f"body: {body_text}")
        sys.exit(1)


def do_login(base_url: str, username: str, password: str) -> str:
    url = base_url + "/API/Core/Login"
    body = {
        "username": username,
        "password": password,
        "token": "",
        "rememberMe": False,
    }
    print_request_section("[1/4]", url, body)
    try:
        status, headers, body_text = post_json(url, body)
    except Exception as e:
        print(f"\nerror: connection to {url} failed")
        print(f"  {type(e).__name__}: {e}")
        print(f"  hint: can you reach the AMP URL from this machine?")
        print(f"  try: curl -v {base_url}/API/")
        sys.exit(1)

    print_response_section(status, headers, body_text, redact_session=True)
    check_status(status, body_text, "Core/Login")

    try:
        parsed = json.loads(body_text)
    except json.JSONDecodeError:
        print("error: Core/Login returned non-JSON body")
        sys.exit(1)

    if not parsed.get("success", False):
        print("error: AMP rejected the login — check username and password")
        sys.exit(1)

    session_id = parsed.get("sessionID", "")
    if not session_id:
        print("error: Core/Login success=true but sessionID is missing or empty")
        sys.exit(1)

    return session_id


def do_get_instances(base_url: str, session_id: str) -> list:
    url = base_url + "/API/Core/GetInstances"
    body = {"SESSIONID": session_id}
    print_request_section("[2/4]", url, body)
    try:
        status, headers, body_text = post_json(url, body)
    except Exception as e:
        print(f"\nerror: connection to {url} failed")
        print(f"  {type(e).__name__}: {e}")
        print(f"  hint: can you reach the AMP URL from this machine?")
        print(f"  try: curl -v {base_url}/API/")
        sys.exit(1)

    print_response_section(status, headers, body_text)
    check_status(status, body_text, "Core/GetInstances")

    try:
        parsed = json.loads(body_text)
    except json.JSONDecodeError:
        print("error: Core/GetInstances returned non-JSON body")
        sys.exit(1)

    # Walk the result array of groups
    groups = parsed.get("result", [])
    if not isinstance(groups, list):
        print("warning: GetInstances result is not a list — AMP may be a game instance panel, not ADS")
        return []

    all_instances = []
    for group in groups:
        available = group.get("AvailableInstances", [])
        if not isinstance(available, list):
            continue
        for inst in available:
            all_instances.append(inst)

    print(f"\n--- GetInstances summary: {len(all_instances)} instance(s) across {len(groups)} group(s) ---")
    for inst in all_instances:
        friendly = inst.get("FriendlyName", "<no FriendlyName>")
        iname = inst.get("InstanceName", "<no InstanceName>")
        running = inst.get("Running", "?")
        app_state = inst.get("AppState", "?")
        metrics = inst.get("Metrics", {})
        cpu_raw = None
        ram_used = None
        ram_max = None
        if isinstance(metrics, dict):
            cpu_block = metrics.get("CPU Usage", {})
            if isinstance(cpu_block, dict):
                cpu_raw = cpu_block.get("RawValue")
            mem_block = metrics.get("Memory Usage", {})
            if isinstance(mem_block, dict):
                ram_used = mem_block.get("RawValue")
                ram_max = mem_block.get("MaxValue")
        metrics_summary = f"cpu={cpu_raw} ramUsed={ram_used} ramMax={ram_max}"
        print(f"  FriendlyName={friendly!r}  InstanceName={iname!r}  Running={running}  AppState={app_state}  Metrics={{{metrics_summary}}}")

    return all_instances


def find_target(instances: list, target_name: str) -> None:
    if not target_name:
        print("\nwarning: AMP_INSTANCE not set — skipping instance lookup. Set AMP_INSTANCE to see target matching.")
        print("  The #1 cause of 'plugin can\'t find my instance' is a mismatch between the profile's")
        print("  ampInstanceName field and what AMP calls the instance (FriendlyName or InstanceName above).")
        return

    for inst in instances:
        friendly = inst.get("FriendlyName", "")
        iname = inst.get("InstanceName", "")
        if target_name == friendly or target_name == iname:
            app_state = inst.get("AppState", "?")
            running = inst.get("Running", "?")
            print(f"\n✓ FOUND instance '{target_name}' — AppState={app_state} Running={running}")
            return

    friendly_names = [inst.get("FriendlyName", "<no FriendlyName>") for inst in instances]
    print(f"\n✗ instance '{target_name}' NOT FOUND.")
    print(f"  AMP knows about these instances: {friendly_names}")
    print(f"  This is the #1 thing to copy into the plugin profile's ampInstanceName field.")
    print(f"  Make sure the value exactly matches a FriendlyName or InstanceName shown above.")


def do_get_status(base_url: str, session_id: str) -> None:
    url = base_url + "/API/Core/GetStatus"
    body = {"SESSIONID": session_id}
    print_request_section("[3/4]", url, body)
    try:
        status, headers, body_text = post_json(url, body)
    except Exception as e:
        print(f"\nerror: connection to {url} failed")
        print(f"  {type(e).__name__}: {e}")
        print(f"  hint: can you reach the AMP URL from this machine?")
        print(f"  try: curl -v {base_url}/API/")
        sys.exit(1)

    print_response_section(status, headers, body_text)

    if status < 200 or status >= 300:
        print(f"note: Core/GetStatus returned {status} — this is normal if you're pointing at ADS (the management panel)")
        print("      If GetInstances worked, that's the expected behavior for ADS.")
        return

    print("note: Core/GetStatus returned a response — this suggests you may be pointing at a game-instance")
    print("      panel directly rather than the ADS. If so, GetInstances may have returned no instances.")


def do_get_updates(base_url: str, session_id: str, instance_name: str) -> None:
    url = base_url + "/API/Core/GetUpdates"
    body = {"SESSIONID": session_id, "InstanceName": instance_name}
    print_request_section("[4/4]", url, body)
    try:
        status, headers, body_text = post_json(url, body)
    except Exception as e:
        print(f"\nerror: connection to {url} failed")
        print(f"  {type(e).__name__}: {e}")
        print(f"  hint: can you reach the AMP URL from this machine?")
        print(f"  try: curl -v {base_url}/API/")
        sys.exit(1)

    print_response_section(status, headers, body_text)
    check_status(status, body_text, "Core/GetUpdates")

    try:
        parsed = json.loads(body_text)
    except json.JSONDecodeError:
        print("error: Core/GetUpdates returned non-JSON body")
        sys.exit(1)

    result = parsed.get("result", {})
    console_entries = []
    if isinstance(result, dict):
        console_entries = result.get("ConsoleEntries", [])
    elif isinstance(result, list):
        console_entries = result

    print(f"\n--- GetUpdates: {len(console_entries)} ConsoleEntries ---")
    for entry in console_entries:
        if isinstance(entry, dict):
            contents = entry.get("Contents", "")
            print(f"  {contents}")
        else:
            print(f"  {entry}")


def main():
    base_url = os.environ.get("AMP_URL", "http://localhost:8080").rstrip("/")
    username = os.environ.get("AMP_USERNAME")
    password = os.environ.get("AMP_PASSWORD")
    instance = os.environ.get("AMP_INSTANCE", "")

    if not username or not password:
        print("error: AMP_USERNAME and AMP_PASSWORD are required env vars", file=sys.stderr)
        print(HELP, file=sys.stderr)
        sys.exit(2)

    print(f"AMP probe target: {base_url}")
    if instance:
        print(f"Target instance:  {instance!r}")
    else:
        print("Target instance:  (not set — GetUpdates will be skipped)")
    print()

    # Step 1: Login
    session_id = do_login(base_url, username, password)

    # Step 2: GetInstances
    instances = do_get_instances(base_url, session_id)
    find_target(instances, instance)

    # Step 3: GetStatus (instance panel diagnostic)
    do_get_status(base_url, session_id)

    # Step 4: GetUpdates (only if instance was provided)
    if instance:
        do_get_updates(base_url, session_id, instance)
    else:
        print()
        print(f"[4/4] skipping Core/GetUpdates — AMP_INSTANCE not set")

    print("\n✓ probe complete")


if __name__ == "__main__":
    if "-h" in sys.argv or "--help" in sys.argv:
        print(HELP)
        sys.exit(0)
    main()
