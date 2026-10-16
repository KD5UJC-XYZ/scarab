// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.differential

import cats.Applicative
import cats.data.EitherT
import cats.effect.Sync
import cats.mtl.Ask
import cats.syntax.all.*
import io.circe.{Json, parser}
import io.circe.syntax.*
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.consensus.state.ConsensusMirrorEnv
import xyz.kd5ujc.scarab.ledger.{Block, Header, StorageError, TxIn, TxOut}
import xyz.kd5ujc.scarab.node.NodeRules
import xyz.kd5ujc.scarab.storage.{Codec, NodeCodecs, SnapshotV4, StateSnapshot, UtxoStore}

/** The repro of a divergence (design §2.5): what it takes to run the block again on the state it
  * was applied to, in seconds, without the chain that led there. Kept by the harness when the two
  * sides disagree, and what `DifferentialReplayMain` reads.
  */
trait Repro[F[_]]:
  /** Keeps the repro of `divergence`, which arose applying `raw` to `pre`; where, if it could be. A
    * repro that cannot be written never hides the divergence: its absence is the `None`.
    */
  def keep(divergence: Divergence, pre: Paired, header: Header, raw: Bytes): F[Option[Path]]

object Repro:
  /** Keeps nothing. */
  def none[F[_]: Applicative]: Repro[F] = (_, _, _, _) => Applicative[F].pure(None)

  /** The files of a bundle, in a directory named after the block (`<slot>-<hash prefix>`):
    *
    *   - `pre.snapshot`: the state before the block, as a format-4 snapshot (the same bytes the
    *     node writes; its UTxO set is the handle, with no entries);
    *   - `utxo.bin`: the entries of the UTxO set that the block reads (inputs, reference inputs,
    *     collateral), complete without the store ([[Bundle.inputs]]);
    *   - `block.cbor`: the block, as received;
    *   - `divergence.json`: what differed, and where (`Bundle.description`).
    */
  object Names:
    val Snapshot = "pre.snapshot"
    val Inputs = "utxo.bin"
    val Block = "block.cbor"
    val Description = "divergence.json"
    val Format = 1

  /** The bundle a repro is read back into. */
  final case class Bundle(
      pre: StateSnapshot[NodeState],
      header: Header,
      raw: Bytes,
      inputs: Map[TxIn, TxOut],
      description: Json
  ):
    /** The state before the block, with the entries it reads in the UTxO set's read set, so that
      * the rules run on it without a store.
      */
    def state: NodeState =
      val ledger = pre.state.ledger
      pre.state.copy(ledger = ledger.copy(utxo = ledger.utxo.withResident(inputs)))

  /** Writes bundles under `dir`. `store` is the UTxO set's backing store, where the node has one
    * (the entries the block reads that the state does not hold are fetched from it).
    */
  def files[F[_]: Sync](
      dir: Path,
      env: F[ConsensusMirrorEnv],
      store: Option[UtxoStore[F]]
  ): Repro[F] = new Repro[F]:
    private val F = Sync[F]

    def keep(divergence: Divergence, pre: Paired, header: Header, raw: Bytes): F[Option[Path]] =
      write(divergence, pre, header, raw).attempt.map(_.toOption.flatten)

    private def write(
        divergence: Divergence,
        pre: Paired,
        header: Header,
        raw: Bytes
    ): F[Option[Path]] =
      val target = dir.resolve(s"${header.slot}-${header.hash.hex.take(8)}")
      if Files.exists(target) then F.pure(Some(target))
      else
        for
          inputs <- readSet(pre.old, header, raw)
          // the snapshot holds the state without the entries of the set, whatever the state held
          bare = pre.old.copy(ledger =
            pre.old.ledger
              .copy(utxo = pre.old.ledger.utxo.copy(diff = Map.empty, resident = Map.empty))
          )
          encoded <- SnapshotV4.format[F](using F, asking(env)).encode(StateSnapshot(pre.at, bare))
          _ <- F.blocking {
            Files.createDirectories(target)
            encoded.foreach(Files.write(target.resolve(Repro.Names.Snapshot), _))
            Files.write(target.resolve(Repro.Names.Block), raw.toArray)
            import NodeCodecs.given
            Files.write(
              target.resolve(Repro.Names.Inputs),
              Codec.encode[Map[TxIn, TxOut]](inputs)
            )
            Files.write(
              target.resolve(Repro.Names.Description),
              describe(divergence, pre, header, encoded.isRight).spaces2.getBytes("UTF-8")
            )
          }
        yield Some(target)

    /** The entries the block's transactions read, from the state's own set and the store. */
    private def readSet(state: NodeState, header: Header, raw: Bytes): F[Map[TxIn, TxOut]] =
      header match
        case _: Header.Shelley =>
          NodeRules.decodeShelley(raw).fold(F.pure(Map.empty[TxIn, TxOut])) { p =>
            UtxoStore
              .complete(store, state.ledger.utxo, p.inputs)
              .map(utxo => p.inputs.flatMap(in => utxo.get(in).map(in -> _)).toMap)
          }
        case _ => F.pure(Map.empty)

  /** The mirror's environment as a capability. */
  private def asking[F[_]: Sync](env: F[ConsensusMirrorEnv]): Ask[F, ConsensusMirrorEnv] =
    new Ask[F, ConsensusMirrorEnv]:
      val applicative = Applicative[F]
      def ask[E2 >: ConsensusMirrorEnv]: F[E2] = env.map(e => e)

  /** What the description of a bundle says: the block, the divergence and where the pre-state is.
    */
  private def describe(
      divergence: Divergence,
      pre: Paired,
      header: Header,
      snapshotWritten: Boolean
  ): Json =
    val verdict = (v: Verdict) =>
      v.fold(f => s"${f.rule.name}: ${f.reason}", _ => "accepted").asJson
    val what = divergence match
      case Divergence.StateDiffers(_, _, _, d) =>
        List(
          "kind" -> "StateDiffers".asJson,
          "path" -> d.path.render.asJson,
          "left" -> d.left.asJson,
          "right" -> d.right.asJson
        )
      case Divergence.VerdictDiffers(_, _, old, neu) =>
        List("kind" -> "VerdictDiffers".asJson, "old" -> verdict(old), "new" -> verdict(neu))
      case Divergence.Unconvertible(_, _, e) =>
        List("kind" -> "Unconvertible".asJson, "error" -> e.toString.asJson)
      case Divergence.NotPorted(_, _) => List("kind" -> "NotPorted".asJson)
    Json.obj(
      List(
        "format" -> Names.Format.asJson,
        "slot" -> header.slot.asJson,
        "blockNo" -> header.blockNo.asJson,
        "hash" -> header.hash.hex.asJson,
        "era" -> header.era.toString.asJson,
        "stage" -> divergence.stage.toString.asJson,
        "preAnchor" -> pre.at
          .map(a => Json.obj("slot" -> a.slot.asJson, "hash" -> a.hash.hex.asJson))
          .asJson,
        "snapshotWritten" -> snapshotWritten.asJson
      ) ++ what*
    )

  /** The bundle in `dir`, or why it does not read. `byronEpochSlots` is the Byron epoch length the
    * block's decoding needs.
    */
  def read[F[_]: Sync](
      dir: Path,
      env: F[ConsensusMirrorEnv],
      byronEpochSlots: BigInt
  ): F[Either[StorageError, Bundle]] =
    given Ask[F, ConsensusMirrorEnv] = asking(env)
    type Result[A] = EitherT[F, StorageError, A]
    def lift[A](either: Either[StorageError, A]): Result[A] = EitherT.fromEither[F](either)
    def corrupt(name: String)(reason: String): StorageError =
      StorageError.Corrupt(dir.resolve(name).toString, reason)
    def bytes(name: String): Result[Array[Byte]] =
      EitherT(
        Sync[F]
          .blocking(Files.readAllBytes(dir.resolve(name)))
          .attempt
          .map(_.leftMap(e => StorageError.Io(dir.resolve(name).toString, String.valueOf(e))))
      )
    val program = for
      snapshot <- bytes(Names.Snapshot)
      pre <- EitherT(SnapshotV4.format[F].decode(snapshot))
      raw <- bytes(Names.Block)
      block <- lift(
        Block
          .decode(Bytes.fromArray(raw), byronEpochSlots)
          .leftMap(e => corrupt(Names.Block)(e.message))
      )
      stored <- bytes(Names.Inputs)
      inputs <- lift {
        import NodeCodecs.given
        Codec.decode[Map[TxIn, TxOut]](stored).leftMap(corrupt(Names.Inputs))
      }
      text <- bytes(Names.Description)
      description <- lift(
        parser.parse(new String(text, "UTF-8")).leftMap(e => corrupt(Names.Description)(e.message))
      )
    yield Bundle(pre, block.header, Bytes.fromArray(raw), inputs, description)
    program.value
