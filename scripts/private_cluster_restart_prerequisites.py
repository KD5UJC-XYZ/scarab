# SPDX-License-Identifier: Apache-2.0
"""Bounded shell inventory and synthetic prerequisites; no Cardano node is started."""
import copy
from pathlib import Path
from private_cluster import JDK

REQUIRED_TOOLS = ("/bin/sh", "cat", "readlink", "sha256sum", "stat", "sleep",
                  "cardano-node", "cardano-cli", "cardano-testnet", "mkdir", "ln", "rm")

# All traversal occurs only after the relay exited. Reject special files, symlinks,
# oversized trees/paths/depth and totals while traversing, not after unbounded output.
# Shell recursion uses positional parameters so child calls cannot replace parent paths.
INVENTORY = r'''set -eu
LC_ALL=C; export LC_ALL
root=$1; entries=0; files=0; bytes=0
walk() {
  [ "$2" -le 32 ] || exit 21
  for item in "$1"/* "$1"/.[!.]* "$1"/..?*; do
    [ ! -L "$item" ] || exit 22
    [ -e "$item" ] || continue
    entries=$((entries + 1)); [ "$entries" -le 8192 ] || exit 23
    case "$item" in *[!A-Za-z0-9_./-]*) exit 24;; esac
    [ "${#item}" -le 240 ] || exit 25
    if [ -d "$item" ]; then
      walk "$item" "$(( $2 + 1 ))"
    elif [ -f "$item" ]; then
      files=$((files + 1)); [ "$files" -le 4096 ] || exit 26
      size=$(stat -c %s "$item")
      case "$size" in ''|*[!0-9]*) exit 27;; esac
      [ "$size" -le 536870912 ] || exit 28
      bytes=$((bytes + size)); [ "$bytes" -le 536870912 ] || exit 29
      printf '%s\t%s\n' "${item#"$root"/}" "$size"
    else
      exit 30
    fi
  done
}
[ -d "$root" ] && [ ! -L "$root" ] || exit 31
walk "$root" 0
'''

START_SCRIPT = ('umask 077; test ! -e /work/restart-relay.pid || exit 1; cd "$1" || exit; shift; "$@" '
                '>> /work/env/logs/node3/restart-stdout.log 2>> /work/env/logs/node3/restart-stderr.log & '
                'node_pid=$!; printf "%s\\n" "$node_pid" > /work/restart-relay.pid; '
                'printf "%s\\n" "$node_pid" > /work/env/logs/node3/node.pid; wait "$node_pid"')


