// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.NonEmptyVector
import cats.effect.{IO, Resource}
import cats.effect.std.Supervisor
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{
  AcquiredLeiosEbs,
  LeiosBlockInfo,
  LeiosCert,
  LeiosCertGate,
  LeiosForestBlock,
  Praos2
}
import xyz.kd5ujc.scarab.consensus.leios.StdGen
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, PraosState, ProtocolState}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  EbAnnouncement,
  Header,
  HeaderVersionInfo,
  LeiosHeader,
  LeiosHeaderFields,
  Nonce,
  PeerError,
  VrfCert
}
import xyz.kd5ujc.scarab.ledger.state.{BlockIssuer, KeyHash, SlotNo, Sorted, WithOrigin}
import xyz.kd5ujc.scarab.network.{FailsOnUncaught, LeiosFetchServer, Protocols}
import xyz.kd5ujc.scarab.network.Peers.*
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word32
import xyz.kd5ujc.scarab.storage.{LeiosEb, LeiosStoreLocks, LeiosStoreSuite, RocksLeiosStore}

/** The endorser block a node learns of over ChainSync, fetched and selected, after the first case
  * of the reference's `Test/Consensus/Leios/RecoveryPath.hs` (ouroboros-consensus 2416493b,
  * `test_forgottenCertRBIsSelectedAgain`, 1-200): node B holds an endorser block and its closure;
  * node A rolls forward B's header that announces it, then B's header that certifies it, as its
  * ChainSync client hands them to the connection's Leios handling ([[LeiosChainSync]]); A's
  * decision loop fetches the body and the closure from B over LeiosFetch, A's store completes the
  * closure, and A's chain selection then no longer hides the certifying block ([[LeiosCertGate]]).
  *
  * What is synthetic, as no capture holds an endorser block with its announcement and certificate:
  * the endorser block, and the two headers, which are built rather than decoded and validated (the
  * header rules on the testnet's own announcing and certifying headers are `Praos2ReferenceSuite`
  * 's, the gate's hand-off of each to the handling `GenesisHeaderGateSuite` 's, and the handling of
  * the testnet's own headers [[LeiosChainSyncSuite]]'s); the header state as of each, which is the
  * state `Praos2.reupdate` leaves; and the ledger view's bound. What the reference's case goes on
  * to do, the certificate's verification (the Recovery Path, X5), is not here (#54).
  */
