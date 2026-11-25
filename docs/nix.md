# Nix development

The flake provides a Docker-free development shell on x86_64 Linux. Its
flake-parts modules separate the shared toolchain in `flake/` from the shell
and formatter in `perSystem/`. `flake.lock` pins the environment inputs.

```sh
nix develop
java -version
bash scripts/sbtw check
```

The shell supplies JDK 25, sbt, Git, curl, Python 3 and the Nix formatter. The
sbt package uses the same JDK as `JAVA_HOME`. Scarab's existing launcher keeps
its pinned sbt version, repository configuration, memory settings and dependency
cache. Scala remains pinned by `build.sbt`. Maven dependencies need network
access on the first build.

```sh
nix flake check
nix fmt -- --fail-on-change
```

These Nix checks validate the environment and Nix formatting; run the Scala
`check` separately as shown above. The flake supplies a development environment,
not a sandboxed application package. It does not run Docker or convert the
Docker-based preflight and reference scenarios to Nix: `scripts/preflight --check`
and the reference scenarios still need Docker
([CONTRIBUTING.md](../CONTRIBUTING.md#build-docker-or-the-nix-shell)).

With direnv and nix-direnv configured, `direnv allow` enables the included `.envrc`.

## Run public networks

From a writable Scarab checkout:

```sh
nix run .#preview
nix run .#preprod
nix run .#mainnet
```

Each app downloads and caches its official configuration on first use, builds
the node through `scripts/sbtw`, then runs `follow` directly with the pinned JDK.
The build lock serializes wrapper builds in the same checkout; it is released
before the node starts. The three networks can run at the same time.

| App | Query port | State directory beneath `SCARAB_DATA_DIR` |
|---|---|---|
| `.#preview` | 7071 | `preview` |
| `.#preprod` | 7072 | `preprod` |
| `.#mainnet` | 7073 | `mainnet` |

`SCARAB_DATA_DIR` defaults to `/tmp/scarab`. Each network directory contains
`config/`, `db/` and `node.socket`. The data directory and the network directory
must belong to you, since the app reads its configuration back from them;
otherwise it stops and asks for a `SCARAB_DATA_DIR` of your own. It removes
other users' write access to the data directory and makes the network directory
private. While a node runs, a second start on the same network directory is
refused, since the node would remove the first one's socket: use another
`SCARAB_DATA_DIR`, or pass both `--db` and `--local-socket`. Node-to-node and
separate metrics listeners are off unless explicitly enabled. Configurations are
downloaded over HTTPS from the official Cardano environment endpoint documented
in [network configuration](running/configuration.md); referenced checkpoints and
peer snapshots are included. Scarab performs its existing genesis hash checks.
Downloads publish a complete configuration directory atomically, marked with a
`.complete` file, and reuse it on later starts. A download that is not a JSON
object is refused, and so is an existing `config/` without the marker (for
example one fetched by hand at the same path): remove it, or name it with
`SCARAB_CONFIG_DIR` to use it as it is. To refresh a default configuration, stop
the corresponding node and remove only its `config/` directory; the next start
downloads it again.

Additional arguments are passed to `follow` with their argument boundaries
preserved. Explicit `--config`, `--topology`, `--db`, `--local-socket` and
`--query-listen`/`--query-port` replace the respective wrapper defaults.

```sh
nix run .#preview -- --seconds 30
nix run .#preprod -- --query-listen off --seconds 30
SCARAB_DATA_DIR=/srv/scarab nix run .#mainnet
SCARAB_CONFIG_DIR=/path/to/config nix run .#preview
nix run .#mainnet -- --help
```

`SCARAB_CONFIG_DIR` selects existing configuration files without downloading.
`SCARAB_SOURCE_DIR` selects a writable checkout when running elsewhere. The
apps use a 3 GiB heap, four active processors and `MALLOC_ARENA_MAX=2` by default;
set `SCARAB_HEAP_SIZE`, `SCARAB_RUN_PROCESSORS` or `MALLOC_ARENA_MAX` to override
these. `SCARAB_QUERY_PORT` overrides the selected network's default query port.
Ctrl-C reaches the node directly. Help creates no run data and performs no
Scala build or configuration download.
