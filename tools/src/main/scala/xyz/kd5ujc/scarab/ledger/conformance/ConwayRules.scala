// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.conformance

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value as CborValue}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  Address,
  Anchor,
  Block,
  ByteStrings,
  CborRead,
  Certificate,
  Constitution,
  ConwayParamUpdate,
  CostModelDecode,
  Credential,
  DRep,
  DecodeField,
  DecoderVersion,
  GovAction,
  GovActionId,
  Header,
  NativeScript,
  PoolRelay,
  PoolRelays,
  Proposal,
  RewardAccount,
  Script,
  ScriptKind,
  Tx,
  TxBody,
  TxIn,
  TxOut,
  Value,
  Voter,
  VotingProcedures,
  Witnesses
}
import xyz.kd5ujc.scarab.ledger.state.{Coin, ProtVer, Word64}
import xyz.kd5ujc.scarab.models.runtime.{Codec, Writer}
import xyz.kd5ujc.scarab.plutus.PlutusData
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, UtxoCodecs, Wire}

/** The rules of the Conway corpus, each mapped to the decoder Scarab reads that structure with in a
  * Conway block, at the dataset's decoder version ([[DecoderVersion.high]], 11), and to the
  * production encoder of the decoded value where Scarab has one: the Haskell-format codecs of the
  * ledger state (`storage.haskell.state`), the header body's
  * [[xyz.kd5ujc.scarab.ledger.HeaderBodyEncoding]], [[Tx.envelope]] and [[PoolRelays.encode]].
  */
