// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.Bytes
import scala.util.control.NonFatal

/** Explicit diagnostic native oracle boundary. Does not authenticate an external process. */
object ConwayNativeLikelihood:
  val Profile = "conway-native-likelihood-v1"
  enum Mode:
    case PureJvm, CheckedJvm, AssistedNative
  val MaxPools = 64
  val MaxResponseBytes = 131072
  final class Request private[ConwayNativeLikelihood] (
      val frozen: ConwayEpochBoundary.Frozen,
      val original: Bytes,
      private[ledger] val rows: Vector[(Bytes, BigInt, BigInt, BigInt)]
  )
  final class Generated private[ConwayNativeLikelihood] (
      val request: Request,
      val evidence: Bytes,
      val nativeResponse: Option[Bytes],
      val likelihoods: Map[Bytes, ConwayNonMyopic.Likelihood],
      val mode: Mode,
      val raw32Mismatches: Int,
      val raw64Mismatches: Int
  ):
    val jvmMismatchWords = raw32Mismatches + raw64Mismatches
    val computedRaw32Words = request.rows.size * 100
    val computedRaw64Words = request.rows.size
    val raw32Comparisons = if mode == Mode.PureJvm then 0 else computedRaw32Words
    val raw64Comparisons = if mode == Mode.PureJvm then 0 else computedRaw64Words
    val nativeValuesAuthoritative = mode == Mode.AssistedNative
    val diagnosticNativeDependency = mode != Mode.PureJvm
    val nativeValidated = mode == Mode.CheckedJvm && jvmMismatchWords == 0
    val generalJvmParityValidated = false
    def forFrozen(
        frozen: ConwayEpochBoundary.Frozen,
        expectedId: Bytes
    ): Either[String, Map[Bytes, ConwayNonMyopic.Likelihood]] = checked {
      require((frozen eq request.frozen) && frozen.id == expectedId, "different frozen source")
      likelihoods
    }
  private def checked[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  def request(frozen: ConwayEpochBoundary.Frozen, expectedId: Bytes): Either[String, Request] =
    checked {
      require(
        frozen != null && expectedId != null && expectedId.size == 32 && frozen.id == expectedId,
        "frozen source identity mismatch"
      )
      val globals = frozen.rewardGlobals.getOrElse(
        throw new IllegalArgumentException("frozen globals required")
      )
      val params = frozen.rewardParameters.getOrElse(
        throw new IllegalArgumentException("frozen parameters required")
      )
      require(
        globals.epochLength == 1000 && frozen.epochLength == 1000 &&
          globals.activeSlotCoefficient == ConwayStake.Ratio(1, 20) &&
          params.decentralization == ConwayStake.Ratio(0, 1) &&
          params.original == frozen.previousParameters && globals.maxSupply == frozen.maxSupply,
        "unsupported native diagnostic geometry"
      )
      val circulation = frozen.maxSupply - frozen.reserves
      require(
        circulation > 0 && frozen.go.pools.size <= MaxPools,
        "native diagnostic circulation/domain"
      )
      val rows = frozen.go.pools.toVector.sortBy(_._1.hex).map { (pool, data) =>
        val blocks = frozen.previousBlocks.getOrElse(pool, BigInt(0))
        require(
          data.coin >= 0 && data.coin <= circulation && blocks >= 0 && blocks <= 1000,
          "native diagnostic stake/count bounds"
        )
        (pool, data.coin, circulation, blocks)
      }
      val wire = s"$Profile\n${frozen.id.hex}\n1000 1 20 0 1\n" +
        rows.map((p, s, c, b) => s"${p.hex} $s $c $b\n").mkString
      new Request(frozen, Bytes.fromArray(wire.getBytes("US-ASCII")), rows)
    }

  /** Caller MUST obtain response by executing its pinned native helper against request.original.
    * Echo/hash equality is binding, not authentication. CheckedJvm selects computed JVM words only
    * after exact native parity. AssistedNative must be explicitly selected and records native
    * authority plus every JVM mismatch count.
    */
  def acceptTrustedNative(
      request: Request,
      response: Bytes,
      mode: Mode = Mode.CheckedJvm
  ): Either[String, Generated] = checked {
    require(
      request != null && mode != null && mode != Mode.PureJvm && response != null && response.size <= MaxResponseBytes,
      "native response bounds"
    )
    val text = new String(response.value.toArray, "US-ASCII")
    val prefix = new String(request.original.value.toArray, "US-ASCII") + "--native--\n"
    require(text.startsWith(prefix) && text.endsWith("\n"), "native request echo mismatch")
    val lines = text.substring(prefix.length).split("\n", -1).toVector.dropRight(1)
    require(lines.size == request.rows.size, "native full pool domain required")
    val calculated = get(ConwayLikelihoodGeneration.calculate(request))
    val calculatedRows =
      calculated.rows.map((pool, probability, words) => pool -> (probability, words)).toMap
    var mismatch32 = 0
    var mismatch64 = 0
    val values = request.rows
      .zip(lines)
      .map { case ((pool, stake, circulation, blocks), line) =>
        require(line.matches("[0-9a-f]{56} [0-9a-f]{16} [0-9a-f]{800}"), "native raw word row")
        val parts = line.split(" ")
        require(parts(0) == pool.hex, "native pool ordering/domain mismatch")
        val rawProbability = java.lang.Long.parseUnsignedLong(parts(1), 16)
        require(
          java.lang.Double.isFinite(java.lang.Double.longBitsToDouble(rawProbability)),
          "nonfinite native probability"
        )
        val words = parts(2).grouped(8).map(java.lang.Integer.parseUnsignedInt(_, 16)).toVector
        val (jvmProbability, jvmWords) = calculatedRows(pool)
        if jvmProbability != rawProbability then mismatch64 += 1
        mismatch32 += jvmWords.zip(words).count((a, b) => a != b)
        pool -> get(ConwayNonMyopic.likelihood(if mode == Mode.CheckedJvm then jvmWords else words))
      }
      .toMap
    require(
      mode == Mode.AssistedNative || mismatch32 + mismatch64 == 0,
      s"native/JVM exact mismatch raw32=$mismatch32 raw64=$mismatch64"
    )
    new Generated(request, response, Some(response), values, mode, mismatch32, mismatch64)
  }

  /** Separate research capability: JVM computation only, with no native response or gate. */
  private[ledger] def generatePureJvm(
      frozen: ConwayEpochBoundary.Frozen,
      expectedId: Bytes
  ): Either[String, Generated] = checked {
    val input = get(request(frozen, expectedId))
    val calculated = get(ConwayLikelihoodGeneration.calculate(input))
    val values =
      calculated.rows.map((pool, _, words) => pool -> get(ConwayNonMyopic.likelihood(words))).toMap
    new Generated(input, calculated.original, None, values, Mode.PureJvm, 0, 0)
  }
