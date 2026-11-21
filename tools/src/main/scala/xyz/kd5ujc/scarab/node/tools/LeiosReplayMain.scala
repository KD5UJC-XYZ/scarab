// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import cats.data.NonEmptyVector
import cats.effect.{ExitCode, IO, IOApp, Ref, Resource}
import cats.mtl.{Ask, Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.{Files, Path as FsPath}
import io.circe.Json
import java.nio.file.{Files as JFiles, Path}
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.consensus.{BlockNoVerdict, BlockRef}
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, ExtLedgerState, MirrorState}
import xyz.kd5ujc.scarab.ledger.{
  BlockFailure,
  ChainError,
  ConfigError,
  Header,
  LedgerDecodeError,
  NodeError,
  StorageError,
  ValidationError
}
import xyz.kd5ujc.scarab.ledger.state.{Diff, Difference, SlotNo}
import xyz.kd5ujc.scarab.models.runtime.DecodeError
import xyz.kd5ujc.scarab.node.{
  LeiosStorage,
  MirrorRules,
  NodeSetup,
  ParallelPlutus,
  ReceivedBlockRecovery,
  ReferenceBuildFlag,
  TransitionRecount,
  VerificationCache
}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.{ChainSync, DijkstraNotYet, ReferenceBuild}
import xyz.kd5ujc.scarab.storage.*
import xyz.kd5ujc.scarab.storage.haskell.{HaskellImport, HaskellImportError}
import xyz.kd5ujc.scarab.storage.haskell.state.ReferenceLayout

/** The offline replay of one window of the public Leios testnet against the reference's ledger
  * states (#54, gate G1): from a reference ledger state, the node's own block application to the
  * slot of a later one, and the state it reaches exported and compared byte for byte with the
  * reference's. No peer, no follower. Everything is the shipped path under
  * `--reference-build leios-b004fe0c`:
  *
  *   1. the reference node's ImmutableDB up to the end state's slot, and its LeiosDB, into a fresh
  *      database, as `import-haskell-db` stores them ([[HaskellImport]]);
  *   2. the start state into it, as `import-ledger-snapshot` imports it ([[LedgerSnapshotImport]]);
  *   3. the blocks after the start state, applied as the node's start applies its stored blocks
  *      ([[ChainStorage.open]]) with the node's own rules, built as `follow` builds them
  *      ([[MirrorRules]]), the endorser-block store open beside them as `follow` opens it;
  *   4. the state at the end slot exported as `export-ledger-snapshot` exports it
  *      ([[LedgerSnapshotExport]]), and its `state`, `tables` and `meta` compared with the end
  *      state's, the first differing byte reported, and for `state` where the decoded states first
  *      differ ([[Diff]]).
  *
  * The block application is the node's alone: this program adds no rule. Until the rules apply a
  * Dijkstra block (#85 D8, which lifts [[DijkstraNotYet.Rules]] in [[MirrorRules]]), the replay
  * halts at the first block after the start state, reported with its slot ([[Outcome.Halted]]); a
  * halt is not a pass.
  *
  * Arguments: `--config <config.json> --haskell-db <reference db> --start <ledger dir> --end
  * <ledger dir> --db <fresh dir> --out <dir> [--end-slot N]`. A ledger directory is the reference's
  * `ledger/<slot>`, named after its tip's slot (or the end's slot is `--end-slot`), holding
  * `state`, `tables` and `meta`; the end directory may hold only some of them, and those it lacks
  * are reported as not compared ([[Outcome.Matched]]).
  */
