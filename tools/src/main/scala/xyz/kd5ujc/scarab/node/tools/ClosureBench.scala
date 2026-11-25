// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import cats.data.NonEmptyVector
import cats.effect.{ExitCode, IO, IOApp, Ref}
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import fs2.io.file.{Files, Path as FsPath}
import io.circe.Json
import java.nio.file.{Files as JFiles, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ByronState, ConsensusExtras, FollowState}
import xyz.kd5ujc.scarab.consensus.leios.EbClosureVerdict
import xyz.kd5ujc.scarab.crypto.bls.RbHash
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, MirrorExtras, ProtocolExtras}
import xyz.kd5ujc.scarab.ledger.{Nonce, StorageError, TxIn, TxOut, Utxo}
import xyz.kd5ujc.scarab.ledger.state.{
  Dijkstra,
  NewEpochState,
  NodeExtras,
  ShelleyBasedEra,
  SlotNo,
  UtxoTables
}
import xyz.kd5ujc.scarab.node.{LeiosClosureCheck, LeiosTxCache, NodeSetup, VerificationCache}
import xyz.kd5ujc.scarab.node.mempool.{DijkstraPoolEnv, MempoolEnv}
import xyz.kd5ujc.scarab.plutus.PlutusBackend
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, Era, StateLayout}
import xyz.kd5ujc.scarab.storage.LeiosEb
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellLedgerFile, SnapshotTableStream}

/** The closure check of an endorser block, timed (#1380): the voting thread's check
  * ([[LeiosClosureCheck.validateEbClosure]], as `LeiosVoter.validateClosure` calls it) of a closure
  * of signed transactions on a reference ledger state, run several times, each from a fresh
  * transaction cache and a fresh witness cache, so that the first run is the cold JVM's and the
  * others are warm.
  *
  * The state is a reference node's ledger snapshot directory (`ledger/<slot>`: `state`, `tables`,
  * `meta`) of the Leios build, read in memory: the extended state of `state`, and of `tables` only
  * the outputs at `address` (hex of the address bytes, or `all` for the whole set), which the
  * closure's transactions spend from before it (each output the closure itself makes is made as it
  * is applied). The closure is the `.tx` envelopes `cardano-cli dijkstra transaction sign` writes,
  * read as [[TxSubmitPeer]] reads them, from the directories or files given, in that order (a
  * directory's in name order), and checked as an endorser block of the slot after the snapshot's.
  *
  * `cached` (0 to 1) is the fraction of the closure the cache holds as validated under the state's
  * parity, spread evenly over it, as the transactions a voter's mempool held before the block:
  * those are reapplied, the others applied in full. Each run prints one JSON line (`closureBench`):
  * the verdict, how many were reapplied and applied, the wall time of the check and its
  * milliseconds per transaction (none of the three for a closure that is not valid, which the check
  * stops at its first refusal). The timed span is the voter's from the tick on: the state ticked to
  * the slot and the closure checked.
  *
  * What it is not: the live voter's latency. The closure is handed over in memory, not looked up in
  * the endorser-block store; the outputs are in the state, not read from the node's UTxO store; and
  * each run's witness cache is empty, where a live node's may hold signatures the mempool checked.
  * It measures the check itself, in memory with a cold witness cache, so that hosts, builds and
  * implementations can be compared on the same input.
  *
  * Run, inside the build container: `tools/runMain xyz.kd5ujc.scarab.node.tools.ClosureBench
  * <config.json> <snapshot dir> <address hex> <cached> <runs> <tx file or directory>...`. Hand-run,
  * not part of the node, not run by `check`.
  */
