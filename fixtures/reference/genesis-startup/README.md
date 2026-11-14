<!-- SPDX-License-Identifier: Apache-2.0 -->
# Genesis start-up refusals

43 start-ups of the unchanged reference node, recorded on 2026-11-05 for
[#796](https://github.com/KD5UJC-XYZ/scarab/issues/796). Each case runs `cardano-node run` from the
pinned reference image (`cardano-reference-11.1.3:local`, cardano-node 11.1.3, git revision
`938cba99`) once, in a container without a network, as an unprivileged user and with a read-only root
file system. It runs on the public preview configuration and genesis files with one change. Every
case fails while the genesis files are read, before any database, socket or peer exists. The
[recipe](../../../scripts/oracle/genesis-startup/run.py) builds the inputs and runs the image.
`provenance.json` records the image identity, the binary's hash and version, the node revision and
the hash of every base file.

`cases.jsonl` holds one case per line:
- the change (`era`, the file it changes; `edits` on the file's original bytes: a text replaced,
  bytes spliced, the file truncated or text appended; `keepHash`, the configured hash of that file
  kept; `absent`, `directory`, `unreadable` or `hidden`, the file missing, a directory, without
  read permission or in a directory without permission);
- how the node was started: `argv0`, its program name (`cardano-node`, or a link named `scarab`),
  and `relativeConfig`, the configuration given as `config.json` from its directory rather than as
  `/in/config.json`;
- `inputs`, the SHA-256 of every readable input file;
- `reference`: the exit code, the whole standard error, and the size and SHA-256 of standard output
  (the node's configuration dump, not compared).

Every hash but the one a case keeps is removed from the configuration, so the files are decoded.
`NativeGenesisStartupSuite` rebuilds each case from the preview files, checks the inputs against
`inputs`, reads it with `NodeSetup` under the configuration path the reference was given, renders the
refusal as `Main` writes it under the program name of `argv0`, and compares standard error whole and
the exit code.

| group | cases |
|---|---|
| hash | Shelley, Alonzo and Conway files that differ from the configured hash; a Byron file whose canonical JSON differs |
| bytes | Shelley truncated, followed by other text, both a refused field and other text, and an invalid string; Conway truncated |
| fields | a key missing, a nested value of another type, zero security parameter, a quorum past `Word64`, a ratio above 1, a too precise coefficient, an invalid date, a time without zone, a year starting with a letter or of three digits, hour 25, a dot without decimals and thirteen decimals, a zone of 24 hours and text after the zone, another nonce tag, a delegate key of the wrong length, a fund address too short; an Alonzo key missing, a cost model value of another type and an `Unknown` cost model key that is not a number; a Conway cost model value of another type, a committee credential, an injection with both `file` and `data`; a Byron field missing |
| file | the Shelley file missing, a directory, without read permission, and in a directory without permission |
| invocation | the program started as `scarab`; the configuration given by a relative path |
| order | a Shelley field refused and a Conway hash that differs; an Alonzo hash that differs and a truncated Conway file: the file read first is the one reported |

Scope: what the reference writes to standard error when it refuses a genesis file, and its exit code.
Outside it: the configuration file itself, Byron read errors other than these, the checkpoints and
Dijkstra files, and the standard output dump. `byron/truncated` (canonical-json's Parsec message) is
not reproduced by Scarab's Byron reader and is held as an incomplete gate in the suite, not as
agreement. The captures are ASCII; the reference writes standard error in the locale's encoding, which
this corpus does not exercise.
