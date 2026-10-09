// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, IOApp, ExitCode, Ref}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.header.PraosNonceEvolution as Nonces
import lab.network.ChainSync
import ReferenceJson.Json
import CoherentSequence.*
import scala.concurrent.duration.*

/** Retained-file process adapter only. No peer, reference-node or durable production API changes.
  */
object ValidatedRestartCapture extends IOApp:
  private def str(s: String): Json = Json.Str(s)
  private def number(n: BigInt): Json = str(n.toString)
  private def obj(fields: (String, Json)*): Json = Json.Obj(fields.toMap)
  private def array(values: Iterable[Json]): Json = Json.Arr(values.toVector)
  private def hash(b: Bytes): Json = str(b.hex)
  private def quote(s: String): String = "\"" + s.flatMap {
    case '"'          => "\\\""
    case '\\'         => "\\\\"
    case c if c < ' ' => f"\\u${c.toInt}%04x"
    case c            => c.toString
  } + "\""
  private[lab] def canonical(value: Json): String = value match
    case Json.Obj(fields) =>
      fields.toVector
        .sortBy(_._1)
        .map((k, v) => quote(k) + ":" + canonical(v))
        .mkString("{", ",", "}")
    case Json.Arr(values) => values.map(canonical).mkString("[", ",", "]")
    case Json.Str(s)      => quote(s)
    case Json.Num(n)      => n
    case Json.Lit(v)      => v
  private def sha(s: String): String =
    ClusterHeaderObservation.sha256(Bytes.fromArray(s.getBytes("UTF-8"))).hex
  private def point(p: ChainSync.Point): Json = p match
    case ChainSync.Point.Block(slot, h) => obj("slot" -> number(slot.value), "hash" -> hash(h))
    case _                              => Json.Lit("null")
  private def nonce(n: Nonces.Nonce): Json = n match
    case Nonces.Nonce.Neutral => obj("kind" -> str("neutral"))
    case Nonces.Nonce.Hash(b) => obj("kind" -> str("hash"), "hash" -> hash(b))
  private def token(t: ValidatedCheckpoint.Token): Json = obj(
    "storeId" -> hash(t.storeId),
    "contextId" -> hash(t.contextId),
    "generation" -> number(t.generation),
    "digest" -> hash(t.digest)
  )
  private[lab] def projection(s: State): Json =
    val n = s.nonces; val f = n.fields
    obj(
      "profile" -> str(ProfileId),
      "contextId" -> hash(s.contextId),
      "tupleId" -> hash(s.id),
      "anchor" -> point(s.acquisition.anchor),
      "tip" -> point(s.acquisition.tip),
      "appliedTip" -> s.scopedAppliedTip.fold[Json](Json.Lit("null"))(point),
      "originals" -> array(
        s.acquisition.originals.map(o =>
          obj(
            "headerSha256" -> hash(ClusterHeaderObservation.sha256(o.envelope)),
            "blockSha256" -> hash(ClusterHeaderObservation.sha256(o.block))
          )
        )
      ),
      "certificate" -> obj(
        "id" -> hash(s.certificates.state.id),
        "contextId" -> hash(s.certificates.state.contextId),
        "tip" -> obj(
          "hash" -> hash(s.certificates.state.tip.hash),
          "slot" -> number(s.certificates.state.tip.slot),
          "block" -> number(s.certificates.state.tip.blockNo)
        ),
        "counters" -> Json.Obj(s.certificates.state.counters.map((k, v) => k.hex -> number(v)))
      ),
      "nonces" -> obj(
        "id" -> hash(n.id),
        "contextId" -> hash(n.contextId),
        "certificateStateId" -> hash(n.certificateStateId),
        "lastSlot" -> number(n.lastSlot),
        "evolving" -> nonce(f.evolving),
        "candidate" -> nonce(f.candidate),
        "epoch" -> nonce(f.epoch),
        "previousEpoch" -> f.previousEpoch.fold[Json](obj("kind" -> str("unknown")))(nonce),
        "lab" -> nonce(f.lab),
        "lastEpochBlock" -> nonce(f.lastEpochBlock)
      ),
      "eligibility" -> s.eligibility.fold[Json](Json.Lit("null"))(e =>
        obj(
          "contextId" -> hash(e.contextId),
          "headers" -> array(
            e.headers.map(h =>
              obj(
                "hash" -> hash(h.headerHash),
                "leaderValue" -> number(h.leaderValue),
                "stakeNumerator" -> number(h.stake.numerator),
                "stakeDenominator" -> number(h.stake.denominator)
              )
            )
          )
        )
      ),
      "ledger" -> obj(
        "environmentId" -> hash(s.ledger.environment.id),
        "checkpointId" -> hash(s.ledger.checkpointId),
        "id" -> hash(s.ledger.id),
        "utxoHex" -> str(s.ledger.outputMap.hex),
        "fees" -> number(s.ledger.fees),
        "slot" -> number(s.ledger.slot)
      )
    )
  private def state(s: DurableSnapshot): Json =
    val content = projection(s.snapshot.state)
    obj(
      "content" -> content,
      "contentSha256" -> str(sha(canonical(content))),
      "revision" -> number(s.snapshot.state.revision),
      "token" -> token(s.token),
      "capacity" -> number(MaxBlocks)
    )
  private def checked[E, A](value: Either[E, A]): IO[A] =
    IO.fromEither(value.left.map(e => new IllegalArgumentException(e.toString)))
  private[lab] def parseToken(text: String): Either[String, ValidatedCheckpoint.Token] =
    text.split(":", -1).toList match
      case a :: b :: g :: d :: Nil
          if g.matches("0|[1-9][0-9]{0,18}") && g.toLongOption.exists(_ >= 0) =>
        def h(s: String) =
          Bytes.fromHex(s).flatMap(b => Either.cond(s.matches("[0-9a-f]{64}"), b, "hash width"))
        for store <- h(a); context <- h(b); digest <- h(d)
        yield ValidatedCheckpoint.Token(store, context, g.toLong, digest)
      case _ => Left("exact token required")
  private[lab] def confirmation(sequence: Int, digest: String): String =
    s"retained $sequence $digest"
  private[lab] def awaitControl(
      expected: String,
      read: IO[Option[String]],
      budget: FiniteDuration
  ): IO[Unit] =
    def loop: IO[Unit] = read.flatMap {
      case Some(line) =>
        IO.raiseUnless(line == expected)(
          new IllegalArgumentException("unexpected controller confirmation")
        )
      case None => IO.sleep(10.millis) *> IO.defer(loop)
    }
    loop.timeoutTo(
      budget,
      IO.raiseError(new IllegalStateException("controller hold/confirmation timeout"))
    )
  private def stdin: IO[IO[Option[String]]] = IO {
    val pending = new java.io.ByteArrayOutputStream()
    IO.blocking {
      var result: Option[String] = None
      while result.isEmpty && System.in.available() > 0 do
        val b = System.in.read()
        require(b >= 0 && b < 128, "ASCII controller line required")
        if b == 10 then
          result = Some(pending.toString("US-ASCII")); pending.reset()
        else
          require(pending.size() < 192, "controller line bound")
          pending.write(b)
      result
    }
  }
  def run(args: List[String]): IO[ExitCode] =
    val work = for
      _ <- IO.raiseUnless(
        args.size == 6 && Set("a", "b", "probe")(args.head) && Set("graceful", "kill")(args(1))
      )(new IllegalArgumentException("phase mode input mount context token"))
      phase = args(0); mode = args(1)
      expected <- checked(Bytes.fromHex(args(4)))
      _ <- IO.raiseUnless(args(4).matches("[0-9a-f]{64}"))(
        new IllegalArgumentException("context pin")
      )
      expectedToken <-
        if phase == "b" then checked(parseToken(args(5))).map(Some(_))
        else
          IO.raiseUnless(args(5) == "-")(new IllegalArgumentException("unexpected token")).as(None)
      read <- stdin
      seq <- Ref.of[IO, Int](0)
      nonce <- IO(java.util.UUID.randomUUID().toString)
      emit = (fields: Vector[(String, Json)]) =>
        seq.getAndUpdate(_ + 1).flatMap { i =>
          IO.println(
            canonical(
              Json.Obj(
                (fields ++ Vector("sequence" -> Json.Num(i.toString), "phase" -> str(phase))).toMap
              )
            )
          ).as(i)
        }
      _ <- emit(
        Vector(
          "record" -> str("process"),
          "nonce" -> str(nonce),
          "pid" -> number(ProcessHandle.current().pid())
        )
      )
      _ <- awaitControl(s"start $nonce", read, 10.seconds)
      context <- IO.blocking(SequenceInput.load(Path.of(args(2)))).flatMap(checked)
      _ <- IO.raiseUnless(context.id == expected)(
        new IllegalArgumentException("independent context mismatch")
      )
      root = Path.of(args(3)); store = root.resolve(if phase == "probe" then "probe" else "state")
      _ <-
        if phase == "probe" then
          for
            saved <- durableCreate[IO](store, context, MaxBlocks, 2.seconds, _ => IO.unit)
              .use(_.snapshot)
            reopened <- durableResume[IO](
              store,
              expected,
              saved.token,
              20.seconds,
              2.seconds,
              _ => IO.unit
            ).use(_.snapshot)
            _ <- IO.raiseUnless(state(saved) == state(reopened))(
              new IllegalStateException("probe mismatch")
            )
            _ <- emit(Vector("record" -> str("probe"), "passed" -> Json.Lit("true")))
          yield ()
        else
          for
            raw <- IO.blocking {
              val input =
                Files.newInputStream(Path.of(args(2)).resolve("scala-sequence-capture.md"))
              try
                val b = input.readNBytes(20 * 1024 * 1024 + 1)
                require(b.length <= 20 * 1024 * 1024, "capture bound"); Bytes.fromArray(b)
              finally input.close()
            }
            originals <- checked(CoherentSequenceCommand.captures(raw))
            blocks <- originals.traverse(o => checked(SequenceInput.block(o)))
            _ <- IO.raiseUnless(
              blocks.size >= 2 && blocks.exists(_.transactionMemos.isEmpty) && blocks.exists(
                _.transactionMemos.size == 2
              )
            )(new IllegalArgumentException("empty plus two-transaction sequence required"))
            ack = (op: String, prefix: Int, saved: DurableSnapshot) =>
              emit(
                Vector(
                  "record" -> str("publication"),
                  "operation" -> str(op),
                  "prefix" -> Json.Num(prefix.toString),
                  "state" -> state(saved)
                )
              )
                .flatMap(i =>
                  awaitControl(confirmation(i, saved.token.digest.hex), read, 5.seconds)
                )
                .as(saved)
            restored = (label: String, saved: DurableSnapshot) =>
              emit(
                Vector("record" -> str("restored"), "step" -> str(label), "state" -> state(saved))
              ).void
            _ <-
              if phase == "a" then
                durableCreate[IO](store, context, MaxBlocks, 2.seconds, _ => IO.unit).use {
                  runtime =>
                    for
                      anchor <- runtime.snapshot.flatMap(s => ack("anchor", 0, s))
                      tip <- blocks.zipWithIndex.foldLeft(IO.pure(anchor)) {
                        case (acc, (block, index)) =>
                          acc.flatMap { before =>
                            for
                              candidate <- runtime.prepare(block).flatMap(checked)
                              published <- runtime.publish(candidate, before.token).flatMap(checked)
                              saved <- runtime.snapshot
                              _ <- IO.raiseUnless(
                                saved.token == published.token && saved.snapshot.state.id == published.value.state.id
                              )(new IllegalStateException("publication snapshot mismatch"))
                              result <- ack("apply", index + 1, saved)
                            yield result
                          }
                      }
                      _ <- emit(
                        Vector(
                          "record" -> str("holding"),
                          "mode" -> str(mode),
                          "digest" -> hash(tip.token.digest)
                        )
                      )
                      _ <-
                        if mode == "graceful" then
                          awaitControl(s"release ${tip.token.digest.hex}", read, 20.seconds)
                        else
                          IO.sleep(20.seconds) *> IO.raiseError(
                            new IllegalStateException("kill hold expired")
                          )
                    yield ()
                }
              else
                for
                  rolled <- durableResume[IO](
                    store,
                    expected,
                    expectedToken.get,
                    20.seconds,
                    2.seconds,
                    _ => IO.unit
                  ).use { runtime =>
                    for
                      before <- runtime.snapshot
                      _ <- restored("tip", before)
                      keep = blocks.size - 2
                      target <-
                        if keep == 0 then IO.pure(before.snapshot.state.acquisition.anchor)
                        else
                          checked(ReferenceCaptureCommand.header(originals(keep - 1).envelope)).map(
                            h =>
                              ChainSync.Point
                                .Block(ChainSync.UInt64.from(h.slot).toOption.get, h.hash)
                          )
                      published <- runtime
                        .rollbackTo(before.snapshot.fence, target, before.token)
                        .flatMap(checked)
                      saved <- runtime.snapshot
                      _ <- IO.raiseUnless(saved.token == published.token)(
                        new IllegalStateException("rollback token mismatch")
                      )
                      result <- ack("rollback-two", keep, saved)
                    yield result
                  }
                  anchored <- durableResume[IO](
                    store,
                    expected,
                    rolled.token,
                    20.seconds,
                    2.seconds,
                    _ => IO.unit
                  ).use { runtime =>
                    for
                      before <- runtime.snapshot
                      _ <- restored("rolled", before)
                      tip <- blocks.takeRight(2).zipWithIndex.foldLeft(IO.pure(before)) {
                        case (acc, (block, index)) =>
                          acc.flatMap { prior =>
                            for
                              candidate <- runtime.prepare(block).flatMap(checked)
                              publication <- runtime
                                .publish(candidate, prior.token)
                                .flatMap(checked)
                              saved <- runtime.snapshot
                              _ <- IO.raiseUnless(publication.token == saved.token)(
                                new IllegalStateException("reapply token mismatch")
                              )
                              result <- ack("reapply", blocks.size - 1 + index, saved)
                            yield result
                          }
                      }
                      published <- runtime
                        .rollbackTo(
                          tip.snapshot.fence,
                          tip.snapshot.state.acquisition.anchor,
                          tip.token
                        )
                        .flatMap(checked)
                      saved <- runtime.snapshot
                      _ <- IO.raiseUnless(published.token == saved.token)(
                        new IllegalStateException("anchor token mismatch")
                      )
                      result <- ack("rollback-anchor", 0, saved)
                    yield result
                  }
                  _ <- durableResume[IO](
                    store,
                    expected,
                    anchored.token,
                    20.seconds,
                    2.seconds,
                    _ => IO.unit
                  ).use { runtime =>
                    runtime.snapshot.flatMap(saved => restored("anchor", saved))
                  }
                yield ()
          yield ()
      _ <- emit(
        Vector(
          "record" -> str("complete"),
          "networkContinuation" -> Json.Lit("false"),
          "powerLossRecovery" -> Json.Lit("false")
        )
      )
    yield ExitCode.Success
    work.handleErrorWith(_ =>
      IO.println("{\"record\":\"error\",\"reason\":\"phase-failed\"}").as(ExitCode(2))
    )