class LeiosFetchEndToEndSuite extends FailsOnUncaught:
  import LeiosStoreSuite.{ok, allowing, word32, slot}

  private val dirs = ResourceFunFixture(Files.forAsync[IO].tempDirectory)

  private def bytes(n: Int, b: Int): Bytes = Bytes(Vector.fill(n)(b.toByte))

  private val txs = Vector.tabulate(70)(LeiosStoreSuite.tx)
  private val eb: LeiosEb = LeiosStoreSuite.eb(
    NonEmptyVector.fromVectorUnsafe(txs.map(t => LeiosEb.hashTx(t) -> word32(t.size.toLong)))
  )
  private val ebHash: EbHash = EbHash.fromBytes(LeiosEb.hashTx(eb.encoded).bytes)
  private val vkey = bytes(32, 7)

  private def header(
      hash: Bytes,
      slot: Long,
      prev: Option[Bytes],
      cert: Boolean,
      announced: Option[EbAnnouncement]
  ): LeiosHeader =
    LeiosHeader(
      bytes(8, 1),
      bytes(8, 1),
      hash,
      BigInt(slot),
      BigInt(slot),
      prev,
      vkey,
      bytes(32, 3),
      VrfCert(bytes(64, 4), bytes(80, 5)),
      BigInt(0),
      bytes(32, 6),
      Header.OpCert(bytes(32, 8), BigInt(0), BigInt(0), bytes(64, 9)),
      LeiosHeaderFields(HeaderVersionInfo(BigInt(12), BigInt(0)), cert, announced),
      bytes(448, 10)
    )

  private val announcing =
    header(bytes(32, 0xa1), 100, None, cert = false, Some(EbAnnouncement(ebHash, eb.encoded.size)))
  private val certifying =
    header(bytes(32, 0xc1), 110, Some(announcing.hash), cert = false, None)
      .copy(leios =
        LeiosHeaderFields(
          HeaderVersionInfo(BigInt(12), BigInt(0)),
          blockBodyContainsLeiosCert = true,
          None
        )
      )
  private val point = EbPoint(slot(100), ebHash)

  private val praos = PraosState(
    WithOrigin.Origin,
    Sorted.empty,
    Nonce.Neutral,
    Nonce.Neutral,
    Nonce.Neutral,
    Nonce.Neutral,
    Nonce.Neutral,
    Nonce.Neutral
  )
  private val extras = ProtocolState
    .initial(
      xyz.kd5ujc.scarab.protocol.Era.Conway,
      NodeSetup
        .fromConfigFile(xyz.kd5ujc.scarab.protocol.ReferenceBuild.LeiosB004fe0c)(
          java.nio.file.Path.of("fixtures/leios/testnet/config/config.json")
        )
        .fold(e => fail(e.message), identity)
        .identity
        .shelleyGenesis
    )
    .extras

  /** The header state after `h`, as `Praos2.reupdate` leaves it, with the view it was validated
    * against; `None` for the state before the first.
    */
  private def after(h: Option[LeiosHeader]): NodeHeaderState =
    val issuer = KeyHash
      .fromBytes[BlockIssuer](Blake2b.hash224.hash(vkey))
      .fold(e => fail(e.toString), identity)
    NodeHeaderState.Shelley(
      ProtocolState(
        ChainDepState.Praos2(
          Praos2.State(
            h.fold(praos)(x =>
              praos.copy(lastSlot = WithOrigin.At(SlotNo.fromUnsigned(x.slot.toLong)))
            ),
            h.flatMap(_.leios.announcement).map(Praos2.AnnouncedBy(issuer, _))
          )
        ),
        extras
      ),
      None,
      None,
      None,
      LeiosBound.Of(Word32.wrap(260000))
    )

  /** B's store, holding the endorser block whole. */
  private def holding(dir: Path): Resource[IO, RocksLeiosStore[IO]] =
    LeiosStoreLocks
      .resource[IO]
      .flatMap(locks => allowing(RocksLeiosStore.resource[IO](dir, locks)))
      .evalTap(s =>
        ok(
          s.writeEbPoint(point, word32(eb.encoded.size.toLong)) *>
            s.writeEbBody(point, eb, Vector.empty) *>
            s.writeTxs(point, txs.zipWithIndex.map((t, i) => i -> t))
        )
      )

  private def empty(dir: Path): Resource[IO, RocksLeiosStore[IO]] =
    LeiosStoreLocks
      .resource[IO]
      .flatMap(locks => allowing(RocksLeiosStore.resource[IO](dir, locks)))

  /** What A's chain selection reads of the two blocks (`BlockInfo`'s Leios fields). */
  private val forest = Map(
    announcing.hash -> LeiosForestBlock(None, LeiosBlockInfo(LeiosCert.Absent, Some(ebHash))),
    certifying.hash -> LeiosForestBlock(
      Some(announcing.hash),
      LeiosBlockInfo(LeiosCert.Carried, None)
    )
  )

  private def peer[A](run: cats.mtl.Raise[IO, PeerError] ?=> IO[A]): IO[Either[PeerError, A]] =
    Handle.allow[PeerError](run.map(_.asRight[PeerError])).rescue(e => IO.pure(e.asLeft[A]))

  dirs.test("an endorser block announced, then certified, over ChainSync is fetched and selected") {
    dir =>
      val connection = 1L
      (
        holding(dir / "b"),
        empty(dir / "a"),
        Supervisor[IO](await = false),
        Resource.eval(LeiosAcquired.create[IO])
      ).tupled.use { (b, a, settling, acquired) =>
        for
          node <- ok(
            LeiosTxCache
              .create[IO]
              .flatMap(txCache =>
                LeiosFetchNode.create[IO](
                  acquired.notifying(a),
                  StdGen.mk(4),
                  slot(0),
                  settling,
                  e => IO.raiseError(new AssertionError(e.toString)),
                  IO.raiseError,
                  _ => IO.pure(None),
                  acquired.notifications.subscribe,
                  IO.pure(None),
                  LeiosMinOfferLead.Default,
                  LeiosMempoolPull.none[IO],
                  txCache
                )
              )
          )
          hiddenBefore = LeiosCertGate.hidden(AcquiredLeiosEbs.empty, forest)
          result <- typed(
            linked(protocols = Set(Protocols.LeiosFetch)).use { (ma, mb) =>
              for
                ca <- ma.channel(Protocols.LeiosFetch)
                cb <- mb.channel(Protocols.LeiosFetch)
                served = LeiosFetchServer.run(cb, LeiosFetchStoreHandler(b))
                client = node.initiator(connection).run(ca, _ => Resource.unit)
                kernel = node.kernel[Unit](
                  IO.pure(Some(slot(120))),
                  IO.pure(_ => Left(())),
                  Stream.empty,
                  IO.raiseError
                )
                completed <- (served.background, client.background, kernel).tupled.surround(
                  node.chainSyncOf(connection) { chainSync =>
                    peer(
                      chainSync.rolledForwardLeios(announcing, after(None)) *>
                        chainSync.rolledForwardLeios(certifying, after(Some(announcing)))
                    ).flatMap(r => IO(assertEquals(r, Right(())))) *> acquired.next
                  }
                )
              yield completed
            }
          )
          novel <- acquired.acquired(point)
          held <- acquired.snapshot
          closure <- ok(a.lookupEbClosure(ebHash))
        yield
          assertEquals(result, Right(point))
          assert(novel)
          assert(closure.nonEmpty)
          assertEquals(hiddenBefore, Set(certifying.hash))
          assertEquals(LeiosCertGate.hidden(held, forest), Set.empty[Bytes])
      }
  }
