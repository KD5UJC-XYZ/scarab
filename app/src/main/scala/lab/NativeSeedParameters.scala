// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.{ConwayRewardStart as R, ConwayStake as S}
import scala.util.control.NonFatal

/** Diagnostic PV9 parameter projection and externally pinned effective Shelley genesis. Complete
  * original parameter bytes remain bound separately from the scoped reward codecs. This is not full
  * PParams validation, ledger seed admission, or live epoch-info authority.
  *
  * Encoding: cardano-ledger-core-1.21.0.0 Core/PParams.hs encCBOR(PParams), BaseTypes.hs
  * BoundedRatio/ProtVer; cardano-ledger-conway-1.23.0.0 Conway/PParams.hs eraPParams. Globals:
  * cardano-ledger-shelley-1.19.0.1 Shelley/Genesis.hs mkShelleyGlobals and
  * Shelley/StabilityWindow.hs. Those windows use ceiling, including fractional results.
  */
private[lab] object NativeSeedParameters:
  private val Max = (BigInt(1) << 64) - 1
  private val SignedMax = (BigInt(1) << 63) - 1
  final class Parameters private[NativeSeedParameters] (
      val original: Bytes,
      val sha256: Bytes,
      val rewards: R.Parameters,
      val feePerByte: BigInt,
      val feeFixed: BigInt,
      val maxTxSize: BigInt,
      val coinsPerUTxOByte: BigInt
  )
  final class Prepared private[NativeSeedParameters] (
      val previous: Parameters,
      val current: Parameters,
      val genesisOriginal: Bytes,
      val genesisSHA256: Bytes,
      val globals: R.Globals,
      val slotLength: S.Ratio,
      val stabilityWindow: BigInt,
      val randomnessWindow: BigInt,
      val epoch: BigInt,
      val pointSlot: BigInt,
      val networkMagic: BigInt,
      val bindingId: Bytes
  ):
    val timingProfileCompatible: Boolean = 2 * randomnessWindow < globals.epochLength
    val rewardSeedAdmission = false
    val runtimeImport = false
    val monetaryParity = false
    val nativeConformance = false
    val epochInfoAuthenticated = false

  private def checked[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def node(v: V): Node = Node(v, Bytes(Vector.empty))
  private def encoded(xs: Vector[V]): Bytes = get(Cbor.encode(V.Arr(xs.map(node))))
  private def uint(n: Node, max: BigInt = Max): BigInt = n.value match
    case V.UInt(v) if v >= 0 && v <= max => v
    case _ => throw new IllegalArgumentException("native parameter unsigned bound/shape")
  private def arr(n: Node, size: Int): Vector[Node] = n.value match
    case V.Arr(xs) if xs.size == size => xs
    case _ => throw new IllegalArgumentException("native parameter array width/shape")
  private def ratio(n: Node, unit: Boolean): S.Ratio = n.value match
    case V.Tag(t, value) if t == 30 =>
      val xs = arr(value, 2)
      val a = uint(xs(0)); val b = uint(xs(1))
      require(b > 0 && a.gcd(b) == 1 && (!unit || a <= b), "native canonical rational")
      S.Ratio(a, b)
    case _ => throw new IllegalArgumentException("native rational tag30 required")

  private def parameters(raw: Bytes): Parameters =
    require(
      raw != null && raw.value != null && raw.size > 0 && raw.size <= 262144,
      "native PParams byte bound"
    )
    val xs = arr(get(Cbor.decode(raw, Cbor.Limits(262144, 12, 8192, 262144))), 31)
    // UInt encodings are bounded by their native types; unrelated semantics remain opaque.
    Vector(0, 1, 5, 6, 7, 13, 14, 25, 26, 27, 28, 29).foreach(i => uint(xs(i)))
    Vector(2, 3, 19).foreach(i => uint(xs(i), (BigInt(1) << 32) - 1))
    Vector(4, 8, 20, 21, 24).foreach(i => uint(xs(i), 65535))
    val a0 = ratio(xs(9), false); val rho = ratio(xs(10), true); val tau = ratio(xs(11), true)
    val pv = arr(xs(12), 2)
    require(uint(pv(0)) == 9 && uint(pv(1)) == 0, "native PV9.0 required in each parameter role")
    xs(15).value match
      case V.Map(entries) =>
        val keys = entries.map((k, _) => uint(k, 255))
        require(keys.distinct.size == keys.size, "duplicate cost-model key")
        entries.foreach { (_, costs) =>
          costs.value match
            case V.Arr(values) =>
              values.foreach { n =>
                n.value match
                  case V.UInt(v) => require(v <= SignedMax, "cost-model Int64 bound")
                  case V.NInt(v) => require(v >= -SignedMax - 1, "cost-model Int64 bound")
                  case _         => throw new IllegalArgumentException("cost-model integer shape")
              }
            case _ => throw new IllegalArgumentException("cost-model array shape")
        }
      case _ => throw new IllegalArgumentException("cost-model map shape")
    arr(xs(16), 2).foreach(ratio(_, false))
    Vector(17, 18).foreach(i => arr(xs(i), 2).foreach(uint(_, SignedMax)))
    arr(xs(22), 5).foreach(ratio(_, true))
    arr(xs(23), 10).foreach(ratio(_, true))
    ratio(xs(30), false)
    val rewardBytes = encoded(
      Vector(
        V.Text(R.PoolParameterFormat),
        V.UInt(9),
        V.UInt(0),
        V.UInt(rho.numerator),
        V.UInt(rho.denominator),
        V.UInt(tau.numerator),
        V.UInt(tau.denominator),
        V.UInt(a0.numerator),
        V.UInt(a0.denominator),
        V.UInt(uint(xs(8)))
      )
    )
    new Parameters(
      raw,
      ClusterHeaderObservation.sha256(raw),
      get(R.decodePoolParameters(rewardBytes)),
      uint(xs(0)),
      uint(xs(1)),
      uint(xs(3)),
      uint(xs(14))
    )

  /** Bounded source-shape extraction only; callers choose stricter supported-payload policy. */
  def decodeParameters(raw: Bytes): Either[String, Parameters] = checked(parameters(raw))

  import ReferenceJson.{Json, field, uint as jsonUInt, string}
  private def obj(j: Json): Map[String, Json] = j match
    case Json.Obj(m) => m
    case _           => throw new IllegalArgumentException("effective genesis object required")
  private def bounded(j: Json, max: BigInt = Max): BigInt =
    val n = jsonUInt(j); require(n <= max, "effective genesis integer bound"); n
  private def decimal(j: Json, positive: Boolean = true): S.Ratio = j match
    case Json.Num(text) =>
      require(text.length <= 128, "effective genesis decimal length")
      val d = new java.math.BigDecimal(text)
      require(d.scale >= -18 && d.scale <= 18, "effective genesis decimal scale")
      val unscaled = BigInt(d.unscaledValue)
      val a = if d.scale < 0 then unscaled * BigInt(10).pow(-d.scale) else unscaled
      val b = if d.scale > 0 then BigInt(10).pow(d.scale) else BigInt(1)
      val gcd = a.gcd(b)
      require(
        a >= 0 && (!positive || a > 0) && a / gcd <= Max && b / gcd <= Max,
        "effective genesis positive rational bound"
      )
      S.Ratio(a / gcd, b / gcd)
    case _ => throw new IllegalArgumentException("effective genesis exact JSON decimal required")

  // Shelley/Genesis.hs ShelleyExtraConfig/InjectionData JSON instances: absent fields and {}
  // mean NoInjection; {"data":object} is EmbeddedInjection. File sources require external
  // resolution/hash evidence not present in this API, so they stay explicitly unsupported.
  // The embedded subset below covers base/enterprise testnet funds and relay-free pools.
  // These are bounded structural checks; they do not prove genesis injection was executed,
  // derive report components from the seed, or establish any ledger admission invariant.
  private def hex(text: String, width: Int): Bytes =
    require(text.length == width * 2, "embedded genesis hash width")
    val bytes = get(Bytes.fromHex(text))
    require(bytes.hex == text, "embedded genesis canonical hex")
    bytes

  private def extraConfig(j: Json): Unit =
    val config = obj(j)
    require(
      config.keySet.subsetOf(Set("initialFunds", "stakePools", "stakeCredentials")),
      "unknown genesis extraConfig field"
    )
    def embedded(name: String): Map[String, Json] =
      config.get(name) match
        case None | Some(Json.Lit("null")) => Map.empty
        case Some(value) =>
          val wrapper = obj(value)
          require(!wrapper.contains("file"), "external genesis injection file unsupported")
          require(
            wrapper.isEmpty || wrapper.keySet == Set("data"),
            "genesis injection wrapper shape"
          )
          val data = wrapper.get("data").map(obj).getOrElse(Map.empty)
          require(data.size <= 1024, "embedded genesis entry bound")
          data
    val funds = embedded("initialFunds")
    val pools = embedded("stakePools")
    val credentials = embedded("stakeCredentials")
    funds.foreach { (address, amount) =>
      require(address.length == 58 || address.length == 114, "embedded genesis address width")
      val bytes = hex(address, address.length / 2)
      val header = bytes.value.head & 0xff
      val kind = header >>> 4
      require(
        (header & 15) == 0 &&
          ((kind <= 3 && bytes.size == 57) || ((kind == 6 || kind == 7) && bytes.size == 29)),
        "embedded genesis base/enterprise testnet address required"
      )
      bounded(amount)
    }
    credentials.foreach { (credential, pool) =>
      hex(credential, 28); hex(string(pool), 28)
    }
    pools.foreach { (key, pool) =>
      hex(key, 28)
      val fields = obj(pool)
      require(
        fields.keySet == Set(
          "poolId",
          "vrf",
          "pledge",
          "cost",
          "margin",
          "accountAddress",
          "owners",
          "relays",
          "metadata"
        ),
        "embedded genesis pool field shape"
      )
      require(string(field(pool, "poolId")) == key, "embedded genesis pool key/id mismatch")
      hex(string(field(pool, "vrf")), 32)
      bounded(field(pool, "pledge")); bounded(field(pool, "cost"))
      val margin = decimal(field(pool, "margin"), false)
      require(margin.numerator <= margin.denominator, "embedded genesis pool margin")
      val account = field(pool, "accountAddress")
      require(
        obj(account).keySet == Set("network", "credential") &&
          string(field(account, "network")) == "Testnet",
        "embedded genesis account shape/network"
      )
      val credential = obj(field(account, "credential"))
      require(
        credential.size == 1 &&
          credential.keySet.subsetOf(Set("keyHash", "scriptHash")),
        "embedded genesis account credential"
      )
      hex(string(credential.head._2), 28)
      val owners = ReferenceJson.array(field(pool, "owners")).map(string)
      require(
        owners.size <= 64 && owners.distinct.size == owners.size,
        "embedded genesis pool owners"
      )
      owners.foreach(hex(_, 28))
      require(
        ReferenceJson.array(field(pool, "relays")).isEmpty &&
          field(pool, "metadata") == Json.Lit("null"),
        "embedded genesis relays/metadata unsupported"
      )
    }

  /** Parameters must come from the caller's already pinned native report components. Genesis bytes
    * have their own independently expected SHA256. Fixed epoch geometry is diagnostic; hard-fork
    * history and source authenticity are not established by matching local hashes.
    */
  def decode(
      previous: Bytes,
      current: Bytes,
      genesis: Bytes,
      expectedGenesisSha: Bytes,
      expectedEpoch: BigInt,
      pointSlot: BigInt,
      networkMagic: BigInt
  ): Either[String, Prepared] = checked {
    require(
      genesis != null && genesis.value != null && genesis.size > 0 && genesis.size <= 4194304,
      "effective genesis byte bound"
    )
    require(
      expectedGenesisSha != null && expectedGenesisSha.size == 32 &&
        ClusterHeaderObservation.sha256(genesis) == expectedGenesisSha,
      "effective genesis pin mismatch"
    )
    require(
      expectedEpoch != null && expectedEpoch >= 0 && expectedEpoch <= Max &&
        pointSlot != null && pointSlot >= 0 && pointSlot <= Max &&
        networkMagic != null && networkMagic >= 0 && networkMagic <= (BigInt(1) << 32) - 1,
      "effective genesis external point/epoch/network bound"
    )
    val j = ReferenceJson.parse(genesis)
    val m = obj(j)
    val required = Set(
      "systemStart",
      "networkMagic",
      "networkId",
      "activeSlotsCoeff",
      "securityParam",
      "epochLength",
      "slotsPerKESPeriod",
      "maxKESEvolutions",
      "slotLength",
      "updateQuorum",
      "maxLovelaceSupply",
      "protocolParams",
      "genDelegs",
      "initialFunds"
    )
    require(
      required.subsetOf(m.keySet) && m.keySet.subsetOf(required ++ Set("staking", "extraConfig")),
      "effective genesis missing/extra root fields"
    )
    m.get("extraConfig").filter(_ != Json.Lit("null")).foreach(extraConfig)
    java.time.Instant.parse(string(field(j, "systemStart")))
    require(string(field(j, "networkId")) == "Testnet", "diagnostic requires testnet genesis")
    require(
      bounded(field(j, "networkMagic"), (BigInt(1) << 32) - 1) == networkMagic,
      "effective genesis network mismatch"
    )
    val epochLength = bounded(field(j, "epochLength"))
    val k = bounded(field(j, "securityParam"))
    val supply = bounded(field(j, "maxLovelaceSupply"))
    val f = decimal(field(j, "activeSlotsCoeff")); val slotLength = decimal(field(j, "slotLength"))
    require(
      (slotLength.numerator * 1000000) % slotLength.denominator == 0,
      "genesis slotLength must be exactly representable in native microseconds"
    )
    require(
      epochLength > 0 && k > 0 && supply > 0 && f.numerator <= f.denominator,
      "effective genesis positive globals"
    )
    require(pointSlot / epochLength == expectedEpoch, "fixed-genesis point/epoch mismatch")
    require(
      bounded(field(j, "slotsPerKESPeriod")) > 0 &&
        bounded(field(j, "maxKESEvolutions")) > 0,
      "genesis positive KES dimensions"
    )
    bounded(field(j, "updateQuorum"))
    Vector("protocolParams", "genDelegs", "initialFunds").foreach(key => obj(field(j, key)))
    m.get("staking").foreach { value =>
      val s = obj(value)
      require(s.keySet == Set("pools", "stake"), "genesis staking shape")
      s.values.foreach(obj)
    }
    def window(multiplier: Int): BigInt =
      val a = multiplier * k * f.denominator
      val result = (a + f.numerator - 1) / f.numerator
      require(result <= Max, "genesis window uint64 overflow"); result
    val globals = get(
      R.decodePulserGlobals(
        encoded(
          Vector(
            V.Text(R.PulserGlobalFormat),
            V.UInt(epochLength),
            V.UInt(f.numerator),
            V.UInt(f.denominator),
            V.UInt(supply),
            V.UInt(k)
          )
        )
      )
    )
    val prev = parameters(previous); val cur = parameters(current)
    val id = ClusterHeaderObservation.sha256(
      encoded(
        Vector(
          V.Text("native-seed-parameters-diagnostic-v1"),
          V.ByteString(prev.sha256),
          V.ByteString(cur.sha256),
          V.ByteString(expectedGenesisSha),
          V.UInt(expectedEpoch),
          V.UInt(pointSlot),
          V.UInt(networkMagic)
        )
      )
    )
    new Prepared(
      prev,
      cur,
      genesis,
      expectedGenesisSha,
      globals,
      slotLength,
      window(3),
      window(4),
      expectedEpoch,
      pointSlot,
      networkMagic,
      id
    )
  }
