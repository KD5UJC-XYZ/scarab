// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.preview

import cats.effect.{ExitCode, IO, IOApp, Ref}
import cats.mtl.{Ask, Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path as FsPath
import io.circe.Json
import io.circe.syntax.*
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorState}
import xyz.kd5ujc.scarab.ledger.{Header, NodeError, StorageError}
import xyz.kd5ujc.scarab.node.{MirrorRules, NodeSetup, ParallelPlutus, VerificationCache}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.storage.*

/** Offline acceptance through the production startup replay, from genesis. The caller stages an
  * immutable prefix in a fresh private database, with archived inputs mounted read-only. No peer or
  * follower is constructed. Snapshot and checkpoint writes have the production policy (V5,
  * retention two). The endpoint/count are independently authenticated by the staging manifest.
  *
  * Arguments: scratch-db genesis-dir reference-index output-dir blocks last-slot last-header-hash.
  */
object K1OfflineMain extends IOApp:
  enum Error extends DomainError:
    case Arguments
    case NotFresh(path: Path)
    case BlockOrder(expected: BigInt, actual: BigInt)
    case ReferencePoint(slot: BigInt, expected: String, actual: String)
    case Endpoint(expectedBlocks: Long, actualBlocks: Long, slot: BigInt, hash: String)
    case MissingTargets(slots: Set[BigInt])
    case SnapshotEvents(events: Vector[StorageEvent])
    case Projection(cause: xyz.kd5ujc.scarab.ledger.state.MirrorError)

  private final case class Config(
      db: Path,
      genesis: Path,
      index: Path,
      out: Path,
      blocks: Long,
      slot: BigInt,
      hash: String
  )
  private final case class Progress(
      blocks: Long = 0,
      slot: BigInt = -1,
      hash: String = "",
      counts: ReplayTrace.Counts = ReplayTrace.Counts(),
      failed: Vector[String] = Vector.empty,
      exported: Set[BigInt] = Set.empty
  )

  private def refuse[A](error: Error)(using Raise[IO, StorageError]): IO[A] =
    StorageError.Corrupt("offline K1 acceptance", error).raise[IO, A]

  private def guard[A](path: Path)(body: => A)(using Raise[IO, StorageError]): IO[A] =
    AtomicFile.guard[IO, A](path.toString)(body)

  private def runReplay(c: Config)(using Raise[IO, NodeError]): IO[Unit] =
    for
      _ <- Vector(c.out, c.db.resolve("snapshots"), c.db.resolve("utxo"), c.db.resolve("volatile"))
        .traverse_(path =>
          guard(path)(Files.exists(path)).flatMap(exists =>
            refuse[Unit](Error.NotFresh(path)).whenA(exists)
          )
        )
      loaded <- IO
        .delay(NodeSetup.fromDirectory(c.genesis))
        .flatMap(_.fold(_.raise[IO, NodeSetup], IO.pure))
      targets <- guard(c.index)(Files.readAllLines(c.index).asScala.toVector)
        .flatMap(lines =>
          ReplayInputs
            .referenceTargets(lines)
            .fold(
              error => StorageError.Corrupt(c.index.toString, error).raise[IO, Map[BigInt, String]],
              IO.pure
            )
        )
        .map(_.filter((slot, _) => slot <= c.slot))
      _ <- guard(c.out)(Files.createDirectories(c.out))
      progress <- Ref.of[IO, Progress](Progress())
      storageFailures <- Ref.of[IO, Vector[StorageEvent]](Vector.empty)
      observations <- IO.delay(new ReplayObservations)
      started <- IO.monotonic
      _ <- DatabaseOwnership.resource[IO](FsPath.fromNioPath(c.db)).use { _ =>
        (
          RocksUtxoStore.resource[IO](FsPath.fromNioPath(c.db.resolve("utxo/live"))),
          VerificationCache.resource[IO]()
        ).tupled.use { (store, cache) =>
          val setup = loaded.copy(praos = loaded.praos.copy(verdicts = cache))
          given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
          given SnapshotFormat[IO, MirrorState] = SnapshotV5.format[IO](SnapshotCheck.Full)
          val backing = new UtxoBacking[IO, MirrorState](
            store,
            FsPath.fromNioPath(c.db.resolve("utxo/checkpoints"))
          )
          val after = (header: Header, state: MirrorState) =>
            (storage: Raise[IO, StorageError]) ?=>
              for
                _ <- IO.delay(cache.noteEpochNonce(state.ledgerEpoch, state.protocol.epochNonce))
                traces <- IO.delay(observations.drain())
                previous <- progress.get
                _ <- refuse[Unit](Error.BlockOrder(BigInt(previous.blocks), header.blockNo))
                  .unlessA(header.blockNo == BigInt(previous.blocks))
                exported <- targets.get(header.slot).traverse { expected =>
                  for
                    _ <- refuse[Unit](Error.ReferencePoint(header.slot, expected, header.hash.hex))
                      .unlessA(header.hash.hex == expected)
                    _ <- header match
                      case shelley: Header.Shelley =>
                        K1Projection
                          .write(shelley, state, store, c.out.resolve("dump"))
                          .flatMap(_.fold(error => refuse[Path](Error.Projection(error)), IO.pure))
                          .void
                      case _ =>
                        refuse[Unit](Error.ReferencePoint(header.slot, expected, header.hash.hex))
                  yield header.slot
                }
                _ <- progress.set(
                  Progress(
                    previous.blocks + 1,
                    header.slot,
                    header.hash.hex,
                    previous.counts.add(traces),
                    previous.failed ++ traces.collect {
                      case t @ ReplayTrace.Transaction(_, _, _, ReplayTrace.Outcome.Failed(_, _)) =>
                        t.id.hex
                    },
                    previous.exported ++ exported
                  )
                )
              yield ()
          val events = new StorageEvents[IO]:
            def emit(event: StorageEvent): IO[Unit] =
              val retain = event match
                case _: StorageEvent.SnapshotFailed | _: StorageEvent.SnapshotRefused |
                    _: StorageEvent.SnapshotSkipped =>
                  storageFailures.update(_ :+ event)
                case _ => IO.unit
              retain *> IO.println(s"K1_STORAGE $event")
          ParallelPlutus.Workers
            .from(4)
            .fold(
              error => StorageError.Corrupt("Plutus worker count", error).raise[IO, Unit],
              workers =>
                ParallelPlutus.create[IO](workers).flatMap { parallel =>
                  val rules = MirrorRules[IO](
                    setup.praos,
                    setup.globals,
                    setup.byronRules,
                    setup.handOver,
                    setup.byronHistory,
                    phase2Observer = Some(observations.observe),
                    utxoStore = Some(store),
                    afterBlock = Some(after),
                    parallelPlutus = Some(parallel)
                  )
                  ChainStorage
                    .open[IO, MirrorState](
                      FsPath.fromNioPath(c.db),
                      ChainStorage.Config(
                        SnapshotPolicy.reference(BigInt(setup.genesis.geometry.securityParam)),
                        keepSnapshots = 2,
                        byronEpochSlots = setup.geometry.byron.epochSlots,
                        progressEvery = 10000
                      ),
                      rules,
                      setup.mirrorStart,
                      events,
                      backing,
                      raw => cache.warm[IO](raw, setup.praos)
                    )
                    .use(_ => IO.unit)
                }
            )
        }
      }
      finished <- IO.monotonic
      result <- progress.get
      failures <- storageFailures.get
      _ <- refuse[Unit](Error.SnapshotEvents(failures)).whenA(failures.nonEmpty)
      _ <- refuse[Unit](Error.Endpoint(c.blocks, result.blocks, result.slot, result.hash))
        .unlessA(result.blocks == c.blocks && result.slot == c.slot && result.hash == c.hash)
      missing = targets.keySet -- result.exported
      _ <- refuse[Unit](Error.MissingTargets(missing)).whenA(missing.nonEmpty)
      summary = Json.obj(
        "blocks" -> result.blocks.asJson,
        "lastSlot" -> result.slot.asJson,
        "lastHash" -> result.hash.asJson,
        "workers" -> 4.asJson,
        "elapsedNanos" -> (finished - started).toNanos.asJson,
        "referenceTargetsExpected" -> targets.size.asJson,
        "referenceTargetsExported" -> result.exported.size.asJson,
        "reachedTransactions" -> result.counts.reachedTransactions.asJson,
        "evaluatedScripts" -> result.counts.evaluatedScripts.asJson,
        "failedTransactions" -> result.counts.failedTransactions.asJson,
        "failedScripts" -> result.counts.failedScripts.asJson,
        "unavailableTransactions" -> result.counts.unavailableTransactions.asJson,
        "collectionFailures" -> result.counts.collectionFailures.asJson,
        "usedMemory" -> result.counts.used.mem.toString.asJson,
        "usedSteps" -> result.counts.used.steps.toString.asJson,
        "failedTransactionIds" -> result.failed.asJson
      )
      _ <- AtomicFile.replace[IO](
        FsPath.fromNioPath(c.out.resolve("summary.json")),
        summary.noSpaces.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      )
      _ <- IO.println(s"K1_COMPLETE ${summary.noSpaces}")
    yield ()

  def run(args: List[String]): IO[ExitCode] =
    args match
      case List(db, genesis, index, out, blocks, slot, hash)
          if blocks.toLongOption
            .exists(_ > 0) && scala.util.Try(BigInt(slot)).toOption.exists(_ >= 0) &&
            hash.matches("[0-9a-f]{64}") =>
        val config = Config(
          Path.of(db),
          Path.of(genesis),
          Path.of(index),
          Path.of(out),
          blocks.toLong,
          BigInt(slot),
          hash
        )
        Handle
          .allow[NodeError](runReplay(config).as(ExitCode.Success))
          .rescue(error => IO.println(s"K1_REFUSED ${error.message}").as(ExitCode.Error))
      case _ => IO.println(s"K1_REFUSED ${Error.Arguments}").as(ExitCode.Error)
