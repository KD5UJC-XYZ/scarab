// SPDX-License-Identifier: Apache-2.0
package lab.vrf {
  import lab.cbor.Bytes

  /** Public scalar one and public nonce one: synthetic test material, never production keys.
    * Variable-time verification math is appropriate here only because every scalar is public.
    */
  private[lab] object EphemeralPublicVrf:
    val publicKey: Bytes = Bytes.fromArray(Ed25519Point.BASE_POINT.encode())
    def prove(alpha: Bytes): (Bytes, Bytes) =
      val h = Draft03Math.h2c(publicKey.toArray, alpha.toArray)
      val gamma = h.encode() // public x = 1
      val c = Draft03Math.challenge(h, gamma, Ed25519Point.BASE_POINT, h) // public k = 1
      val s =
        Draft03Math.scalar(Draft03Math.integer(c).add(java.math.BigInteger.ONE).mod(Draft03Math.L))
      (Bytes.fromArray(Draft03Math.output(h)), Bytes.fromArray(gamma ++ c ++ s))
}

package lab {
  import cats.effect.IO
  import lab.cbor.{Bytes, Cbor, Node, Value as V}
  import lab.header.{PraosCertificateState as C, PraosNonceEvolution as N, PraosEligibility as E}
  import lab.ledger.{ConwayEpochBoundary as B, ConwayRewardStart as R}
  import lab.vrf.{EphemeralPublicVrf, PraosVrfCertificate as Vrf, PraosLeaderThreshold as Leader}
  import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
  import org.bouncycastle.crypto.signers.Ed25519Signer
  import ReferenceJson.Json as J

  /** Entirely generated synthetic supplied state plus exact signed empty-block originals. No
    * captures, filesystem reads, network calls, native execution, or production secrets.
    */
  private[lab] object EphemeralStreamingFixture:
    private def get[A](e: Either[?, A]): A =
      e.fold(e => throw new IllegalArgumentException(e.toString), identity)
    private def b(n: Int, size: Int = 32) = Bytes(Vector.fill(size)(n.toByte))
    private def raw(s: String) = Bytes.fromArray(s.getBytes("UTF-8"))
    private def node(v: V) = Node(v, Bytes.empty)
    private def array(xs: V*) = V.Arr(xs.toVector.map(node))
    private def encoded(v: V) = get(Cbor.encode(v))
    private def bs(b: Bytes) = V.ByteString(b)
    private def obj(xs: (String, J)*) = J.Obj(xs.toMap)
    private def num(n: BigInt) = J.Num(n.toString)
    private def str(s: String) = J.Str(s)
    private def arr(xs: J*) = J.Arr(xs.toVector)
    private val empty = obj()
    private val nul = J.Lit("null")
    private def json(j: J) = SyntheticRewardProjection.encode(j)
    private def sign(key: Ed25519PrivateKeyParameters, message: Bytes): Bytes =
      val signer = new Ed25519Signer()
      signer.init(true, key)
      val bytes = message.toArray
      signer.update(bytes, 0, bytes.length)
      Bytes.fromArray(signer.generateSignature())
    private val cold = new Ed25519PrivateKeyParameters(b(11).toArray, 0)
    private val leaf = new Ed25519PrivateKeyParameters(b(12).toArray, 0)
    private val coldPublic = Bytes.fromArray(cold.generatePublicKey().getEncoded())
    private val leafPublic = Bytes.fromArray(leaf.generatePublicKey().getEncoded())
    val issuer: Bytes = Blake2b.hash224.hash(coldPublic)
    private val vrfHash = Blake2b.hash256.hash(EphemeralPublicVrf.publicKey)
    private val kesPairs = (0 until 6).foldLeft(Vector.empty[Bytes] -> leafPublic) {
      case ((done, child), _) =>
        val pair = Bytes(child.value ++ child.value)
        (done :+ pair, Blake2b.hash256.hash(pair))
    }
    private val kesRoot = kesPairs._2
    private val opcertSignature = sign(cold, Bytes(kesRoot.value ++ Vector.fill(16)(0.toByte)))
    val epochLength: BigInt = 40
    val rewardWindow: BigInt = 4
    val slots: Vector[BigInt] = ((1 to 20) ++ (40 to 44)).map(BigInt(_)).toVector
    val pin: Bytes = b(77)
    val anchorHash: Bytes = b(88)
    private val accountHash = b(13, 28)
    private val accountName = "keyHash-" + accountHash.hex
    private val accountCredential = obj("keyHash" -> str(accountHash.hex))
    private val one = obj("numerator" -> num(1), "denominator" -> num(1))
    private val zero = obj("numerator" -> num(0), "denominator" -> num(1))
    private val pool = obj(
      "spsVrf" -> str(vrfHash.hex),
      "spsPledge" -> num(0),
      "spsCost" -> num(0),
      "spsMargin" -> zero,
      "spsAccountId" -> accountCredential,
      "spsOwners" -> arr(),
      "spsDelegators" -> arr(accountCredential),
      "spsDeposit" -> num(0)
    )
    private val snapshot = obj(
      "activeStake" -> obj(
        accountName -> obj("swdStake" -> num(100), "swdDelegation" -> str(issuer.hex))
      ),
      "stakePoolsSnapShot" -> obj(
        issuer.hex -> obj(
          "stake" -> num(100),
          "stakeRatio" -> one,
          "selfDelegatedOwners" -> arr(),
          "selfDelegatedOwnersStake" -> num(0),
          "vrf" -> str(vrfHash.hex),
          "pledge" -> num(0),
          "cost" -> num(0),
          "margin" -> zero,
          "numDelegators" -> num(1),
          "accountId" -> accountCredential
        )
      )
    )
    private val tip = obj(
      "era" -> str("Conway"),
      "hash" -> str(anchorHash.hex),
      "slot" -> num(0),
      "block" -> num(0),
      "epoch" -> num(0)
    )
    private val ledgerJson = obj(
      "lastEpoch" -> num(0),
      "stakeDistrib" -> obj(
        "pdTotalActiveStake" -> num(100),
        "unPoolDistr" -> obj(
          issuer.hex -> obj(
            "individualPoolStakeVrf" -> str(vrfHash.hex),
            "individualPoolStake" -> one,
            "individualTotalPoolStake" -> num(100)
          )
        )
      ),
      "stateBefore" -> obj(
        "esLState" -> obj(
          "delegationState" -> obj(
            "dstate" -> obj(
              "accounts" -> obj(
                accountName -> obj(
                  "balance" -> num(100),
                  "reward" -> num(100),
                  "deposit" -> num(0),
                  "spool" -> str(issuer.hex)
                )
              )
            ),
            "pstate" -> obj(
              "stakePools" -> obj(issuer.hex -> pool),
              "futureStakePoolParams" -> empty,
              "retiring" -> empty
            )
          ),
          "utxoState" -> obj("fees" -> num(0), "stake" -> obj("credentials" -> empty))
        ),
        "esSnapshots" -> obj(
          "pstakeMark" -> snapshot,
          "pstakeSet" -> snapshot,
          "pstakeGo" -> snapshot,
          "feeSS" -> num(0)
        )
      )
    )
    private val originals = Map(
      "transfer-genesis.md" -> json(
        obj(
          "networkId" -> str("Testnet"),
          "networkMagic" -> num(1082026),
          "epochLength" -> num(epochLength),
          "slotsPerKESPeriod" -> num(1000),
          "maxKESEvolutions" -> num(64),
          "activeSlotsCoeff" -> num(1),
          "securityParam" -> num(1)
        )
      ),
      "pre-tips.md" -> json(arr(tip, tip)),
      "pre-ledger-state.md" -> json(ledgerJson),
      "pre-protocol-state.md" -> json(
        obj(
          "lastSlot" -> num(0),
          "oCertCounters" -> obj(issuer.hex -> num(0)),
          "epochNonce" -> nul,
          "evolvingNonce" -> nul,
          "candidateNonce" -> nul,
          "labNonce" -> nul,
          "lastEpochBlockNonce" -> nul,
          "previousEpochNonce" -> nul
        )
      ),
      "pre-parameters.md" -> json(
        obj(
          "protocolVersion" -> obj("major" -> num(9), "minor" -> num(0)),
          "txFeePerByte" -> num(44),
          "txFeeFixed" -> num(155381),
          "maxTxSize" -> num(16384),
          "utxoCostPerByte" -> num(4310)
        )
      ),
      "pre-utxo.md" -> json(empty),
      "pre-utxo-cbor.md" -> raw("a0\n")
    )
    private val manifest = raw(
      "format\t" + SequenceInput.ProfileId + "\n" + SequenceInput.sources.toVector
        .sortBy(_._1)
        .map((key, name) => key + "\t" + ClusterHeaderObservation.sha256(originals(name)).hex)
        .mkString("\n") + "\n"
    )
    lazy val context: SequenceInput.Context = get(SequenceInput.bind(manifest, originals))
    lazy val stakeSeed: ConwayStakeSeed.Prepared = get(
      ConwayStakeSeed.decode(
        originals("pre-ledger-state.md"),
        context.sourcePins("preLedgerSha256"),
        get(Bytes.fromHex("a0")),
        ClusterHeaderObservation.sha256(get(Bytes.fromHex("a0"))),
        epochLength
      )
    )
    private lazy val parameters = get(
      R.decodePoolParameters(
        encoded(
          array(
            V.Text(R.PoolParameterFormat),
            V.UInt(9),
            V.UInt(0),
            V.UInt(0),
            V.UInt(1),
            V.UInt(0),
            V.UInt(1),
            V.UInt(0),
            V.UInt(1),
            V.UInt(1)
          )
        )
      )
    )
    private lazy val globals = get(
      R.decodePulserGlobals(
        encoded(
          array(
            V.Text(R.PulserGlobalFormat),
            V.UInt(epochLength),
            V.UInt(1),
            V.UInt(1),
            V.UInt(1000000),
            V.UInt(1)
          )
        )
      )
    )
    lazy val profile: CoherentSequence.SyntheticRewardProfile = get(
      CoherentSequence.syntheticRewardProfile(
        parameters,
        globals,
        rewardWindow,
        Some(false),
        Some(0),
        Some(false),
        Some(false),
        Some(0),
        Some(false),
        Some(false)
      )
    )
    lazy val initialPots: B.Pots = B.Pots(0, 999900, 0, 1000000)
    def runtime: IO[CoherentSequence.Runtime[IO]] = CoherentSequence
      .createWithSyntheticRewards[IO](
        context,
        stakeSeed,
        profile,
        initialPots,
        Map.empty,
        Map.empty,
        pin
      )
      .map(get(_))

    private val components =
      Vector(encoded(array()), encoded(array()), encoded(V.Map(Vector.empty)), encoded(array()))
    private val bodyHash =
      Blake2b.hash256.hash(Bytes(components.flatMap(x => Blake2b.hash256.hash(x).value)))
    private def original(header: Bytes): BoundedChainFollower.Original =
      val envelope = encoded(array(V.UInt(6), V.Tag(24, node(bs(header)))))
      val block = Bytes(
        Vector(0x82.toByte, 0x07.toByte, 0x85.toByte) ++ header.value ++ components.flatMap(_.value)
      )
      BoundedChainFollower.Original(envelope, block)
    private def combine(a: N.Nonce, b: N.Nonce): N.Nonce = (a, b) match
      case (N.Nonce.Neutral, x) => x
      case (x, N.Nonce.Neutral) => x
      case (N.Nonce.Hash(x), N.Nonce.Hash(y)) =>
        N.Nonce.Hash(Blake2b.hash256.hash(Bytes(x.value ++ y.value)))
    lazy val blocks: Vector[SequenceInput.Block] =
      var certContext = context.certificates
      var nonceContext = context.nonces.context
      var cert = context.certificateSeed
      var nonce = context.nonces.seed
      val fraction = get(Leader.Fraction.checked(1, 1))
      slots.map { slot =>
        val crossing = slot / epochLength != cert.tip.slot / epochLength
        val oldCertContext = certContext; val oldNonceContext = nonceContext
        if crossing then
          certContext = get(C.Context.checkedSuccessor(certContext, pin, certContext.registrations))
          nonceContext = get(N.Context.checkedSuccessor(nonceContext, certContext))
        val epochNonce =
          if crossing then combine(nonce.fields.candidate, nonce.fields.lastEpochBlock)
          else nonce.fields.epoch
        val vrfNonce = epochNonce match
          case N.Nonce.Neutral => Vrf.NeutralNonce
          case N.Nonce.Hash(b) => get(Vrf.Hash32.fromBytes(b))
        val input = get(Vrf.Input.create(get(Vrf.Slot.fromBigInt(slot)), vrfNonce))
        val (output, proof) = EphemeralPublicVrf.prove(get(Vrf.alpha(input)))
        val body = array(
          V.UInt(cert.tip.blockNo + 1),
          V.UInt(slot),
          bs(cert.tip.hash),
          bs(coldPublic),
          bs(EphemeralPublicVrf.publicKey),
          array(bs(output), bs(proof)),
          V.UInt(components.map(_.size).sum),
          bs(bodyHash),
          array(bs(kesRoot), V.UInt(0), V.UInt(0), bs(opcertSignature)),
          array(V.UInt(11), V.UInt(2))
        )
        val kes = Bytes(sign(leaf, encoded(body)).value ++ kesPairs._1.flatMap(_.value))
        val h = encoded(array(body, bs(kes)))
        val block = get(SequenceInput.block(original(h)))
        require(
          get(lab.chain.CardanoBodyCommitment.inspect(block.original.block)).bodyCommitmentMatched
        )
        val step = get(
          if crossing then
            C.applySuccessorHeader(oldCertContext, certContext, cert, h, block.header.hash)
          else C.applyHeader(certContext, cert, h, block.header.hash)
        )
        val nonceStep = get(
          if crossing then N.applySuccessorHeader(oldNonceContext, nonceContext, nonce, step)
          else N.applyHeader(nonceContext, nonce, step)
        )
        val eligibility = get(
          if crossing then
            E.Context.checkedSuccessor(
              oldCertContext,
              certContext,
              cert,
              slot / epochLength,
              epochLength,
              vrfNonce,
              fraction,
              Map(issuer -> fraction),
              pin
            )
          else
            E.Context.checked(
              certContext,
              cert,
              slot / epochLength,
              epochLength,
              vrfNonce,
              fraction,
              Map(issuer -> fraction),
              pin
            )
        )
        get(E.check(eligibility, Vector(step)))
        cert = step.after; nonce = nonceStep.after
        block
      }

    /** Structural original identity remains consistent; only the KES signature is corrupted. */
    def invalidSignature(block: SequenceInput.Block): SequenceInput.Block =
      val raw = block.header.raw
      val bad = Bytes(raw.value.updated(raw.size - 1, (raw.value.last ^ 1).toByte))
      get(SequenceInput.block(original(bad)))
}
