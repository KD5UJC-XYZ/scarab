// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{Decoder, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.plutus.{ExUnits, PlutusLanguage}
import xyz.kd5ujc.scarab.protocol.Era

/** An explicit decoder version. [[DecoderVersion.at]] builds one of an era's range, `ProtVerLow` to
  * `ProtVerHigh`
  * (`libs/cardano-ledger-core/internal/Cardano/Ledger/Internal/Definition/Era.hs:87-141`,
  * cardano-ledger f649f975), and refuses any other; the era's entry points read at whatever version
  * they are given, as `decodeFull'` does
  * (`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding.hs:93-100`).
  *
  * At f649f975 no decoder branches inside an era's range: the decoders branch at versions 2, 7, 9
  * and 12 (`ifDecoderVersionAtLeast`, `whenDecoderVersionAtLeast`), 3 (`decodeMetadatum`'s `> 2`),
  * 12 (`decodeAnnSet`'s `>= 12`) and 5, 7, 9 and 12 (`guardPlutus`), against ranges of Alonzo 5 to
  * 6, Babbage 7 to 8 and Conway 9 to 11. So every version of an era gives the verdict and the value
  * its lowest gives; a refusal can still differ in the version it names (an auxiliary Plutus script
  * refused before its language's version, `AuxiliaryScriptProtocol`), which no line of these
  * corpora reaches. That a version reaches each place that branches on it is shown by reading a
  * part across the branch it meets, at 9 or at 12, through each entry point.
  */
