### Changed (release image and Nix apps, #1418)

- **The JVM of the release image and of the Nix network apps no longer writes its memory to disk.** A forging node
  holds its VRF and KES keys in the heap, so the image's entry point and the Nix apps start the JVM with
  `-XX:-CreateCoredumpOnCrash` (no core file on a crash), `-XX:ErrorFile=/dev/null` (no `hs_err` crash report, which
  prints the arrays and objects the registers point at; the summary on standard output remains),
  `-XX:-HeapDumpOnOutOfMemoryError` (also when `JAVA_TOOL_OPTIONS` or `JDK_JAVA_OPTIONS` asks for a heap dump) and
  `-XX:+DisableAttachMechanism` (no `jcmd`, `jmap` or `jstack`), and the Nix apps set a core file limit of 0
  (`SCARAB_CORE_LIMIT`). `_JAVA_OPTIONS`, which the JVM reads after its command line, turns any of them back on:
  `jcmd` for JFR or native memory diagnostics now needs `_JAVA_OPTIONS=-XX:-DisableAttachMechanism`. The forging
  guide's new section "Keys in memory" (`docs/running/forging.md`) lists the options, the opt-in and what stays the
  host's: the container's core file limit, `kernel.core_pattern`, swap, hibernation, `kernel.yama.ptrace_scope` and an
  account of the node's own.
