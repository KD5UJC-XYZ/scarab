# Retained-state reference relay process restart

`scripts/private_cluster_restart.py` adds one opt-in scenario to the existing private
Conway PV9 launcher. It restarts the task-owned **node 3 process** inside the same
reference container, preserving that relay's actual database, configuration,
genesis and topology. This is different from the earlier scripted fresh-harness
restart. Source and scripted tests are implemented; one bounded retained-state relay
process restart passed on reviewed source `478cc00` (see the fourth-attempt receipt
summary below). This does not establish Scala acquisition resume or crash recovery.

## Storage ownership and cleanup

The runner inherits the existing unique internal Docker network and container, the
3 CPU / 6 GiB reference allocation, and the 2 GiB `/work` tmpfs. Node 3's database
remains at `/work/env/node-data/node3/db`; no persistent volume, host database bind,
container replacement, database copying or unrelated path is added. The relay uses
no forging credentials. Other disposable cluster credentials remain inside the
same task-owned tmpfs and are never exported.

The inherited watchdog and `Runner.run` finalizer stop/remove the unique reference
container and network, including the restarted child and all retained state. The
restart uses a pinned static pidfd helper in the immutable test image at
`/opt/reference/libexec/restart-pidfd`, root-owned with mode `0555`. Only a PID receipt
and two relay log files are added inside `/work`. No helper is uploaded to runtime
data mounts, and effective `noexec` on `/work` and `/tmp` plus read-only root are
required before signalling. Existing capture exports only allowlisted
public files and logs, plus non-secret process identities, file digests and queried
state outside Git. No private keys or raw database files are copied out. Logs/state
are private evidence and must not be published raw.

## Sequence and exact checks

1. Use the existing Scala handshake against node 3. Await at least eight reference
   blocks and three-node convergence while all nodes are running, so this
   security-parameter-5 development chain can have immutable data.
2. Pause both owned producers. Reuse the transfer harness's separate, bracketed
   state queries and record the relay
   process identity and original argument vector. Verify the binary SHA-256,
   executable, working directory, exact configuration/topology/database/socket
   paths, loopback port, and the absence of forging credential options. Only the
   narrow observed argument profile is accepted; unexpected arguments fail closed.
3. Bind public genesis/configuration/topology/port hashes and database directory
   device/inode. Request `SIGTERM` through the small `private_cluster_pidfd.c` helper
   inside that same owned container. It opens a Linux pidfd first, then verifies the
   expected start ticks, then calls `pidfd_send_signal` on the held descriptor.
   Exit/PID reuse after opening cannot redirect that signal to another process.
   Missing/denied pidfd syscalls fail closed, with no numeric `kill` fallback.
   Wait for observed exit; no timeout fallback sends SIGKILL. Changed start ticks
   are a replacement error, never successful exit. Recheck the old PID and PID file
   before launch and refuse an observed supervisor replacement.
4. With the old process stopped, record the bounded database file inventory and
   immutable chunk digests. Require a nonempty immutable chunk, no database
   symlinks, at most 4096 files and 512 MiB. Bounded POSIX shell traversal plus the
   preflight-verified `stat -c %s` replaces `find`; hidden entries are included.
   Traversal also limits all entries to 8192, depth to 32, and absolute path length
   to 240 bytes. Special files and unsupported names fail closed.
   Require unchanged directory and public
   identity files before launching.
5. Launch the verified `cardano-node` binary directly, with the original option
   vector and working directory. The executable's absolute pinned path replaces
   only a possible argv[0] alias. A task-owned shell writes a separate launch PID
   receipt and waits/reaps the child. The observed replacement PID must match this
   receipt, have a different PID/start-time identity, and retain the exact options.
6. Keep producers paused while awaiting the exact prior Conway hash/slot/block/
   epoch. Check the restarted startup log still describes a credential-free relay.
   Repeat bracketed queries and require identical point, whole UTxO (JSON and exact
   CBOR), protocol parameters and actual fee pot. Check producer paused states, retained
   immutable bytes, directory identity and all genesis/configuration hashes again.
7. Resume producers in `finally`, including cancellation and partial-pause failure.
   Run the existing Scala original-header/BlockFetch/body comparison against node 3
   after restart. The inherited two-epoch growth/convergence smoke then completes.

