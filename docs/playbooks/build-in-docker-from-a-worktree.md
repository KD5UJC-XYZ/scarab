# Build in Docker from a worktree

No host JDK or sbt ([ADR 0004](../adr/0004-docker-only-build.md)). A linked worktree's `.git` file points outside the mounted checkout, so mount the common Git metadata read-only at its original path.

```sh
docker build -f docker/jdk.Dockerfile -t scarab-jdk:local .
scarab_git_metadata="$(git rev-parse --path-format=absolute --git-common-dir)"
scarab_cache="$(dirname "$scarab_git_metadata")/.cache"
docker run --rm -v "$PWD":/work -v "$scarab_cache":/work/.cache \
  -v "$scarab_git_metadata:$scarab_git_metadata:ro" \
  -w /work --user "$(id -u):$(id -g)" -e HOME=/work/.cache/home \
  scarab-jdk:local bash scripts/sbtw check
```

The cache line applies in a linked worktree, whose own `.cache` is an empty mount point: mount the main checkout's cache and never copy jars into the worktree. In a main checkout drop that line.

For many small edits keep one container of your own, with its own sbt socket:

```sh
docker run -d --name mine-sbt -v "$PWD":/work -v "$scarab_cache":/work/.cache \
  -v "$scarab_git_metadata:$scarab_git_metadata:ro" -w /work \
  --user "$(id -u):$(id -g)" -e HOME=/work/.cache/home \
  -e SBT_GLOBAL_BASE=/tmp/sbt-global scarab-jdk:local tail -f /dev/null
```

Start sbt once inside it with the server enabled (a copy of `scripts/sbtw` kept outside the repository and without `-Dsbt.server.autostart=false`), then send commands:

```sh
docker exec mine-sbt <launcher> --client "ledger/testOnly xyz.kd5ujc.scarab.ledger.*"
docker rm -f mine-sbt
```

`<launcher>` is that copy, at a path the container can see. Rules: your own container name and `SBT_GLOBAL_BASE`; never recompile under a live devnet (`scripts/devnet.py` runs frozen copies of the classes); never kill by pattern, kill by PID or remove your container; remove the container when finished. `bash scripts/sbtw node/runtimeClasspathFile` writes the classpath file the scenarios use.
