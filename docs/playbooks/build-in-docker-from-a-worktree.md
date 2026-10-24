# Build in Docker from a worktree

No host JDK or sbt ([ADR 0004](../adr/0004-docker-only-build.md)); never run `java` or `sbt` on the host. Other playbooks write `sbtw ...`: it is the function below. A linked worktree's `.git` file points outside the mounted checkout, so the common Git metadata is mounted read-only at its original path, and the main checkout's dependency cache is mounted at `/work/.cache` (never copy jars into a worktree).

```sh
docker build -f docker/jdk.Dockerfile -t scarab-jdk:local .
scarab_git_metadata="$(git rev-parse --path-format=absolute --git-common-dir)"
scarab_cache="$(dirname "$scarab_git_metadata")/.cache"
sbtw() {
  docker run --rm -v "$PWD":/work -v "$scarab_cache":/work/.cache \
    -v "$scarab_git_metadata:$scarab_git_metadata:ro" -w /work \
    --user "$(id -u):$(id -g)" -e HOME=/work/.cache/home \
    scarab-jdk:local bash scripts/sbtw "$@"
}
sbtw check
```

For many small edits keep one container of your own, with its own sbt socket:

```sh
docker run -d --name mine-sbt -v "$PWD":/work -v "$scarab_cache":/work/.cache \
  -v "$scarab_git_metadata:$scarab_git_metadata:ro" -w /work \
  --user "$(id -u):$(id -g)" -e HOME=/work/.cache/home \
  -e SBT_GLOBAL_BASE=/tmp/sbt-global scarab-jdk:local tail -f /dev/null
```

Start sbt once inside it with the server enabled (a copy of `scripts/sbtw` kept outside the repository and without `-Dsbt.server.autostart=false`), then send commands and finish:

```sh
docker exec mine-sbt <launcher> --client "ledger/testOnly xyz.kd5ujc.scarab.ledger.*"
docker rm -f mine-sbt
```

`<launcher>` is that copy, at a path the container can see. Rules: your own container name and `SBT_GLOBAL_BASE`; never recompile under a live devnet (`scripts/devnet.py` runs frozen copies of the classes); never kill by pattern, kill by PID or remove your container; remove the container when finished. `sbtw node/runtimeClasspathFile` writes the classpath file the scenarios use.
