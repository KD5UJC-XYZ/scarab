// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import io.circe.{ACursor, Json}
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.MirrorStep
import xyz.kd5ujc.scarab.ledger.{Address, Ptr, TxIn, TxOut as StoredOut, Value as StoredValue}
import xyz.kd5ujc.scarab.ledger.rules.{EraNes, ShelleyInstantStakeOps}
import xyz.kd5ujc.scarab.ledger.state.{
  Babbage,
  CompactCoin,
  Credential,
  KeyHash,
  NewEpochState,
  ShelleyBasedEra,
  ShelleyInstantStake,
  Sorted,
  Staking
}
import xyz.kd5ujc.scarab.ledger.state.given_Order_Ptr
import xyz.kd5ujc.scarab.node.read.ErasStates
import xyz.kd5ujc.scarab.protocol.Era

/** Babbage's instant stake through `EraStake[Babbage]` (`addInstantStake` and `deleteInstantStake`,
  * Shelley's functions, `Shelley/State/Stake.hs:108-145`, cardano-ledger f649f975) against the
  * reference's own results and the flat path's (`ShelleyInstantStakeOps`):
  *
  *   - the 22 recorded Babbage submissions of `fixtures/reference/v1-context`, each by its
  *     reference verdict and validity tag (none, the collateral, or the inputs and outputs);
  *   - the 32 cases of `fixtures/reference/instant-stake`, half of them on pointer addresses, each
  *     the produced outputs added and the consumed ones deleted (`updateStakeDistribution`,
  *     `Shelley/Rules/Utxo.hs:613-624`), against the Babbage result the released functions gave and
  *     the flat path's;
  *   - every block of the era-walking capture ([[ErasStates]]) between two Babbage states: the
  *     outputs the block added to the UTxO set and those it took out, applied to the stake before
  *     the block, against the stake after it, which the flat path made;
  *   - on each of those states, an output at a pointer address of each registered account's
  *     pointer, with the lovelace of a recorded output, added and deleted again, against the flat
  *     path's.
  */
