// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref, Resource}
import cats.mtl.{Ask, Handle, Stateful, Tell}
import com.comcast.ip4s.*
import java.nio.file.{Files, Path}
import java.time.Instant
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.consensus.{BlockRef, ChainSelection, Compute, SelectView, VolatileChain}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.ConsensusMode
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era, NtNHeader}

/** Exercise the shared production header path without a Genesis runtime: original native blocks
  * supply every era, and malformed signed headers must fail before any BlockFetch request.
  */
class PraosHeaderValidationSuite extends FailsOnUncaught:
  private final case class Capture(
      setup: NodeSetup,
      raws: Vector[Bytes],
      blocks: Vector[Block],
      states: Vector[MirrorState]
  )
  private lazy val capture: Capture =
    val root = Path.of("fixtures/eras")
    val setup =
      NodeSetup.fromDirectory(root.resolve("genesis")).fold(e => fail(e.message), identity)
    val raws = Files
      .readString(root.resolve("blocks.hex"))
      .linesIterator
      .filter(_.nonEmpty)
      .map(hex => Bytes.fromHex(hex).fold(e => fail(e.message), identity))
      .toVector
    val blocks = raws.map(raw =>
      Block.decode(raw, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity)
    )
    val states =
      blocks.zip(raws).foldLeft(Vector(setup.mirrorStart)) { case (known, (block, raw)) =>
        known :+ MirrorSteps
          .block(setup, known.last, block.header, raw)
          .fold(e => fail(e.toString), identity)
      }
    Capture(setup, raws, blocks, states)

  private given Tell[IO, GenesisInternalProblem] with
    val functor = summon[cats.Functor[IO]]
    def tell(problem: GenesisInternalProblem) = IO(fail(problem.message))

  private def run(index: Int, rawHeader: Bytes): IO[(Either[Fault, SyncEvent], Int)] =
    val c = capture
    val target = c.blocks(index).header
    val applied = c.blocks
      .take(index)
      .zip(c.states.drop(1))
      .foldLeft(
        VolatileChain.genesis(
          c.setup.geometry.k.toInt,
          c.setup.mirrorStart,
          c.setup.byronRules.map(_.ledger.genesis.hash)
        )
      ) { case (known, (block, state)) =>
        known.extend(block.header, state).fold(e => fail(e.message), _._1)
      }
    def view(ref: BlockRef): SelectView = c.blocks
      .find(_.header.point == ref.point)
      .fold(fail("missing original header"))(block => SelectView.of(block.header))
    val selection = ChainSelection.Local(
      applied.anchor.point,
      applied.anchor.block.fold(SelectView(-1, 0, None, 0, None))(view),
      applied.blocks.map((ref, _) => ChainSelection.LocalBlock(ref.point, view(ref)))
    )
    val tip = ChainSync.Tip(
      target.point,
      ChainSync.UInt64.from(target.blockNo).fold(e => fail(e.toString), identity)
    )
    val byron = target match
      case h: Header.Byron => Some(NtNHeader.ByronInfo(h.boundary, h.blockSizeHint.value))
      case _               => None
    val wire = NtNHeader(target.era, rawHeader, byron)
    for
      _ <- IO(assertEquals(c.setup.config.settings.consensusMode, ConsensusMode.PraosMode))
      state <- Ref.of[IO, VolatileChain[MirrorState]](applied)
      given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(state)
      given Ask[IO, ChainSelection.Local] = Ask.const[IO, ChainSelection.Local](selection)
      inner = HeaderCheck.forNodeChain[IO, MirrorState](
        NodeHeaderRules(c.setup.praos, c.setup.globals, c.setup.byronRules, c.setup.handOver),
        Compute.inline[IO],
        10000
      )
      clock = new SlotClock[IO]:
        def now = IO.pure(Instant.parse("2100-01-01T00:00:00Z"))
        def geometry = IO(fail("canonical timing must use the captured ledger"))
      fetched <- Ref.of[IO, Int](0)
      source = Resource.eval(Ref.of[IO, Boolean](false)).map { sent =>
        new ChainSyncServer.Source[IO, NtNHeader]:
          def intersect(points: Vector[ChainSync.Point]) =
            IO.pure(if points.contains(applied.tip) then Right(applied.tip -> tip) else Left(tip))
          def poll = sent
            .getAndSet(true)
            .map(already => Option.unless(already)(ChainSyncServer.Update.RollForward(wire, tip)))
          def await = IO.never[ChainSyncServer.Update[NtNHeader]]
      }
      blocks = new BlockFetchServer.Blocks[IO]:
        def range(from: ChainSync.Point, to: ChainSync.Point) =
          fetched.update(_ + 1).as(Some(fs2.Stream.emit(c.raws(index)).covary[IO]))
      sink = new TxSubmissionServer.Sink[IO]:
        def known(id: Bytes) = IO.pure(false)
        def submit(originals: Vector[Bytes])(using
            cats.mtl.Raise[IO, xyz.kd5ujc.scarab.network.NetworkError]
        ) = IO.unit
      bearers <- Bearer.inMemory[IO](64)
      (theirs, ours) = bearers
      result <- Handle
        .allow[NetworkError](
          NodeToNodeServer
            .accept(theirs, 42, ServerHandlers(NtNHeader.codec, source, blocks, sink))
            .void
        )
        .rescue(_ => IO.unit)
        .background
        .use { _ =>
          Handle
            .allow[NetworkError] {
              NodeToNode.over[IO, NtNHeader](ours, 42, NtNHeader.codec, NodeToNode.Settings()).use {
                connection =>
                  Peer
                    .over(
                      connection,
                      Peer.Settings(
                        SocketAddress(host"127.0.0.1", port"3001"),
                        42,
                        byronEpochSlots = c.setup.geometry.byron.epochSlots
                      ),
                      _ => IO.unit,
                      ChainSyncHeights
                        .selected(c.blocks.take(index).map(_.header), applied.candidates),
                      headers = Some(HeaderClock.clocked(clock, inner))
                    )
                    .use(peer =>
                      // The body is fetched from the decisions, independently of the header's
                      // publication; the header's body is waited for before counting requests.
                      Fault.fiber(
                        peer.intersect(applied.candidates) *> peer.next.flatTap {
                          case SyncEvent.RollForward(header, _) => peer.fetch(header).void
                          case SyncEvent.RollBackward(_, _)     => IO.unit
                        }
                      )
                    )
              }
            }
            .rescue(error => IO(fail(error.message)))
        }
      requests <- fetched.get
    yield (result, requests)

  for era <- Vector(
      Era.Byron,
      Era.Shelley,
      Era.Allegra,
      Era.Mary,
      Era.Alonzo,
      Era.Babbage,
      Era.Conway
    )
  do
    test(s"Praos mode validates the original $era header before fetching its original body") {
      val index = capture.blocks.indexWhere(block => block.era == era && !block.header.isBoundary)
      assert(index >= 0)
      run(index, capture.blocks(index).header.raw).map { (result, fetched) =>
        assertEquals(
          result.map {
            case SyncEvent.RollForward(header, _) => header
            case other                            => fail(s"unexpected $other")
          },
          Right(capture.blocks(index).header)
        )
        assertEquals(fetched, 1)
      }
    }

  private def flip(raw: Bytes, signature: Bytes): Bytes =
    val bytes = raw.toArray
    val start = bytes.indices
      .find(offset => bytes.startsWith(signature.toArray, offset))
      .getOrElse(fail("signature not in original header"))
    bytes(start) = (bytes(start) ^ 1).toByte
    Bytes.fromArray(bytes)

  for era <- Vector(Era.Byron, Era.Shelley) do
    test(s"Praos mode refuses a tampered original $era signature without fetching a body") {
      val index = capture.blocks.indexWhere(block => block.era == era && !block.header.isBoundary)
      assert(index >= 0)
      val h = capture.blocks(index).header
      val signature = h match
        case b: Header.Byron =>
          b.main.fold(fail("Byron main header missing"))(_.signature match
            case signed: Header.ByronSignature.Delegated => signed.signature)
        case s: Header.Shelley => s.kesSignature
      run(index, flip(h.raw, signature)).map { (result, fetched) =>
        result match
          case Left(
                Fault.Failed(
                  PeerError.InvalidHeader(_, HeaderError.Byron(ByronChainError.SignatureRejected))
                )
              ) if era == Era.Byron =>
            ()
          case Left(
                Fault.Failed(
                  PeerError.InvalidHeader(
                    _,
                    HeaderError.TPraos(TPraosError.OCert(OCertError.KesSignatureRejected))
                  )
                )
              ) if era == Era.Shelley =>
            ()
          case other => fail(s"unexpected $other")
        assertEquals(fetched, 0)
      }
    }

  test("Praos mode rejects an epoch-boundary envelope before crypto or body fetching") {
    val c = capture
    val index = c.blocks.indices
      .find { i =>
        c.blocks(i).header match
          case h: Header.Shelley =>
            c.states(i).ext.ledgerState.state match
              case ledger: xyz.kd5ujc.scarab.consensus.state.EraLedgerState.InShelleyEra[e] =>
                val geometry = c.states(i).geometry(c.setup.geometry)
                geometry.epochOf(h.slot) > ledger.state.nes.epoch.toBigInt
              case _ => false
          case _ => false
      }
      .getOrElse(fail("original capture has no Shelley-based epoch crossing"))
    val h = c.blocks(index).header
    val decoded = Cbor.decode(h.raw).fold(error => fail(error.message), identity)
    val oversized = decoded.value match
      case Value.Arr(outer) =>
        outer.head.value match
          case Value.Arr(body) =>
            val sizeIndex = if h.era.usesTPraos then 7 else 6
            val changed = body.updated(sizeIndex, Node(Value.UInt(BigInt(1) << 40), Bytes.empty))
            Cbor
              .encode(Value.Arr(outer.updated(0, Node(Value.Arr(changed), Bytes.empty))))
              .fold(error => fail(error.message), identity)
          case other => fail(s"unexpected original header body: $other")
      case other => fail(s"unexpected original header: $other")
    run(index, oversized).map { (result, fetched) =>
      result match
        case Left(
              Fault.Failed(
                PeerError.InvalidHeader(_, HeaderError.Envelope(_: EnvelopeError.BlockSizeTooLarge))
              )
            ) =>
          ()
        case other => fail(s"unexpected $other")
      assertEquals(fetched, 0)
    }
  }