object ClosureBench extends IOApp:
  /** The build whose snapshots and transactions the bench reads. */
  val Build = LeiosReplayMain.Build

  enum Problem:
    case Usage
    case Setup(detail: String)
    case State(detail: String)
    case Tables(detail: String)
    case Transactions(detail: String)
    case Tick(detail: String)
    case Storage(error: StorageError)

    def message: String = this match
      case Usage =>
        "usage: ClosureBench <config.json> <snapshot dir> <address hex|all> <cached 0..1> <runs> " +
          "<tx file or directory>..."
      case Setup(m)        => s"setup: $m"
      case State(m)        => s"state: $m"
      case Tables(m)       => s"tables: $m"
      case Transactions(m) => s"transactions: $m"
      case Tick(m)         => s"tick: $m"
      case Storage(e)      => s"storage: ${e.message}"

  final case class Options(
      config: Path,
      snapshot: Path,
      address: Option[Bytes],
      cached: BigDecimal,
      runs: Int,
      txs: List[Path]
  )

  def parse(args: List[String]): Either[Problem, Options] = args match
    case config :: snapshot :: address :: cached :: runs :: txs if txs.nonEmpty =>
      (
        (if address == "all" then Some(None) else Bytes.fromHex(address).toOption.map(Some(_))),
        scala.util.Try(BigDecimal(cached)).toOption.filter(c => c >= 0 && c <= 1),
        runs.toIntOption.filter(_ > 0)
      ).mapN((a, c, r) => Options(Path.of(config), Path.of(snapshot), a, c, r, txs.map(Path.of(_))))
        .toRight(Problem.Usage)
    case _ => Left(Problem.Usage)

  /** The extended state of the snapshot's `state` file and its slot, the name of its directory. */
  private def stateOf(snapshot: Path)(using
      Raise[IO, Problem]
  ): IO[(xyz.kd5ujc.scarab.consensus.state.ExtLedgerState, BigInt)] =
    for
      raw <- IO.blocking(Bytes.wrap(JFiles.readAllBytes(snapshot.resolve("state"))))
      file <- HaskellLedgerFile
        .decode(raw, StateLayout.Leios)
        .leftMap(e => Problem.State(e.render))
        .liftTo[IO]
      ext <- file.ext.toRight(Problem.State("no Shelley-based ledger")).liftTo[IO]
      slot <- snapshot.getFileName.toString.toLongOption
        .map(BigInt(_))
        .toRight(Problem.State(s"${snapshot.getFileName} is not a slot"))
        .liftTo[IO]
    yield (ext, slot)

  extension [A](e: Either[Problem, A])
    private def liftTo[F[_]](using F: Raise[F, Problem], A: cats.Applicative[F]): F[A] =
      e.fold(F.raise(_), A.pure)

  /** The outputs of the snapshot's `tables` at `address` (all of them for none), streamed once. */
  private def outputsAt(snapshot: Path, address: Option[Bytes])(using
      Raise[IO, Problem]
  ): IO[Map[TxIn, TxOut]] =
    Ref.of[IO, Map[TxIn, TxOut]](Map.empty).flatMap { kept =>
      val sink = new SnapshotTableStream.SemanticSink[IO, Dijkstra]:
        def insert(key: TxIn, value: TxOut): IO[Unit] =
          kept.update(_.updated(key, value)).whenA(address.forall(value.address.raw == _))
      Handle
        .allow[xyz.kd5ujc.scarab.storage.haskell.state.SnapshotTables.Error](
          SnapshotTableStream
            .readInto[IO, Dijkstra](
              Files[IO].readAll(FsPath.fromNioPath(snapshot.resolve("tables"))),
              sink
            )
            .void
        )
        .rescue(e => Raise[IO, Problem].raise(Problem.Tables(e.toString)))
        *> kept.get
    }

  /** `nes` with `outputs` as its UTxO set. */
  private def withOutputs(nes: NewEpochState[Dijkstra], outputs: Map[TxIn, TxOut]) =
    nes.copy[Dijkstra](es =
      nes.es.copy[Dijkstra](ls =
        nes.es.ls.copy[Dijkstra](utxo =
          nes.es.ls.utxo.copy[Dijkstra](utxo = UtxoTables(Utxo.fromMap(outputs)))
        )
      )
    )

  /** A submitted transaction (`cardano-cli` 's envelope form) in the form a block and an endorser
    * block's closure hold it, with its validity flag, as the forger writes it
    * ([[xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx.forBlockInclusion]]).
    */
  private[tools] def inBlockForm(submitted: Bytes): Either[Unconverted, Bytes] =
    xyz.kd5ujc.scarab.codec.Cbor
      .decode(submitted, xyz.kd5ujc.scarab.codec.Cbor.Limits.Unbounded)
      .leftMap(Unconverted.Syntax(_))
      .flatMap(n =>
        xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx.submitted(n).leftMap(Unconverted.Tx(_))
      )
      .map(xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx.forBlockInclusion)

  /** Why a submitted transaction is not turned into its block form: its bytes are not CBOR, or not
    * a Dijkstra transaction.
    */
  enum Unconverted:
    case Syntax(cause: xyz.kd5ujc.scarab.codec.Cbor.Error)
    case Tx(cause: xyz.kd5ujc.scarab.ledger.era.EraTxError)

  /** The closure: each transaction's bytes, in order, under its hash as an endorser block lists it.
    */
  private def closureOf(paths: List[Path])(using
      Raise[IO, Problem]
  ): IO[NonEmptyVector[Bytes]] =
    val reader = xyz.kd5ujc.scarab.node.mempool.RelayedTransactions.of(Build)
    val era = TxSubmitPeer.eras("dijkstra")
    paths
      .flatTraverse(p => TxSubmitPeer.files(p).map(_.toList))
      .flatMap(
        _.traverse(f =>
          IO.blocking(JFiles.readString(f)).map(TxSubmitPeer.signed(f, _, reader, era))
        )
      )
      .flatMap(read =>
        read.sequence
          .leftMap(u => Problem.Transactions(u.message))
          .flatMap(
            _.toVector.traverse(t =>
              inBlockForm(t.original).leftMap(e => Problem.Transactions(s"${t.file}: $e"))
            )
          )
          .flatMap(txs => NonEmptyVector.fromVector(txs).toRight(Problem.Transactions("none")))
          .liftTo[IO]
      )

  /** The positions of a closure of `n` transactions that a cache holding the fraction `cached` of
    * it holds, spread evenly: position `i` is held when the floor of `cached` times `i + 1` exceeds
    * that of `cached` times `i`, exactly, so that `n` times `cached`, floored, are held.
    */
  private[tools] def seeded(n: Int, cached: BigDecimal): Vector[Int] =
    // the product taken exactly, not within the default precision, before it is floored
    def floored(i: Int) =
      cached.bigDecimal
        .multiply(java.math.BigDecimal.valueOf(i.toLong))
        .setScale(0, java.math.RoundingMode.FLOOR)
    (0 until n).filter(i => floored(i + 1).compareTo(floored(i)) > 0).toVector

  final case class Run(
      run: Int,
      verdict: String,
      reapplied: Option[Long],
      applied: Option[Long],
      seconds: Double,
      msPerTx: Option[Double]
  )

  def bench(options: Options)(using Raise[IO, Problem]): IO[Vector[Run]] =
    for
      setup <- IO
        .blocking(NodeSetup.fromConfigFile(Build)(options.config, None))
        .flatMap(_.leftMap(e => Problem.Setup(e.message)).liftTo[IO])
      loaded <- stateOf(options.snapshot)
      (ext0, snapshotSlot) = loaded
      outputs <- outputsAt(options.snapshot, options.address)
      _ <- log(
        "state",
        "slot" -> Json.fromBigInt(snapshotSlot),
        "outputs" -> Json.fromInt(outputs.size)
      )
      ext <- (ext0.ledgerState.state match
        case EraLedgerState.InShelleyEra(ShelleyBasedEra.SDijkstra, s) =>
          Right(
            ext0.copy(ledgerState =
              ext0.ledgerState.withState(
                EraLedgerState.InShelleyEra(
                  ShelleyBasedEra.SDijkstra,
                  s.copy(nes = withOutputs(s.nes, outputs))
                )
              )
            )
          )
        case other => Left(Problem.State(s"not in Dijkstra: ${other.era}"))
      ).liftTo[IO]
      nes <- (ext.ledgerState.state match
        case EraLedgerState.InShelleyEra(ShelleyBasedEra.SDijkstra, s) => Right(s.nes)
        case other => Left(Problem.State(s"not in Dijkstra: ${other.era}"))
      ).liftTo[IO]
      mirror = setup.mirrorStart.copy(
        ext = ext,
        extras = MirrorExtras.InShelley(
          NodeExtras.empty,
          ConsensusExtras(
            FollowState(Some(Era.Dijkstra), None, None),
            ByronState.initial,
            ProtocolExtras(BigInt(100), Nonce.Neutral)
          )
        )
      )
      closure <- closureOf(options.txs)
      _ <- log("closure", "transactions" -> Json.fromInt(closure.length))
      slot = snapshotSlot + 1
      plutus = PlutusBackend.default
      env = MempoolEnv(setup.globals, setup.geometry, plutus.interruptible, plutus.decoder)
      at <- SlotNo.fromBigInt(slot).leftMap(v => Problem.Tick(v.toString)).liftTo[IO]
      tick = IO
        .delay(LeiosClosureCheck.ticked(env.rules, nes, at))
        .flatMap(_.leftMap(e => Problem.Tick(e.toString)).liftTo[IO])
      // the parity the cache is seeded under; each run ticks again inside its timed span
      parityState <- tick
      body = LeiosEb.of(closure)
      hashes = body.transactions.toVector.map(_._1)
      eb = EbHash.fromBytes(xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(body.encoded))
      point = EbPoint(xyz.kd5ujc.scarab.protocol.LeiosWord.Word64.fromUnsigned(slot.toLong), eb)
      entries = hashes.zip(closure.toVector)
      cachedSet = seeded(hashes.length, options.cached).map(hashes)
      parity = LeiosClosureCheck.parityOf(parityState)
      // the announcing block's hash stands for no real block: any 32 bytes key the announcement
      announcing <- RbHash
        .fromBytes(eb.bytes)
        .toRight(Problem.Transactions("an endorser block hash is not 32 bytes"))
        .liftTo[IO]
      runs <- (1 to options.runs).toVector.traverse { run =>
        VerificationCache.resource[IO](setup.layout).use { witnesses =>
          for
            cache <- LeiosTxCache.create[IO]
            _ <- cache.insertAnnouncement(snapshotSlot, announcing, eb)
            _ <- cache.insertBody(eb, body)
            _ <- cache.insertApplied(cachedSet, parity)
            start <- IO.monotonic
            ticked <- tick
            verdict <- Handle
              .allow[StorageError](
                LeiosClosureCheck.validateEbClosure[IO](
                  h => IO.pure(Option.when(h == eb)(entries)),
                  None,
                  cache,
                  witnesses.warmWitnesses[IO],
                  point,
                  ticked,
                  DijkstraPoolEnv
                    .of(env, slot)
                    .copy(
                      clock = setup.txClock.at(mirror, Era.Dijkstra, slot),
                      verdicts = witnesses
                    )
                )
              )
              .rescue(e => Raise[IO, Problem].raise(Problem.Storage(e)))
            end <- IO.monotonic
            seconds = (end - start).toNanos / 1e9
            result = verdict match
              case EbClosureVerdict.Valid(reapplied, applied) =>
                Run(
                  run,
                  "valid",
                  Some(reapplied.toLong),
                  Some(applied.toLong),
                  seconds,
                  Some(1000 * seconds / closure.length)
                )
              case EbClosureVerdict.Invalid(reason) =>
                Run(run, s"invalid: $reason", None, None, seconds, None)
              case EbClosureVerdict.Unreadable(defect) =>
                Run(run, s"unreadable: $defect", None, None, seconds, None)
            _ <- log(
              "run",
              "run" -> Json.fromInt(run),
              "verdict" -> Json.fromString(result.verdict),
              "transactions" -> Json.fromInt(closure.length),
              "cached" -> Json.fromInt(cachedSet.length),
              "reapplied" -> result.reapplied.fold(Json.Null)(Json.fromLong),
              "applied" -> result.applied.fold(Json.Null)(Json.fromLong),
              "seconds" -> Json.fromDoubleOrNull(result.seconds),
              "msPerTx" -> result.msPerTx.fold(Json.Null)(Json.fromDoubleOrNull),
              "scope" -> Json.fromString("in memory, cold witness cache, no store lookup")
            )
          yield result
        }
      }
    yield runs

  private def log(event: String, fields: (String, Json)*): IO[Unit] =
    IO.println(Json.obj(("closureBench" -> Json.fromString(event)) +: fields*).noSpaces)

  def run(args: List[String]): IO[ExitCode] =
    Handle
      .allow[Problem](
        parse(args).liftTo[IO].flatMap(bench).as(ExitCode.Success)
      )
      .rescue(p => IO.println(p.message).as(ExitCode.Error))
