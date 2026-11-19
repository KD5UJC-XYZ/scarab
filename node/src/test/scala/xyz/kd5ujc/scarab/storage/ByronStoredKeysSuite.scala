// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{ExitCode, IO, Ref}
import cats.mtl.{Ask, Handle}
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.file.{Files as JFiles, Path as JPath}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorState}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  BlockFailure,
  ByronChainError,
  DecodeField,
  LedgerDecodeError,
  NodeError,
  ValidationError
}
import xyz.kd5ujc.scarab.ledger.DecodeSite.Qualifier
import xyz.kd5ujc.scarab.node.{Main, MirrorRules, NodeSetup}
import xyz.kd5ujc.scarab.node.log.NodeEvent
import xyz.kd5ujc.scarab.node.mempool.ByronPayloads
import xyz.kd5ujc.scarab.plutus.PlutusBackend
import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.StorageTestKit.{ok, tempDir}
import xyz.kd5ujc.scarab.storage.haskell.{BlockPosition, ChunkSlots, HaskellImport}
import xyz.kd5ujc.scarab.storage.haskell.HaskellDbKit.{Item, attempt, write}
import xyz.kd5ujc.scarab.storage.haskell.state.ReferenceLayout

/** A Byron block the node holds in its own store, or imports from a reference node's ImmutableDB,
  * whose body holds a verification key not in its shortest form (#1219). The import reads the
  * header only (`HaskellImport`) and stores it; replay and resume read the body with the value
  * decoders (`ByronBody.decode`), which read a key with `decodeBytesCanonical` (`decCBORXPub`,
  * cardano-ledger f649f975 `eras/byron/crypto/src/Cardano/Crypto/Signing/VerificationKey.hs:96-97`)
  * as the reference reads its own disk blocks (`decodeDisk = decodeByronBlock`, ouroboros-consensus
  * 82ecba32
  * `ouroboros-consensus-cardano/src/byron/Ouroboros/Consensus/Byron/Node/Serialisation.hs:53-54`).
  *
  * The block is preprod's of epoch 0, slot 2,163 (`fixtures/preprod/blocks.hex`, its third line),
  * its first vote's voter written `59 00 40` + 64 bytes; its header is unchanged, so its header
  * checks pass and its body is the first thing to refuse it. A replay that refuses a stored block
  * stops the start, as for any stored block the rules refuse; a resume ends the chain before it.
  */
class ByronStoredKeysSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  override def munitIOTimeout: Duration = 3.minutes

  private val setup: NodeSetup =
    NodeSetup
      .fromDirectory(ReferenceBuild.Default)(JPath.of("fixtures/preprod/genesis"))
      .fold(e => fail(e.message), identity)
  private val epochSlots = setup.geometry.byron.epochSlots
  private val chunk = ChunkSlots
    .fromSecurityParam(setup.geometry.k.toLong)
    .getOrElse(fail("chunk size"))
  private given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
  private given SnapshotFormat[IO, MirrorState] =
    SnapshotV10.format[IO](SnapshotCheck.Full, ReferenceLayout.Release11_1)

  /** The epoch 0 boundary block, the first main block and the block of the first proposal. */
  private val recorded: Vector[Bytes] =
    JFiles
      .readAllLines(JPath.of("fixtures/preprod/blocks.hex"))
      .asScala
      .toVector
      .take(3)
      .map(l => Bytes.fromHex(l.trim).fold(e => fail(e.message), identity))

  private val voter: Node = ByronPayloads.items(ByronPayloads.vote)(0)

  /** The block of the first proposal, its first vote's voter written wide. */
  private val wideVoter: Bytes =
    val raw = recorded(2)
    raw.take(voter.offset) ++ ByronPayloads.wide(voter) ++
      raw.drop(voter.offset + voter.original.size)

  private def header(raw: Bytes) =
    Block.decode(raw, epochSlots).fold(e => fail(e.message), _.header)

  private def ref(raw: Bytes): BlockRef =
    val h = header(raw)
    BlockRef(h.slot, h.hash, h.blockNo, h.isBoundary)

  /** The cause the replay names for the wide voter. */
  private val refused = ValidationError(
    header(wideVoter).point,
    BlockFailure.Byron(
      ByronChainError.Body(
        LedgerDecodeError
          .Expected(
            DecodeField.Voter / Qualifier.Canonical,
            LedgerDecodeError.Shape.Bytes,
            voter.value
          )
      )
    )
  )

  private def config(identified: Boolean) =
    ChainStorage.Config(
      snapshotPolicy = SnapshotPolicy.EverySlots(BigInt(1000000)),
      chunkSlots = chunk.value,
      byronEpochSlots = epochSlots,
      identity = Option.when(identified)(setup.identity)
    )

  private val rules: MirrorRules[IO] =
    MirrorRules[IO](
      setup.praos,
      setup.globals,
      setup.byronRules,
      setup.handOver,
      setup.byronHistory,
      PlutusBackend.default
    )

  /** A Haskell ImmutableDB holding `blocks`, as the reference lays one out. */
  private def haskellDb(root: JPath, blocks: Vector[Bytes]): Unit =
    write(
      root,
      setup.identity.magic,
      chunk,
      blocks.map { raw =>
        val h = header(raw)
        val position =
          if h.isBoundary then BlockPosition.Ebb((h.slot / epochSlots).toLong)
          else BlockPosition.Regular(h.slot.toLong)
        // a Byron header's hash is over a wrapper, so no header slice is recorded
        Item(position, raw.toArray, h.hash, 0, 0)
      }
    )

  /** What a start found: the anchor the replay reached, the blocks resumed from the volatile log,
    * and how many logged blocks were dropped (`volatileResumed`).
    */
  private final case class Started(
      anchor: Option[BlockRef],
      resumed: Vector[BlockRef],
      dropped: Int
  )

  /** Open the database at `dir` (replay), then resume its volatile log: what the start found, or
    * why it stopped.
    */
  private def start(dir: Path, identified: Boolean): IO[Either[NodeError, Started]] =
    Ref.of[IO, Vector[StorageEvent]](Vector.empty).flatMap { log =>
      Handle
        .allow[NodeError] {
          ChainStorage
            .open[IO, MirrorState](
              dir,
              config(identified),
              rules,
              setup.mirrorStart,
              e => log.update(_ :+ e),
              StateBacking.none[IO, MirrorState],
              _ => IO.unit
            )
            .use { recovered =>
              ChainStorage
                .resume[IO, MirrorState](
                  recovered,
                  setup.geometry.k.toInt,
                  setup.origin,
                  rules,
                  epochSlots,
                  e => log.update(_ :+ e),
                  _ => IO.unit
                )
                .product(log.get)
                .map { (resumed, events) =>
                  Started(
                    recovered.anchor,
                    resumed.map(_.ref),
                    events.collect { case StorageEvent.VolatileResumed(_, n, _) => n }.sum
                  )
                }
            }
            .map(_.asRight[NodeError])
        }
        .rescue(e => IO.pure(e.asLeft))
    }

  test("the recorded blocks, imported from a reference node's database, replay") {
    tempDir.use { dir =>
      val haskell = dir.toNioPath.resolve("haskell")
      haskellDb(haskell, recorded)
      for
        imported <- attempt(
          HaskellImport.run[IO](
            HaskellImport.Config(
              Path.fromNioPath(haskell),
              dir / "db",
              setup.identity,
              epochSlots,
              chunk
            ),
            _ => IO.unit
          )
        )
        started <- start(dir / "db", identified = true)
      yield
        assertEquals(imported.map(_.appended), Right(3L))
        assertEquals(started, Right(Started(Some(ref(recorded(2))), Vector.empty, 0)))
    }
  }

  test(
    "an imported block whose key is not in its shortest form is stored, and its replay stops the start"
  ) {
    tempDir.use { dir =>
      val haskell = dir.toNioPath.resolve("haskell")
      haskellDb(haskell, recorded.take(2) :+ wideVoter)
      for
        imported <- attempt(
          HaskellImport.run[IO](
            HaskellImport.Config(
              Path.fromNioPath(haskell),
              dir / "db",
              setup.identity,
              epochSlots,
              chunk
            ),
            _ => IO.unit
          )
        )
        started <- start(dir / "db", identified = true)
      yield
        // the import reads the header, which is the recorded one
        assertEquals(imported.map(_.appended), Right(3L))
        assertEquals(started, Left(refused))
        assertEquals(Main.startupFailure(refused), (NodeEvent.Failed(refused), ExitCode(1)))
    }
  }

  test("a logged block whose key is not in its shortest form ends the resumed chain before it") {
    tempDir.use { dir =>
      for
        _ <- ok {
          VolatileStore
            .open[IO](dir / "volatile")
            .use((volatile, _) =>
              (recorded.take(2) :+ wideVoter).traverse_(raw => volatile.append(ref(raw), raw))
            )
        }
        first <- start(dir, identified = false)
        // the log was cut back: the next start resumes the same chain and drops nothing
        second <- start(dir, identified = false)
      yield
        assertEquals(first, Right(Started(None, recorded.take(2).map(ref), 1)))
        assertEquals(second, Right(Started(None, recorded.take(2).map(ref), 0)))
    }
  }