`cardano-testnet` is used only by initial fresh cluster creation. Restart never
reruns it: there is no timestamp refresh, genesis regeneration, identity regeneration,
database deletion or automatic fallback to a fresh environment. A failed rejoin,
changed configuration, empty DB, supervisor intervention or differing state produces
failure evidence and inherited cleanup, not a restart success.

The requested stop is TERM and the test checks retained recovery after process
exit. It does not prove a particular shutdown flush protocol, crash durability,
power-loss behavior, container/host restart, consensus correctness or an atomic
ledger query. Immutable chunk equality and stable point/UTxO observations are
explicitly narrower evidence.

## Running and remaining gates

After main grants a live resource slot, compose this packet with the latest
committed main and its precompiled isolated Scala build:

Build the tiny Linux helper with an already available compiler (install nothing),
placing its output outside Git. A matching-architecture offline compiler container
can also perform this step. The reference image has no interpreter/compiler; the
helper is statically linked and uses only Linux process/proc APIs.

```sh
cc -std=c11 -O2 -Wall -Wextra -Werror -static \
  scripts/private_cluster_pidfd.c -o "$PRIVATE_HELPER_PATH"
sha256sum "$PRIVATE_HELPER_PATH"
```

Package only that binary into a unique local test image using
`scripts/prepare_restart_image.py --base-image <exact-local-sha256-ID> --tag
cardano-restart-test-<unique-name>:local --helper <path> --helper-sha256 <digest>
--source-sha256 <C-source-digest> --output <new-private-receipt-directory>`.
The builder verifies the local base ID, creates a unique temporary local alias,
and builds with `--pull=false --network=none`. Its context contains only the
Dockerfile and hash-pinned ELF. The helper is copied root-owned `0555`; existing
base layers must remain unchanged. The temporary alias is removed, and no shared
reference tag is replaced. Dockerfile/base/helper/source/result image hashes are
recorded outside Git. These are trusted local inputs, not signatures or a proof
that a binary was compiled from a particular source.

Before a live cluster, use `scripts/smoke_restart_image.py --image <exact-image-ID>
--helper-sha256 <digest> --output <new-private-directory>`. It starts only an owned
sleep child under the reference runtime limits, non-root UID, read-only root,
dropped capabilities and no-new-privileges. It checks effective `noexec` data
mounts and helper ownership/hash before pidfd TERM; no Cardano process starts.
Mount checks fail closed without automatically changing flags.

```sh
python3 scripts/private_cluster_restart.py \
  --reference-image "$PINNED_RESTART_TEST_IMAGE_ID" \
  --scala-repo "$ISOLATED_SCALA_BUILD" \
  --pidfd-helper-sha256 "$PRIVATE_HELPER_SHA256" \
  --output "$NEW_PRIVATE_EVIDENCE_DIRECTORY_OUTSIDE_GIT" --seconds 420
```

Budgets are 300–480 seconds, with each restart poll bounded and 120 seconds reserved
for the remaining workload. The transient Scala probe adds 1 CPU / 1 GiB, for the
existing combined 4 CPU / 7 GiB ceiling. No ports or public peers are added. An
unexpected real process option or supervisor behavior must be investigated from
the preserved failure, not bypassed by broadening ownership checks or regenerating
state. The fourth attempt establishes the narrow process-restart observations
below; crash durability and other restart configurations remain untested.