class BabbageStakeSuite extends FunSuite:
  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private val stake = EraStake.babbage

  /** A stored output read as a Babbage one. */
  private def babbage(what: String, out: StoredOut): TxOut[Babbage] =
    right(what)(StoredTxOut.babbage.fromStored(out))

  private def utxo(what: String, outs: Iterable[(TxIn, StoredOut)]): UTxO[Babbage] =
    UTxO[Babbage](outs.map((i, o) => i -> babbage(what, o)).toMap)

  /** `updateStakeDistribution` (`Shelley/Rules/Utxo.hs:613-624`, cardano-ledger f649f975): the
    * produced outputs added, then the consumed ones deleted.
    */
  private def update(
      what: String,
      held: ShelleyInstantStake,
      produced: Iterable[(TxIn, StoredOut)],
      consumed: Iterable[(TxIn, StoredOut)]
  ): ShelleyInstantStake =
    val added = right(s"$what add")(stake.addInstantStake(utxo(what, produced), held))
    right(s"$what delete")(stake.deleteInstantStake(utxo(what, consumed), added))

  private def flat(
      what: String,
      held: ShelleyInstantStake,
      produced: Iterable[(TxIn, StoredOut)],
      consumed: Iterable[(TxIn, StoredOut)]
  ): ShelleyInstantStake =
    right(s"$what flat")(
      ShelleyInstantStakeOps.update(
        held,
        produced.toVector.sortBy(_._1).map(_._2),
        consumed.toVector.sortBy(_._1).map(_._2)
      )
    )

  // ---- the reference's results

  private val document: Json =
    parse(Files.readString(Path.of("fixtures/reference/instant-stake/results.json")))
      .fold(e => fail(e.message), identity)

  private def field(json: Json, name: String): ACursor = json.hcursor.downField(name)
  private def text(c: ACursor): String = c.as[String].fold(e => fail(e.message), identity)
  private def integer(c: ACursor): BigInt = c.as[BigInt].fold(e => fail(e.message), identity)
  private def array(c: ACursor): Vector[Json] =
    c.as[Vector[Json]].fold(e => fail(e.message), identity)
  private def bytes(c: ACursor): Bytes =
    Bytes.fromHex(text(c)).fold(e => fail(e.toString), identity)

  private def coin(n: BigInt): CompactCoin =
    CompactCoin.fromBigInt(n).fold(e => fail(e.toString), identity)

  private def instantStake(json: Json): ShelleyInstantStake =
    ShelleyInstantStake(
      SortedMap.from(array(field(json, "credentials")).map { entry =>
        val hash = KeyHash.fromBytes[Staking](bytes(field(entry, "keyHash")))
        Credential.KeyHashObj(hash.fold(e => fail(e.toString), identity)) ->
          coin(BigInt(text(field(entry, "coin"))))
      })(using summon[cats.Order[Credential[Staking]]].toOrdering),
      SortedMap.from(array(field(json, "pointers")).map { entry =>
        Ptr(
          integer(field(entry, "slot")),
          integer(field(entry, "txIx")),
          integer(field(entry, "certIx"))
        ) -> coin(BigInt(text(field(entry, "coin"))))
      })(using summon[cats.Order[Ptr]].toOrdering)
    )

  private def outputs(input: Json, side: String): Vector[(TxIn, StoredOut)] =
    array(field(input, side)).map { entry =>
      val address = right("address")(Address.decode(bytes(field(entry, "address"))))
      TxIn(bytes(field(entry, "txId")), integer(field(entry, "index"))) ->
        StoredOut(address, StoredValue.coin(BigInt(text(field(entry, "coin")))))
    }

  test("the reference's instant-stake cases, under Babbage's class, give the reference's results") {
    val rows = array(field(document, "cases"))
    val compared = rows.map { row =>
      val input = row.hcursor.downField("input").focus.getOrElse(fail("input"))
      val name = text(field(input, "name"))
      val held = instantStake(input)
      val expected = instantStake(row.hcursor.downField("babbage").focus.getOrElse(fail("babbage")))
      val produced = outputs(input, "produce")
      val consumed = outputs(input, "consume")
      val typed = update(name, held, produced, consumed)
      assertEquals(typed, expected, name)
      assertEquals(typed, flat(name, held, produced, consumed), name)
      // the credentials' lens reads the credentials' stake
      assertEquals(stake.instantStakeCredentialsL.get(typed), expected.credentialStake, name)
      name
    }
    assertEquals(compared.size, 32)
    assertEquals(compared.count(_.startsWith("pointer:")), 16)
  }

  // ---- the recorded Babbage transactions

  /** The stake transition a recorded submission makes, by the reference's verdict and the
    * transaction's validity tag: none for a refused submission; for an accepted transaction whose
    * scripts are to fail, its collateral taken out and its collateral return, if any, put in
    * (`updateUTxOState`'s `Phase2Invalid`, `Babbage/Rules/Utxo.hs:462-486`, `collOuts`,
    * `Babbage/Collateral.hs:43-50`, cardano-ledger f649f975); for an accepted valid one, its
    * inputs' outputs taken out and its outputs put in (`Phase2Valid`, Shelley's `updateUTxOState`).
    */
  enum Submission:
    case Refused, Phase2Invalid, Phase2Valid

  test("each recorded Babbage submission changes the stake as the flat path does, by its verdict") {
    val cases = Vector("babbage-pv7", "babbage-pv8").flatMap { name =>
      parse(Files.readString(Path.of(s"fixtures/reference/v1-context/$name.json")))
        .fold(e => fail(e.message), identity)
        .asArray
        .getOrElse(fail(s"$name: not an array"))
        .map { j =>
          val accepted = j.hcursor
            .downField("reference")
            .get[Boolean]("accepted")
            .fold(e => fail(e.message), identity)
          (xyz.kd5ujc.scarab.ledger.PlutusCase.fromJson(j), accepted)
        }
    }
    val compared = cases.map { (c, accepted) =>
      val what = c.name
      val body = c.tx.body
      val kind =
        if !accepted then Submission.Refused
        else if c.tx.isValid then Submission.Phase2Valid
        else Submission.Phase2Invalid
      val (produced, consumed) = kind match
        case Submission.Refused => (Vector.empty, Vector.empty)
        case Submission.Phase2Invalid =>
          assertEquals(c.collateral.keySet, body.collateral.toSet, what)
          (
            body.collateralReturn.toVector
              .map(TxIn.mkCollateralTxIn(body.id, body.outputs.size) -> _),
            c.collateral.toVector
          )
        case Submission.Phase2Valid =>
          assertEquals(c.spent.keySet, body.inputs.toSet, what)
          (body.outputs.zipWithIndex.map((o, i) => TxIn(body.id, i) -> o), c.spent.toVector)
      // the stake of the outputs the transaction may spend, which a deletion takes out
      val held = flat(
        what,
        ShelleyInstantStake(Sorted.empty, Sorted.empty),
        (c.spent ++ c.collateral).toVector,
        Vector.empty
      )
      val typed = update(what, held, produced, consumed)
      assertEquals(typed, flat(what, held, produced, consumed), what)
      (kind, produced.nonEmpty, typed != held)
    }
    // the reference refused 18; of the four it accepted, two are to fail their scripts, which
    // take their collateral and have no collateral return, and two spend their inputs; every
    // accepted one moves stake
    assertEquals(
      compared.groupMapReduce(_._1)(_ => 1)(_ + _),
      Map(Submission.Refused -> 18, Submission.Phase2Invalid -> 2, Submission.Phase2Valid -> 2)
    )
    assertEquals(compared.filter(_._1 == Submission.Refused).count(_._3), 0)
    assertEquals(compared.filter(_._1 != Submission.Refused).count(_._3), 4)
    assertEquals(
      compared.filter(_._1 == Submission.Phase2Invalid).count(_._2),
      0
    )
  }

  // ---- the era-walking capture

  private lazy val states: Vector[(ErasStates.Sample, NewEpochState[Babbage])] =
    ErasStates.samples
      .filter(_.era == Era.Babbage)
      .flatMap(s => MirrorStep.nesOf(s.mirror).map(s -> _))
      .map { case (s, i: EraNes.InEra[e]) =>
        s -> NewEpochState
          .retype(i.era, ShelleyBasedEra.SBabbage)(i.nes)
          .getOrElse(fail("not Babbage"))
      }

  private def utxoOf(s: ErasStates.Sample): Map[TxIn, StoredOut] =
    MirrorStep.utxoOf(s.mirror).getOrElse(fail("no UTxO")).toMap

  test("each Babbage block of the era-walking capture changes the stake as the flat path did") {
    val steps = states.zip(states.drop(1)).map { case ((before, b), (after, a)) =>
      val what = s"block ${after.anchor}"
      val (u0, u1) = (utxoOf(before), utxoOf(after))
      val produced = u1.filter((k, _) => !u0.contains(k))
      val consumed = u0.filter((k, _) => !u1.contains(k))
      val held = b.es.ls.utxo.instantStake
      val typed = update(what, held, produced, consumed)
      assertEquals(typed, a.es.ls.utxo.instantStake, what)
      assertEquals(typed, flat(what, held, produced, consumed), what)
      (produced.nonEmpty || consumed.nonEmpty, typed != held)
    }
    assertEquals((steps.size, steps.count(_._1), steps.count(_._2)), (187, 2, 2))
  }

  test("an output at each registered account's pointer adds to and leaves the pointer's stake") {
    val compared = states.flatMap { (s, nes) =>
      val accounts = nes.es.ls.cert.dState.accounts
      val held = nes.es.ls.utxo.instantStake
      val amounts = utxoOf(s).values.map(_.value.coin).filter(_ > 0).toVector.sorted
      accounts.ptrs.keys.toVector.zipWithIndex.map { (ptr, i) =>
        val what = s"${s.anchor} $ptr"
        val raw = Bytes.fromArray(
          Array(0x40.toByte) ++ Array.fill[Byte](28)(i.toByte) ++ Ptr.encode(ptr).value.toArray
        )
        val address = right(what)(Address.decode(raw))
        assertEquals(
          address.kind,
          Address.Kind.Shelley(
            0,
            address.kind match {
              case Address.Kind.Shelley(_, p, _, _) => p
              case other                            => fail(s"$what: $other")
            },
            None,
            Some(ptr)
          ),
          what
        )
        val amount = amounts(i % amounts.size)
        val out = Vector(
          TxIn(Bytes(Vector.fill[Byte](32)(i.toByte)), 0) ->
            StoredOut(address, StoredValue.coin(amount))
        )
        val added = update(what, held, out, Vector.empty)
        assertEquals(added, flat(what, held, out, Vector.empty), what)
        assertEquals(
          added.ptrStake.get(ptr).map(_.toBigInt),
          Some(held.ptrStake.get(ptr).fold(amount)(_.toBigInt + amount)),
          what
        )
        assertEquals(added.credentialStake, held.credentialStake, what)
        assertEquals(update(what, added, Vector.empty, out), held, what)
        ptr
      }
    }
    assertEquals(compared.size, 564)
  }
