// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{ConwayGenesis, Credential, LedgerDecodeError, RewardAccount}
import xyz.kd5ujc.scarab.ledger.era.{EraTxError, GovAction, ProposalProcedure}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraProposals
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, ShelleyBasedEra}
import xyz.kd5ujc.scarab.models.runtime.DecodeError
import xyz.kd5ujc.scarab.protocol.DijkstraNotYet
import xyz.kd5ujc.scarab.storage.haskell.state.{
  EraGovReadError,
  HaskellStateCodecs,
  LeiosStateCodecs,
  Versions
}

/** A proposal's parameter update in a Dijkstra state of the Leios build's layout, on the era's
  * types (#826, #85): the keys Dijkstra adds, 34 to 56, read and written as the reference's
  * `EncCBOR PParamsUpdate` writes them (cardano-ledger 9df9f98c
  * `libs/cardano-ledger-core/src/Cardano/Ledger/ Core/PParams.hs:250-262`) by the state's codec of
  * a proposal ([[LeiosStateCodecs.dijkstraProposal]]). Every update of
  * `fixtures/reference/dijkstra-pparams` the reference read, in a parameter change, is written as
  * the reference wrote it again (its `reencoded`), and every one it refused is refused; so are the
  * two updates the Leios testnet proposed (keys 45 and 46 in epochs 22 to 24, keys 47 and 48 in
  * epochs 71 to 73). The 11.1 layout, whose Dijkstra parameters are not the Leios build's, still
  * refuses them.
  */
class DijkstraParamProposalSuite extends FunSuite:
  private val v12 = Versions.eraLow(ShelleyBasedEra.SDijkstra)
  private val codec = LeiosStateCodecs.dijkstraProposal(using v12)

  private def hex(text: String): Bytes =
    Bytes.fromHex(text.replace(" ", "")).fold(e => fail(e.toString), identity)

  private def written[A](e: Either[?, A]): A = e.fold(x => fail(x.toString), identity)

  /** `[deposit, return account, [0, null, update, null], anchor]`: a parameter change of no parent
    * and no policy (`Procedures.hs:567-580, 987-993`, cardano-ledger 9df9f98c).
    */
  private def proposal(update: Bytes): Bytes =
    val account = written(
      LeiosStateCodecs.rewardAccount.write(RewardAccount(0, Credential.Key(hex("0d" * 28))))
    )
    val anchor = written(LeiosStateCodecs.anchor(using v12).write(ConwayGenesis.noAnchor))
    hex("84 1b000000174876e800" + account.hex + "8400f6" + update.hex + "f6" + anchor.hex)

  private def read(bytes: Bytes): ProposalProcedure[Dijkstra] =
    codec.decodeBytes(bytes).fold(e => fail(e.render), identity)

  private def keys(p: ProposalProcedure[Dijkstra]): Set[BigInt] = p.pProcGovAction match
    case GovAction.ParameterChange(_, u, _) => DijkstraProposals.updateKeys(u).toSet
    case other                              => fail(s"not a parameter change: $other")

  /** A case of the corpus: its name, the update's bytes, and the reference's writing of it again,
    * none where it refused them.
    */
  private final case class Row(name: String, update: Bytes, reencoded: Option[Bytes])

  private val rows: Vector[Row] =
    Files
      .readAllLines(Path.of("fixtures/reference/dijkstra-pparams/cases.jsonl"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map { line =>
        val c = parse(line).fold(e => fail(e.message), identity).hcursor
        val reference = c.downField("reference")
        Row(
          c.get[String]("id").fold(e => fail(e.message), identity),
          hex(c.get[String]("update").fold(e => fail(e.message), identity)),
          Option.unless(reference.downField("refused").succeeded)(
            hex(reference.get[String]("reencoded").fold(e => fail(e.message), identity))
          )
        )
      }

  test("every update the reference read is written as it wrote it again; its refusals refused") {
    val (accepted, refused) = rows.partition(_.reencoded.isDefined)
    assert(accepted.nonEmpty && refused.nonEmpty)
    refused.foreach(r =>
      assert(
        codec.decodeBytes(proposal(r.update)) match {
          case Left(DecodeError(DecodeError.Kind.Domain(EraGovReadError.Proposal(_)), _, _, _)) =>
            true
          case _ => false
        },
        r.name
      )
    )
    accepted.foreach { r =>
      val again = r.reencoded.getOrElse(fail(r.name))
      assertEquals(codec.write(read(proposal(r.update))), Right(proposal(again)), r.name)
    }
    // the updates of Dijkstra's keys among them
    assert(accepted.exists(r => keys(read(proposal(r.update))).exists(_ >= 34)))
  }

  test("the Leios testnet's two updates read as Dijkstra's and write their bytes") {
    Vector(
      ("a2182d1a0004b320182e1a001e8480", Set(45, 46)),
      ("a2182f821a80befc001b0000008bb2c9700018301a00b71b00", Set(47, 48))
    ).foreach { (update, expected) =>
      val raw = proposal(hex(update))
      val p = read(raw)
      assertEquals(keys(p), expected.map(BigInt(_)), update)
      assertEquals(codec.write(p), Right(raw), update)
    }
  }

  test("an update of Conway's keys and Dijkstra's is one update, its ratios written reduced") {
    // {0: 44, 9: 2/4, 37: 6/4}: tag 30 ratios are written reduced, as the reference's `Ratio`
    val p = read(proposal(hex("a3 00 182c 09 d81e820204 1825 d81e820604")))
    assertEquals(keys(p), Set(0, 9, 37).map(BigInt(_)))
    assertEquals(codec.write(p), Right(proposal(hex("a3 00 182c 09 d81e820102 1825 d81e820302"))))
  }

  test("entries are refused in order: a bad Conway value after a Dijkstra key, a key past 56") {
    // a ratio of -1 at key 10, after key 34
    assert(codec.decodeBytes(proposal(hex("a2 1822 01 0a d81e822001"))) match {
      case Left(DecodeError(DecodeError.Kind.Domain(EraGovReadError.Proposal(_)), _, _, _)) => true
      case _                                                                                => false
    })
    codec.decodeBytes(proposal(hex("a2 1822 01 1839 01"))).left.map(_.kind) match
      case Left(
            DecodeError.Kind.Domain(
              EraGovReadError.Proposal(
                EraTxError.Decode(LedgerDecodeError.UnknownKey(_, key))
              )
            )
          ) =>
        assertEquals(key, BigInt(57))
      case other => fail(s"not the unknown key: $other")
  }

  test("the 11.1 layout refuses Dijkstra's keys and its Dijkstra parameters") {
    val raw = hex("8400f6" + "a2182d1a0004b320182e1a001e8480" + "f6")
    assertEquals(
      HaskellStateCodecs.govAction(using v12).decodeBytes(raw).left.map(_.kind),
      Left(DecodeError.Kind.Domain(DijkstraNotYet.ParameterUpdate))
    )
    assertEquals(
      HaskellStateCodecs.dijkstraPParams.decodeBytes(hex("80")).left.map(_.kind),
      Left(DecodeError.Kind.Domain(DijkstraNotYet.LedgerState))
    )
  }