The normal discovery command includes the new Docker-free scripted tests:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_private_cluster*.py'
```

They cover process/path/binary ownership, identity changes, start PID receipts,
bounded stop/rejoin failures, preserved state/configuration, cancellation/resume,
probe routing, and inherited cleanup without a persistent volume. They do not claim
reference restart interoperability.

On Linux with an existing C compiler, native tests statically compile the helper in
a temporary directory, signal only a test-owned child, and inject exit/reuse races
around handle acquisition, identity verification and signalling. They assert the
signal operation receives only the opened descriptor. No host security settings
change. Non-Linux/compiler-unavailable environments explicitly skip those native
tests; scripted guards still run, but that is not native pidfd verification.

Scala acquisition resume and Scala crash recovery are explicitly `not-tested` in
receipts. Pairing the retained reference process with the separate acquisition
follower's persisted checkpoint/resume scenario is a later integration packet;
an ordinary post-restart Scala capture does not establish either recovery claim.

## Preserved first live attempt

The first authorized smoke reached a successful relay handshake and stable paused
baseline, then failed after 61.54 seconds: it queried a producer socket after sending
SIGSTOP. A stopped producer cannot answer that query. No relay termination/restart
occurred. The owned container and network were both verified removed. Private
evidence is preserved outside Git at `/home/<user>/cardano-restart-live1-20261008`.

The correction queries three-node convergence before pausing, then uses observed
producer stopped states and the stable relay point throughout restart. It makes no
producer socket query while paused. The readiness point can precede the drained
relay baseline; only the latter is the exact pre/post restart comparison point.
Post-resume three-node convergence remains part of the inherited final smoke.
The new ordering regression rejects any producer socket query during the pause.

## Preserved second live attempt and packaging correction

The second run on `adbd941` reached stable Conway block 9/slot 130 with paused
producer evidence, exact process identity and bracketed whole-state queries.
OCI rejected execution of `/work/restart-pidfd` before the helper started (exit
126, permission denied). No pidfd call or relay restart occurred. Cleanup verified
no remaining owned containers/networks after 45.50 seconds. Private evidence is
preserved at `/home/<user>/cardano-restart-live2-20261008`; effective mount flags and
post-install mode were not captured in that failed run.

The immutable-image correction leaves runtime mount/security flags unchanged.
Its owned-child smoke verified effective non-executable data mounts, root-owned
`0555` helper bytes, successful pidfd TERM and complete cleanup. A first packaging
build failed because BuildKit interpreted a bare `FROM sha256:<ID>` as a registry
name; that receipt is preserved. The successful builder instead uses a unique
local alias verified against the exact base ID. No image pull or package install
completed. Neither packaging nor the child smoke establishes retained Cardano
database recovery; that still requires the separately authorized live retry.

## Preserved third live attempt

The image-packaged helper run on `21b0619` verified the immutable helper and
effective mount restrictions, captured stable Conway block 8/slot 192, sent TERM
through pidfd to the exact observed relay identity, and observed process exit.
It then failed before relaunch because the reference image has no `find`
executable, required by the retained-database inventory. No restart or recovery
success is claimed. All owned containers/networks were removed after 51.85 seconds.
Private evidence remains at `/home/<user>/cardano-restart-live3-20261008`.
The inventory dependency needs a reviewed correction and preflight coverage before
another cluster attempt; no package installation or automatic fallback occurred.

## Inventory correction and prerequisite gate

`private_cluster_restart_prerequisites.py` supplies the bounded inventory and an
automatic synthetic gate called by `RestartRunner.preflight`, before environment
generation or any Cardano node start/stop. `--preflight-only` runs this gate without
a cluster. It uses the exact pinned reference test image, UID/GID 1000, read-only
root, effective non-executable data mounts, dropped capabilities, no-new-privileges,
and the unchanged 3 CPU/6 GiB reference plus 1 CPU/1 GiB JVM ceiling. Its unique
internal network, containers and fake files are cleaned through the inherited
cleanup adapter. It does not create keys or invoke `cardano-testnet create-env`.

The gate inventories these external dependencies across the active restart path:

| Execution surface | Commands and exercised forms |
| --- | --- |
| Host | Python standard library; Docker context/info/image-inspect, internal-network create/remove/list, run, attached/detached exec, inspect, stop, remove and ps through the same adapters |
| Reference image | `/bin/sh`; `cat` (files, stdin, proc); `readlink -f` (files/directories/proc); `stat -c %u:%g:%a:%F`, `%d:%i`, `%s`; `sha256sum --`; `sleep`; pinned pidfd helper |
| Shell builtins | `command -v`, test predicates including symlinks, positional parameters, glob traversal including hidden files, arithmetic, printf, redirections, background launch, wait, kill STOP/CONT, cd, shift, umask, exec |
| Reference CLI | `cardano-node run --help`; `cardano-testnet create-env --help` and `cardano --help`; `cardano-cli conway query` help for tip, utxo, ledger-state, protocol-state, protocol-parameters |
| Synthetic setup only | `mkdir -p`, `ln -s`, `rm` for a nested fake immutable chunk, hidden file, dangling/file symlinks and unsupported name |
| Pinned JVM image | `/bin/sh`, `cat` of the actual isolated runtime classpath, and `java` with the production CPU/heap flags loading `lab.Main --help` on the read-only build mount |

The actual retained-inventory adapter checks the fake tree and rejects symlinks
and unsupported names. The exact detached restart wrapper launches an owned sleep
child, writes both PID receipts and log streams, exercises proc observations and
STOP/CONT, then the pinned helper sends TERM. No Cardano daemon starts. Synthetic
write/read/capture and inherited Docker cleanup execute, rather than only checking
command names. CLI help and JVM loading establish prerequisites; they do not prove
live query, recovery or protocol semantics.

A missing-utility regression forces `stat` unavailable and asserts that preflight
fails before the main container or Scala scenario launches. Native inventory tests
cover hidden/nested completeness, dangling/directory/file symlink rejection, sparse
oversize files, file-count bounds and depth bounds. Existing state, ownership,
cancellation and cleanup guards remain in the suite. The first exact-image gate
passed with no remaining containers/networks; private evidence is at
`/home/<user>/cardano-restart-prereq-20261008-first`. All three live failures and
packaging receipts remain preserved. A successful synthetic gate is readiness for
review, not a retained-state restart success or authorization for another cluster.

## Successful fourth live attempt

Reviewed source `478cc00a0e83d60816c5a4dcc129ada4ef729f32` passed the integrated
synthetic preflight and retained-state process restart using immutable test image
`sha256:f3df75d7cafc75ef868654c07d3111837fc131e72085f181738457cf8bf3851e`.
Private full evidence remains at `/home/<user>/cardano-restart-live4-20261008`;
all three prior failed runs remain unchanged. The completed run took 144.31 seconds
within the 420-second workload/600-second overall budgets and unchanged combined
4 CPU/7 GiB limits. Both synthetic and live cleanup receipts contain no remaining
owned containers or networks.

The exact observed relay PID/start-tick identity changed from `197/172004826` to
`1709/172008799` after pidfd TERM and observed exit. The replacement matched the
runner's launch receipt and retained the executable hash, original options and
working directory. It rejoined the same Conway block 8/slot 144/epoch 0 point
`9001f1c9210aae32f8bedc3a8903d30e166e0077266f01d36cd202d593c7e748`.
Database directory identity `154:156`, public genesis/configuration/topology/port
hashes and the nonempty 3385-byte immutable chunk remained unchanged. The chunk
SHA-256 was `7c3d2942354bd902f7b4dac791eb9341399d48f0162cbd8c0953aee2de739214`.

Bracketed pre/post observations contained eight UTxOs, byte-identical whole-UTxO
JSON and exact exported CBOR hex, identical protocol parameters and fee pot `0 -> 0`.
Producers remained paused across the state comparisons. These are separate CLI
acquisitions, not an atomic snapshot. After resume, Scala captured block 9/slot 310
with the restart point as parent; original header bytes, parent, body size and body
hash checks passed (header protocol 11.2, ledger Conway PV9). The inherited final
smoke observed all three nodes grow from block 9 to 38, epoch 0 to 2, with convergence.

This is reference relay process recovery from its retained database after TERM,
not a container/host restart, power-loss/crash durability test, Scala acquisition
resume, Scala crash recovery, signature verification or ledger conformance result.
No transaction was submitted in this scenario. Raw state/logs/binaries stay private.


## Main integration verification

Only the seven restart-specific source/test/document files were imported through
the reviewed patch with SHA-256
`dca85ea9932b3bccd7aafe8b3e38ac450fcf47d5333db01dadad474ffbf133fc`.
The worker's earlier merge was not imported; no private binaries, images, keys,
logs or state were added. The live source identity and scope above remain the
reviewed worker run, not a new run of the integrated main tree.

Integrated validation passed 829 public Scala tests and 25 public gates. Full
launcher discovery with the existing Linux compiler passed 95 tests, including
the native pidfd signal/race tests against test-owned children. A Docker-call
sentinel observed zero calls. This test-only native exercise does not add a live
restart or power-loss claim. A separate isolated Java-image run reported 93 tests
and one native-helper setup skip because that image lacks a compiler; the native
group was then exercised successfully in the bounded host run without installing
anything. Private receipts remain at
`/home/<user>/cardano-restart-native-integration-20261008`.