def synthetic_prerequisites(parent):
    """Use the actual runner adapters against synthetic files and an owned sleep child."""
    r = copy.copy(parent)
    r.name = parent.name + "-preflight"
    r.save = lambda name, value: parent.save("prereq-" + name, value)
    database = "/work/env/node-data/node3/db"
    try:
        r.docker("network", "create", "--internal", r.name)
        r.docker("run", "-d", "--pull=never", "--name", r.name, "--network", r.name,
                 "--cpus=3", "--memory=6g", "--memory-swap=6g", "--pids-limit=384",
                 "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user", "1000:1000", "--read-only",
                 "--tmpfs", "/work:rw,nosuid,nodev,size=2g,uid=1000,gid=1000,mode=0700",
                 "--tmpfs", "/tmp:rw,nosuid,nodev,size=128m,mode=1777",
                 "--entrypoint=/bin/sh", r.image, "-c", "sleep 180")
        r.save("container.md", r.docker("inspect", r.name).stdout)
        for command in REQUIRED_TOOLS:
            r.execute("/bin/sh", "-c", 'command -v "$1"', "required-tool", command)
        r.verify_pidfd_helper()
        r.execute("/bin/sh", "-c", '''set -eu
mkdir -p /work/env/node-data/node3/db/immutable /work/env/logs/node3
printf abc > /work/env/node-data/node3/db/immutable/00000.chunk
printf hidden > /work/env/node-data/node3/db/.hidden
''')
        inventory = r.database_inventory()
        if inventory["files"] != {".hidden": 6, "immutable/00000.chunk": 3}:
            raise ValueError("synthetic complete inventory differs")
        r.save("inventory.md", inventory)
        for target in ("immutable/00000.chunk", "missing"):
            r.execute("ln", "-s", target, database + "/forbidden-link")
            try:
                try:
                    r.database_inventory()
                except RuntimeError:
                    pass
                else:
                    raise ValueError("inventory accepted symlink")
            finally:
                r.execute("rm", database + "/forbidden-link")
        r.execute("/bin/sh", "-c", 'printf oversized > "$1"', "synthetic", database + "/bad name")
        try:
            try:
                r.database_inventory()
            except RuntimeError:
                pass
            else:
                raise ValueError("inventory accepted unsupported name")
        finally:
            r.execute("rm", database + "/bad name")
        # Exercise exact CLI families without creating an environment or starting nodes.
        help_commands = [("cardano-node", "run", "--help"), ("cardano-testnet", "create-env", "--help"),
                         ("cardano-testnet", "cardano", "--help")]
        help_commands += [("cardano-cli", "conway", "query", kind, "--help") for kind in
                          ("tip", "utxo", "ledger-state", "protocol-state", "protocol-parameters")]
        for command in help_commands:
            r.execute(*command, timeout=10)
        # Real detached launch wrapper/PID receipt/log redirections, but a sleep child only.
        r.docker("exec", "-d", r.name, "/bin/sh", "-c", START_SCRIPT, "synthetic-restart",
                 "/work/env", "/bin/sh", "-c", "exec sleep 30")
        def child_ready():
            result = r.execute("cat", "/work/restart-relay.pid", check=False)
            return int(result.stdout.strip()) if result.returncode == 0 and result.stdout.strip().isdigit() else None
        pid = r.wait_for("synthetic-child", child_ready, seconds=5)
        state = r.stat(pid)
        if state is None or r.pid() != pid:
            raise ValueError("synthetic process identity missing")
        r.execute("readlink", "-f", f"/proc/{pid}/exe")
        r.execute("readlink", "-f", f"/proc/{pid}/cwd")
        r.execute("cat", f"/proc/{pid}/cmdline")
        r.execute("/bin/sh", "-c", 'kill -STOP "$1"', "owned-child", str(pid))
        if "State:\tT" not in r.execute("cat", f"/proc/{pid}/status").stdout:
            raise ValueError("synthetic pause not observed")
        r.execute("/bin/sh", "-c", 'kill -CONT "$1"', "owned-child", str(pid))
        from private_cluster_restart import PIDFD
        result = r.execute(PIDFD, str(pid), str(state["startTicks"]))
        r.save("stop.md", result.stdout)
        def exited():
            observed = r.stat(pid)
            return observed is None or observed["state"] == "Z"
        r.wait_for("synthetic-exit", exited, seconds=5)
        # Exercise write/read/capture primitives with fake logs/config, never private material.
        r.write_json("synthetic.json", {"synthetic": True})
        r.save("capture.md", r.read("synthetic.json") + r.read("logs/node3/restart-stdout.log") +
               r.read("logs/node3/restart-stderr.log"))
        repo = str(Path(r.args.scala_repo).resolve())
        result = r.docker("run", "--rm", "--pull=never", "--name", r.name + "-scala",
             "--network=container:" + r.name, "--cpus=1", "--memory=1g", "--memory-swap=1g", "--pids-limit=128",
             "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user", "1000:1000", "--read-only",
             "--tmpfs", "/tmp:size=64m", "-v", repo + ":/work:ro", "-w", "/work", "--entrypoint=/bin/sh", JDK,
             "-c", 'set -eu; command -v cat; command -v java; '
             'java -XX:ActiveProcessorCount=1 -Xmx512m -cp "$(cat app/target/runtime-classpath.txt)" lab.Main --help', timeout=30)
        r.save("jvm.md", result.stdout + result.stderr)
        r.save("result.md", {"passed": True, "cardanoNodeStarted": False, "requiredTools": REQUIRED_TOOLS,
                            "inventory": "bounded-shell-stat", "helpCommands": help_commands})
    finally:
        r.cleanup()
