// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import io.circe.Json

/** What one event says in the log: its kind (which gives its namespace), its severity, and the
  * fields of its JSON line after `event` and `at`, in order. Only this package builds one, from an
  * event through its [[Traced]] instance.
  */
final case class Line private[log] (
    kind: EventKind,
    severity: Severity,
    fields: List[(String, Json)]
):
  /** The line at another severity than its kind's usual one. */
  private[log] def at(other: Severity): Line = copy(severity = other)

object Line:
  extension [F[_]](log: Log[F])
    /** Log `event`: its line, as its [[Traced]] instance writes it. */
    def emit[E](event: E)(using traced: Traced[E]): F[Unit] = log.tell(traced.line(event))

/** The first part of a namespace: the part of the node an event comes from. */
enum Area:
  /** Start, stop, the process and its environment. */
  case Node

  /** Following peers: the chain, its epochs and eras, and how the peers serve it. */
  case Chain

  /** The ledger state: epoch accounts, the state mirror check. */
  case Ledger

  /** The old and the new ledger rules side by side (`--rules differential`). */
  case Rules

  /** Connections, in either direction. */
  case Net
  case Mempool
  case Forge

  /** The chain database: blocks, snapshots, replay. */
  case Storage

  /** What the libraries log through SLF4J (http4s, fs2, ...). */
  case Library

/** Every kind of line the node logs, with its namespace and its usual severity: the node's
  * `MetaTrace` (hermod-tracing `trace-dispatcher/src/Cardano/Logging/Types.hs` 170-179, which gives
  * each message a namespace and a severity and lists `allNamespaces`). The closed list is what the
  * log configuration names, what `docs/running.md` tabulates (`LogDocsSuite`), and what the golden
  * renders once each.
  *
  * The name of the case is the last part of the namespace (`Chain.Extended`), and the `event` field
  * of the JSON line is the same name with a lower-case initial (`extended`): the names the devnet
  * scenarios and the run watchers match on. A `Traced` instance may raise or lower the severity of
  * one event (an inbound connection refused is a `Warning`, one accepted is `Info`).
  *
  * The two-level namespace (area, kind) is flatter than the Haskell node's
  * (`ChainDB.AddBlockEvent.AddedToCurrentChain`): it is enough to configure each event on its own
  * or a whole area at once, and both levels are closed types here, so a configuration that names a
  * namespace that does not exist is refused at start.
  */
enum EventKind(val area: Area, val severity: Severity):
  // the node
  case Started extends EventKind(Area.Node, Severity.Notice)
  case Blst extends EventKind(Area.Node, Severity.Info)
  case Listening extends EventKind(Area.Node, Severity.Notice)
  case Memory extends EventKind(Area.Node, Severity.Info)
  case CpuStarvation extends EventKind(Area.Node, Severity.Warning)
  case StateDump extends EventKind(Area.Node, Severity.Info)
  case StateDumpRefused extends EventKind(Area.Node, Severity.Warning)
  case VerificationCache extends EventKind(Area.Node, Severity.Debug)
  case Error extends EventKind(Area.Node, Severity.Error)
  case Finished extends EventKind(Area.Node, Severity.Notice)

  // following the chain
  case Connected extends EventKind(Area.Chain, Severity.Info)
  case Extended extends EventKind(Area.Chain, Severity.Info)
  case RolledBack extends EventKind(Area.Chain, Severity.Notice)
  case Epoch extends EventKind(Area.Chain, Severity.Notice)
  case EraTransition extends EventKind(Area.Chain, Severity.Notice)
  case EpochValidated extends EventKind(Area.Chain, Severity.Info)
  case Reconnecting extends EventKind(Area.Chain, Severity.Warning)
  case HeadersChecked extends EventKind(Area.Chain, Severity.Info)
  case ChainSyncRounds extends EventKind(Area.Chain, Severity.Info)
  case SlowFetch extends EventKind(Area.Chain, Severity.Info)

  // the ledger state
  case LedgerEpoch extends EventKind(Area.Ledger, Severity.Info)
  case MirrorChecked extends EventKind(Area.Ledger, Severity.Info)
  case MirrorCheckFailed extends EventKind(Area.Ledger, Severity.Warning)

  // the differential rules
  case DifferentialSampled extends EventKind(Area.Rules, Severity.Debug)
  case DifferentialTicked extends EventKind(Area.Rules, Severity.Debug)
  case DifferentialSummary extends EventKind(Area.Rules, Severity.Notice)
  case RulesDiverged extends EventKind(Area.Rules, Severity.Error)
  case RulesHalted extends EventKind(Area.Rules, Severity.Critical)

  // connections
  case Connection extends EventKind(Area.Net, Severity.Info)
  case Inbound extends EventKind(Area.Net, Severity.Info)

  // the mempool
  case MempoolAdded extends EventKind(Area.Mempool, Severity.Info)
  case MempoolRejected extends EventKind(Area.Mempool, Severity.Info)
  case MempoolDropped extends EventKind(Area.Mempool, Severity.Info)
  case MempoolAdopted extends EventKind(Area.Mempool, Severity.Info)
  case MempoolSynced extends EventKind(Area.Mempool, Severity.Info)

  // forging
  case ForgeStarted extends EventKind(Area.Forge, Severity.Notice)
  case Forged extends EventKind(Area.Forge, Severity.Info)
  case Adopted extends EventKind(Area.Forge, Severity.Info)
  case Lost extends EventKind(Area.Forge, Severity.Notice)
  case KesEvolved extends EventKind(Area.Forge, Severity.Info)
  case KesReloaded extends EventKind(Area.Forge, Severity.Notice)
  case KesReloadRefused extends EventKind(Area.Forge, Severity.Error)
  case KesPersistFailed extends EventKind(Area.Forge, Severity.Error)
  case ForgeSkipped extends EventKind(Area.Forge, Severity.Info)
  case ForgeFailed extends EventKind(Area.Forge, Severity.Error)
  case ForgeEpoch extends EventKind(Area.Forge, Severity.Notice)

  // the chain database
  case FormatMigrated extends EventKind(Area.Storage, Severity.Notice)
  case ImmutableOpened extends EventKind(Area.Storage, Severity.Info)
  case VolatileOpened extends EventKind(Area.Storage, Severity.Info)
  case VolatileResumed extends EventKind(Area.Storage, Severity.Info)
  case NetworkRecorded extends EventKind(Area.Storage, Severity.Info)
  case SnapshotSkipped extends EventKind(Area.Storage, Severity.Warning)
  case SnapshotRestored extends EventKind(Area.Storage, Severity.Info)
  case SnapshotWritten extends EventKind(Area.Storage, Severity.Info)
  case SnapshotFailed extends EventKind(Area.Storage, Severity.Error)
  case SnapshotRefused extends EventKind(Area.Storage, Severity.Error)
  case ReplayStarted extends EventKind(Area.Storage, Severity.Info)
  case ReplayProgress extends EventKind(Area.Storage, Severity.Info)
  case ReplayFinished extends EventKind(Area.Storage, Severity.Info)
  case ShutdownSnapshot extends EventKind(Area.Storage, Severity.Info)

  // the libraries
  case Slf4j extends EventKind(Area.Library, Severity.Info)

  /** A line of this kind, at its usual severity. */
  def line(fields: (String, Json)*): Line = Line(this, severity, fields.toList)

  /** The `event` field of the JSON line: the case name with a lower-case initial. */
  lazy val name: String = toString.head.toLower.toString + toString.tail

  /** `Area.Kind`, as the configuration names it. */
  def namespace: String = s"$area.$this"
