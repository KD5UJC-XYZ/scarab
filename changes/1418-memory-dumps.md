### Changed (release image and Nix apps, #1418)

- **The node's JVM no longer writes its memory to disk.** A forging node holds its VRF and KES keys in the heap. The
  release image's entry point is now the launcher `scripts/scarab-jvm`, which the Nix network apps also end in. It
  sets the core file limit to 0, removes `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` and `_JAVA_OPTIONS` from the JVM's
  environment (naming on standard error those it removed), and starts the JVM with no core file on a crash, no crash
  report, no compiler replay file, no heap dump when the heap runs out, no attach (`jcmd`, `jmap`, `jstack`) and no
  performance data file, then execs it, so signals and exit statuses are as before. `SCARAB_HEAP_SIZE` and
  `SCARAB_RUN_PROCESSORS` size the JVM in the image as in the Nix apps. `SCARAB_JVM_DIAGNOSTICS=1` starts the JVM
  with its own defaults and the three variables, for diagnostics on a node that holds no keys. The forging guide's new
  section "Keys in memory" (`docs/running/forging.md`) lists what the launcher does and what stays the host's: where
  `kernel.core_pattern` sends cores, swap, hibernation, `kernel.yama.ptrace_scope` and an account of the node's own.
- A candidate image verifies only with the launcher's entry point and a launcher equal to `scripts/scarab-jvm`.
