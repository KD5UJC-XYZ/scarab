<!-- SPDX-License-Identifier: Apache-2.0 -->
# Starting the node's JVM

Part of [Running the node and the devnet scenarios](../running.md).

The release image and the Nix network apps ([nix.md](../nix.md)) start every node (relay, follower or block producer)
through one launcher, `scripts/scarab-jvm`. It keeps the JVM from writing its memory to disk, which matters for a
forging node ([forging.md](forging.md#keys-in-memory) says why, and what stays the host's), and it is where the heap,
the processor count and diagnostics are set. The `java ... -cp "$(cat node/target/runtime-classpath.txt)"` commands
elsewhere in this guide start the node from a class path in the JDK image: that is a developer's path, which takes JVM
options on its own command line and does none of what follows.

## The calling form

```text
scarab-jvm <absolute path of java> <java arguments>
```

The image's entry point is the launcher, the base image's `java`, the flag that lets Scala 3's lazy vals use
`sun.misc.Unsafe` without Java 25's warning, and `-jar /opt/scarab/scarab.jar` (`candidate_oci.ENTRYPOINT` and
`docker/candidate.Dockerfile` are the exact text), so `docker run <image> follow ...` puts the node's arguments after
the JAR, where they reach the node, not the JVM. The Nix apps call the launcher with their JDK's `java`, the class
path and the arguments of `follow`; they also set `MALLOC_ARENA_MAX=2` unless it is given, as the image does.

The launcher runs in bash's privileged mode (`bash -p`), which reads no `BASH_ENV` or `ENV` file and takes no function
from the environment, and it ends in `exec <java>`: the JVM is the process the container or the shell started, with
its signals and its exit status, so `docker stop` and Ctrl-C reach the node directly.

## Environment variables

It reads these, in either mode. The launcher checks the form of the value; the JVM can still refuse one it passes
(`SCARAB_HEAP_SIZE=999999999g` starts no JVM: `Could not reserve enough space for ... object heap`, exit 1).

| Variable | Accepted by the launcher | Unset | Set but empty | Effect |
|---|---|---|---|---|
| `SCARAB_HEAP_SIZE` | decimal digits not starting with 0, with an optional `k`, `m` or `g` in either case (`3g`, `3072m`) | the JVM's own maximum heap (below); the Nix apps give `3g` | refused; the Nix apps give `3g` | `-Xmx<value>` |
| `SCARAB_RUN_PROCESSORS` | decimal digits not starting with 0 | the processors the JVM sees; the Nix apps give `4` | refused; the Nix apps give `4` | `-XX:ActiveProcessorCount=<value>` |
| `SCARAB_JVM_DIAGNOSTICS` | `1` or `0` | off | off | `1` turns [diagnostics](#diagnostics) on |

The JVM's own maximum heap, without `SCARAB_HEAP_SIZE` or an `-Xmx` argument, is a quarter of the memory it sees: a
container's memory limit, else the host's memory. Below 512 MiB the JVM's minimums take over: in the pinned JRE a
128 MiB limit gave a 64 MiB heap, and 256 to 512 MiB about 128 MiB; 1 GiB gave 256 MiB and 4 GiB 1 GiB.

Unless diagnostics are on, it removes these from the JVM's environment:

| Variable | Why |
|---|---|
| `JAVA_TOOL_OPTIONS` | the JVM reads it before its command line: options there (`OnError`, `HeapDumpPath`, a recording started at launch) could write memory whatever the options below say |
| `JDK_JAVA_OPTIONS` | `java` puts it before its command line, as above |
| `_JAVA_OPTIONS` | the JVM reads it after its command line, so it overrides the options below |
| `JDK_ALTERNATE_VM` | it selects the VM, the directory `java` loads `libjvm.so` from, instead of the JRE's own |

The environment is otherwise trusted: `PATH` and the native loader's inputs (`LD_PRELOAD`, `LD_LIBRARY_PATH`,
`LD_AUDIT`) are read before the launcher runs or by the programs it starts, and it cannot defend against them.
Whoever sets the container's environment controls the process.

## What it sets

Unless diagnostics are on, it sets the core file limit to 0, soft and hard, whatever Docker, systemd or the shell gave
it, and starts the JVM with these options, after the sizing options and before its own arguments:

| Option | What it stops |
|---|---|
| `-XX:-CreateCoredumpOnCrash` | a core file when the JVM crashes on a fault it catches (`SIGSEGV` in a native library, for example). The JVM does not catch `SIGABRT`, from a native library's `abort()` or from another process: the core file limit of 0 stops that core, except that a `kernel.core_pattern` that pipes cores to a program gets the dump whatever the limit ([forging.md](forging.md#keys-in-memory)) |
| `-XX:ErrorFile=/dev/null`, `-XX:-ErrorFileToStdout`, `-XX:-ErrorFileToStderr` | the crash report `hs_err_pid<pid>.log`, which holds the registers, the top of the stack and the contents of an array or object a register points at. The summary on standard output, with the signal and the frame that failed, remains. A JVM that cannot open its error file writes the report to its working directory, else to the temporary directory, else to standard output; `/dev/null` exists in every OCI container and on a Linux host |
| `-XX:-DumpReplayDataOnError` | the replay file `replay_pid<pid>.log` that a crash of a JIT compiler thread writes, with values of static fields |
| `-XX:-HeapDumpOnOutOfMemoryError` | a heap dump when the heap runs out. It is off by default too |
| `-XX:+DisableAttachMechanism` | HotSpot's attach mechanism, which its tools use: `jcmd`, `jmap` and `jstack` from another process, so a heap dump on request, a JFR recording started from outside and heap dump options set in the running JVM. It does not stop a debugger or a reader of `/proc/<pid>/mem` |
| `-XX:+PerfDisableSharedMem` | the performance data file `/tmp/hsperfdata_<user>/<pid>`, through which `jps` and `jstat` find the JVM and read its counters; the counters, not a copy of the heap, stay in the JVM's memory |

The node writes no heap dump or JFR recording of its own: `--stall-dump` writes the runtime's fiber snapshot to a file
and `kill -QUIT` prints a thread dump on standard output, both stack traces rather than the contents of the heap.
Those two, `--memory-report-seconds` and the metrics need no attach.

## Diagnostics

With `SCARAB_JVM_DIAGNOSTICS=1` the launcher still checks and passes the sizing variables and its own arguments, but
passes none of the eight options above, removes none of the four variables, leaves the core file limit as it found
it, and prints:

```text
scarab-jvm: memory diagnostics enabled: do not use on a forging node (core files, crash reports, heap dumps and attach are the JVM's defaults, with JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS, _JAVA_OPTIONS and JDK_ALTERNATE_VM as given)
```

The JVM then behaves as its own defaults, the options it is given and the host make it: what a crash leaves (a report,
a core file where the core file limit and `kernel.core_pattern` allow one) and which tools work (attach, `jps` and
`jstat`) depend on the options in effect, the user the tools run as and the paths the JVM can write. Any process of
the node's user, and root, can attach and make the JVM write a heap dump. Run diagnostics only on a separate node that
has never loaded keys, such as a relay with no key files; a node started with `--start-as-non-producing-node` has read
its key files and is not one ([forging.md](forging.md#keys-in-memory)).

The release image's base is a JRE, with no `jcmd` in it: run `jcmd` from a JDK image in the node's process namespace,
or on the host as the node's user.

```sh
docker run --name node -e SCARAB_JVM_DIAGNOSTICS=1 -e JAVA_TOOL_OPTIONS=-XX:NativeMemoryTracking=summary \
  -v <data dir>:/data <image> follow --config /data/config/config.json --topology /data/config/topology.json
docker run --rm --pid container:node --user 10001:10001 --entrypoint jcmd scarab-jdk:local 1 VM.native_memory summary
```

## Other JVM options

The launcher's own arguments are trusted: they follow its options and can override them. On the launcher's command
line, add only sizing and collector options (`-Xms`, `-Xmx`, `-XX:+UseG1GC`, `-XX:MaxGCPauseMillis` and the like).
On a node that has loaded keys, never add an option that writes or exposes memory: a heap dump or its path, an error
file, a flight recording to a file (its heap and object samples), an agent (`-javaagent`, `-agentlib`, `-agentpath`,
a debugger), attach, native memory tracking's detail dumps, or an options file (`@file`, `-XX:Flags=`,
`-XX:VMOptionsFile=`) that could carry any of these. Memory diagnostics belong on a separate node that has never
loaded keys ([above](#diagnostics)).

The worked example is the collector settings for a host with one or two CPUs (a fixed 3 GiB heap, G1 and a 20 ms pause
goal; [configuration.md](configuration.md#preprod) has the measurements behind them). Name the collector: the JVM
picks G1 by itself only when it sees at least two processors and at least 1,792 MiB of memory (measured in the pinned
JRE on one CPU: `-XX:ActiveProcessorCount=2` gave G1 under a 1,792 MiB limit and the serial collector under 1,700 MiB,
and one processor the serial collector under either). The node's data directory, with the configuration and the
genesis files it names, must be writable by the image's user, `10001:10001`.

`docker run`:

```sh
docker run --entrypoint /opt/scarab/scarab-jvm -v <data dir>:/data <image> /opt/java/openjdk/bin/java \
  -Xms3g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=20 --sun-misc-unsafe-memory-access=allow -jar /opt/scarab/scarab.jar \
  follow --config /data/config/config.json --topology /data/config/topology.json --db /data/db
```

A compose file (`entrypoint` replaces the image's, `command` follows it; the directory `data` beside the file holds
`config/` and `db/`):

```yaml
services:
  node:
    image: <image>
    volumes:
      - ./data:/data
    entrypoint: ["/opt/scarab/scarab-jvm", "/opt/java/openjdk/bin/java", "-Xms3g", "-Xmx3g", "-XX:+UseG1GC",
                 "-XX:MaxGCPauseMillis=20", "--sun-misc-unsafe-memory-access=allow", "-jar", "/opt/scarab/scarab.jar"]
    command: ["follow", "--config", "/data/config/config.json", "--topology", "/data/config/topology.json",
              "--db", "/data/db"]
    stop_grace_period: 2m
```

The Nix apps take only `SCARAB_HEAP_SIZE` and `SCARAB_RUN_PROCESSORS` (and `SCARAB_JVM_DIAGNOSTICS`). For other
options, call the launcher from the Nix shell ([nix.md](../nix.md)) in the checkout, after building its class path,
with the arguments the app would give `follow` (its configuration, database and socket under the network's
directory, and the query port):

```sh
bash scripts/sbtw node/compile node/runtimeClasspathFile
MALLOC_ARENA_MAX=2 bash -p scripts/scarab-jvm "$JAVA_HOME/bin/java" -Xms3g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=20 \
  --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED \
  -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main follow \
  --config "$D/preprod/config/config.json" --topology "$D/preprod/config/topology.json" \
  --db "$D/preprod/db" --local-socket "$D/preprod/node.socket" --query-listen 7072
```

(`D` is the apps' `SCARAB_DATA_DIR`, and the configuration is the one the app downloaded.)

## Exit statuses

The launcher's own failures and the node's can share a number; the line on standard error tells them apart.

| Status | When | Standard error |
|---|---|---|
| 2 | a sizing or diagnostics variable the launcher does not accept, or a path of `java` that is not absolute; the JVM does not start | one of the lines below |
| 1 | the core file limit could not be set; the JVM does not start | bash's `.../scarab-jvm: line N: ulimit: core file size: cannot modify limit: ...` |
| 126, 127 | the path of `java` is not executable, or does not exist | bash's `.../scarab-jvm: line N: <path>: Permission denied`, or `... No such file or directory` |
| any other, 1 and 2 included | the JVM's or the node's own, as [troubleshooting.md](troubleshooting.md#failures-that-stop-the-node-154) lists them | the JVM's message, or the node's `error` or `fatal` event |
| 143 | the usual status after `SIGTERM` (`docker stop`), once the node has closed down | none |
| 137 | `SIGKILL`: what `docker stop` sends when its timeout (10 s unless `-t` says otherwise) runs out first | none |

The launcher's lines, exactly:

```text
scarab-jvm: the first argument must be the absolute path of java
scarab-jvm: SCARAB_HEAP_SIZE must be a heap size such as 3g or 3072m
scarab-jvm: SCARAB_RUN_PROCESSORS must be a whole number of processors
scarab-jvm: SCARAB_JVM_DIAGNOSTICS must be 1, 0 or unset
scarab-jvm: dropped <names> from the JVM's environment; SCARAB_JVM_DIAGNOSTICS=1 keeps them (never on a forging node)
```

The last is a notice, not a failure: `<names>` lists, space-separated, each of the four variables that was set.

The Nix apps also stop with 2, before the launcher, in a checkout without `scripts/scarab-jvm`.

## Upgrading from an image before the launcher

> An image that started `java` directly took JVM options from `JAVA_TOOL_OPTIONS` (or `JDK_JAVA_OPTIONS`,
> `_JAVA_OPTIONS`). The launcher removes them and names them on standard error, so if nothing is changed the node
> starts with the JVM's own maximum heap ([above](#environment-variables)) and the processors the JVM sees, and the
> JDK's tools no longer reach it.
>
> | Before | Now |
> |---|---|
> | `JAVA_TOOL_OPTIONS=-Xmx3g` | `SCARAB_HEAP_SIZE=3g` |
> | `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=N` | `SCARAB_RUN_PROCESSORS=N` |
> | collector options in those variables | the launcher's arguments ([other JVM options](#other-jvm-options)) |
> | any other option in those variables | on a node that has loaded keys, none; on a separate node that never has, diagnostics |
> | `jcmd`, `jmap`, `jstack` (attach) or `jps`, `jstat` (performance data) | diagnostics on a separate node that has never loaded keys, with the tools from a JDK image ([diagnostics](#diagnostics)) |
> | `docker run --entrypoint java <image> ...` | `docker run --entrypoint /opt/scarab/scarab-jvm <image> /opt/java/openjdk/bin/java <options> --sun-misc-unsafe-memory-access=allow -jar /opt/scarab/scarab.jar <arguments of follow>`, which keeps the launcher's settings |
> | `SCARAB_CORE_LIMIT` (read only by a review branch of the Nix apps) | ignored: the core file limit is 0, or the inherited one with diagnostics |
>
> The Nix apps' Scala build, before the launcher, still gets the caller's `JAVA_TOOL_OPTIONS` and the like; and the
> apps need a checkout that has `scripts/scarab-jvm`.
