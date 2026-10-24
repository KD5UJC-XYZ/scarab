// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.preview

import cats.effect.IO
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.Path as FsPath
import io.circe.Json
import io.circe.syntax.*
import java.io.RandomAccessFile
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import xyz.kd5ujc.scarab.plutus.{DataSerialiser, ScalusEvaluator}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{MirrorState, MirrorStep}
import xyz.kd5ujc.scarab.ledger.{Block, StorageError, ValidationError}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.node.{MirrorRules, NativeSnapshots, NodeSetup, ParallelPlutus}
import xyz.kd5ujc.scarab.storage.{RocksUtxoStore, UtxoStore}

/** Opt-in original-block acceptance. Every mode sees the same pre-block state and store; only the
  * Scala serial baseline advances. Input directories must be read-only mounts. Each window gets its
  * own fresh scratch store, closed before the next window in a single JVM.
  */
object RecordedReplay extends munit.Assertions:

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 255}%02x").mkString

  private def writeLine(path: Path, value: Json): IO[Unit] = IO.blocking {
    val _ = Files.writeString(
      path,
      value.noSpaces + "\n",
      StandardOpenOption.CREATE,
      StandardOpenOption.APPEND
    )
  }

  /** The parsed value and its binding digest always come from one read, including for optional
    * expected-failure files. Read-only mounts remain required for the original chunk/checkpoint
    * inputs.
    */
  private def readBound[A](path: Path)(
      parse: Vector[String] => Either[ReplayInputs.Error, A]
  ): IO[(A, String)] =
    IO.blocking(Files.readAllBytes(path)).map { bytes =>
      val value = ReplayInputs.lines(bytes).flatMap(parse).fold(e => fail(e.toString), identity)
      value -> sha256(bytes)
    }

  private def readBlock(config: ReplayInputs.Config, record: ReplayInputs.Record): IO[Bytes] =
    IO.blocking {
      val file =
        new RandomAccessFile(config.capture.resolve("immutable").resolve(record.chunk).toFile, "r")
      try
        assert(
          record.offset + record.length <= file.length(),
          s"record extends past ${record.chunk}"
        )
        val bytes = new Array[Byte](record.length)
        file.seek(record.offset)
        file.readFully(bytes)
        Bytes.fromArray(bytes)
      finally file.close()
    }

  private def countsJson(counts: ReplayTrace.Counts): Json = Json.obj(
    "reachedTransactions" -> counts.reachedTransactions.asJson,
    "evaluatedScripts" -> counts.evaluatedScripts.asJson,
    "failedTransactions" -> counts.failedTransactions.asJson,
    "failedScripts" -> counts.failedScripts.asJson,
    "unavailableTransactions" -> counts.unavailableTransactions.asJson,
    "collectionFailures" -> counts.collectionFailures.asJson,
    "usedMemory" -> counts.used.mem.toString.asJson,
    "usedSteps" -> counts.used.steps.toString.asJson
  )

  private def traceJson(transaction: ReplayTrace.Transaction): Json =
    val outcome = transaction.outcome match
      case ReplayTrace.Outcome.Passed(_)            => "passed"
      case ReplayTrace.Outcome.Failed(_, _)         => "failed"
      case ReplayTrace.Outcome.Unavailable(_, _, _) => "unavailable"
      case ReplayTrace.Outcome.Collection(_)        => "collection"
    Json.obj(
      "txId" -> transaction.id.hex.asJson,
      "slot" -> transaction.slot.toString.asJson,
      "isValid" -> transaction.isValid.asJson,
      "outcome" -> outcome.asJson,
      "evaluations" -> transaction.outcome.evaluations.map { evaluation =>
        val (kind, used, logs, failure) = evaluation.result match
          case ReplayTrace.Result.Passed(used, logs) => ("passed", used, logs, Json.Null)
          case ReplayTrace.Result.Failed(reason, used, logs) =>
            (
              "failed",
              used,
              logs,
              Json.obj(
                "kind" -> reason.productPrefix.asJson,
                "cause" -> reason.toString.asJson
              )
            )
        val request = Json.obj(
          "language" -> evaluation.request.language.id.asJson,
          "protocol" -> evaluation.request.protocolMajor.asJson,
          "script" -> evaluation.request.script.hex.asJson,
          "arguments" -> evaluation.request.args
            .map(arg => Bytes.fromArray(DataSerialiser.serialise(ScalusEvaluator.toData(arg))).hex)
            .asJson,
          "costModel" -> evaluation.request.costModel.asJson,
          "budgetMemory" -> evaluation.request.budget.mem.toString.asJson,
          "budgetSteps" -> evaluation.request.budget.steps.toString.asJson
        )
        Json.obj(
          "redeemerTag" -> evaluation.redeemer.tag.asJson,
          "redeemerIndex" -> evaluation.redeemer.index.toString.asJson,
          "language" -> evaluation.request.language.id.asJson,
          "scriptSha256" -> sha256(evaluation.request.script.toArray).asJson,
          "result" -> kind.asJson,
          "failure" -> failure,
          "requestSha256" -> sha256(request.noSpaces.getBytes(StandardCharsets.UTF_8)).asJson,
          "request" -> (if transaction.outcome.isInstanceOf[ReplayTrace.Outcome.Failed] then request
                        else Json.Null),
          "usedMemory" -> used.mem.toString.asJson,
          "usedSteps" -> used.steps.toString.asJson,
          "budgetMemory" -> evaluation.request.budget.mem.toString.asJson,
          "budgetSteps" -> evaluation.request.budget.steps.toString.asJson,
          "logs" -> logs.asJson
        )
      }.asJson
    )

  /** The callback is the existing synchronous `Observation => Unit` tool boundary. Its single
    * block-local builder only receives observations landed by the owner, never worker fibers. It is
    * sealed into an immutable trace after validation; normalization and JSON are untimed.
    */
  private def validate(
      setup: NodeSetup,
      store: UtxoStore[IO],
      state: MirrorState,
      block: Block,
      raw: Bytes,
      parallel: Option[ParallelPlutus[IO]],
      observe: Boolean
  )(using
      cats.mtl.Raise[IO, StorageError]
  ): IO[(MirrorState, Vector[ReplayTrace.Transaction], Long)] =
    IO.defer {
      val observations = new ReplayObservations
      val rules = MirrorRules[IO](
        setup.praos,
        setup.globals,
        setup.byronRules,
        setup.handOver,
        setup.byronHistory,
        phase2Observer = if observe then Some(observations.observe) else None,
        utxoStore = Some(store),
        parallelPlutus = parallel
      )
      Handle
        .allow[ValidationError] {
          for
            began <- IO.monotonic
            next <- rules(state, block.header, raw)
            ended <- IO.monotonic
          yield (next, observations.drain(), (ended - began).toNanos)
        }
        .rescue(e => IO(fail(e.message)))
    }

  /** Kept reusable by the same-JVM manifest test: no sbt restart or simultaneous checkpoint copies
    * are needed for the failure epochs. The caller removes only its own scratch output afterwards.
    */
  def replay(config: ReplayInputs.Config): IO[Json] =
    for
      setup <- IO
        .blocking(NodeSetup.fromDirectory(config.genesis))
        .map(_.fold(e => fail(e.message), identity))
      beganWindow <- IO.monotonic
      _ <- IO.blocking {
        assert(!Files.exists(config.output), s"output must be fresh: ${config.output}")
        Files.createDirectories(config.output)
      }
      recordInput <- readBound(config.capture.resolve("records.jsonl"))(ReplayInputs.records)
      (entries, recordsSha) = recordInput
      snapshotBytes <- IO.blocking(Files.readAllBytes(config.snapshot))
      // the archived recorded corpora are V5; NativeSnapshots brings them to V7 in memory
      _ <- IO(
        assert(
          Vector("SCRBSNP5", "SCRBSNP7").exists(m =>
            snapshotBytes.take(8).sameElements(m.getBytes("US-ASCII"))
          )
        )
      )
      loaded <- NativeSnapshots.decode[IO](snapshotBytes, setup.mirrorEnv())
      initial <- IO(loaded.fold(e => fail(e.message), identity))
      anchor <- IO(initial.anchor.getOrElse(fail("recorded replay requires a snapshot anchor")))
      genesisHashes <- IO.blocking {
        Vector(
          "byron-genesis.json",
          "shelley-genesis.json",
          "alonzo-genesis.json",
          "conway-genesis.json",
          "config.json",
          "configuration.json",
          "configuration.yaml"
        ).flatMap { name =>
          val file = config.genesis.resolve(name)
          Option.when(Files.isRegularFile(file))(name -> sha256(Files.readAllBytes(file)))
        }.toMap
      }
      expectedInput <- config.expectedFailures.traverse(path =>
        readBound(path)(ReplayInputs.expectedFailures)
      )
      expected = expectedInput.map(_._1)
      expectedSha = expectedInput.map(_._2)
      referenceInput <- config.reference.traverse(dir =>
        readBound(dir.resolve("index.jsonl"))(ReplayInputs.referenceTargets)
      )
      referenceTargets = referenceInput.fold(Map.empty[BigInt, String])(_._1)
      selected = entries.filter(_.slot > anchor.slot)
      _ <- IO(assert(selected.nonEmpty, "no block follows the baseline"))
      targets <- config.reference match
        case Some(_) =>
          IO(
            ReplayInputs
              .referenceWindow(referenceTargets, anchor.slot, selected)
              .fold(e => fail(e.toString), identity)
          )
        case None
            if config.legacyOutputs && config.schedule == ReplayInputs.Schedule.RotatedMeasurements =>
          IO.pure(Map.empty[BigInt, String])
        case None => IO(fail(ReplayInputs.Error.Missing(ReplayInputs.Setting.Reference).toString))
      references <- config.reference.fold(IO.pure(Map.empty[BigInt, ReplayReference.Bound])) {
        directory =>
          targets.toVector
            .sortBy(_._1)
            .traverse { (slot, hash) =>
              ReplayReference
                .load[IO](directory, slot, hash)
                .map(_.fold(e => fail(e.toString), value => slot -> value))
            }
            .map(_.toMap)
      }
      _ <- IO(
        assertEquals(
          references.keySet,
          targets.keySet,
          "every target has a complete native reference body"
        )
      )
      _ <- writeLine(
        config.output.resolve("binding.jsonl"),
        Json.obj(
          "snapshot" -> config.snapshot.toString.asJson,
          "snapshotSha256" -> sha256(snapshotBytes).asJson,
          "checkpoint" -> config.checkpoint.toString.asJson,
          "anchorSlot" -> anchor.slot.toString.asJson,
          "anchorBlock" -> anchor.blockNo.toString.asJson,
          "anchorHash" -> anchor.hash.hex.asJson,
          "recordsSha256" -> recordsSha.asJson,
          "referenceIndexSha256" -> referenceInput.map(_._2).asJson,
          "referenceBodiesSha256" -> references.toVector
            .sortBy(_._1)
            .map((slot, reference) => slot.toString -> reference.sha256)
            .toMap
            .asJson,
          "genesisSha256" -> genesisHashes.asJson,
          "expectedFailuresSha256" -> expectedSha.asJson,
          "schedule" -> config.schedule.toString.asJson,
          "workers" -> config.workers.map(_.value).asJson,
          "rounds" -> config.rounds.asJson
        )
      )
      summary <- Handle
        .allow[StorageError] {
          RocksUtxoStore.resource[IO](FsPath.fromNioPath(config.output.resolve("rocks"))).use {
            store =>
              for
                restored <- store.restore(FsPath.fromNioPath(config.checkpoint))
                _ <- IO(assert(restored, "baseline checkpoint missing"))
                stamp <- store.flushed
                _ <- IO(
                  assertEquals(stamp, initial.state.utxo.flushed, "checkpoint/snapshot stamp")
                )
                services <- config.workers
                  .traverse(n => ParallelPlutus.create[IO](n).map(n.value -> _))
                modes = (0 -> Option.empty[ParallelPlutus[IO]]) +: services
                  .map((n, s) => n -> Some(s))
                finalPair <- selected.zipWithIndex.foldLeftM(
                  (
                    initial.state,
                    ReplayTrace.Counts(),
                    Vector.empty[Bytes],
                    Set.empty[BigInt],
                    anchor
                  )
                ) { case ((state, counts, failedIds, exported, previous), (record, index)) =>
                  for
                    raw <- readBlock(config, record)
                    block <- IO(Block.decodeShelley(raw).fold(e => fail(e.message), identity) match
                      case b: Block.Shelley => b
                      case _                => fail("Preview capture contains a Byron block"))
                    linked <- IO(
                      ReplayInputs
                        .link(previous, block.header)
                        .fold(e => fail(e.toString), identity)
                    )
                    blockSha = sha256(raw.toArray)
                    _ <- IO(
                      record.check(block.header, blockSha).fold(e => fail(e.toString), identity)
                    )
                    original <- validate(setup, store, state, block, raw, None, config.trace)
                    (serialState, trace, originalNanos) = original
                    measuredModes = config.schedule match
                      case ReplayInputs.Schedule.Comparison          => modes.filter(_._1 != 0)
                      case ReplayInputs.Schedule.RotatedMeasurements => modes
                    _ <- writeLine(
                      config.output.resolve("timings.jsonl"),
                      Json.obj(
                        "slot" -> record.slot.asJson,
                        "block" -> block.header.blockNo.asJson,
                        "hash" -> block.header.hash.hex.asJson,
                        "round" -> 0.asJson,
                        "workers" -> 0.asJson,
                        "nanos" -> originalNanos.asJson
                      )
                    ).whenA(config.schedule == ReplayInputs.Schedule.Comparison)
                    _ <- (0 until config.rounds).toVector.traverse_ { round =>
                      val rotation = (index + round) % measuredModes.size
                      (measuredModes.drop(rotation) ++ measuredModes
                        .take(rotation)).traverse_ { (workers, service) =>
                        for
                          actual <- validate(setup, store, state, block, raw, service, config.trace)
                          _ <- IO {
                            assert(
                              actual._1 == serialState,
                              s"full state: slot=${record.slot} workers=$workers"
                            )
                            assertEquals(
                              ReplayTrace.compare(trace, actual._2),
                              None,
                              s"phase2 order/requests/results/budgets/logs: slot=${record.slot} workers=$workers"
                            )
                          }
                          _ <- writeLine(
                            config.output.resolve("timings.jsonl"),
                            Json.obj(
                              "slot" -> record.slot.asJson,
                              "block" -> block.header.blockNo.asJson,
                              "hash" -> block.header.hash.hex.asJson,
                              "round" -> round.asJson,
                              "workers" -> workers.asJson,
                              "nanos" -> actual._3.asJson
                            )
                          )
                        yield ()
                      }
                    }
                    _ <- writeLine(
                      config.output.resolve("blocks.jsonl"),
                      Json.obj(
                        "slot" -> record.slot.asJson,
                        "block" -> block.header.blockNo.asJson,
                        "hash" -> block.header.hash.hex.asJson,
                        "sha256" -> blockSha.asJson,
                        "transactions" -> trace.map(traceJson).asJson
                      )
                    )
                    _ <-
                      if index % 128 == 127 then UtxoStore.advance(store, serialState.utxo)
                      else IO.unit
                    flushed <- store.flushed
                    next = MirrorStep.mapUtxo(serialState)(_.settled(flushed))
                    _ <- references.get(record.slot).traverse_ { native =>
                      for
                        _ <- IO(
                          assertEquals(block.header.hash.hex, native.hash, "native reference point")
                        )
                        exported <- K1Projection
                          .write(block.header, next, store, config.output.resolve("dump"))
                          .flatMap(_.fold(error => IO(fail(error.toString)), IO.pure))
                        actualBytes <- IO.blocking(Files.readAllBytes(exported))
                        actual <- IO {
                          val text =
                            ReplayInputs.lines(actualBytes).fold(e => fail(e.toString), identity)
                          io.circe.parser
                            .parse(text.mkString("\n"))
                            .fold(e => fail(e.message), identity)
                        }
                        _ <- IO(
                          ReplayReference
                            .compare(native, actual, block.era)
                            .fold(e => fail(e.toString), identity)
                        )
                        _ <- writeLine(
                          config.output.resolve("reference-comparisons.jsonl"),
                          Json.obj(
                            "slot" -> record.slot.asJson,
                            "hash" -> native.hash.asJson,
                            "referenceFile" -> native.source.toString.asJson,
                            "referenceSha256" -> native.sha256.asJson,
                            "actualSha256" -> sha256(actualBytes).asJson,
                            "matchedRequiredNativeFields" -> true.asJson
                          )
                        )
                      yield ()
                    }
                    _ <- IO
                      .println(s"PREVIEW_REPLAY blocks=${index + 1} slot=${record.slot}")
                      .whenA(index % 500 == 499)
                  yield (
                    next,
                    counts.add(trace),
                    failedIds ++ trace.collect {
                      case t @ ReplayTrace.Transaction(_, _, _, ReplayTrace.Outcome.Failed(_, _)) =>
                        t.id
                    },
                    if targets.contains(record.slot) then exported + record.slot else exported,
                    linked
                  )
                }
                (after, totals, failedIds, exported, _) = finalPair
                _ <- IO {
                  assertEquals(
                    after.plutus.failed - initial.state.plutus.failed,
                    totals.failedTransactions
                  )
                  assertEquals(
                    after.plutus.scripts - initial.state.plutus.scripts,
                    totals.evaluatedScripts
                  )
                }.whenA(config.trace)
                _ <- IO {
                  assertEquals(
                    exported,
                    targets.keySet,
                    "every required native body was compared, without filtering any reference target"
                  )
                  expected.foreach(ids =>
                    assertEquals(failedIds, ids, "original invalid transactions, in order")
                  )
                  assertEquals(totals.unavailableTransactions, 0L)
                  assertEquals(totals.collectionFailures, 0L)
                }
                _ <- UtxoStore.advance(store, after.utxo)
              yield Json.obj(
                "blocks" -> selected.size.asJson,
                "failedTransactionIds" -> failedIds.map(_.hex).asJson,
                "referenceTargetsExported" -> exported.size.asJson,
                "referenceTargetsRequired" -> targets.size.asJson,
                "referenceBodyComparisons" -> exported.size.asJson,
                "comparisonScope" -> (if config.reference.isDefined then
                                        "required-native-projection-fields-and-scala-execution-modes"
                                      else "scala-execution-modes-only").asJson,
                "phase2" -> countsJson(totals),
                "finalScripts" -> after.plutus.scripts.asJson,
                "initialScripts" -> initial.state.plutus.scripts.asJson
              )
          }
        }
        .rescue(e => IO(fail(e.message)))
      _ <- IO.blocking {
        val walk = Files.walk(config.output.resolve("rocks"))
        try walk.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete)
        finally walk.close()
      }
      endedWindow <- IO.monotonic
      seconds = (endedWindow - beganWindow).toNanos / 1e9
      compatible =
        if config.legacyOutputs then
          Json.obj(
            "blocks" -> summary.hcursor.downField("blocks").focus.getOrElse(Json.Null),
            "initialScripts" -> summary.hcursor
              .downField("initialScripts")
              .focus
              .getOrElse(Json.Null),
            "finalScripts" -> summary.hcursor.downField("finalScripts").focus.getOrElse(Json.Null),
            "seconds" -> seconds.asJson,
            "comparisonScope" -> Json.fromString("scala-execution-modes-only")
          )
        else summary.deepMerge(Json.obj("seconds" -> seconds.asJson))
      _ <- IO.blocking(Files.writeString(config.output.resolve("summary.json"), compatible.spaces2))
      _ <-
        if !config.legacyOutputs then IO.unit
        else
          IO.blocking {
            Files.writeString(config.output.resolve("replay-summary.json"), compatible.spaces2)
            val rows =
              Files.readAllLines(config.output.resolve("timings.jsonl")).asScala.toVector.map {
                line =>
                  val json = io.circe.parser.parse(line).fold(e => fail(e.message), identity)
                  json.mapObject(_.filter { case (key, _) =>
                    Set("slot", "round", "workers", "nanos")(key)
                  })
              }
            val _ =
              Files.writeString(config.output.resolve("timings.json"), Json.arr(rows*).noSpaces)
          }
    yield compatible