object LeiosReplayMain extends IOApp:
  /** The build the testnet runs. */
  val Build: ReferenceBuild = ReferenceBuild.LeiosB004fe0c

  /** The files of a reference ledger directory, compared in this order. */
  val Compared: Vector[String] = Vector("state", "tables", "meta")

  val Usage: String =
    "usage: LeiosReplayMain --config <config.json> --haskell-db <reference db> --start <ledger dir> --end <ledger dir> --db <fresh dir> --out <dir> [--end-slot N]"

  final case class Window(
      config: Path,
      haskellDb: Path,
      start: Path,
      end: Path,
      db: Path,
      out: Path,
      endSlot: Option[BigInt] = None
  )

  /** Why the window was not replayed: no verdict on the rules. */
  enum Error extends DomainError:
    /** An argument that is no flag of the program, or a flag given twice. */
    case UnexpectedArgument(argument: String)
    case MissingFlag(flag: String)
    case Config(cause: ConfigError)

    /** The end directory is not named after a slot. */
    case EndSlot(name: String)
    case NotFresh(path: Path)
    case Stage(cause: HaskellImportError)

    /** The start state was not imported (a part of Dijkstra the codecs refuse, among others). */
    case Start(cause: LedgerSnapshotImport.Error)

    /** The source holds no block at the end slot: the staged chain ends elsewhere. */
    case StagedTip(expected: BigInt, actual: Option[BlockRef])

    /** The replay did not start from the imported state (a snapshot skipped or refused). */
    case NotRestored(expected: BlockRef, restored: Option[BlockRef], events: Vector[StorageEvent])
    case SnapshotEvents(events: Vector[StorageEvent])
    case Replay(cause: NodeError)

    /** The replay ended before or after the end slot. */
    case Reached(expected: BigInt, actual: Option[BlockRef])
    case Export(cause: LedgerSnapshotExport.Error)

    /** The end directory is not there, or holds none of `state`, `tables` and `meta`. */
    case NoReference(dir: Path)

    /** An exported file is the reference's own (`expected` and `exported` are one file). */
    case Alias(expected: Path, exported: Path)

  /** Where a `state` that differs in its bytes first differs as a state. */
  enum Located:
    case At(difference: Difference)

    /** The two decode to equal states: only the encoding differs. */
    case SameValue

    /** One side does not decode in the build's layout. */
    case Undecodable(side: Side, cause: DecodeError)

  enum Side:
    case Expected, Actual

  /** The first byte at which two files differ: one is a prefix of the other where a side has none.
    */
  final case class ByteDifference(
      file: String,
      offset: Long,
      expected: Option[Int],
      actual: Option[Int],
      expectedSize: Long,
      actualSize: Long
  )

  enum Outcome:
    /** Every file the end directory holds is the exported one, byte for byte; `absent` are the
      * files it does not hold, which are not compared: a window with one is not a pass.
      */
    case Matched(end: BlockRef, applied: Long, compared: Vector[String], absent: Vector[String])

    /** The exported state differs from the reference's. */
    case Differs(
        end: BlockRef,
        applied: Long,
        first: ByteDifference,
        located: Option[Located],
        others: Vector[ByteDifference]
    )

    /** The rules do not apply the block at `point` yet ([[BlockFailure.NotYet]]). */
    case Halted(point: ChainSync.Point, part: DijkstraNotYet, applied: Long)

    /** The node has no verdict on the block of `slot` for a part it does not hold yet
      * ([[noVerdictNotYet]]): the block withheld, as the follower withholds it, and not a pass.
      */
    case Withheld(slot: BigInt, cause: BlockNoVerdict, applied: Long)

    /** The rules refuse the block at `point`, which the reference applied. */
    case Refused(point: ChainSync.Point, failure: BlockFailure, applied: Long)

  /** The exit code of an outcome: zero only for a complete match. */
  def exitCode(outcome: Outcome): ExitCode = outcome match
    case Outcome.Matched(_, _, _, absent) =>
      if absent.isEmpty then ExitCode.Success else ExitCode(6)
    case _: Outcome.Halted   => ExitCode(3)
    case _: Outcome.Withheld => ExitCode(3)
    case _: Outcome.Differs  => ExitCode(4)
    case _: Outcome.Refused  => ExitCode(5)

  def parse(args: List[String]): Either[Error, Window] =
    val names = Set("config", "haskell-db", "start", "end", "db", "out", "end-slot")
    @annotation.tailrec
    def go(rest: List[String], acc: Map[String, String]): Either[Error, Map[String, String]] =
      rest match
        case Nil => Right(acc)
        case flag :: value :: tail
            if flag.startsWith("--") && names.contains(flag.drop(2)) &&
              !acc.contains(flag.drop(2)) =>
          go(tail, acc.updated(flag.drop(2), value))
        case other :: _ => Left(Error.UnexpectedArgument(other))
    go(args, Map.empty).flatMap { flags =>
      def path(name: String) =
        flags.get(name).map(Path.of(_)).toRight(Error.MissingFlag(s"--$name"))
      val endSlot = flags
        .get("end-slot")
        .traverse(t => t.toLongOption.filter(_ >= 0).map(BigInt(_)).toRight(Error.EndSlot(t)))
      (
        path("config"),
        path("haskell-db"),
        path("start"),
        path("end"),
        path("db"),
        path("out"),
        endSlot
      ).mapN(Window.apply)
    }

  /** The slot of a reference ledger directory: its name. */
  def slotOf(dir: Path): Either[Error, BigInt] =
    val name = Option(dir.getFileName).fold("")(_.toString)
    name.toLongOption.filter(_ >= 0).map(BigInt(_)).toRight(Error.EndSlot(name))

  /** The first difference of two files, if any. */
  def firstDifference(name: String, expected: FsPath, actual: FsPath): IO[Option[ByteDifference]] =
    val files = Files[IO]
    val block = 1 << 20
    def blocks(path: FsPath) =
      files.readAll(path).chunkN(block, allowFewer = true).map(_.toArray)
    (files.size(expected), files.size(actual)).flatMapN { (left, right) =>
      blocks(expected)
        .zipAll(blocks(actual))(Array.emptyByteArray, Array.emptyByteArray)
        .zipWithIndex
        .collectFirst(Function.unlift { case ((a, b), index) =>
          val at = java.util.Arrays.mismatch(a, b)
          Option.when(at >= 0)(
            ByteDifference(
              name,
              index * block + at,
              Option.when(at < a.length)(a(at) & 0xff),
              Option.when(at < b.length)(b(at) & 0xff),
              left,
              right
            )
          )
        })
        .compile
        .last
    }

  /** Where two `state` files whose bytes differ first differ as states of `layout`. */
  def locate(layout: ReferenceLayout, expected: Bytes, actual: Bytes): Located =
    def ext(side: Side, bytes: Bytes): Either[Located, Option[ExtLedgerState]] =
      layout.decode(bytes).map(_.ext).leftMap(Located.Undecodable(side, _))
    (ext(Side.Expected, expected), ext(Side.Actual, actual))
      .mapN((e, a) =>
        Diff[Option[ExtLedgerState]].diff(e, a).fold(Located.SameValue)(Located.At(_))
      )
      .merge

  /** The window's files compared: the outcome once the replay reached `end`. */
  /** The reference end state, recorded before anything is written: its directory and the files of
    * [[Compared]] it holds, at least one. Only [[reference]] makes one.
    */
  final case class Reference private[LeiosReplayMain] (dir: Path, files: NonEmptyVector[String])

  /** The end directory's files as they are now, or [[Error.NoReference]] for a directory that is
    * not there or holds none of [[Compared]]: a window is compared with the reference's state,
    * never with what the export writes.
    */
  def reference(dir: Path)(using Raise[IO, Error]): IO[Reference] =
    Compared
      .filterA(name => IO.blocking(JFiles.isRegularFile(dir.resolve(name))))
      .flatMap(present =>
        lift(
          NonEmptyVector
            .fromVector(present)
            .map(Reference(dir, _))
            .toRight(Error.NoReference(dir))
        )
      )

  def compare(
      layout: ReferenceLayout,
      reference: Reference,
      exported: Path,
      end: BlockRef,
      applied: Long
  )(using Raise[IO, Error]): IO[Outcome] =
    val expected = reference.dir
    val compared = reference.files.toVector
    val absent = Compared.filterNot(compared.contains)
    // an exported file that is the expected one would be compared with itself
    compared
      .traverse_(name =>
        IO.blocking(
          JFiles.exists(exported.resolve(name)) &&
            JFiles.isSameFile(expected.resolve(name), exported.resolve(name))
        ).flatMap(same =>
          Error.Alias(expected.resolve(name), exported.resolve(name)).raise[IO, Unit].whenA(same)
        )
      ) *> {
      compared
        .flatTraverse(name =>
          firstDifference(
            name,
            FsPath.fromNioPath(expected.resolve(name)),
            FsPath.fromNioPath(exported.resolve(name))
          ).map(_.toVector)
        )
        .flatMap {
          case first +: others =>
            val located =
              if first.file == "state" then
                IO.blocking(
                  Some(
                    locate(
                      layout,
                      Bytes.wrap(JFiles.readAllBytes(expected.resolve("state"))),
                      Bytes.wrap(JFiles.readAllBytes(exported.resolve("state")))
                    )
                  )
                )
              else IO.pure(None)
            located.map(Outcome.Differs(end, applied, first, _, others))
          case _ => IO.pure(Outcome.Matched(end, applied, compared, absent))
        }
    }

  private def lift[A](result: Either[Error, A])(using Raise[IO, Error]): IO[A] =
    result.fold(_.raise[IO, A], IO.pure)

  private def fresh(path: Path)(using Raise[IO, Error]): IO[Unit] =
    IO.blocking(
      JFiles.exists(path) && scala.util.Using.resource(JFiles.list(path))(_.findAny().isPresent)
    ).flatMap(used => Error.NotFresh(path).raise[IO, Unit].whenA(used))

  /** The setup of the testnet's build from its configuration. */
  def setupOf(config: Path)(using Raise[IO, Error]): IO[NodeSetup] =
    IO.blocking(NodeSetup.fromConfigFile(Build)(config, None))
      .flatMap(r => lift(r.leftMap(Error.Config(_))))

  /** Steps 1 and 2: the chain to `endSlot` and the start state, into a fresh `db`; `locks` is the
    * process's one instance ([[LeiosStoreLocks]]).
    */
  def stage(window: Window, setup: NodeSetup, endSlot: BigInt, locks: LeiosStoreLocks[IO])(using
      Raise[IO, Error]
  ): IO[LedgerSnapshotImport.Report] =
    val importOptions = HaskellImportCommand.Options(
      window.haskellDb,
      window.db,
      None,
      Some(window.config),
      Some(endSlot.toLong),
      Build
    )
    val startOptions = LedgerSnapshotImportCommand.Options(
      window.start,
      window.db,
      Some(window.config),
      None,
      None,
      None,
      unverified = true,
      Build
    )
    for
      chunkSlots <- lift(
        setup.immutableChunkSlots.toRight(
          Error.Config(ConfigError(xyz.kd5ujc.scarab.ledger.ConfigProblem.MissingChunkSize))
        )
      )
      staged <- Handle
        .allow[HaskellImportError](
          HaskellImport.run[IO](
            HaskellImportCommand.config(importOptions, setup, chunkSlots),
            locks,
            p =>
              log(
                "staging",
                "appended" -> Json.fromLong(p.appended),
                "slot" -> Json.fromLong(p.slot)
              )
          )
        )
        .rescue(e => Error.Stage(e).raise[IO, HaskellImport.Report])
      _ <- log(
        "staged",
        "appended" -> Json.fromLong(staged.appended),
        "leiosBodies" -> staged.leios.fold(Json.Null)(c => Json.fromLong(c.bodies)),
        "seconds" -> Json.fromLong(staged.elapsed.toSeconds)
      )
      _ <- Error
        .StagedTip(endSlot, staged.tip)
        .raise[IO, Unit]
        .unlessA(staged.tip.exists(_.slot == endSlot))
      started <- importStart(startOptions, setup)
    yield started

  /** Step 2 alone: `options.ledger` into `options.db`, which already holds its point's block. */
  def importStart(options: LedgerSnapshotImportCommand.Options, setup: NodeSetup)(using
      Raise[IO, Error]
  ): IO[LedgerSnapshotImport.Report] =
    given Ask[IO, ConsensusMirrorEnv] = setup.mirrorAsk(IO.pure(Map.empty))
    lift(LedgerSnapshotImportCommand.inputs(options, setup).leftMap(Error.Config(_))).flatMap(
      config =>
        Handle
          .allow[LedgerSnapshotImport.Error](LedgerSnapshotImport.run[IO](config))
          .rescue(e => Error.Start(e).raise[IO, LedgerSnapshotImport.Report])
    )

  /** The replay's end: the last block applied and how many, or the verdict that stopped it. */
  enum Replayed:
    case Reached(anchor: Option[BlockRef], applied: Long)
    case Stopped(outcome: Outcome)

  /** Step 3: the blocks of `db` after its imported state `from`, applied as the node's start
    * applies its stored blocks, with the node's own rules and the endorser-block store open. The
    * rules are built as `follow` builds them; the application of a Dijkstra block, and of an
    * endorser block's closure before the block that certifies it, is theirs alone (#85 D8).
    */
  def replay(db: Path, setup: NodeSetup, from: BlockRef, locks: LeiosStoreLocks[IO])(using
      Raise[IO, Error]
  ): IO[Replayed] =
    val dir = FsPath.fromNioPath(db)
    for
      codec <- lift(ReferenceBuildFlag.layout(Build).leftMap(Error.Config(_)))
      applied <- Ref.of[IO, (Long, Option[Header])]((0L, None))
      retained <- Ref.of[IO, Vector[StorageEvent]](Vector.empty)
      restored <- Ref.of[IO, Option[Option[BlockRef]]](None)
      events = new StorageEvents[IO]:
        def emit(event: StorageEvent): IO[Unit] =
          val kept = event match
            case StorageEvent.SnapshotRestored(_, anchor) => restored.set(Some(anchor))
            // a shutdown snapshot that fails is reported as `SnapshotFailed`; one not written
            // because the tip already has one is not a failure
            case _: StorageEvent.SnapshotFailed | _: StorageEvent.SnapshotRefused |
                _: StorageEvent.SnapshotSkipped =>
              retained.update(_ :+ event)
            case _ => IO.unit
          kept *> log("storage", "detail" -> Json.fromString(event.toString))
      result <- Handle
        .allow[NodeError](
          opened(dir, setup, codec, events, applied, locks).map(Replayed.Reached(_, 0L))
        )
        .rescue {
          case stop if noVerdictNotYet.isDefinedAt(stop) =>
            val (slot, cause) = noVerdictNotYet(stop)
            applied.get.map(a => Replayed.Stopped(Outcome.Withheld(slot, cause, a._1)))
          case ValidationError(point, failure) =>
            applied.get.map(a => Replayed.Stopped(verdict(point, failure, a._1)))
          case other => Error.Replay(other).raise[IO, Replayed]
        }
      count <- applied.get.map(_._1)
      start <- restored.get
      kept <- retained.get
      _ <- Error
        .NotRestored(from, start.flatten, kept)
        .raise[IO, Unit]
        .unlessA(start.flatten.contains(from))
      _ <- Error.SnapshotEvents(kept).raise[IO, Unit].whenA(kept.nonEmpty)
    yield result match
      case Replayed.Reached(anchor, _) => Replayed.Reached(anchor, count)
      case stopped                     => stopped

  /** The node's own block application, built as `follow` builds it (`Main.ruled`), without the
    * observers `follow` adds for its dumps and Plutus cases. This is the seam of the Dijkstra rules
    * (#85 D8): they lift [[DijkstraNotYet.Rules]] in [[MirrorRules]] itself, and where they take
    * the endorser-block store for a certifying block's closure, it is the `leios` store [[opened]]
    * gives the chain database.
    */
  def rulesOf(
      setup: NodeSetup,
      praos: xyz.kd5ujc.scarab.consensus.PraosConfig,
      utxo: UtxoStore[IO],
      parallel: ParallelPlutus[IO],
      after: (Header, MirrorState, MirrorState) => Raise[IO, StorageError] ?=> IO[Unit]
  ): MirrorRules[IO] =
    MirrorRules[IO](
      praos,
      setup.globals,
      setup.byronRules,
      setup.handOver,
      setup.byronHistory,
      xyz.kd5ujc.scarab.plutus.PlutusBackend.default,
      utxoStore = Some(utxo),
      afterBlock = Some(after),
      parallelPlutus = Some(parallel)
    )

  /** The part of Dijkstra that `failure` names as not implemented yet, wherever the rules put it: a
    * block no rule applies ([[BlockFailure.NotYet]]), or a block or transaction no decoder of the
    * rules reads ([[LedgerDecodeError.NotYet]]). None: the failure is a verdict on the block.
    */
  def notYet(failure: BlockFailure): Option[DijkstraNotYet] =
    @annotation.tailrec
    def decoded(error: LedgerDecodeError): Option[DijkstraNotYet] = error match
      case LedgerDecodeError.NotYet(part)          => Some(part)
      case LedgerDecodeError.Transaction(_, inner) => decoded(inner)
      case _                                       => None
    failure match
      case BlockFailure.NotYet(part)                    => Some(part)
      case BlockFailure.Decode(ChainError.Decode(_, e)) => decoded(e)
      case BlockFailure.TxDecode(_, e)                  => decoded(e)
      case _                                            => None

  /** The errors the rules raise for a block they have no verdict on because the node does not hold
    * a part of Dijkstra yet, with the block's slot and the no-verdict the follower withholds the
    * block for ([[BlockNoVerdict]]): none on main. Once #85 D8(c) lands, a proposal that updates a
    * parameter Dijkstra adds (keys 34 to 56, which the state's record holds only once #826's format
    * change lands) is such a no-verdict, and this becomes the one case that maps the error (c)
    * raises for it to (c)'s `BlockNoVerdict`. Any other error stays an error of the run, with no
    * verdict.
    */
  val noVerdictNotYet: PartialFunction[NodeError, (BigInt, BlockNoVerdict)] =
    PartialFunction.empty

  /** The outcome of a block the rules did not apply: a halt where they name a part not implemented
    * yet, else their refusal of a block the reference applied.
    */
  def verdict(point: ChainSync.Point, failure: BlockFailure, applied: Long): Outcome =
    notYet(failure).fold(Outcome.Refused(point, failure, applied))(
      Outcome.Halted(point, _, applied)
    )

  /** The chain database opened on `dir` as `follow` opens it, its stored blocks replayed; the
    * anchor it reaches.
    */
  private def opened(
      dir: FsPath,
      setup: NodeSetup,
      layout: ReferenceLayout,
      events: StorageEvents[IO],
      applied: Ref[IO, (Long, Option[Header])],
      locks: LeiosStoreLocks[IO]
  )(using Raise[IO, NodeError]): IO[Option[BlockRef]] =
    given Ask[IO, ConsensusMirrorEnv] = setup.mirrorAsk(IO.pure(Map.empty))
    given SnapshotFormat[IO, MirrorState] =
      SnapshotV10
        .format[IO](SnapshotCheck.Structure, layout)
        .admitting(StateAdmission.restored(layout.build))
    val stores = for
      _ <- DatabaseOwnership.resource[IO](dir)
      leios <- LeiosStorage.resource[IO](
        Build,
        setup.config.settings.leios.database,
        Some(dir),
        locks
      )
      utxo <- RocksUtxoStore.resource[IO](dir / "utxo" / "live")
      cache <- VerificationCache.resource[IO]()
      parallel <- Resource.eval(ParallelPlutus.create[IO](ParallelPlutus.Workers.default))
    yield (leios, utxo, cache, parallel)
    stores.use { (leios, utxo, cache, parallel) =>
      val after = (header: Header, _: MirrorState, state: MirrorState) =>
        (_: Raise[IO, StorageError]) ?=>
          IO.delay(cache.noteEpochNonce(state.ledgerEpoch, state.protocol.epochNonce)) *>
            applied.update((n, _) => (n + 1, Some(header)))
      val praos = setup.praos.copy(verdicts = cache)
      ChainStorage
        .open[IO, MirrorState](
          dir,
          ChainStorage.Config(
            setup.defaultSnapshotPolicy,
            byronEpochSlots = setup.geometry.byron.epochSlots,
            identity = Some(setup.identity)
          ),
          rulesOf(setup, praos, utxo, parallel, after),
          setup.mirrorStart,
          events,
          new UtxoBacking[IO, MirrorState](utxo, dir / "utxo" / "checkpoints"),
          raw => cache.warm[IO](raw, praos),
          snapshotMigration = Some(SnapshotMigration.toCurrent[IO]),
          reopen = Some(TransitionRecount.reopen[IO](setup.genesis.geometry)),
          leios = leios
        )(ReceivedBlockRecovery.startUp(setup.geometry.byron.epochSlots))
        .use(recovered => IO.pure(recovered.anchor))
    }

  /** Step 4: the state at `end` exported to `out`. */
  def exported(db: Path, out: Path, end: BigInt)(using
      Raise[IO, Error]
  ): IO[LedgerSnapshotExport.Report] =
    for
      layout <- lift(ReferenceBuildFlag.layout(Build).leftMap(Error.Config(_)))
      slot <- lift(SlotNo.fromBigInt(end).leftMap(_ => Error.EndSlot(end.toString)))
      report <- Handle
        .allow[LedgerSnapshotExport.Error](
          LedgerSnapshotExport.run[IO](
            LedgerSnapshotExport
              .Config(FsPath.fromNioPath(db), FsPath.fromNioPath(out), Some(slot), layout)
          )
        )
        .rescue(e => Error.Export(e).raise[IO, LedgerSnapshotExport.Report])
    yield report

  /** The window replayed from its staged database: steps 3 and 4. */
  def replayed(
      window: Window,
      setup: NodeSetup,
      from: BlockRef,
      endSlot: BigInt,
      expected: Reference,
      locks: LeiosStoreLocks[IO]
  )(using Raise[IO, Error]): IO[Outcome] =
    replay(window.db, setup, from, locks).flatMap {
      case Replayed.Stopped(outcome) => IO.pure(outcome)
      case Replayed.Reached(anchor, applied) =>
        for
          end <- lift(anchor.filter(_.slot == endSlot).toRight(Error.Reached(endSlot, anchor)))
          report <- exported(window.db, window.out, endSlot)
          layout <- lift(ReferenceBuildFlag.layout(Build).leftMap(Error.Config(_)))
          outcome <- compare(layout, expected, report.directory.toNioPath, end, applied)
        yield outcome
    }

  /** The whole window: steps 1 to 4. */
  def window(window: Window)(using Raise[IO, Error]): IO[Outcome] =
    for
      endSlot <- lift(window.endSlot.fold(slotOf(window.end))(Right(_)))
      // the reference's files, before the export can write any
      expected <- reference(window.end)
      _ <- fresh(window.db)
      setup <- setupOf(window.config)
      _ <- log("referenceBuild", ReferenceBuildFlag.fields(Build)*)
      // the process's one instance: the window opens a Leios store nowhere else
      locks <- LeiosStoreLocks.make[IO]
      started <- stage(window, setup, endSlot, locks)
      _ <- log(
        "started",
        "slot" -> Json.fromBigInt(started.anchor.slot),
        "hash" -> Json.fromString(started.anchor.hash.hex),
        "outputs" -> Json.fromLong(started.count)
      )
      outcome <- replayed(window, setup, started.anchor, endSlot, expected, locks)
    yield outcome

  private def pointFields(point: ChainSync.Point): List[(String, Json)] = point match
    case ChainSync.Point.Origin => List("slot" -> Json.Null)
    case ChainSync.Point.Block(slot, hash) =>
      List("slot" -> Json.fromBigInt(slot.value), "hash" -> Json.fromString(hash.hex))

  private def endFields(end: BlockRef, applied: Long): List[(String, Json)] =
    List(
      "slot" -> Json.fromBigInt(end.slot),
      "hash" -> Json.fromString(end.hash.hex),
      "applied" -> Json.fromLong(applied)
    )

  private def differenceFields(d: ByteDifference): Json =
    Json.obj(
      "file" -> Json.fromString(d.file),
      "offset" -> Json.fromLong(d.offset),
      "expected" -> d.expected.fold(Json.Null)(Json.fromInt),
      "actual" -> d.actual.fold(Json.Null)(Json.fromInt),
      "expectedSize" -> Json.fromLong(d.expectedSize),
      "actualSize" -> Json.fromLong(d.actualSize)
    )

  /** The line that reports an outcome. */
  def report(outcome: Outcome): IO[Unit] = outcome match
    case Outcome.Matched(end, applied, compared, absent) =>
      log(
        "matched",
        (endFields(end, applied) ++ List(
          "compared" -> Json.fromValues(compared.map(Json.fromString)),
          "notCompared" -> Json.fromValues(absent.map(Json.fromString))
        ))*
      )
    case Outcome.Differs(end, applied, first, located, others) =>
      log(
        "differs",
        (endFields(end, applied) ++ List(
          "first" -> differenceFields(first),
          "located" -> located.fold(Json.Null)(l => Json.fromString(l.toString)),
          "others" -> Json.fromValues(others.map(differenceFields))
        ))*
      )
    case Outcome.Halted(point, part, applied) =>
      log(
        "halted",
        (pointFields(point) ++ List(
          "notYet" -> Json.fromString(part.toString),
          "applied" -> Json.fromLong(applied)
        ))*
      )
    case Outcome.Withheld(slot, cause, applied) =>
      log(
        "withheld",
        "slot" -> Json.fromBigInt(slot),
        "cause" -> Json.fromString(cause.toString),
        "applied" -> Json.fromLong(applied)
      )
    case Outcome.Refused(point, failure, applied) =>
      log(
        "refused",
        (pointFields(point) ++ List(
          "failure" -> Json.fromString(failure.toString),
          "applied" -> Json.fromLong(applied)
        ))*
      )

  private def log(event: String, fields: (String, Json)*): IO[Unit] =
    IO.println(
      Json
        .obj(
          (Vector(
            "kind" -> Json.fromString("leiosReplay"),
            "event" -> Json.fromString(event)
          ) ++ fields)*
        )
        .noSpaces
    )

  def run(args: List[String]): IO[ExitCode] =
    parse(args) match
      case Left(e) => IO.println(s"$Usage\n$e").as(ExitCode(2))
      case Right(w) =>
        Handle
          .allow[Error](window(w).flatTap(report).map(exitCode))
          .rescue(e => log("error", "error" -> Json.fromString(e.toString)).as(ExitCode.Error))