object ConwayRules:
  private val era = Era.Conway

  /** The decoder version every rule of this era is read and written at: the era's `eraProtVerHigh`,
    * 11, as the dataset's verify decodes a sample with `decodeFull'` and writes it with
    * `serialize'` at that version (`app/Generation/EraSpec.hs:81-88`,
    * `app/Generation/Rule.hs:46-62`, dataset ed6c49a4). The node reads a Conway block at
    * `eraProtVerLow`, 9; no decoder or encoder branches between the two.
    */
  val version: DecoderVersion = DecoderVersion.high(era)
  private given ByteStrings = ByteStrings.of(version)
  private given ProtVer = ProtVer(Word64.fromUnsigned(version.value.toLong), Word64.zero)
  private val mode = Address.Mode.of(era)
  private val forms = TxOut.Forms.of(era)
  private val newest = ScriptKind.newest(era)

  import Embedding.*
  import Step.*

  private def node(bytes: Bytes): Either[Refusal, Node] =
    CborRead.decode(bytes).leftMap(Refusal.Ledger(_))

  private def ledger[A](r: Either[xyz.kd5ujc.scarab.ledger.LedgerDecodeError, A]) =
    r.leftMap(Refusal.Ledger(_))

  /** The bytes `codec` writes `a` as. */
  def written[A](codec: Codec[A])(a: A): Either[Unwritten, Bytes] =
    Either
      .catchNonFatal {
        val out = new Writer
        codec.encode(a, out)
        out.result
      }
      .leftMap(Unwritten.Defect(_))

  private def present[A](o: Option[A]): Either[Refusal, A] = o.toRight(Refusal.Form(Shape.Present))

  // ---- parents built around a sample

  /** A Conway body of no inputs, no outputs and no fee, with `key` holding the sample. */
  private def body(key: String)(sample: Bytes): Either[Refusal, TxBody] =
    ledger(TxBody.decode(hex("a4008001800200" + key) ++ sample, era, version))

  /** A witness set holding the sample under `prefix`. */
  private def witnesses(prefix: String, suffix: String = "")(sample: Bytes) =
    ledger(Witnesses.decode(hex(prefix) ++ sample ++ hex(suffix), era, version))

  /** A proposal procedure of a zero deposit, a testnet key reward account and an empty anchor. */
  private def proposalAround(action: Bytes): Either[Refusal, Proposal] =
    node(hex("8400581de0" + zeros(28)) ++ action ++ hex("8260" + "5820" + zeros(32)))
      .flatMap(n => ledger(Proposal.decode(n, version)))

  private def action(prefix: String, suffix: String = "")(sample: Bytes) =
    proposalAround(hex(prefix) ++ sample ++ hex(suffix)).map(_.action)

  /** A pool's vote on a governance action, with the sample as the voting procedure. */
  private def procedure(sample: Bytes) =
    node(hex("a18204581c" + zeros(28) + "a1825820" + zeros(32) + "00") ++ sample)
      .flatMap(n =>
        VotingProcedures.decode(n, version).leftMap(Refusal.Voting(_)).flatMap { all =>
          present(all.headOption.flatMap(_._2.headOption.map(_._2)))
        }
      )

  /** A Praos header whose body holds the sample at `field` ([[Embedding.headerBody]]). */
  private def header(field: Int, inner: Bytes => Bytes)(sample: Bytes) =
    Header
      .fromRaw(era, headerAround(headerBody(field, inner(sample))), version)
      .leftMap(Refusal.Chain(_))

  private def output(prefix: String, head: String = "a3")(sample: Bytes) =
    node(hex(head + "00581d61" + zeros(28) + "0100" + prefix) ++ sample)
      .flatMap(n => ledger(TxOut.decode(n, mode, forms, newest)))

  private def value(n: Node) =
    ledger(Value.decode(n, DecodeField.OutputValue, mode.values, forms.records))

  private def relays(prefix: String, suffix: String = "")(sample: Bytes) =
    node(hex(prefix) ++ sample ++ hex(suffix))
      .flatMap(n => PoolRelays.fromBody(n, version).leftMap(Refusal.Relay(_)))

  private def param(key: String)(sample: Bytes) =
    node(hex("a1" + key) ++ sample).flatMap(n => ledger(ConwayParamUpdate.decode(n)))

  /** A whole transaction of an empty body and witness set whose auxiliary data is the sample, of
    * `shape` when the rule's type takes one form only, and the auxiliary data it keeps.
    */
  private def auxiliary(shape: Option[Shape])(bytes: Bytes): Either[Refusal, Bytes] =
    node(bytes).flatMap { n =>
      val fits = (shape, n.value) match
        case (None, _)                                             => true
        case (Some(Shape.Map), CborValue.Map(_))                   => true
        case (Some(Shape.Array), CborValue.Arr(_))                 => true
        case (Some(Shape.Tag259), CborValue.Tag(t, _)) if t == 259 => true
        case _                                                     => false
      shape.filterNot(_ => fits).map(Refusal.Form(_)).toLeft(()).flatMap { _ =>
        ledger(Tx.fromEnvelope(hex("84a3008001800200a0f5") ++ bytes, era, version))
          .flatMap(t => present(t.auxiliary))
      }
    }

  // ---- writers

  private val unsigned: Codec[BigInt] = Codec.uintBig((BigInt(1) << 64) - 1)
  private def hash(size: Int)(b: Bytes) = written(Wire.hash(size))(b)
  private def leafBytes(b: Bytes) = written(Codec.bytes)(b)
  private def govAction(a: GovAction) = written(HaskellStateCodecs.govAction)(a)
  private def mintMap(assets: Map[Bytes, Map[Bytes, BigInt]]) =
    written(UtxoCodecs.value)(Value(0, assets)).flatMap(Step.at(_, List(Index(1))))

  private val signable = Writing.Encoder("HeaderBodyEncoding (Header.Shelley.signable)")
  private val txOutCodec = Writing.Encoder("UtxoCodecs.txOut")
  private val valueCodec = Writing.Encoder("UtxoCodecs.value")
  private val govActionCodec = Writing.Encoder("HaskellStateCodecs.govAction")
  private val paramValue =
    Writing.Kept("ConwayParamUpdate entry, via HaskellStateCodecs.conwayParamUpdate")
  private val relaysCodec = Writing.Encoder("PoolRelays.encode")

  private def fromHeader(path: List[Step])(h: Header.Shelley) = Step.at(h.signable, path)

  private def leafHash(name: String, size: Int, site: DecodeField) =
    DatasetRule(s"CborRead.bytesN($size, $name)", Reach.Direct, Writing.Leaf(s"Wire.hash($size)"))(
      b => node(b).flatMap(n => ledger(CborRead.bytesN(n, size, site)))
    )(hash(size))

  private def credential(site: DecodeField) =
    DatasetRule(
      "Credential.decode",
      Reach.Direct,
      Writing.Encoder("HaskellStateCodecs.nodeCredential")
    )(b => node(b).flatMap(n => ledger(Credential.decode(n, site))))(
      written(HaskellStateCodecs.nodeCredential)
    )

  private def output(forms: TxOut.Forms, name: String) =
    DatasetRule(name, Reach.Direct, txOutCodec)(b =>
      node(b).flatMap(n => ledger(TxOut.decode(n, mode, forms, newest)))
    )(written(UtxoCodecs.txOut))

  private def auxiliaryRule(shape: Option[Shape]) =
    DatasetRule(
      s"Tx.fromEnvelope${shape.fold("")(s => s" (${s.render} only)")}",
      Reach.Embedded("a transaction's auxiliary data"),
      Writing.Kept("Tx.auxiliary")
    )(auxiliary(shape))(Right(_))

  /** Plutus data of `shape`, read as a datum of a witness set and written again as the bytes the
    * witness set keeps of it ([[Witnesses.datums]]), as the reference keeps those of `Data era`
    * (`MemoBytes`) and hashes them. The shape is read from those bytes by [[PlutusData.decode]].
    */
  private def dataRule(shape: Option[Shape]) =
    DatasetRule(
      "Witnesses.decode",
      Reach.Embedded("a witness set's datums"),
      Writing.Kept("Witnesses.datums")
    )(b =>
      witnesses("a10481")(b).flatMap(w => present(w.datums.headOption)).flatMap { kept =>
        PlutusData.decode(kept).leftMap(Refusal.Data(_)).flatMap { d =>
          (shape, d) match
            case (None, _)                                 => Right(kept)
            case (Some(Shape.Integer), _: PlutusData.I)    => Right(kept)
            case (Some(Shape.ByteString), _: PlutusData.B) => Right(kept)
            case (Some(s), _)                              => Left(Refusal.Form(s))
        }
      }
    )(Right(_))

  private def headerLeaf(field: Int, path: List[Step], inner: Bytes => Bytes = identity) =
    DatasetRule("Header.fromRaw", Reach.Embedded("a Praos header body"), signable)(
      header(field, inner)
    )(fromHeader(Index(field) :: path))

  private def opCertLeaf(slot: Int) =
    headerLeaf(8, List(Index(slot)), sample => opCert(slot, sample))

  /** The rules, by the name of their directory in the corpus. */
  val rules: Map[String, DatasetRule] = Map(
    "block" -> DatasetRule.unwritten("Block.decodeShelley", Reach.Embedded("[7, block]"))(b =>
      Block.decodeShelley(hex("8207") ++ b, _ => version).leftMap(Refusal.Chain(_))
    ),
    "header" -> DatasetRule("Header.fromRaw", Reach.Direct, Writing.Kept("Header.Shelley.raw"))(b =>
      Header.fromRaw(era, b, version).leftMap(Refusal.Chain(_))
    )(h => Right(h.raw)),
    "header_body" -> DatasetRule(
      "Header.fromRaw",
      Reach.Embedded("[body, kes signature]"),
      signable
    )(b => Header.fromRaw(era, headerAround(b), version).leftMap(Refusal.Chain(_)))(h =>
      Right(h.signable)
    ),
    "transaction" -> DatasetRule("Tx.fromEnvelope", Reach.Direct, Writing.Encoder("Tx.envelope"))(
      b => ledger(Tx.fromEnvelope(b, era, version))
    )(t =>
      t.witnesses.raw
        .toRight(Unwritten.NotKept)
        .map(w => Tx.envelope(Block.Transaction(t.body.raw, w, t.auxiliary, t.isValid)))
    ),
    "transaction_body" -> DatasetRule("TxBody.decode", Reach.Direct, Writing.Kept("TxBody.raw"))(
      b => ledger(TxBody.decode(b, era, version))
    )(t => Right(t.raw)),
    "transaction_witness_set" -> DatasetRule(
      "Witnesses.decode",
      Reach.Direct,
      Writing.Kept("Witnesses.raw")
    )(b => ledger(Witnesses.decode(b, era, version)))(_.raw.toRight(Unwritten.NotKept)),
    "transaction_input" -> DatasetRule(
      "TxIn.decode",
      Reach.Direct,
      Writing.Encoder("UtxoCodecs.txIn")
    )(b => node(b).flatMap(n => ledger(TxIn.decode(n))))(written(UtxoCodecs.txIn)),
    "transaction_output" -> output(forms, "TxOut.decode"),
    "babbage_transaction_output" -> output(forms, "TxOut.decode"),
    "alonzo_transaction_output" -> output(
      TxOut.Forms.PairOrDatumHash,
      "TxOut.decode (PairOrDatumHash)"
    ),
    "value" -> DatasetRule("Value.decode", Reach.Direct, valueCodec)(b => node(b).flatMap(value))(
      written(UtxoCodecs.value)
    ),
    "%multiasset<positive_coin>" -> DatasetRule(
      "Value.decode",
      Reach.Embedded("[0, multiasset]"),
      valueCodec
    )(b => node(hex("8200") ++ b).flatMap(value))(v =>
      written(UtxoCodecs.value)(v).flatMap(Step.at(_, List(Index(1))))
    ),
    "mint" -> DatasetRule("TxBody.decode", Reach.Embedded("a body's key 9"), valueCodec)(
      body("09")
    )(t => mintMap(t.mint)),
    "nonzero_int64" -> DatasetRule(
      "TxBody.decode",
      Reach.Embedded("a mint quantity of a body"),
      valueCodec
    )(b => body("09")(hex("a1581c" + zeros(28) + "a140") ++ b))(t =>
      mintMap(t.mint).flatMap(Step.at(_, List(FirstValue, FirstValue)))
    ),
    "asset_name" -> DatasetRule("Value.decode", Reach.Embedded("an output value"), valueCodec)(b =>
      node(hex("8200a1581c" + zeros(28) + "a1") ++ b ++ hex("01")).flatMap(value)
    )(v => written(UtxoCodecs.value)(v).flatMap(Step.at(_, List(Index(1), FirstValue, FirstKey)))),
    "withdrawals" -> DatasetRule("TxBody.decode", Reach.Embedded("a body's key 5"), govActionCodec)(
      body("05")
    )(t =>
      govAction(
        GovAction.TreasuryWithdrawals(
          t.withdrawals.toVector.map((a, c) => a -> Coin.value(c)),
          None
        )
      ).flatMap(Step.at(_, List(Index(1))))
    ),
    "network_id" -> DatasetRule(
      "TxBody.decode",
      Reach.Embedded("a body's key 15"),
      Writing.Leaf("Codec.uintBig")
    )(b => body("0f")(b).flatMap(t => present(t.networkId)))(written(unsigned)),
    "positive_coin" -> DatasetRule(
      "TxBody.decode",
      Reach.Embedded("a body's donation"),
      Writing.Leaf("Codec.uintBig")
    )(
      body("16")
    )(t => written(unsigned)(t.donation)),
    "script" -> DatasetRule.unwritten("Script.decode", Reach.Direct)(b =>
      node(b).flatMap(n => ledger(Script.decode(n, newest)))
    ),
    "native_script" -> DatasetRule(
      "Script.native (Timelock)",
      Reach.Direct,
      Writing.Kept("the bytes Script.hash is taken over")
    )(b =>
      node(b).flatMap(n =>
        ledger(Script.native(n, NativeScript.Form.Timelock)).tupleLeft(n.original)
      )
    )((original, script) =>
      Either.cond(
        script.hash == Blake2b.hash224.hash(Bytes(0.toByte +: original.value)),
        original,
        Unwritten.HashMismatch
      )
    ),
    "plutus_v1_script" -> plutusScript(DecodeField.PlutusV1),
    "plutus_v2_script" -> plutusScript(DecodeField.PlutusV2),
    "plutus_v3_script" -> plutusScript(DecodeField.PlutusV3),
    // with a reference script, so that the output is written as a map whatever its datum
    "datum_option" -> DatasetRule("TxOut.decode", Reach.Embedded("an output's key 2"), txOutCodec)(
      b => output("02", "a4")(b ++ hex("03d818458200820180"))
    )(o => written(UtxoCodecs.txOut)(o).flatMap(Step.at(_, List(Key(2))))),
    "data" -> DatasetRule("TxOut.decode", Reach.Embedded("an output's inline datum"), txOutCodec)(
      output("028201")
    )(o => written(UtxoCodecs.txOut)(o).flatMap(Step.at(_, List(Key(2), Index(1))))),
    "plutus_data" -> dataRule(None),
    "%constr<plutus_data>" -> dataRule(None),
    "big_int" -> dataRule(Some(Shape.Integer)),
    "bounded_bytes" -> dataRule(Some(Shape.ByteString)),
    "certificate" -> DatasetRule.unwritten("Certificate.decode", Reach.Direct)(b =>
      node(b).flatMap(n => ledger(Certificate.decode(n, era, version)))
    ),
    "certificates" -> DatasetRule
      .unwritten("CborRead.oset of Certificate.decode, non-empty", Reach.Direct)(b =>
        node(b).flatMap(n =>
          ledger(
            CborRead
              .oset(n, DecodeField.Certificates, version)(Certificate.decode(_, era, version))(
                Certificate.identity
              )
              .flatMap(CborRead.nonEmpty(_, DecodeField.Certificates, true))
          )
        )
      ),
    "redeemer" -> DatasetRule.unwritten("Witnesses.decode", Reach.Embedded("{5: [redeemer]}"))(b =>
      witnesses("a10581")(b).flatMap(w => present(w.redeemers.headOption))
    ),
    "redeemers" -> DatasetRule(
      "Witnesses.decode",
      Reach.Embedded("a witness set's key 5"),
      Writing.Kept("Witnesses.redeemersRaw")
    )(witnesses("a105"))(_.redeemersRaw.toRight(Unwritten.NotKept)),
    "vkeywitness" -> DatasetRule.unwritten(
      "Witnesses.decode",
      Reach.Embedded("{0: [vkeywitness]}")
    )(b => witnesses("a10081")(b).flatMap(w => present(w.vkeys.headOption))),
    "vkey" -> DatasetRule(
      "Witnesses.decode",
      Reach.Embedded("a vkey witness"),
      Writing.Leaf("Codec.bytes")
    )(
      witnesses("a1008182", "5840" + zeros(64))
    )(w => w.vkeys.headOption.toRight(Unwritten.NotKept).flatMap(v => leafBytes(v._1))),
    "signature" -> DatasetRule(
      "Witnesses.decode",
      Reach.Embedded("a vkey witness"),
      Writing.Leaf("Codec.bytes")
    )(
      witnesses("a10081825820" + zeros(32))
    )(w => w.vkeys.headOption.toRight(Unwritten.NotKept).flatMap(v => leafBytes(v._2))),
    "bootstrap_witness" -> DatasetRule.unwritten(
      "Witnesses.decode",
      Reach.Embedded("{2: [bootstrap_witness]}")
    )(b => witnesses("a10281")(b).flatMap(w => present(w.bootstrap.headOption))),
    "auxiliary_data" -> auxiliaryRule(None),
    "metadata" -> auxiliaryRule(Some(Shape.Map)),
    "auxiliary_data_map" -> auxiliaryRule(Some(Shape.Tag259)),
    "auxiliary_data_array" -> auxiliaryRule(Some(Shape.Array)),
    "auxiliary_scripts" -> DatasetRule(
      "Tx.fromEnvelope",
      Reach.Embedded("[{}, scripts], a transaction's auxiliary data"),
      Writing.Kept("Tx.auxiliary")
    )(b => auxiliary(None)(hex("82a0") ++ b))(Step.at(_, List(Index(1)))),
    "metadatum" -> DatasetRule.unwritten(
      "Tx.fromEnvelope",
      Reach.Embedded("{0: metadatum}, a transaction's auxiliary data")
    )(b => auxiliary(None)(hex("a100") ++ b)),
    "credential" -> credential(DecodeField.CertificateCredential),
    "stake_credential" -> credential(DecodeField.CertificateCredential),
    "drep_credential" -> credential(DecodeField.CertificateCredential),
    "committee_cold_credential" -> credential(DecodeField.CommitteeMember),
    "committee_hot_credential" -> credential(DecodeField.CommitteeMember),
    "drep" -> DatasetRule(
      "DRep.decode",
      Reach.Direct,
      Writing.Encoder("HaskellStateCodecs.nodeDRep")
    )(b => node(b).flatMap(n => ledger(DRep.decode(n))))(written(HaskellStateCodecs.nodeDRep)),
    "voter" -> DatasetRule.unwritten("Voter.decode", Reach.Direct)(b =>
      node(b).flatMap(n => ledger(Voter.decode(n)))
    ),
    "vote" -> DatasetRule(
      "VotingProcedures.decode",
      Reach.Embedded("a voting procedure"),
      Writing.Encoder("HaskellStateCodecs.vote")
    )(b => procedure(hex("82") ++ b ++ hex("f6")).map(_.vote))(written(HaskellStateCodecs.vote)),
    "voting_procedure" -> DatasetRule
      .unwritten("VotingProcedures.decode", Reach.Embedded("{voter: {id: procedure}}"))(
        procedure
      ),
    "voting_procedures" -> DatasetRule.unwritten(
      "VotingProcedures.decode, requireBodyDecodable, non-empty",
      Reach.Direct
    )(b =>
      node(b).flatMap(n =>
        VotingProcedures
          .decode(n, version)
          .flatMap(VotingProcedures.requireBodyDecodable)
          .leftMap(Refusal.Voting(_))
          .flatMap(all => ledger(CborRead.nonEmpty(all, DecodeField.VotingProcedures, true)))
      )
    ),
    "gov_action" -> DatasetRule(
      "Proposal.decode",
      Reach.Embedded("a proposal procedure"),
      govActionCodec
    )(
      proposalAround
    )(p => govAction(p.action)),
    "gov_action_id" -> DatasetRule(
      "GovActionId.decode",
      Reach.Direct,
      Writing.Encoder("HaskellStateCodecs.govActionId")
    )(b => node(b).flatMap(n => ledger(GovActionId.decode(n))))(
      written(HaskellStateCodecs.govActionId)
    ),
    "proposal_procedure" -> DatasetRule(
      "Proposal.decode",
      Reach.Direct,
      Writing.Encoder("HaskellStateCodecs.proposal")
    )(b => node(b).flatMap(n => ledger(Proposal.decode(n, version))))(
      written(HaskellStateCodecs.proposal)
    ),
    "proposal_procedures" -> DatasetRule.unwritten(
      "CborRead.oset of Proposal.decode, non-empty",
      Reach.Direct
    )(b =>
      node(b).flatMap(n =>
        ledger(
          CborRead
            .oset(n, DecodeField.ProposalProcedures, version)(Proposal.decode(_, version))(
              Proposal.identity
            )
            .flatMap(CborRead.nonEmpty(_, DecodeField.ProposalProcedures, true))
        )
      )
    ),
    "constitution" -> DatasetRule(
      "Proposal.decode",
      Reach.Embedded("a NewConstitution action"),
      Writing.Encoder("HaskellStateCodecs.constitution")
    )(action("8305f6")) {
      case GovAction.NewConstitution(_, script, anchor) =>
        written(HaskellStateCodecs.constitution)(Constitution(anchor, script))
      case _ => Left(Unwritten.NotKept)
    },
    "protocol_version" -> DatasetRule(
      "Proposal.decode",
      Reach.Embedded("a HardFork action"),
      govActionCodec
    )(
      action("8301f6")
    )(a => govAction(a).flatMap(Step.at(_, List(Index(2))))),
    "major_protocol_version" -> DatasetRule(
      "Proposal.decode",
      Reach.Embedded("a HardFork action's major version"),
      govActionCodec
    )(action("8301f682", "00"))(a => govAction(a).flatMap(Step.at(_, List(Index(2), Index(0))))),
    "epoch" -> DatasetRule(
      "Proposal.decode",
      Reach.Embedded("an UpdateCommittee action's member term"),
      govActionCodec
    )(action("8504f680a18200581c" + zeros(28), "d81e820102"))(a =>
      govAction(a).flatMap(Step.at(_, List(Index(3), FirstValue)))
    ),
    "coin" -> DatasetRule(
      "Proposal.decode",
      Reach.Embedded("a proposal's deposit"),
      Writing.Encoder("HaskellStateCodecs.proposal")
    )(b =>
      node(hex("84") ++ b ++ hex("581de0" + zeros(28) + "8106" + "8260" + "5820" + zeros(32)))
        .flatMap(n => ledger(Proposal.decode(n, version)))
    )(p => written(HaskellStateCodecs.proposal)(p).flatMap(Step.at(_, List(Index(0))))),
    "anchor" -> DatasetRule(
      "Anchor.decode",
      Reach.Direct,
      Writing.Encoder("HaskellStateCodecs.anchor")
    )(b => node(b).flatMap(n => ledger(Anchor.decode(n, version))))(
      written(HaskellStateCodecs.anchor)
    ),
    "url" -> DatasetRule(
      "Anchor.decode",
      Reach.Embedded("an anchor"),
      Writing.Encoder("HaskellStateCodecs.anchor")
    )(b =>
      node(hex("82") ++ b ++ hex("5820" + zeros(32))).flatMap(n =>
        ledger(Anchor.decode(n, version))
      )
    )(a => written(HaskellStateCodecs.anchor)(a).flatMap(Step.at(_, List(Index(0))))),
    "reward_account" -> DatasetRule(
      "RewardAccount.decode",
      Reach.Direct,
      Writing.Encoder("HaskellStateCodecs.rewardAccount")
    )(b =>
      node(b).flatMap(n =>
        ledger(CborRead.bytes(n, DecodeField.RewardAccount).flatMap(RewardAccount.decode))
      )
    )(written(HaskellStateCodecs.rewardAccount)),
    "address" -> DatasetRule("Address.decode", Reach.Direct, Writing.Kept("Address.raw"))(b =>
      node(b).flatMap(n =>
        ledger(CborRead.bytes(n, DecodeField.OutputAddress).flatMap(Address.decode(_, mode)))
      )
    )(a => leafBytes(a.raw)),
    "relay" -> DatasetRule("PoolRelays.fromBody", Reach.Embedded("[relay]"), relaysCodec)(b =>
      relays("81")(b).flatMap(rs => present(rs.headOption.as(rs)))
    )(rs => Step.at(PoolRelays.encode(rs), List(Index(0)))),
    "dns_name" -> DatasetRule(
      "PoolRelays.fromBody",
      Reach.Embedded("a multi-host relay"),
      relaysCodec
    )(
      relays("818202")
    )(rs => Step.at(PoolRelays.encode(rs), List(Index(0), Index(1)))),
    "ipv4" -> DatasetRule(
      "PoolRelays.fromBody",
      Reach.Embedded("a single-host address relay"),
      relaysCodec
    )(b =>
      relays("818400f6", "f6")(b).flatMap(rs =>
        present(rs.headOption.collect { case PoolRelay.SingleHostAddr(_, Some(_), _) => rs })
      )
    )(rs => Step.at(PoolRelays.encode(rs), List(Index(0), Index(2)))),
    "ipv6" -> DatasetRule(
      "PoolRelays.fromBody",
      Reach.Embedded("a single-host address relay"),
      relaysCodec
    )(b =>
      relays("818400f6f6")(b).flatMap(rs =>
        present(rs.headOption.collect { case PoolRelay.SingleHostAddr(_, _, Some(_)) => rs })
      )
    )(rs => Step.at(PoolRelays.encode(rs), List(Index(0), Index(3)))),
    "pool_metadata" -> DatasetRule(
      "Certificate.decode",
      Reach.Embedded("a pool registration"),
      Writing.Kept("PoolParams.metadata")
    )(b =>
      node(
        hex(
          "8a03581c" + zeros(28) + "5820" + zeros(32) + "0000d81e820001581de0" + zeros(28) + "8080"
        ) ++ b
      ).flatMap(n => ledger(Certificate.decode(n, era, version))).flatMap {
        case Certificate.RegisterPool(params) => present(params.metadata)
        case _                                => present(None)
      }
    )(Right(_)),
    "protocol_param_update" -> DatasetRule("ConwayParamUpdate.decode", Reach.Direct, paramValue)(
      b => node(b).flatMap(n => ledger(ConwayParamUpdate.decode(n)))
    )(written(HaskellStateCodecs.conwayParamUpdate)),
    "drep_voting_thresholds" -> paramRule("181a", 26),
    "pool_voting_thresholds" -> paramRule("1819", 25),
    "ex_units" -> paramRule("14", 20),
    "ex_unit_prices" -> paramRule("13", 19),
    "cost_models" -> DatasetRule.unwritten("CostModelDecode.decode (Open)", Reach.Direct)(b =>
      node(b).flatMap(n => ledger(CostModelDecode.decode(n, CostModelDecode.Format.Open)))
    ),
    "unit_interval" -> DatasetRule(
      "CborRead.rational (Unit)",
      Reach.Direct,
      Writing.Encoder("Wire.unitInterval")
    )(b =>
      node(b).flatMap(n => ledger(CborRead.rational(n, DecodeField.Quorum, CborRead.Bound.Unit)))
    )(
      written(Wire.unitInterval)
    ),
    "nonnegative_interval" -> DatasetRule(
      "CborRead.rational (NonNegative)",
      Reach.Direct,
      Writing.Encoder("Wire.nonNegativeInterval")
    )(b =>
      node(b).flatMap(n =>
        ledger(CborRead.rational(n, DecodeField.PriceMem, CborRead.Bound.NonNegative))
      )
    )(written(Wire.nonNegativeInterval)),
    "operational_cert" -> headerLeaf(8, Nil),
    "kes_vkey" -> opCertLeaf(0),
    "kes_period" -> opCertLeaf(2),
    "vrf_vkey" -> headerLeaf(4, Nil),
    "block_number" -> headerLeaf(0, Nil),
    "slot" -> headerLeaf(1, Nil),
    "transaction_id" -> leafHash("transaction id", 32, DecodeField.InputId),
    "hash32" -> leafHash("hash32", 32, DecodeField.AnchorHash),
    "auxiliary_data_hash" -> leafHash("auxiliary data hash", 32, DecodeField.AuxiliaryDataHash),
    "script_data_hash" -> leafHash("script data hash", 32, DecodeField.ScriptDataHash),
    "vrf_keyhash" -> leafHash("pool vrf", 32, DecodeField.PoolVrf),
    "script_hash" -> leafHash("script hash", 28, DecodeField.PolicyHash),
    "guardrails_script_hash" -> leafHash("policy hash", 28, DecodeField.PolicyHash),
    "hash28" -> leafHash("hash28", 28, DecodeField.PolicyHash),
    "addr_keyhash" -> leafHash("signer", 28, DecodeField.Signer),
    "pool_keyhash" -> leafHash("pool operator", 28, DecodeField.PoolOperator),
    "policy_id" -> leafHash("policy", 28, DecodeField.Mint),
    "language" -> DatasetRule.Unmapped(Unmapped.NoDecoder)
  )

  private def plutusScript(site: DecodeField) =
    DatasetRule(
      "CborRead.bytes (Script.decode's Plutus bytes)",
      Reach.Direct,
      Writing.Leaf("Codec.bytes")
    )(b => node(b).flatMap(n => ledger(CborRead.bytes(n, site))))(leafBytes)

  private def paramRule(key: String, field: Int) =
    DatasetRule(
      "ConwayParamUpdate.decode",
      Reach.Embedded(s"a parameter update's key $field"),
      paramValue
    )(
      param(key)
    )(u => written(HaskellStateCodecs.conwayParamUpdate)(u).flatMap(Step.at(_, List(Key(field)))))

