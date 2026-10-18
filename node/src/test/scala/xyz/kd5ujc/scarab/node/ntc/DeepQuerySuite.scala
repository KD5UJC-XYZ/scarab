// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.effect.{Ref, SyncIO}
import cats.mtl.Raise
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.consensus.{Anchor, VolatileChain}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.PreprodDeepTx.onSmallStack
import xyz.kd5ujc.scarab.ledger.{Block, StorageError}
import xyz.kd5ujc.scarab.node.{MirrorSteps, NodeSetup}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.ChainSync.{DecodeResult, Point, Role}
import xyz.kd5ujc.scarab.protocol.{Era, LocalStateQuery}
import xyz.kd5ujc.scarab.network.LocalStateQueryServer.Refusal
import xyz.kd5ujc.scarab.storage.MemoryUtxoStore

/** GetCBOR is recursively valid in the reference (Shelley/Ledger/Query.hs:944,1017 at
  * ouroboros-consensus 82ecba3). Its interpretation must not depend on the JVM worker stack.
  */
class DeepQuerySuite extends FunSuite:
  private val depth = 20000
  private val setup = NodeSetup
    .fromDirectory(Path.of("fixtures/devnet/lsq/genesis"))
    .fold(e => fail(e.message), identity)
  private val env = QueryEnv(
    setup.geometry.systemStart,
    setup.geometry,
    setup.praos.forks,
    setup.mirrorEnv().byron,
    setup.globals.maxSupply,
    setup.genesis.raw,
    setup.mirrorEnv().ledger
  )
  private val view = QueryView(Point.Origin, None, setup.mirrorStart)

  private def nested(tag: Int, layers: Int = depth): Bytes = CborOut.build { w =>
    w.array(2).uint(0).array(2).uint(0).array(2).uint(view.era.index)
    (0 until layers).foreach(_ => w.array(2).uint(9))
    w.array(1).uint(tag)
  }

  private def decoded(raw: Bytes): NtcQuery =
    NtcQuery.decode(raw).fold(e => fail(e.message), identity)

  private def result(raw: Bytes): Bytes =
    Cbor.decode(raw).fold(e => fail(e.message), identity).value match
      case Value.Arr(Vector(inner)) => inner.original
      case _                        => fail("expected singleton era answer")

  private def unwrap(raw: Bytes, layers: Int): Bytes =
    (0 until layers).foldLeft(raw) { (current, _) =>
      Cbor.decode(current).fold(e => fail(e.message), identity).value match
        case Value.Tag(t, inner) if t == 24 =>
          inner.value match
            case Value.ByteString(bytes) => bytes
            case _                       => fail("expected GetCBOR bytes")
        case _ => fail("expected tag 24")
    }

  test("semantic decode, UTxO planning, diagnostic and answer walks survive 20000 wrappers") {
    onSmallStack {
      val query = decoded(nested(7))
      assertEquals(QueryAnswers.utxoNeeds(query), QueryAnswers.UtxoNeeds.Whole)
      assertEquals(
        NtcQuery.describe(query),
        s"IfCurrent(${view.era.index},GetCBOR[$depth](GetUTxOWhole))"
      )
      val answer = QueryAnswers
        .answerOrWhy(env, view, Map.empty, query)
        .fold(e => fail(e.toString), identity)
      val leaf = unwrap(result(answer), depth)
      val plain = QueryAnswers
        .answerOrWhy(
          env,
          view,
          Map.empty,
          NtcQuery.IfCurrent(view.era.index, LedgerQuery.GetUTxOWhole)
        )
        .fold(e => fail(e.toString), identity)
      assertEquals(leaf, result(plain))
    }
  }

  test("unsupported and malformed deep queries return typed failures on a small stack") {
    onSmallStack {
      val query = decoded(nested(14))
      assertEquals(QueryAnswers.utxoNeeds(query), QueryAnswers.UtxoNeeds.Nothing)
      assertEquals(
        QueryAnswers.answerOrWhy(env, view, Map.empty, query),
        Left(Unanswered.Unsupported)
      )
      assert(NtcQuery.decode(nested(99)).left.exists {
        case NtcQuery.Error.UnknownTag(_, 99) => true
        case _                                => false
      })
    }
  }

  test("pre-Conway era gate peels wrappers for both accepted and refused query kinds") {
    val beforeConway = NodeSetup
      .fromDirectory(Path.of("fixtures/eras/genesis"))
      .fold(e => fail(e.message), identity)
    // The public era capture has 50 Byron blocks, followed by its first Shelley block.
    val state = Files
      .readAllLines(Path.of("fixtures/eras/blocks.hex"))
      .asScala
      .filter(_.nonEmpty)
      .take(51)
      .foldLeft(beforeConway.mirrorStart) { (current, line) =>
        val raw = Bytes.fromHex(line).fold(e => fail(e.message), identity)
        val block = Block
          .decode(raw, beforeConway.geometry.byron.epochSlots)
          .fold(e => fail(e.message), identity)
        MirrorSteps
          .block(beforeConway, current, block.header, raw)
          .fold(e => fail(e.toString), identity)
      }
    val early = QueryView(Point.Origin, None, state)
    assertEquals(early.era, Era.Shelley)
    onSmallStack {
      def wrapped(leaf: LedgerQuery): NtcQuery = NtcQuery.IfCurrent(
        early.era.index,
        (0 until depth).foldLeft(leaf)((q, _) => LedgerQuery.GetCBOR(q))
      )
      assertEquals(
        QueryAnswers.answerOrWhy(env, early, Map.empty, wrapped(LedgerQuery.GetEpochNo)),
        Left(Unanswered.Unsupported)
      )
      val answer = QueryAnswers
        .answerOrWhy(env, early, Map.empty, wrapped(LedgerQuery.DebugChainDepState))
        .fold(e => fail(e.toString), identity)
      val plain = QueryAnswers
        .answerOrWhy(
          env,
          early,
          Map.empty,
          NtcQuery.IfCurrent(early.era.index, LedgerQuery.DebugChainDepState)
        )
        .fold(e => fail(e.toString), identity)
      assertEquals(unwrap(result(answer), depth), result(plain))
    }
  }

  test("the transport accepts 20000 wrappers and safely refuses input over its item bound") {
    onSmallStack {
      val legal = nested(7)
      val wire = LocalStateQuery
        .encode(LocalStateQuery.State.Acquired, Role.Client, LocalStateQuery.Message.Query(legal))
        .fold(e => fail(e.message), identity)
      assertEquals(
        LocalStateQuery.decodePrefix(LocalStateQuery.State.Acquired, Role.Client, wire),
        DecodeResult.Decoded(LocalStateQuery.Message.Query(legal), wire.size)
      )
      val tooDeep = CborOut.build(_.array(2).uint(3).raw(nested(7, 40000)))
      assert(
        LocalStateQuery.decodePrefix(LocalStateQuery.State.Acquired, Role.Client, tooDeep) match
          case DecodeResult.Failed(_) => true
          case _                      => false
      )
    }
  }

  test("an acquired session answers and logs a refusal at 20000 query layers on a small stack") {
    given Raise[SyncIO, StorageError] with
      def functor = cats.Functor[SyncIO]
      def raise[E <: StorageError, A](error: E): SyncIO[A] = SyncIO.delay(fail(error.message))
    onSmallStack {
      val program = for
        chain <- Ref.of[SyncIO, VolatileChain[MirrorState]](
          VolatileChain(5, Anchor(None, setup.mirrorStart), Vector.empty)
        )
        store <- MemoryUtxoStore[SyncIO]
        source = StateQuerySource(chain, SyncIO.pure(env), store)
        session <- source
          .acquire(LocalStateQuery.Target.VolatileTip)
          .map(_.fold(e => fail(e.toString), identity))
        answered <- session.answer(nested(7))
        refused <- session.answer(nested(14))
      yield
        assert(answered.isRight)
        assertEquals(
          refused,
          Left(
            Refusal.Unsupported(
              s"IfCurrent(${view.era.index},GetCBOR[$depth](GetRewardProvenance))"
            )
          )
        )
      program.unsafeRunSync()
    }
  }