class DecoderVersionSuite extends FunSuite:
  import DecoderVersionSuite.*

  private val reference: Map[Era, (Int, Int)] = Map(
    Era.Byron -> (0, 1),
    Era.Shelley -> (2, 2),
    Era.Allegra -> (3, 3),
    Era.Mary -> (4, 4),
    Era.Alonzo -> (5, 6),
    Era.Babbage -> (7, 8),
    Era.Conway -> (9, 11),
    Era.Dijkstra -> (12, 12)
  )

  test("an era's range is ProtVerLow to ProtVerHigh, from Shelley its lowest the era's version") {
    assertEquals(reference.keySet, Era.values.toSet)
    for era <- Era.values do
      val range = DecoderVersion.range(era)
      assertEquals((range.low, range.high), reference(era), s"$era")
      assertEquals(DecoderVersion.high(era).value, range.high, s"$era: eraProtVerHigh")
      assertEquals(range.versions.map(_.value), (range.low to range.high).toVector, s"$era")
    for era <- Era.values.filterNot(_ == Era.Byron) do
      assertEquals(DecoderVersion.range(era).low, DecoderVersion.of(era).value, s"$era")
    // the readers of Shelley-shaped parts read a Byron era as Shelley, outside Byron's own range
    assertEquals(DecoderVersion.of(Era.Byron), DecoderVersion.of(Era.Shelley))
    assert(!DecoderVersion.range(Era.Byron).contains(DecoderVersion.of(Era.Byron).value))
  }

  test("every version of an era's range is built, and from Shelley the lowest is the era's own") {
    for
      era <- Era.values
      major <- DecoderVersion.range(era).low to DecoderVersion.range(era).high
    do assertEquals(DecoderVersion.at(era, major).map(_.value), Right(major), s"$era $major")
    for era <- Era.values.filterNot(_ == Era.Byron) do
      assertEquals(
        DecoderVersion.at(era, DecoderVersion.of(era).value),
        Right(DecoderVersion.of(era))
      )
  }

  test("a version outside an era's range is refused, with the era and its range") {
    for era <- Era.values do
      val range = DecoderVersion.range(era)
      for major <- Vector(range.low - 1, range.high + 1, DecoderVersion.MaxVersion + 1) do
        assertEquals(
          DecoderVersion.at(era, major),
          Left(DecoderVersion.OutOfRange(era, major, range)),
          s"$era $major"
        )
    assertEquals(
      DecoderVersion.at(Era.Conway, 12).leftMap(_.range),
      Left(DecoderVersion.Range(9, 11))
    )
    assertEquals(
      DecoderVersion.at(Era.Byron, 2).leftMap(_.range),
      Left(DecoderVersion.Range(0, 1))
    )
  }

  private val at8 = version(Era.Babbage, 8)
  private val at9 = DecoderVersion.of(Era.Conway)
  private val at11 = DecoderVersion.high(Era.Conway)
  private val at12 = DecoderVersion.Dijkstra

  test("output addresses follow version 7 while their value type and forms follow the era") {
    val at6 = DecoderVersion.high(Era.Alonzo)
    val at7 = DecoderVersion.of(Era.Babbage)
    val address = "60" + "11" * 28
    val trailing = "581e" + address + "00"
    for era <- Shelley do
      val ttl = if era == Era.Shelley then "0300" else ""
      val prefix = if era == Era.Shelley then "a4" else "a3"
      def body(output: String) = hex(prefix + "00800181" + output + "0200" + ttl)
      val raw = body("82" + trailing + "00")
      assertEquals(
        TxBody.decode(raw, era, at6).map(_.outputs.map(_.address.raw)),
        Right(Vector(hex(address))),
        s"$era at 6 crops trailing address bytes"
      )
      assertEquals(
        TxBody.decode(raw, era, at7),
        Left(LedgerDecodeError.AddressLength(DecodeField.EnterpriseAddressLength, 29, 30)),
        s"$era at 7 requires the whole address"
      )
      val multiasset = body("82581d" + address + "8200a0")
      if era.ordinal < Era.Mary.ordinal then
        assertEquals(
          TxBody.decode(multiasset, era, at9),
          Left(LedgerDecodeError.MultiAssetNotAllowed(DecodeField.OutputValue))
        )
      else assert(TxBody.decode(multiasset, era, at9).isRight, s"$era retains MaryValue")
      val mapOutput = body("a200581d" + address + "0100")
      assertEquals(TxBody.decode(mapOutput, era, at6).isRight, era.ordinal >= Era.Babbage.ordinal)
  }

  test("multi-asset quantities and cost models follow version 9 independently of era") {
    val address = "581d60" + "11" * 28
    val zeroAsset = "a1581c" + "22" * 28 + "a14000"
    for era <- Vector(Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway) do
      val raw = hex("a30080018182" + address + "8200" + zeroAsset + "0200")
      assertEquals(
        TxBody.decode(raw, era, at8).map(_.outputs.map(_.value.assets.size)),
        Right(Vector(0))
      )
      assertEquals(
        TxBody.decode(raw, era, at9),
        Left(LedgerDecodeError.ZeroQuantity(DecodeField.OutputValue))
      )
    // CostModels.hs:311-337 at f649f975: short V1 is refused below 9, retained from 9.
    val shortModel = "a112a10080"
    for era <- Vector(Era.Alonzo, Era.Babbage) do
      val raw = hex("a40080018002000682a1581c" + "00" * 28 + shortModel + "00")
      assertEquals(TxBody.decode(raw, era, at8), Left(LedgerDecodeError.CostModelLength(0, 166, 0)))
      assert(TxBody.decode(raw, era, at9).isRight, s"$era at 9 reads an open cost model")
    val proposal = hex("8400581de0" + "00" * 28 + "8400f6" + shortModel + "f682605820" + "00" * 32)
    val body = hex("a40080018002001481") ++ proposal
    assertEquals(
      TxBody.decode(body, Era.Conway, at8),
      Left(LedgerDecodeError.CostModelLength(0, 166, 0))
    )
    assert(TxBody.decode(body, Era.Conway, at9).isRight)
  }

  test("pointer normalization and collateral-return addresses follow the supplied version") {
    val address = "40" + "11" * 28 + "90808080000000"
    val normalized = hex("40" + "11" * 28 + "000000")
    val output = "825824" + address + "00"
    for era <- Vector(Era.Alonzo, Era.Babbage, Era.Conway) do
      val raw = hex("a300800181" + output + "0200")
      assertEquals(
        TxBody.decode(raw, era, at8).map(_.outputs.map(_.address.raw)),
        Right(Vector(normalized))
      )
      assertEquals(
        TxBody.decode(raw, era, at9),
        Left(LedgerDecodeError.Pointer(Ptr.Failure.OutOfRange))
      )
    val collateral = hex("a40080018002001082581e60" + "11" * 28 + "0000")
    for era <- Vector(Era.Babbage, Era.Conway) do
      assert(TxBody.decode(collateral, era, DecoderVersion.high(Era.Alonzo)).isRight)
      assertEquals(
        TxBody.decode(collateral, era, at8),
        Left(LedgerDecodeError.AddressLength(DecodeField.EnterpriseAddressLength, 29, 30))
      )
  }

  test("Mary values at version 12 allow indefinite pairs and require a non-empty asset map") {
    val address = "581d60" + "11" * 28
    val assets = "a1581c" + "22" * 28 + "a14001"
    for era <- Vector(Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway) do
      def body(value: String) = hex("a30080018182" + address + value + "0200")
      val indefinite = body("9f00" + assets + "ff")
      assertEquals(
        TxBody.decode(indefinite, era, at11),
        Left(LedgerDecodeError.IndefiniteLength(DecodeField.OutputValue))
      )
      assertEquals(
        TxBody.decode(indefinite, era, at12).map(_.outputs.map(_.value)),
        TxBody.decode(body("8200" + assets), era, at11).map(_.outputs.map(_.value))
      )
      assert(TxBody.decode(body("8200" + assets), era, at12).isRight)
      assert(TxBody.decode(body("8200a0"), era, at11).isRight)
      assertEquals(
        TxBody.decode(body("8200a0"), era, at12),
        Left(LedgerDecodeError.Empty(DecodeField.OutputValue))
      )
  }

  test("Mary-family mint maps must be non-empty from version 12") {
    val raw = hex("a400800180020009a0")
    for era <- Vector(Era.Mary, Era.Alonzo, Era.Babbage) do
      assert(TxBody.decode(raw, era, at11).isRight, s"$era at 11 accepts an empty mint map")
      assertEquals(
        TxBody.decode(raw, era, at12),
        Left(LedgerDecodeError.Empty(DecodeField.Mint))
      )
  }

  // Version-12 record branches at cardano-ledger f649f975:
  // Plutus/ExUnits.hs:195-230; Conway/Governance/Procedures.hs:182-190,456-466,522-534,985-995;
  // BaseTypes.hs:996-1004; Alonzo/TxAuxData.hs:217-257.
  test("redeemer execution units accept indefinite pairs only from version 12") {
    val raw = hex("a105a182000082009f0000ff")
    for era <- Vector(Era.Alonzo, Era.Babbage, Era.Conway) do
      assertEquals(
        Witnesses.decode(raw, era, at11),
        Left(LedgerDecodeError.IndefiniteLength(DecodeField.ExUnits))
      )
      assertEquals(
        Witnesses.decode(raw, era, at12).map(_.redeemers),
        Right(Vector(Redeemer(0, 0, hex("00"), ExUnits.zero)))
      )
      assertEquals(
        Witnesses.decode(hex("a105a182000082009f00ff"), era, at12),
        Left(LedgerDecodeError.ArrayLength(DecodeField.ExUnits, 2, 1))
      )
  }

  private val zeroHash = "00" * 32
  private val reward = "581de0" + "00" * 28
  private val anchor = "82605820" + zeroHash
  private val expectedAnchor = Anchor("", hex(zeroHash))
  private val expectedAccount =
    RewardAccount.decode(hex("e0" + "00" * 28)).fold(e => fail(e.toString), identity)

  private def proposalBody(proposal: String): Bytes = hex("a40080018002001481" + proposal)
  private def proposal(action: String, anch: String = anchor): String =
    "8400" + reward + action + anch

  test("proposal procedures, action IDs, constitutions and anchors follow version 12") {
    val id = "9f5820" + zeroHash + "00ff"
    val previous = Some(GovActionId(hex(zeroHash), 0))
    val cases = Vector(
      (
        "9f00" + reward + "8106" + anchor + "ff",
        DecodeField.ProposalProcedure,
        GovAction.Info,
        expectedAnchor
      ),
      (
        proposal("8203" + id),
        DecodeField.GovernanceActionId,
        GovAction.NoConfidence(previous),
        expectedAnchor
      ),
      (
        proposal("8400" + id + "a0f6"),
        DecodeField.GovernanceActionId,
        GovAction.ParameterChange(previous, ConwayParamUpdate.empty, None),
        expectedAnchor
      ),
      (
        proposal("8301" + id + "820900"),
        DecodeField.GovernanceActionId,
        GovAction.HardFork(previous, 9, 0),
        expectedAnchor
      ),
      (
        proposal("8504" + id + "80a0d81e820101"),
        DecodeField.GovernanceActionId,
        GovAction.UpdateCommittee(previous, Set.empty, Map.empty, Rational.one),
        expectedAnchor
      ),
      (
        proposal("8305" + id + "82" + anchor + "f6"),
        DecodeField.GovernanceActionId,
        GovAction.NewConstitution(previous, None, expectedAnchor),
        expectedAnchor
      ),
      (
        proposal("8305f69f" + anchor + "f6ff"),
        DecodeField.Constitution,
        GovAction.NewConstitution(None, None, expectedAnchor),
        expectedAnchor
      ),
      (
        proposal("8106", "9f605820" + zeroHash + "ff"),
        DecodeField.Anchor,
        GovAction.Info,
        expectedAnchor
      ),
      (
        proposal("8305f6829f605820" + zeroHash + "fff6"),
        DecodeField.Anchor,
        GovAction.NewConstitution(None, None, expectedAnchor),
        expectedAnchor
      )
    )
    for (encoded, site, action, anch) <- cases do
      val raw = proposalBody(encoded)
      assertEquals(
        TxBody.decode(raw, Era.Conway, at11),
        Left(LedgerDecodeError.IndefiniteLength(site))
      )
      assertEquals(
        TxBody.decode(raw, Era.Conway, at12).map(_.proposals),
        Right(Vector(Proposal(0, expectedAccount, action, anch)))
      )
      assertEquals(TxBody.decode(raw, Era.Conway), TxBody.decode(raw, Era.Conway, at11))
  }

  test("voting action IDs, voting procedures and their anchors follow version 12") {
    val id = "825820" + zeroHash + "00"
    val cases = Vector(
      ("9f5820" + zeroHash + "00ff", "8201f6", DecodeField.GovernanceActionId, None),
      (id, "9f01f6ff", DecodeField.VotingProcedure, None),
      (id, "82019f605820" + zeroHash + "ff", DecodeField.Anchor, Some(expectedAnchor))
    )
    for (actionId, procedure, site, anch) <- cases do
      val raw = hex("a400800180020013a18204581c" + "00" * 28 + "a1" + actionId + procedure)
      assertEquals(
        TxBody.decode(raw, Era.Conway, at11),
        Left(
          LedgerDecodeError.Voting(
            VotingProcedures.DecodeError.InvalidStructure(LedgerDecodeError.IndefiniteLength(site))
          )
        )
      )
      assertEquals(
        TxBody.decode(raw, Era.Conway, at12).map(_.votes),
        Right(
          Vector(
            Voter.Pool(hex("00" * 28)) -> Vector(
              GovActionId(hex(zeroHash), 0) -> VotingProcedure(Vote.Yes, anch)
            )
          )
        )
      )
  }

  test("certificate anchors follow version 12") {
    val raw = hex("a40080018002000481830f8200581c" + "00" * 28 + "9f605820" + zeroHash + "ff")
    assertEquals(
      TxBody.decode(raw, Era.Conway, at11),
      Left(LedgerDecodeError.IndefiniteLength(DecodeField.Anchor))
    )
    assertEquals(
      TxBody.decode(raw, Era.Conway, at12).map(_.certificates),
      Right(
        Vector(Certificate.CommitteeResign(Credential.Key(hex("00" * 28)), Some(expectedAnchor)))
      )
    )
  }

  private def legacyUpdate(era: Era, update: String): Bytes =
    val prefix = if era == Era.Shelley then "a50080018002000300" else "a4008001800200"
    hex(prefix + "0682a1581c" + "00" * 28 + update + "00")

  private def proposedParams(body: TxBody): Vector[ConwayParam] =
    body.proposals.flatMap { p =>
      p.action match
        case GovAction.ParameterChange(_, update, _) => update.params
        case _                                       => Vector.empty
    }

  test("parameter prices and both execution-unit limits accept indefinite records from 12") {
    val zero = ExUnits.zero
    val one = Rational.one
    val cases = Vector(
      (19, "9fd81e820101d81e820101ff", DecodeField.Prices, ConwayParam.Prices(one, one)),
      (20, "9f0000ff", DecodeField.MaxTxExUnits, ConwayParam.MaxTxExUnits(zero)),
      (21, "9f0000ff", DecodeField.MaxBlockExUnits, ConwayParam.MaxBlockExUnits(zero))
    )
    for (key, encoded, site, expected) <- cases do
      val update = "a1" + f"$key%02x" + encoded
      for era <- Vector(Era.Alonzo, Era.Babbage) do
        val raw = legacyUpdate(era, update)
        assertEquals(TxBody.decode(raw, era, at11), Left(LedgerDecodeError.IndefiniteLength(site)))
        assertEquals(
          TxBody.decode(raw, era, at12).map(_.update),
          Right(Some(Update(Map(hex("00" * 28) -> ParamUpdate(Map(key -> hex(encoded)))), 0)))
        )
      val raw = proposalBody(proposal("8400f6" + update + "f6"))
      assertEquals(
        TxBody.decode(raw, Era.Conway, at11),
        Left(LedgerDecodeError.IndefiniteLength(site))
      )
      assertEquals(
        TxBody.decode(raw, Era.Conway, at12).map(proposedParams),
        Right(Vector(expected))
      )
  }

  // BoundedRatio switches from decodeInteger to Word64 components before reduction:
  // BaseTypes.hs:416-427; Binary/Decoding/DecCBOR.hs:214-225 at f649f975.
  private def unsignedFailure(site: DecodeSite, encoded: String): LedgerDecodeError =
    LedgerDecodeError.Expected(site, LedgerDecodeError.Shape.Unsigned, node(hex(encoded)).value)

  test("bounded rational components are unsigned at 12 in pool margins and committee quorums") {
    val negative = "d81e822020"
    val registration =
      "8a03581c" + "00" * 28 + "5820" + zeroHash + "0000" + negative + reward + "8080f6"
    for era <- Shelley do
      val prefix = if era == Era.Shelley then "a50080018002000300" else "a4008001800200"
      val raw = hex(prefix + "0481" + registration)
      assertEquals(
        TxBody
          .decode(raw, era, at11)
          .map(_.certificates.collect { case Certificate.RegisterPool(params) =>
            params.margin
          }),
        Right(Vector(Rational.one))
      )
      assertEquals(
        TxBody.decode(raw, era, at12),
        Left(unsignedFailure(DecodeField.PoolMargin, "20"))
      )
    val raw = proposalBody(proposal("8504f680a0" + negative))
    assertEquals(
      TxBody.decode(raw, Era.Conway, at11).map(_.proposals.map(_.action)),
      Right(Vector(GovAction.UpdateCommittee(None, Set.empty, Map.empty, Rational.one)))
    )
    assertEquals(
      TxBody.decode(raw, Era.Conway, at12),
      Left(unsignedFailure(DecodeField.Quorum, "20"))
    )
  }

  test("bounded rationals in every parameter path use the supplied version") {
    val ratio = "d81e822020"
    val one = Rational.one
    val zero = Rational.zero
    val pool =
      PoolVotingThresholds.fromVector(Vector.fill(5)(one)).fold(e => fail(e.toString), identity)
    val drep =
      DRepVotingThresholds.fromVector(Vector.fill(10)(one)).fold(e => fail(e.toString), identity)
    val cases = Vector(
      (9, ratio, DecodeField.A0, ConwayParam.A0(one)),
      (10, ratio, DecodeField.Rho, ConwayParam.Rho(one)),
      (11, ratio, DecodeField.Tau, ConwayParam.Tau(one)),
      (19, "82" + ratio + "d81e820001", DecodeField.PriceMem, ConwayParam.Prices(one, zero)),
      (19, "82d81e820001" + ratio, DecodeField.PriceSteps, ConwayParam.Prices(zero, one)),
      (25, "85" + ratio * 5, DecodeField.PoolVotingThresholds, ConwayParam.PoolThresholds(pool)),
      (26, "8a" + ratio * 10, DecodeField.DRepVotingThresholds, ConwayParam.DRepThresholds(drep)),
      (
        33,
        ratio,
        DecodeField.MinFeeRefScriptCostPerByte,
        ConwayParam.MinFeeRefScriptCostPerByte(one)
      )
    )
    for (key, encoded, site, expected) <- cases do
      val keyBytes = if key < 24 then f"$key%02x" else "18" + f"$key%02x"
      val update = "a1" + keyBytes + encoded
      val raw = proposalBody(proposal("8400f6" + update + "f6"))
      // The previous decoder's exact typed values are also the stored reader's contract.
      assertEquals(
        ConwayParamUpdate.decodeStored(node(hex(update))).map(_.params),
        Right(Vector(expected))
      )
      assertEquals(
        TxBody.decode(raw, Era.Conway, at11).map(proposedParams),
        Right(Vector(expected))
      )
      assertEquals(TxBody.decode(raw, Era.Conway, at12), Left(unsignedFailure(site, "20")))
      val legacyEras = Shelley.filter(e =>
        e != Era.Conway && (key < 19 || e.ordinal >= Era.Alonzo.ordinal) && key < 25
      )
      for era <- legacyEras do
        val body = legacyUpdate(era, update)
        assertEquals(
          TxBody.decode(body, era, at11).map(_.update),
          Right(Some(Update(Map(hex("00" * 28) -> ParamUpdate(Map(key -> hex(encoded)))), 0)))
        )
        assertEquals(TxBody.decode(body, era, at12), Left(unsignedFailure(site, "20")))
    for era <- Vector(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo) do
      val raw = legacyUpdate(era, "a10c" + ratio)
      assertEquals(
        TxBody.decode(raw, era, at11).map(_.update),
        Right(Some(Update(Map(hex("00" * 28) -> ParamUpdate(Map(12 -> hex(ratio)))), 0)))
      )
      assertEquals(TxBody.decode(raw, era, at12), Left(unsignedFailure(DecodeField.D, "20")))
  }

  test("version-12 rational components refuse bignums even when reduction would fit") {
    val big = "c249010000000000000000"
    val raw = proposalBody(proposal("8400f6a109d81e82" + big + big + "f6"))
    assertEquals(
      TxBody.decode(raw, Era.Conway, at11).map(proposedParams),
      Right(Vector(ConwayParam.A0(Rational.one)))
    )
    assertEquals(TxBody.decode(raw, Era.Conway, at12), Left(unsignedFailure(DecodeField.A0, big)))
    val valid = proposalBody(proposal("8400f6a109d81e9f0101fff6"))
    for v <- Vector(at11, at12) do
      assertEquals(
        TxBody.decode(valid, Era.Conway, v).map(proposedParams),
        Right(Vector(ConwayParam.A0(Rational.one)))
      )
  }

  test("auxiliary list records and Plutus V4 are accepted from version 12") {
    given ByteStrings = ByteStrings.of(at12)
    assertEquals(
      AuxiliaryScripts
        .read(hex("d90103a1058140"), at12.value)
        .map(_.map(s => (s.language, s.code, s.hash))),
      Right(
        Vector(
          (
            PlutusLanguage.V4,
            hex(""),
            hex("65fc709a5e019b8aba76f6977c1c8770e4b36fa76f434efc588747b7")
          )
        )
      )
    )
    for era <- Vector(Era.Alonzo, Era.Babbage, Era.Conway) do
      def tx(aux: String) = hex("84a3008001800200a0f5" + aux)
      val indefinite = "9fa080ff"
      assertEquals(
        Tx.fromEnvelope(tx(indefinite), era, at11),
        Left(LedgerDecodeError.IndefiniteLength(DecodeField.AuxiliaryData))
      )
      assertEquals(
        Tx.fromEnvelope(tx(indefinite), era, at12).map(_.auxiliary),
        Right(Some(hex(indefinite)))
      )
      val v4 = "d90103a1058140"
      assertEquals(
        Tx.fromEnvelope(tx(v4), era, at11),
        Left(LedgerDecodeError.UnknownTag(DecodeField.AuxiliaryField, 5))
      )
      assertEquals(Tx.fromEnvelope(tx(v4), era, at12).map(_.auxiliary), Right(Some(hex(v4))))
  }

  test("witness script languages remain tied to the era at explicit versions") {
    val plutusV2 = hex("a1068140")
    assertEquals(
      Witnesses.decode(plutusV2, Era.Alonzo, at12),
      Left(LedgerDecodeError.UnsupportedLanguage(ScriptKind.PlutusV2, ScriptKind.PlutusV1))
    )
    assert(Witnesses.decode(plutusV2, Era.Babbage, DecoderVersion.high(Era.Alonzo)).isRight)
    val timelock = hex("a10181820400")
    assert(Witnesses.decode(timelock, Era.Shelley, at12).isLeft)
    assert(Witnesses.decode(timelock, Era.Allegra, DecoderVersion.of(Era.Shelley)).isRight)
  }

  test("Alonzo witness sets refuse duplicate vkeys, native scripts and datums from version 12") {
    val vkey = "825820" + "11" * 32 + "5840" + "22" * 64
    val script = "8200581c" + "33" * 28
    val cases = Vector(
      ("a10082" + vkey + vkey, DecodeField.VkeyWitnesses),
      ("a10182" + script + script, DecodeField.NativeScripts),
      ("a104820000", DecodeField.Datums)
    )
    for
      era <- Vector(Era.Alonzo, Era.Babbage, Era.Conway)
      (raw, field) <- cases
    do
      assert(Witnesses.decode(hex(raw), era, at11).isRight, s"$era $field at 11")
      assertEquals(
        Witnesses.decode(hex(raw), era, at12),
        Left(LedgerDecodeError.DuplicateElement(field))
      )
    for era <- Vector(Era.Shelley, Era.Allegra, Era.Mary) do
      assertEquals(
        Witnesses.decode(hex("a10082" + vkey + vkey), era, at12).map(_.vkeys.size),
        Right(1)
      )
      assert(Witnesses.decode(hex("a10080"), era, at12).isRight)
      assert(Witnesses.decode(hex("a100d9010280"), era, at12).isLeft)
  }

  test("redeemers must use the map form from version 12, retaining last-wins map entries") {
    val listed = hex("a1058184000000820000")
    assert(Witnesses.decode(listed, Era.Conway, at11).isRight)
    assertEquals(
      Witnesses.decode(listed, Era.Conway, at12),
      Left(
        LedgerDecodeError.Expected(
          DecodeField.Redeemers,
          LedgerDecodeError.Shape.Map,
          node(hex("8184000000820000")).value
        )
      )
    )
    val mapped = hex("a105a282000082008200008200008201820000")
    assertEquals(
      Witnesses.decode(mapped, Era.Conway, at12).map(_.redeemers.map(_.data)),
      Right(Vector(hex("01")))
    )
  }

  test("a body reads its inputs, withdrawals and certificates at the version given (9)") {
    val duplicate = LedgerDecodeError.DuplicateElement(DecodeField.Inputs)
    // `decodeSetEnforceNoDuplicates` from 9 (`Decoder.hs`, f649f975); `Set.fromList` below it
    assertEquals(TxBody.decode(duplicateInputs, Era.Conway, at8).map(_.inputs.size), Right(1))
    assertEquals(TxBody.decode(duplicateInputs, Era.Conway, at9), Left(duplicate))
    assertEquals(TxBody.decode(duplicateInputs, Era.Conway), Left(duplicate))
    for v <- DecoderVersion.range(Era.Conway).versions do
      assertEquals(TxBody.decode(duplicateInputs, Era.Conway, v), Left(duplicate), s"${v.value}")
    // the same account withdrawn twice: refused from 9, the last kept below it
    val withdrawals = hex("a400800180020005a2" + account + "00" + account + "01")
    assertEquals(TxBody.decode(withdrawals, Era.Conway, at8).map(_.withdrawals.size), Right(1))
    assertEquals(
      TxBody.decode(withdrawals, Era.Conway, at9),
      Left(LedgerDecodeError.DuplicateElement(DecodeField.Withdrawals))
    )
    // the body's certificates are read at the body's version
    val certificates = hex("a40080018002000481") ++ registration
    assertEquals(
      TxBody.decode(certificates, Era.Conway, at8).map(_.certificates.map(owners)),
      Right(Vector(1))
    )
    assertEquals(
      TxBody.decode(certificates, Era.Conway, at9),
      Left(LedgerDecodeError.DuplicateElement(DecodeField.PoolOwners))
    )
  }

  test("a certificate reads its pool owners and its anchor's url at the version given (9)") {
    assertEquals(Certificate.decode(node(registration), Era.Conway, at8).map(owners), Right(1))
    assertEquals(
      Certificate.decode(node(registration), Era.Conway, at9),
      Left(LedgerDecodeError.DuplicateElement(DecodeField.PoolOwners))
    )
    // `textDecCBOR 64` below 9 and 128 from it (`BaseTypes.hs:644-697`, f649f975)
    val url = "7864" + "61" * 100
    val drep = node(hex("841082" + "00581c" + "11" * 28 + "0082" + url + "5820" + "22" * 32))
    assertEquals(
      Certificate.decode(drep, Era.Conway, at8),
      Left(LedgerDecodeError.TextTooLong(DecodeField.AnchorUrl, 64, 100))
    )
    assert(Certificate.decode(drep, Era.Conway, at9).isRight)
  }

  test("a witness set reads its lists and chain codes at the version given (9, 12)") {
    // `decodeNonEmptyList` from 9 (`Alonzo/TxWits.hs`, f649f975)
    val empty = hex("a10080")
    assertEquals(Witnesses.decode(empty, Era.Conway, at8).map(_.vkeys), Right(Vector.empty))
    assertEquals(
      Witnesses.decode(empty, Era.Conway, at9),
      Left(LedgerDecodeError.Empty(DecodeField.VkeyWitnesses))
    )
    // `DecCBOR ChainCode` checks 32 bytes from 12 (`Keys/Bootstrap.hs:72-78`, f649f975)
    for v <- DecoderVersion.range(Era.Conway).versions do
      assertEquals(
        Witnesses.decode(chainCode31, Era.Conway, v).map(_.bootstrap.map(_.chainCode.size)),
        Right(Vector(31)),
        s"${v.value}"
      )
    assertEquals(
      Witnesses.decode(chainCode31, Era.Conway, at12),
      Left(LedgerDecodeError.ByteLength(DecodeField.ChainCode, 32, 31))
    )
  }

  test("a transaction reads its body and auxiliary data at the version given (9)") {
    assertEquals(Tx.fromEnvelope(transaction, Era.Conway, at8).map(_.body.inputs.size), Right(1))
    assertEquals(
      Tx.fromEnvelope(transaction, Era.Conway, at9),
      Left(LedgerDecodeError.DuplicateElement(DecodeField.Inputs))
    )
    // a metadata label written twice: refused from 9 (`decodeMetadata`, `Metadata.hs`, f649f975)
    val labels = hex("a200000000")
    val duplicateLabels = LedgerDecodeError.DuplicateKey(DecodeField.MetadataLabels)
    val whole = hex("84a3008001800200a0f5") ++ labels
    assert(Tx.fromEnvelope(whole, Era.Conway, at8).isRight)
    assertEquals(Tx.fromEnvelope(whole, Era.Conway, at9), Left(duplicateLabels))
    val parts = Block.Transaction(hex("a3008001800200"), hex("a0"), Some(labels), true)
    assert(Tx.fromBlock(parts, Era.Conway, at8).isRight)
    assertEquals(Tx.fromBlock(parts, Era.Conway, at9), Left(duplicateLabels))
  }

  test("a header reads its byte strings at the version given (12)") {
    // `decodeBytes` takes a chunked string from 12 only (`Decoder.hs:350-373`, f649f975)
    val definite = hex("82" + headerBody + "5901c0" + "08" * 448)
    val chunked = hex("82" + headerBody + "5f" + ("5900e0" + "08" * 224) * 2 + "ff")
    val read = Header.fromRaw(Era.Conway, definite).map(_.signable)
    assert(read.isRight, s"$read")
    assert(Header.fromRaw(Era.Conway, chunked, at11).isLeft)
    assertEquals(Header.fromRaw(Era.Conway, chunked, at12).map(_.signable), read)
  }

  test("a block passes its explicit version to the header's byte-string decoder") {
    val chunked = hex("82" + headerBody + "5f" + ("5900e0" + "08" * 224) * 2 + "ff")
    val raw = hex("820785") ++ chunked ++ hex("8080a080")
    val header = Header.fromRaw(Era.Conway, chunked, at12).fold(e => fail(s"$e"), identity)
    assert(Block.decodeShelley(raw).isLeft)
    assert(Block.decodeShelley(raw, _ => at11).isLeft)
    assertEquals(
      Block.decodeShelley(raw, _ => at12).map {
        case b: Block.Shelley => b.header
        case other            => fail(s"expected Shelley block, got $other")
      },
      Right(header)
    )
  }

  test("both transaction entry points pass their explicit version to the witness decoder") {
    val parts = Block.Transaction(hex("a3008001800200"), chainCode31, None, true)
    val raw = Tx.envelope(parts)
    val refused = Left(LedgerDecodeError.ByteLength(DecodeField.ChainCode, 32, 31))
    assertEquals(
      Tx.fromBlock(parts, Era.Conway, at11).map(_.witnesses.bootstrap.map(_.chainCode.size)),
      Right(Vector(31))
    )
    assertEquals(
      Tx.fromEnvelope(raw, Era.Conway, at11).map(_.witnesses.bootstrap.map(_.chainCode.size)),
      Right(Vector(31))
    )
    assertEquals(Tx.fromBlock(parts, Era.Conway, at12), refused)
    assertEquals(Tx.fromEnvelope(raw, Era.Conway, at12), refused)
  }

  test("a block reads its records, header and auxiliary data at the version given (9, 12)") {
    // a block body whose auxiliary data names the one transaction twice: `decodeIntMap` refuses it
    // from 9 and keeps the last entry below it
    val aux = DetachedRecordsReferenceSuite.block(
      Era.Conway,
      hex("81a0" + "81a0" + "a2" + "00a10001" + "00a10002" + "80")
    )
    assertEquals(
      Block.decodeShelley(aux, _ => at8).map {
        case b: Block.Shelley => b.transactions.map(_.auxiliaryData)
        case _                => Vector.empty
      },
      Right(Vector(Some(hex("a10002"))))
    )
    assertEquals(
      Block.decodeShelley(aux, _ => at9),
      Left(
        ChainError.Decode(
          DecodeField.Block,
          LedgerDecodeError.DuplicateKey(DecodeField.AuxiliaryData)
        )
      )
    )
    // a block whose header is a detached record: read below 12, where `RecD` reads one, and
    // refused at 12, where no record is detached ([[DetachedRecords.Shapes]])
    val detached = rows
      .find(r => r.source == "header-decoding" && r.name == "babbage block detached header")
      .map(r => hex("8206") ++ r.raw)
      .getOrElse(fail("no detached Babbage block in the header-decoding corpus"))
    for v <- DecoderVersion.range(Era.Babbage).versions do
      assert(Block.decodeShelley(detached, _ => v).isRight, s"${v.value}")
    assert(Block.decodeShelley(detached, _ => at12).isLeft)
  }

  test("a body, a witness set and a transaction read detached records below 12 only") {
    for kind <- Vector(Kind.Body, Kind.Witnesses, Kind.Transaction) do
      val detached = rows.filter(r =>
        r.source == "recd-detached" && r.era == Era.Conway && r.kind == kind &&
          DetachedRecords.mayHold(r.raw) && accepted(read(r, None))
      )
      assert(detached.nonEmpty, s"no accepted detached Conway $kind")
      assert(
        detached.exists(r => !accepted(read(r, Some(at12)))),
        s"every detached Conway $kind read at 12"
      )
  }

  private val rows: Vector[Row] = load.fold(e => fail(e.toString), identity)

  test("the corpora hold each Shelley-based era's bodies, witness sets, transactions and headers") {
    for era <- Shelley do
      for kind <- Vector(Kind.Body, Kind.Transaction) do
        assert(rows.exists(r => r.era == era && r.kind == kind), s"no $era $kind")
      assert(
        rows.exists(r => r.era == era && Set(Kind.Witnesses, Kind.Transaction).contains(r.kind)),
        s"no $era witness set"
      )
    for era <- Vector(Era.Shelley, Era.Alonzo, Era.Babbage, Era.Conway) do
      for kind <- Vector(Kind.Header, Kind.Block) do
        assert(rows.exists(r => r.era == era && r.kind == kind), s"no $era $kind")
    for era <- Vector(Era.Alonzo, Era.Babbage, Era.Conway) do
      assert(rows.exists(r => r.era == era && r.kind == Kind.BlockBody), s"no $era block body")
  }

  for era <- Shelley do
    test(s"every version of $era's range reads the corpora as its own version does") {
      val own = rows.filter(_.era == era)
      for
        row <- own
        v <- DecoderVersion.range(era).versions
      do
        assertEquals(
          read(row, Some(v)),
          read(row, None),
          s"${row.source}: ${row.name} at ${v.value}"
        )
    }

