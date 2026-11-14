// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.conformance

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value as CborValue}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  Anchor,
  GovAction,
  Block,
  ByteStrings,
  CborRead,
  Credential,
  DecodeField,
  DecoderVersion,
  GovActionId,
  Header,
  LedgerDecodeError,
  RewardAccount,
  TxIn,
  Value
}
import xyz.kd5ujc.scarab.ledger.dijkstra.{
  DijkstraAnchor,
  DijkstraAuxData,
  DijkstraCert,
  DijkstraDatum,
  DijkstraGovActionId,
  DijkstraLevel,
  DijkstraParamUpdate,
  DijkstraProposal,
  DijkstraRelay,
  DijkstraScript,
  DijkstraTx,
  DijkstraTxIn,
  DijkstraTxOut,
  DijkstraValue,
  DijkstraVoting,
  DijkstraWitnesses,
  V12
}
import xyz.kd5ujc.scarab.ledger.state.{ProtVer, ShelleyBasedEra}
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.plutus.PlutusData
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, UtxoCodecs, Versions, Wire}

/** The rules of the Dijkstra corpus, each mapped to the decoder of the Leios testnet's Dijkstra
  * that Scarab reads a Leios block with ([[DijkstraTx]], [[DijkstraWitnesses]] and the readers of
  * [[V12]], at decoder version 12). Scarab has no encoder of these values: a decoded sample is
  * written again from the bytes its decoded value keeps, by a production codec of a scalar, or by a
  * Haskell-format codec of the ledger state where the decoded value is a type that codec writes.
  */
