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
Docker-based preflight and reference scenarios to Nix. The owner authorized
this Nix workflow in the implementing chat session on 2026-11-24.

With direnv and nix-direnv configured, `direnv allow` enables the included `.envrc`.