object DecoderVersionSuite:
  val Shelley: Vector[Era] =
    Vector(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)

  def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => sys.error(e.toString), identity)

  def node(b: Bytes): xyz.kd5ujc.scarab.codec.Node =
    CborRead.decode(b).fold(e => sys.error(e.toString), identity)

  def version(era: Era, major: Int): DecoderVersion =
    DecoderVersion.at(era, major).fold(e => sys.error(e.toString), identity)

  private val input = "825820" + "11" * 32 + "00"

  /** A testnet key reward account. */
  private val account = "581de0" + "11" * 28

  /** A pool registration whose two owners are one key: `decodeSet` refuses it from 9. */
  val registration: Bytes =
    val owner = "581c" + "11" * 28
    hex(
      "8a03581c" + "00" * 28 + "5820" + "00" * 32 + "0000d81e820001581de0" + "00" * 28 + "82" +
        owner + owner + "80" + "f6"
    )

  /** The owners of a pool registration, 0 for any other certificate. */
  def owners(c: Certificate): Int = c match
    case Certificate.RegisterPool(params) => params.owners.size
    case _                                => 0

  /** A Praos header body of protocol version 9.0 (`Praos/BlockHeader.hs:203-229`, f649f975). */
  val headerBody: String =
    "8a" + "01" + "02" + "f6" + "5820" + "03" * 32 + "5820" + "04" * 32 + "82" + "5840" +
      "01" * 64 + "5850" + "02" * 80 + "00" + "5820" + "05" * 32 + "84" + "5820" + "06" * 32 +
      "00" + "00" + "5840" + "07" * 64 + "82" + "09" + "00"

  /** Whether an entry point read a line. */
  def accepted(r: Read): Boolean = r match
    case Read.Body(result)                => result.isRight
    case Read.Witnesses(result)           => result.isRight
    case Read.Transaction(result)         => result.isRight
    case Read.Header(result)              => result.isRight
    case Read.Block(result, transactions) => result.isRight && transactions.forall(_.isRight)

  /** A Conway body of one input written twice, no outputs and no fee. */
  val duplicateInputs: Bytes = hex("a300" + "82" + input + input + "0180" + "0200")

  /** A Conway transaction of that body, an empty witness set and no auxiliary data. */
  val transaction: Bytes = hex("84") ++ duplicateInputs ++ hex("a0f5f6")

  /** A witness set of one bootstrap witness whose chain code is 31 bytes. */
  val chainCode31: Bytes =
    hex("a10281845820" + "22" * 32 + "5840" + "33" * 64 + "581f" + "44" * 31 + "41a0")

  enum Kind(val wire: String):
    case Body extends Kind("body")
    case Witnesses extends Kind("witnesses")
    case Transaction extends Kind("transaction")
    case Header extends Kind("header")
    case Block extends Kind("block")
    case BlockBody extends Kind("blockbody")

  /** A line of a reference corpus, of a part one of the entry points reads. */
  final case class Row(source: String, name: String, era: Era, kind: Kind, raw: Bytes)

  /** What an entry point read from a line at a version (`None`: the era's own), refusals included.
    */
  enum Read:
    case Body(result: Either[LedgerDecodeError, TxBody])
    case Witnesses(result: Either[LedgerDecodeError, xyz.kd5ujc.scarab.ledger.Witnesses])
    case Transaction(result: Either[LedgerDecodeError, Tx])
    case Header(result: Either[ChainError, xyz.kd5ujc.scarab.ledger.Header.Shelley])
    case Block(
        result: Either[ChainError, xyz.kd5ujc.scarab.ledger.Block],
        transactions: Vector[Either[LedgerDecodeError, Tx]]
    )

  def read(row: Row, at: Option[DecoderVersion]): Read =
    val era = row.era
    def blockOf(raw: Bytes) =
      val block = at.fold(xyz.kd5ujc.scarab.ledger.Block.decodeShelley(raw))(v =>
        xyz.kd5ujc.scarab.ledger.Block.decodeShelley(raw, _ => v)
      )
      val transactions = block.toOption.toVector.flatMap {
        case b: xyz.kd5ujc.scarab.ledger.Block.Shelley =>
          b.transactions.map(t => at.fold(Tx.fromBlock(t, era))(Tx.fromBlock(t, era, _)))
        case _ => Vector.empty
      }
      Read.Block(block, transactions)
    row.kind match
      case Kind.Body =>
        Read.Body(at.fold(TxBody.decode(row.raw, era))(TxBody.decode(row.raw, era, _)))
      case Kind.Witnesses =>
        Read.Witnesses(
          at.fold(xyz.kd5ujc.scarab.ledger.Witnesses.decode(row.raw, era))(
            xyz.kd5ujc.scarab.ledger.Witnesses.decode(row.raw, era, _)
          )
        )
      case Kind.Transaction =>
        Read.Transaction(at.fold(Tx.fromEnvelope(row.raw, era))(Tx.fromEnvelope(row.raw, era, _)))
      case Kind.Header =>
        Read.Header(
          at.fold(xyz.kd5ujc.scarab.ledger.Header.fromRaw(era, row.raw))(
            xyz.kd5ujc.scarab.ledger.Header.fromRaw(era, row.raw, _)
          )
        )
      case Kind.Block =>
        blockOf(hex("82" + f"${era.blockTag}%02x") ++ row.raw)
      case Kind.BlockBody =>
        blockOf(DetachedRecordsReferenceSuite.block(era, row.raw))

  /** The corpora of the reference's verdicts on each era's parts, read by the decoders of each. */
  val Sources: Vector[String] = Vector(
    "era-decoders",
    "recd-detached",
    "recd-records",
    "tx-envelope",
    "header-decoding"
  )

  enum LoadError:
    case Unreadable(path: Path, cause: Throwable)
    case Line(path: Path, number: Int, cause: io.circe.Error)

  private val eras: Map[String, Era] = Era.values.map(e => e.toString.toLowerCase -> e).toMap

  /** A line of a part this suite reads, of a Shelley-based era; any other is `None`. */
  private def line(source: String): Decoder[Option[Row]] = Decoder.instance { c =>
    for
      name <- c.get[String]("name")
      era <- c.get[String]("era").map(eras.get)
      kind <- c.get[String]("kind").map(k => Kind.values.find(_.wire == k))
      raw <- c
        .get[String]("bytes")
        .flatMap(h =>
          Bytes.fromHex(h).leftMap(e => io.circe.DecodingFailure(s"bytes: $e", c.history))
        )
    yield (era.filter(Shelley.contains), kind).mapN((e, k) => Row(source, name, e, k, raw))
  }

  def load: Either[LoadError, Vector[Row]] =
    Sources.flatTraverse { source =>
      val path = Path.of(s"fixtures/reference/$source/cases.jsonl")
      Either
        .catchNonFatal(Files.readAllLines(path).asScala.toVector)
        .leftMap(LoadError.Unreadable(path, _))
        .flatMap(
          _.zipWithIndex
            .filter(_._1.nonEmpty)
            .traverse((text, i) =>
              parser
                .decode[Option[Row]](text)(using line(source))
                .leftMap(LoadError.Line(path, i + 1, _))
            )
            .map(_.flatten)
        )
    }