object DijkstraRules:
  private val era = Era.Dijkstra

  /** The decoder version every rule of this era is read at: the era's `eraProtVerLow`, as the node
    * reads a block of the era, whatever its protocol version.
    */
  val version: DecoderVersion = DecoderVersion.of(era)
  private given ByteStrings = ByteStrings.of(version)
  private given ProtVer = Versions.eraLow(ShelleyBasedEra.SDijkstra)

  import Embedding.*
  import Step.*

  private def node(bytes: Bytes): Either[Refusal, Node] =
    CborRead.decode(bytes).leftMap(Refusal.Ledger(_))

  private def ledger[A](r: Either[LedgerDecodeError, A]) = r.leftMap(Refusal.Ledger(_))

  private def read[A](f: Node => Either[LedgerDecodeError, A])(b: Bytes) =
    node(b).flatMap(n => ledger(f(n)))

  private def written[A](codec: Codec[A])(a: A) = ConwayRules.written(codec)(a)
  private val unsigned: Codec[BigInt] = Codec.uintBig((BigInt(1) << 64) - 1)
  private def leafBytes(b: Bytes) = written(Codec.bytes)(b)

  private def unwritten[A](decoder: String)(f: Node => Either[LedgerDecodeError, A]) =
    DatasetRule.unwritten(decoder, Reach.Direct)(read(f))

  private def kept[A](decoder: String, keeps: String)(f: Node => Either[LedgerDecodeError, A])(
      raw: A => Bytes
  ) =
    DatasetRule(decoder, Reach.Direct, Writing.Kept(keeps))(read(f))(a => Right(raw(a)))

  private def shaped(shape: Shape)(b: Bytes): Either[Refusal, Node] =
    node(b).flatMap { n =>
      val fits = (shape, n.value) match
        case (Shape.Map, CborValue.Map(_))                   => true
        case (Shape.Array, CborValue.Arr(_))                 => true
        case (Shape.Tag259, CborValue.Tag(t, _)) if t == 259 => true
        case _                                               => false
      Either.cond(fits, n, Refusal.Form(shape))
    }

  private def auxiliaryRule(shape: Shape) =
    DatasetRule(
      s"DijkstraAuxData.decode (${shape.render} only)",
      Reach.Direct,
      Writing.Kept("DijkstraAuxData.raw")
    )(b => shaped(shape)(b).flatMap(n => ledger(DijkstraAuxData.decode(n))))(a => Right(a.raw))

  private def leafHash(size: Int, site: DecodeField) =
    DatasetRule(s"V12.bytesN($size)", Reach.Direct, Writing.Leaf(s"Wire.hash($size)"))(
      read(V12.bytesN(_, size, site))
    )(written(Wire.hash(size)))

  private def word64(site: DecodeField) =
    DatasetRule("V12.word64", Reach.Direct, Writing.Leaf("Codec.uintBig"))(
      read(V12.word64(_, site))
    )(
      written(unsigned)
    )

  private def credential(site: DecodeField) =
    DatasetRule(
      "Credential.decode",
      Reach.Direct,
      Writing.Encoder("HaskellStateCodecs.nodeCredential")
    )(
      read(Credential.decode(_, site))
    )(written(HaskellStateCodecs.nodeCredential))

  /** Plutus data of `shape`, read as a datum of a witness set and written again as the bytes the
    * witness set keeps of it ([[DijkstraWitnesses.datums]]), as the reference keeps those of
    * `Data era`.
    */
  private def dataRule(shape: Option[Shape]) =
    DatasetRule(
      "DijkstraWitnesses.decode",
      Reach.Embedded("a witness set's datums"),
      Writing.Kept("DijkstraWitnesses.datums")
    )(b =>
      read(DijkstraWitnesses.decode)(hex("a10481") ++ b)
        .flatMap(w => w.datums.headOption.toRight(Refusal.Form(Shape.Present)))
        .flatMap { kept =>
          PlutusData.decode(kept).leftMap(Refusal.Data(_)).flatMap { d =>
            (shape, d) match
              case (None, _)                                 => Right(kept)
              case (Some(Shape.Integer), _: PlutusData.I)    => Right(kept)
              case (Some(Shape.ByteString), _: PlutusData.B) => Right(kept)
              case (Some(s), _)                              => Left(Refusal.Form(s))
          }
        }
    )(Right(_))

  /** A Leios header whose body holds the sample at `field`, the item at `path` of the body's
    * original bytes, which the Leios header keeps and signs.
    */
  private def headerLeaf(field: Int, path: List[Step], inner: Bytes => Bytes = identity) =
    DatasetRule(
      "Header.fromRaw (LeiosHeader)",
      Reach.Embedded("a Leios header body"),
      Writing.Kept("the header body's original bytes (LeiosHeader.signable)")
    )(b =>
      Header
        .fromRaw(era, headerAround(headerBody(field, inner(b), leios = true)))
        .leftMap(Refusal.Chain(_))
    )(h => Step.at(h.signable, Index(field) :: path))

  /** A top-level Dijkstra body of no inputs, no outputs and no fee, with `key` holding the sample.
    */
  private def body(key: String)(sample: Bytes) =
    read(DijkstraTx.body(_, DijkstraLevel.Top))(hex("a4008001800200" + key) ++ sample)

  private def relay(prefix: String, suffix: String = "")(sample: Bytes) =
    read(DijkstraRelay.decode)(hex(prefix) ++ sample ++ hex(suffix))

  /** A proposal procedure of a zero deposit, a testnet key reward account and an empty anchor. */
  private def proposal(prefix: String, suffix: String = "")(sample: Bytes) =
    read(DijkstraProposal.decode)(
      hex("8400581de0" + zeros(28) + prefix) ++ sample ++ hex(suffix + "8260" + "5820" + zeros(32))
    )

  private def output(prefix: String, suffix: String)(sample: Bytes) =
    read(DijkstraTxOut.decode)(hex(prefix) ++ sample ++ hex(suffix))

  private val govActionCodec = Writing.Encoder("HaskellStateCodecs.govAction")
  private val valueCodec = Writing.Encoder("UtxoCodecs.value")

  private def mintMap(assets: Map[Bytes, Map[Bytes, BigInt]]) =
    written(UtxoCodecs.value)(Value(0, assets)).flatMap(Step.at(_, List(Index(1))))

  private def notEmbedded(parent: String) = DatasetRule.Unmapped(Unmapped.NotEmbedded(parent))

  /** The rules, by the name of their directory in the corpus. */
  val rules: Map[String, DatasetRule] = Map(
    "block" -> DatasetRule.unwritten(
      "Block.decodeShelley (LeiosBlock)",
      Reach.Embedded("[8, block]")
    )(b => Block.decodeShelley(hex("8208") ++ b).leftMap(Refusal.Chain(_))),
    "header" -> DatasetRule(
      "Header.fromRaw (LeiosHeader)",
      Reach.Direct,
      Writing.Kept("Header.Shelley.raw")
    )(b => Header.fromRaw(era, b).leftMap(Refusal.Chain(_)))(h => Right(h.raw)),
    "header_body" -> DatasetRule(
      "Header.fromRaw (LeiosHeader)",
      Reach.Embedded("[body, kes signature]"),
      Writing.Kept("the header body's original bytes (LeiosHeader.signable)")
    )(b => Header.fromRaw(era, headerAround(b)).leftMap(Refusal.Chain(_)))(h => Right(h.signable)),
    "operational_cert" -> headerLeaf(8, Nil),
    "kes_vkey" -> headerLeaf(8, List(Index(0)), opCert(0, _)),
    "kes_period" -> headerLeaf(8, List(Index(2)), opCert(2, _)),
    "vrf_vkey" -> headerLeaf(4, Nil),
    "block_number" -> headerLeaf(0, Nil),
    "slot" -> headerLeaf(1, Nil),
    "eb_announcement" -> headerLeaf(11, Nil),
    "block_body" -> notEmbedded("a Leios block, after its header"),
    "transaction" -> DatasetRule(
      "DijkstraTx.fromSubmission",
      Reach.Direct,
      Writing.Kept("DijkstraTx.raw")
    )(b => ledger(DijkstraTx.fromSubmission(b)))(t => Right(t.raw)),
    "transaction_body" -> kept("DijkstraTx.body (Top)", "DijkstraTxBody.raw")(
      DijkstraTx.body(_, DijkstraLevel.Top)
    )(
      _.raw
    ),
    "sub_transaction_body" -> kept("DijkstraTx.body (Sub)", "DijkstraTxBody.raw")(
      DijkstraTx.body(_, DijkstraLevel.Sub)
    )(_.raw),
    "transaction_witness_set" -> kept("DijkstraWitnesses.decode", "DijkstraWitnesses.raw")(
      DijkstraWitnesses.decode
    )(
      _.raw
    ),
    "redeemers" -> DatasetRule.unwritten(
      "DijkstraWitnesses.decode",
      Reach.Embedded("a witness set's key 5")
    )(b => read(DijkstraWitnesses.decode)(hex("a105") ++ b)),
    "vkeywitness" -> DatasetRule.unwritten(
      "DijkstraWitnesses.decode",
      Reach.Embedded("{0: [vkeywitness]}")
    )(b => read(DijkstraWitnesses.decode)(hex("a10081") ++ b)),
    "bootstrap_witness" -> DatasetRule.unwritten(
      "DijkstraWitnesses.decode",
      Reach.Embedded("{2: [bootstrap_witness]}")
    )(b => read(DijkstraWitnesses.decode)(hex("a10281") ++ b)),
    "vkey" -> DatasetRule(
      "DijkstraWitnesses.decode",
      Reach.Embedded("a vkey witness"),
      Writing.Leaf("Codec.bytes")
    )(b => read(DijkstraWitnesses.decode)(hex("a1008182") ++ b ++ hex("5840" + zeros(64))))(w =>
      w.vkeys.headOption.toRight(Unwritten.NotKept).flatMap(v => leafBytes(v._1))
    ),
    "signature" -> DatasetRule(
      "DijkstraWitnesses.decode",
      Reach.Embedded("a vkey witness"),
      Writing.Leaf("Codec.bytes")
    )(b => read(DijkstraWitnesses.decode)(hex("a10081825820" + zeros(32)) ++ b))(w =>
      w.vkeys.headOption.toRight(Unwritten.NotKept).flatMap(v => leafBytes(v._2))
    ),
    "auxiliary_data" -> kept("DijkstraAuxData.decode", "DijkstraAuxData.raw")(
      DijkstraAuxData.decode
    )(_.raw),
    "metadata" -> auxiliaryRule(Shape.Map),
    "auxiliary_data_map" -> auxiliaryRule(Shape.Tag259),
    "auxiliary_data_array" -> auxiliaryRule(Shape.Array),
    "auxiliary_scripts" -> DatasetRule(
      "DijkstraAuxData.decode",
      Reach.Embedded("[{}, scripts]"),
      Writing.Kept("DijkstraAuxData.raw")
    )(b => read(DijkstraAuxData.decode)(hex("82a0") ++ b))(a => Step.at(a.raw, List(Index(1)))),
    "metadatum" -> DatasetRule.unwritten(
      "DijkstraAuxData.decode",
      Reach.Embedded("{0: metadatum}")
    )(b => read(DijkstraAuxData.decode)(hex("a100") ++ b)),
    "native_script" -> DatasetRule(
      "DijkstraScript.native",
      Reach.Direct,
      Writing.Kept("the bytes DijkstraScript.hash is taken over")
    )(b => node(b).flatMap(n => ledger(DijkstraScript.native(n)).tupleLeft(n.original)))(
      (original, script) =>
        Either.cond(
          script.hash == Blake2b.hash224.hash(Bytes(0.toByte +: original.value)),
          original,
          Unwritten.HashMismatch
        )
    ),
    "script" -> unwritten("DijkstraScript.script")(DijkstraScript.script),
    "plutus_v1_script" -> plutusScript(DecodeField.PlutusV1),
    "plutus_v2_script" -> plutusScript(DecodeField.PlutusV2),
    "plutus_v3_script" -> plutusScript(DecodeField.PlutusV3),
    "plutus_data" -> dataRule(None),
    "%constr<plutus_data>" -> dataRule(None),
    "big_int" -> dataRule(Some(Shape.Integer)),
    "bounded_bytes" -> dataRule(Some(Shape.ByteString)),
    "datum_option" -> unwritten("DijkstraDatum.decode")(DijkstraDatum.decode),
    "data" -> DatasetRule
      .unwritten("DijkstraTxOut.decode", Reach.Embedded("an output's inline datum"))(
        output("a300581d61" + zeros(28) + "0100028201", "")
      ),
    "transaction_input" -> DatasetRule(
      "DijkstraTxIn.decode",
      Reach.Direct,
      Writing.Encoder("UtxoCodecs.txIn")
    )(
      read(DijkstraTxIn.decode)
    )(i => written(UtxoCodecs.txIn)(TxIn(i.id, BigInt(i.index)))),
    "transaction_output" -> kept("DijkstraTxOut.decode", "DijkstraTxOut.raw")(DijkstraTxOut.decode)(
      _.raw
    ),
    "babbage_transaction_output" -> kept("DijkstraTxOut.decode", "DijkstraTxOut.raw")(
      DijkstraTxOut.decode
    )(_.raw),
    "alonzo_transaction_output" -> DatasetRule(
      "DijkstraTxOut.decode (an array only)",
      Reach.Direct,
      Writing.Kept("DijkstraTxOut.raw")
    )(b => shaped(Shape.Array)(b).flatMap(n => ledger(DijkstraTxOut.decode(n))))(o => Right(o.raw)),
    "value" -> unwritten("DijkstraValue.decode")(DijkstraValue.decode),
    "%multiasset<positive_coin>" -> DatasetRule.unwritten(
      "DijkstraValue.decode",
      Reach.Embedded("[0, multiasset]")
    )(b => read(DijkstraValue.decode)(hex("8200") ++ b)),
    "mint" -> DatasetRule("DijkstraTx.body (Top)", Reach.Embedded("a body's key 9"), valueCodec)(
      body("09")
    )(t => mintMap(t.mint)),
    "nonzero_int64" -> DatasetRule(
      "DijkstraTx.body (Top)",
      Reach.Embedded("a mint quantity of a body"),
      valueCodec
    )(b => body("09")(hex("a1581c" + zeros(28) + "a140") ++ b))(t =>
      mintMap(t.mint).flatMap(Step.at(_, List(FirstValue, FirstValue)))
    ),
    "asset_name" -> DatasetRule(
      "DijkstraTx.body (Top)",
      Reach.Embedded("an asset name of a body's mint"),
      valueCodec
    )(b => body("09")(hex("a1581c" + zeros(28) + "a1") ++ b ++ hex("01")))(t =>
      mintMap(t.mint).flatMap(Step.at(_, List(FirstValue, FirstKey)))
    ),
    "withdrawals" -> DatasetRule(
      "DijkstraTx.body (Top)",
      Reach.Embedded("a body's key 5"),
      govActionCodec
    )(
      body("05")
    )(t =>
      written(HaskellStateCodecs.govAction)(GovAction.TreasuryWithdrawals(t.withdrawals, None))
        .flatMap(Step.at(_, List(Index(1))))
    ),
    "network_id" -> DatasetRule(
      "DijkstraTx.body (Top)",
      Reach.Embedded("a body's key 15"),
      Writing.Leaf("Codec.uintBig")
    )(b => body("0f")(b).flatMap(t => t.networkId.toRight(Refusal.Form(Shape.Present))))(n =>
      written(unsigned)(BigInt(n))
    ),
    "positive_coin" -> DatasetRule(
      "DijkstraTx.body (Top)",
      Reach.Embedded("a body's donation"),
      Writing.Leaf("Codec.uintBig")
    )(
      body("16")
    )(t => written(unsigned)(t.donation)),
    "account_balance_intervals" -> DatasetRule
      .unwritten("DijkstraTx.body (Top)", Reach.Embedded("a body's key 26"))(
        body("181a")
      ),
    "account_balance_interval" -> DatasetRule.unwritten(
      "DijkstraTx.body (Top)",
      Reach.Embedded("an interval of a body's key 26")
    )(b => body("181a")(hex("a1581de0" + zeros(28)) ++ b)),
    "certificate" -> unwritten("DijkstraCert.decode")(DijkstraCert.decode),
    "certificates" -> unwritten("DijkstraCert.set")(DijkstraCert.set),
    "relay" -> unwritten("DijkstraRelay.decode")(DijkstraRelay.decode),
    "dns_name" -> DatasetRule
      .unwritten("DijkstraRelay.decode", Reach.Embedded("a multi-host relay"))(relay("8202")),
    "ipv4" -> DatasetRule
      .unwritten("DijkstraRelay.decode", Reach.Embedded("a single-host address relay"))(b =>
        relay("8400f6", "f6")(b).flatMap {
          case r @ DijkstraRelay.SingleHostAddr(_, Some(_), _) => Right(r)
          case _                                               => Left(Refusal.Form(Shape.Present))
        }
      ),
    "ipv6" -> DatasetRule
      .unwritten("DijkstraRelay.decode", Reach.Embedded("a single-host address relay"))(b =>
        relay("8400f6f6")(b).flatMap {
          case r @ DijkstraRelay.SingleHostAddr(_, _, Some(_)) => Right(r)
          case _                                               => Left(Refusal.Form(Shape.Present))
        }
      ),
    "pool_metadata" -> notEmbedded("a Dijkstra pool registration"),
    "credential" -> credential(DecodeField.CertificateCredential),
    "stake_credential" -> credential(DecodeField.CertificateCredential),
    "drep_credential" -> credential(DecodeField.CertificateCredential),
    "committee_cold_credential" -> credential(DecodeField.CommitteeMember),
    "committee_hot_credential" -> credential(DecodeField.CommitteeMember),
    "drep" -> notEmbedded("a Dijkstra delegation certificate"),
    "reward_account" -> DatasetRule(
      "RewardAccount.decode (V12.bytes)",
      Reach.Direct,
      Writing.Encoder("HaskellStateCodecs.rewardAccount")
    )(read(n => V12.bytes(n, DecodeField.RewardAccount).flatMap(RewardAccount.decode)))(
      written(HaskellStateCodecs.rewardAccount)
    ),
    "address" -> DatasetRule(
      "DijkstraTxOut.decode",
      Reach.Embedded("[address, 0]"),
      Writing.Kept("Address.raw")
    )(
      output("82", "00")
    )(o => written(Codec.bytes)(o.address.raw)),
    "voter" -> unwritten("DijkstraVoting.voter")(DijkstraVoting.voter),
    // non-empty, as the body that holds them requires (`DijkstraTx.body`'s key 19)
    "voting_procedures" -> unwritten("DijkstraVoting.procedures, non-empty")(n =>
      DijkstraVoting.procedures(n).flatMap(V12.nonEmpty(_, DecodeField.VotingProcedures))
    ),
    "voting_procedure" -> DatasetRule.unwritten(
      "DijkstraVoting.procedures",
      Reach.Embedded("{voter: {id: procedure}}")
    )(b =>
      read(DijkstraVoting.procedures)(
        hex("a18204581c" + zeros(28) + "a1825820" + zeros(32) + "00") ++ b
      )
    ),
    "vote" -> DatasetRule
      .unwritten("DijkstraVoting.procedures", Reach.Embedded("a voting procedure"))(b =>
        read(DijkstraVoting.procedures)(
          hex("a18204581c" + zeros(28) + "a1825820" + zeros(32) + "0082") ++ b ++ hex("f6")
        )
      ),
    "gov_action_id" -> DatasetRule(
      "DijkstraGovActionId.decode",
      Reach.Direct,
      Writing.Encoder("HaskellStateCodecs.govActionId")
    )(read(DijkstraGovActionId.decode))(i =>
      written(HaskellStateCodecs.govActionId)(GovActionId(i.txId, BigInt(i.index)))
    ),
    "anchor" -> DatasetRule(
      "DijkstraAnchor.decode",
      Reach.Direct,
      Writing.Encoder("HaskellStateCodecs.anchor")
    )(
      read(DijkstraAnchor.decode)
    )(a => written(HaskellStateCodecs.anchor)(Anchor(a.url, a.hash))),
    "url" -> DatasetRule(
      "DijkstraAnchor.decode",
      Reach.Embedded("an anchor"),
      Writing.Encoder("HaskellStateCodecs.anchor")
    )(b => read(DijkstraAnchor.decode)(hex("82") ++ b ++ hex("5820" + zeros(32))))(a =>
      written(HaskellStateCodecs.anchor)(Anchor(a.url, a.hash)).flatMap(Step.at(_, List(Index(0))))
    ),
    "proposal_procedure" -> unwritten("DijkstraProposal.decode")(DijkstraProposal.decode),
    "proposal_procedures" -> unwritten("DijkstraProposal.set")(DijkstraProposal.set),
    "gov_action" -> DatasetRule
      .unwritten("DijkstraProposal.decode", Reach.Embedded("a proposal procedure"))(b =>
        read(DijkstraProposal.decode)(
          hex("8400581de0" + zeros(28)) ++ b ++ hex("8260" + "5820" + zeros(32))
        )
      ),
    "constitution" -> DatasetRule
      .unwritten("DijkstraProposal.decode", Reach.Embedded("a NewConstitution action"))(b =>
        read(DijkstraProposal.decode)(
          hex("8400581de0" + zeros(28) + "8305f6") ++ b ++ hex("8260" + "5820" + zeros(32))
        )
      ),
    "protocol_version" -> DatasetRule
      .unwritten("DijkstraProposal.decode", Reach.Embedded("a HardFork action"))(
        proposal("8301f6")
      ),
    "major_protocol_version" -> DatasetRule.unwritten(
      "DijkstraProposal.decode",
      Reach.Embedded("a HardFork action's major version")
    )(proposal("8301f682", "00")),
    "epoch" -> word64(DecodeField.RetirementEpoch),
    "coin" -> word64(DecodeField.ProposalDeposit),
    "protocol_param_update" -> unwritten("DijkstraParamUpdate.decode")(DijkstraParamUpdate.decode),
    "drep_voting_thresholds" -> paramRule("181a", 26),
    "pool_voting_thresholds" -> paramRule("1819", 25),
    "ex_units" -> paramRule("14", 20),
    "ex_unit_prices" -> paramRule("13", 19),
    "cost_models" -> paramRule("12", 18),
    "unit_interval" -> DatasetRule(
      "V12.ratio (Unit)",
      Reach.Direct,
      Writing.Encoder("Wire.unitInterval")
    )(
      read(V12.ratio(_, DecodeField.Quorum, V12.Interval.Unit))
    )(written(Wire.unitInterval)),
    "nonnegative_interval" -> DatasetRule(
      "V12.ratio (NonNegative)",
      Reach.Direct,
      Writing.Encoder("Wire.nonNegativeInterval")
    )(read(V12.ratio(_, DecodeField.PriceMem, V12.Interval.NonNegative)))(
      written(Wire.nonNegativeInterval)
    ),
    "transaction_id" -> leafHash(32, DecodeField.InputId),
    "hash32" -> leafHash(32, DecodeField.AnchorHash),
    "auxiliary_data_hash" -> leafHash(32, DecodeField.AuxiliaryDataHash),
    "script_data_hash" -> leafHash(32, DecodeField.ScriptDataHash),
    "vrf_keyhash" -> leafHash(32, DecodeField.PoolVrf),
    "script_hash" -> leafHash(28, DecodeField.PolicyHash),
    "guardrails_script_hash" -> leafHash(28, DecodeField.PolicyHash),
    "hash28" -> leafHash(28, DecodeField.PolicyHash),
    "addr_keyhash" -> leafHash(28, DecodeField.Signer),
    "pool_keyhash" -> leafHash(28, DecodeField.PoolOperator),
    "policy_id" -> leafHash(28, DecodeField.Mint),
    "language" -> DatasetRule.Unmapped(Unmapped.NoDecoder)
  )

  private def plutusScript(site: DecodeField) =
    DatasetRule(
      "V12.bytes (DijkstraScript.plutus's bytes)",
      Reach.Direct,
      Writing.Leaf("Codec.bytes")
    )(
      read(V12.bytes(_, site))
    )(leafBytes)

  private def paramRule(key: String, field: Int) =
    DatasetRule.unwritten(
      "DijkstraParamUpdate.decode",
      Reach.Embedded(s"a parameter update's key $field")
    )(b => read(DijkstraParamUpdate.decode)(hex("a1" + key) ++ b))
