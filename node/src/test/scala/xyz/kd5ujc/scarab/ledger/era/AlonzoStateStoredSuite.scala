// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{ProtocolParams, Update as StoredUpdate}
import xyz.kd5ujc.scarab.ledger.era.alonzo.{AlonzoPParams, AlonzoTxOut}
import xyz.kd5ujc.scarab.ledger.state.{Alonzo, NewEpochState, ShelleyBasedEra}
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.protocol.StateLayout
import xyz.kd5ujc.scarab.storage.SnapshotV10Sections
import xyz.kd5ujc.scarab.storage.haskell.state.{
  HaskellState,
  HaskellStateCodecs,
  NativeStateCodecs,
  Params,
  ReferenceLayout
}

/** Alonzo's era types over every recorded Alonzo state the repository holds beside the era walk's
  * (`EraPParamsStoredSuite`): the Haskell state `alonzo-3013`, the native V10 snapshots
  * `alonzo-349` and `alonzo-539` and the Preview snapshot `preview-4320`. Each state's parameter
  * records read into Alonzo's record and are written by its encoder as the state's codec writes
  * them; its stored update proposals read into the era with their bytes kept; and the outputs its
  * state holds read as Alonzo's outputs, written back to the stored form and to their bytes (none
  * here: these snapshots keep the UTxO set in their tables). The state is written back whole.
  */
class AlonzoStateStoredSuite extends FunSuite:
  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"not an array: $other")

  private def at(n: Node, path: Int*): Node = path.foldLeft(n)((m, i) => items(m)(i))

  private def decode(bytes: Bytes): Node = right("CBOR")(Cbor.decode(bytes, HaskellState.limits))

  /** The current era's index in the telescope (`[1, [telescope, ...]]`) and its `NewEpochState`. */
  private def current(root: Node): (Int, Node) =
    val telescope = items(at(root, 1, 0))
    val index = telescope.size - 1
    (index, at(root, 1, 0, index, 1, 1, 1))

  /** What a state holds that the era reads: its parameter records, its proposals and its outputs,
    * each read and written back.
    */
  private def read(what: String, codec: Codec[NewEpochState[Alonzo]], node: Node): (Int, Int, Int) =
    val nes = right(s"$what decode")(codec.decode(node))
    val gov = nes.es.ls.utxo.gov
    val params = Params.codecIn(StateLayout.Node11_1, ShelleyBasedEra.SAlonzo)
    val records = Vector(gov.curPParams, gov.prevPParams) ++ gov.futurePParams.nextParams.toVector
    records.foreach { (p: ProtocolParams) =>
      val pp = PParams[Alonzo](right(s"$what parameters")(AlonzoPParams.fromStored(p)))
      assertEquals(AlonzoPParams.toStored(pp.hkd, p), p, what)
      assertEquals(Right(PParams.encodePParams(pp).bytes), params.write(p), what)
    }
    val proposals = Vector(gov.curProposals, gov.futureProposals).filter(_.nonEmpty).map { ps =>
      val stored = StoredUpdate(ps.map((k, u) => k.bytes -> u).toMap, BigInt(0))
      val kept = right(s"$what proposals")(KeptUpdate.fromStored[Alonzo](stored))
      assertEquals(kept.stored, stored, what)
      kept.update.proposals.updates.size
    }
    val outputs = nes.es.ls.utxo.utxo.utxo.valuesIterator.toVector
    outputs.foreach { out =>
      val era = right(s"$what output")(AlonzoTxOut.fromStored(out))
      assertEquals(AlonzoTxOut.toStored(era), out, what)
      assertEquals(EncCBOR[AlonzoTxOut].encCBOR(era).bytes, out.raw, what)
    }
    assertEquals(codec.write(nes), Right(node.original), what)
    (records.size, proposals.sum, outputs.size)

  test("the recorded Haskell Alonzo state's parameters, proposals and outputs are the era's") {
    val path = "fixtures/haskell-state/alonzo-3013/state"
    val (index, nes) = current(decode(Bytes.wrap(Files.readAllBytes(Path.of(path)))))
    assertEquals(index, 4, path)
    // three records and the three delegates' proposals; the UTxO set is in the snapshot's tables,
    // not its state (UTxO-HD), and is not read here
    assertEquals(read(path, HaskellStateCodecs.newEpochState[Alonzo], nes), (3, 3, 0))
  }

  test("the native V10 Alonzo snapshots' parameters, proposals and outputs are the era's") {
    val counts = Vector("eras/alonzo-349", "eras/alonzo-539", "preview-4320").map { name =>
      val path = s"fixtures/snapshot-v10/$name.bin"
      val haskell = right(path)(
        SnapshotV10Sections.haskellState(
          Files.readAllBytes(Path.of(path)),
          ReferenceLayout.Release11_1
        )
      )
      val (index, nes) = current(decode(haskell))
      assertEquals(index, 4, path)
      name -> read(path, NativeStateCodecs.newEpochState[Alonzo], nes)
    }.toMap
    // the records, the delegates' proposals and the outputs the state holds (none: the snapshots
    // keep the UTxO set beside the state)
    assertEquals(
      counts,
      Map(
        "eras/alonzo-349" -> (2, 0, 0),
        "eras/alonzo-539" -> (3, 3, 0),
        "preview-4320" -> (2, 0, 0)
      )
    )
  }
