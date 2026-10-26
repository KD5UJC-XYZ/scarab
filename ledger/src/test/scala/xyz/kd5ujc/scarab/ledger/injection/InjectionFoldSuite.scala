// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.injection

import io.circe.Json
import munit.FunSuite
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{GenesisState, PreprodDeepTx}
import xyz.kd5ujc.scarab.ledger.state.Hash32

class InjectionFoldSuite extends FunSuite:
  private enum Control extends DomainError:
    case Key, Value, Fold
  private type Error = InjectionFold.Error[Control, Control, Control]
  private type Pairs = Vector[(String, Json)]
  private val path = Vector("input.json")
  private def bytes(text: String): Bytes =
    Bytes.fromArray(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  private def hash(data: Bytes): Hash32 =
    Hash32.fromBytes(Blake2b.hash256.hash(data)).fold(e => fail(e.toString), identity)
  private def start(
      source: GenesisState.InjectionData[(String, Json)]
  ): InjectionFold.Step[Pairs, Error] =
    InjectionFold.start(source, Vector.empty[(String, Json)])(
      key => Either.cond(key != "bad", key, Control.Key),
      value => Either.cond(!value.isNull, value, Control.Value),
      (acc, pair) => Either.cond(pair._1 != "stop", acc :+ pair, Control.Fold)
    )
  private def first(raw: Bytes, expected: Hash32): InjectionFold.Step[Pairs, Error] =
    start(GenesisState.InjectionData.FromFile(path, expected)) match
      case InjectionFold.Step.NeedChunk(input) =>
        assertEquals(input.requestedBytes, 65536)
        input.feed(raw)
      case other => fail(s"expected initial file demand, got $other")

  test("no source and embedded entries produce no chunk demand and preserve duplicate order") {
    val pairs = Vector("x" -> Json.fromInt(1), "x" -> Json.fromInt(2))
    assertEquals(
      start(GenesisState.InjectionData.NoInjection),
      InjectionFold.Step.Done(Vector.empty)
    )
    assertEquals(start(GenesisState.InjectionData.Embedded(pairs)), InjectionFold.Step.Done(pairs))
    assertEquals(
      start(GenesisState.InjectionData.Embedded(Vector("stop" -> Json.Null))),
      InjectionFold.Step.Failed(InjectionFold.Error.Fold(Control.Fold))
    )
  }

  test(
    "the whole last demanded chunk is hashed before completed parsing; later bytes are not demanded"
  ) {
    val firstChunk = bytes("{\"x\":1,\"x\":2} ignored remainder")
    val tail = bytes("never requested")
    assertEquals(
      first(firstChunk, hash(firstChunk)),
      InjectionFold.Step.Done(Vector("x" -> Json.fromInt(1), "x" -> Json.fromInt(2)))
    )
    assertEquals(
      first(firstChunk, hash(firstChunk ++ tail)),
      InjectionFold.Step.Failed(
        InjectionFold.Error.HashMismatch(path, hash(firstChunk ++ tail), hash(firstChunk))
      )
    )
  }

  test(
    "complete entry syntax precedes key, key precedes value, and fold precedes later syntax and hash"
  ) {
    val wrong = hash(bytes("wrong"))
    val controls: Vector[(String, Error)] = Vector(
      "{\"bad\":null}" -> InjectionFold.Error.Key(path, "bad", Control.Key),
      "{\"ok\":null}" -> InjectionFold.Error.Value(path, "ok", Control.Value),
      "{\"bad\":1,\"later\":?}" -> InjectionFold.Error.Key(path, "bad", Control.Key),
      "{\"stop\":1,\"later\":?}" -> InjectionFold.Error.Fold(Control.Fold)
    )
    controls.foreach { (text, expected) =>
      assertEquals(first(bytes(text), wrong), InjectionFold.Step.Failed(expected))
    }
    first(bytes("{\"bad\":?}"), wrong) match
      case InjectionFold.Step.Failed(InjectionFold.Error.Syntax(`path`, _)) => ()
      case other => fail(s"expected incomplete-entry syntax refusal, got $other")
  }

  test("an exhausted stream offers only empty feeds and never requests another filesystem read") {
    val raw = bytes("{\"x\":1")
    val eof = first(raw, hash(raw)) match
      case InjectionFold.Step.NeedChunk(input) => input.feed(Bytes.empty)
      case other                               => fail(s"expected one EOF read, got $other")
    @tailrec def feedEmpty(step: InjectionFold.Step[Pairs, Error], count: Int): Unit =
      if count > 0 then
        step match
          case InjectionFold.Step.NeedEof(input) => feedEmpty(input.resume, count - 1)
          case other                             => fail(s"expected pending EOF demand, got $other")
    feedEmpty(eof, 20000)
  }

  test("wide input folds on a small stack without retaining earlier chunks") {
    val count = 20000
    val raw = bytes(Vector.fill(count)("\"x\":0").mkString("{", ",", "}"))
    val result = PreprodDeepTx.onSmallStack {
      val initial = InjectionFold.start[String, Json, Int, Control, Control, Control](
        GenesisState.InjectionData.FromFile(path, hash(raw)),
        0
      )(Right(_), Right(_), (n, _) => Right(n + 1))
      @tailrec def feed(
          step: InjectionFold.Step[Int, Error],
          rest: Bytes
      ): InjectionFold.Step[Int, Error] = step match
        case InjectionFold.Step.NeedChunk(input) if rest.nonEmpty =>
          feed(input.feed(rest.take(input.requestedBytes)), rest.drop(input.requestedBytes))
        case other => other
      feed(initial, raw)
    }
    assertEquals(result, InjectionFold.Step.Done(count))
  }

  test("incremental digest values are persistent and independent of chunk partition") {
    val left = bytes("left")
    val right = bytes("right")
    val prefix = Hash32.Blake2b256.empty.add(left)
    assertEquals(prefix.add(right).finish, hash(left ++ right))
    assertEquals(prefix.finish, hash(left))
    assertEquals(Hash32.Blake2b256.empty.finish, hash(Bytes.empty))
  }