/** The bytes of the parents that rules are read inside. */
object Embedding:
  def hex(s: String): Bytes = Bytes.wrap(s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray)

  def zeros(n: Int): String = "00" * n

  /** A Praos header body of zeros whose field `field` is `sample`: block number, slot, no previous
    * hash, issuer and VRF keys, a VRF certificate, body size and hash, an operational certificate
    * and protocol version 10.0.
    */
  def headerBody(field: Int, sample: Bytes, leios: Boolean = false): Bytes =
    val praos = Vector(
      "00",
      "00",
      "f6",
      "5820" + zeros(32),
      "5820" + zeros(32),
      "825840" + zeros(64) + "5850" + zeros(80),
      "00",
      "5820" + zeros(32),
      "845820" + zeros(32) + "00005840" + zeros(64),
      if leios then "820c00" else "820a00"
    )
    // the Leios header body adds the certificate flag and the endorser block announcement
    val fields = (if leios then praos ++ Vector("f4", "f6") else praos).map(hex)
    hex(if leios then "8c" else "8a") ++ fields.updated(field, sample).reduce(_ ++ _)

  /** An operational certificate of zeros whose item `slot` is `sample`. */
  def opCert(slot: Int, sample: Bytes): Bytes =
    val items = Vector("5820" + zeros(32), "00", "00", "5840" + zeros(64)).map(hex)
    hex("84") ++ items.updated(slot, sample).reduce(_ ++ _)

  /** A header of `body` and a KES signature of zeros. */
  def headerAround(body: Bytes): Bytes = hex("82") ++ body ++ hex("5901c0" + zeros(448))
