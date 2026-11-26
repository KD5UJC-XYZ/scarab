### Changed (release image and Nix apps, #1418)

- **The node's JVM no longer writes its memory to disk.** A forging node holds its VRF and KES keys in the heap. The
  release image's entry point is now the launcher `scripts/scarab-jvm`, which the Nix network apps also end in. It
  runs in bash's privileged mode (no `BASH_ENV`, no functions from the environment), sets the core file limit to 0,
  removes `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS` and `JDK_ALTERNATE_VM` from the JVM's environment
  (naming on standard error those it removed), and starts the JVM, named by its path, with no core file on a crash, no
  crash report, no compiler replay file, no heap dump when the heap runs out, no attach (`jcmd`, `jmap`, `jstack`) and
  no performance data file, then execs it, so signals and exit statuses are as before. `SCARAB_HEAP_SIZE` and
  `SCARAB_RUN_PROCESSORS` size the JVM in the image as in the Nix apps. `SCARAB_JVM_DIAGNOSTICS=1` starts the JVM
  with its own defaults and the four variables, for diagnostics on a node that holds no keys. The forging guide's new
  section "Keys in memory" (`docs/running/forging.md`) lists what the launcher does and what stays the host's: where
  `kernel.core_pattern` sends cores, swap, hibernation, `kernel.yama.ptrace_scope` and an account of the node's own.
- **Upgrading:** an image run that set `JAVA_TOOL_OPTIONS` now gets the JVM's default heap, with a one-line notice
  that the variable was dropped. Replace `JAVA_TOOL_OPTIONS=-Xmx3g` with `SCARAB_HEAP_SIZE=3g` and
  `-XX:ActiveProcessorCount=N` with `SCARAB_RUN_PROCESSORS=N`; other JVM options go on the launcher's command line
  (`docker run --entrypoint /opt/scarab/scarab-jvm <image> /opt/java/openjdk/bin/java <options> --sun-misc-unsafe-memory-access=allow -jar /opt/scarab/scarab.jar follow ...`).
- A candidate image verifies only with the launcher's entry point, its JAR, launcher and `/opt/scarab` owned by root
  and not writable by others, and a launcher equal to `scripts/scarab-jvm` at the candidate's authenticated source
  commit.
