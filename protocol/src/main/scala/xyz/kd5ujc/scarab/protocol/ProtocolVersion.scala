// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

/** The node-to-node protocol versions the handshake knows, as the reference's `NodeToNodeVersion`
  * (ouroboros-network `cardano-diffusion/api/lib/Cardano/Network/NodeToNode/Version.hs` 85 to 95,
  * the same at `c5117705` that cardano-node 11.1.3 pins and at `4b3ab766` that the Leios testnet
  * release pins):
  *   - V14: the Plomin hard fork, mandatory on mainnet from 2025-01-29;
  *   - V15: SRV support in the topology (nothing changes on the wire);
  *   - V16: experimental; adds the Peras flag to the version data (the fifth element, `Version.hs`
  *     187 to 189) and, if both sides set it, the Peras mini-protocols.
  * The wire number stays an `Int` in [[Handshake]], whose refusals must carry numbers this ADT does
  * not know; [[fromNumber]] is the one way in.
  */
enum NtnVersion(val number: Int):
  case V14 extends NtnVersion(14)
  case V15 extends NtnVersion(15)
  case V16 extends NtnVersion(16)

  /** Version data of this version carries the Peras flag (`isValidNtnVersionDataForVersion`). */
  def carriesPeras: Boolean = this.ordinal >= NtnVersion.V16.ordinal

object NtnVersion:
  given Ordering[NtnVersion] = Ordering.by(_.ordinal)

  /** What cardano-node 11.1.3 offers by default: consensus 4.2.1.0 `Cardano/Node.hs` 437 to 456,
    * `latestReleasedNodeVersion = (Just NodeToNodeV_15, Just NodeToClientV_23)`; the versions above
    * it are offered only with `ExperimentalProtocolsEnabled` (`Node.hs` of the diffusion 1152 to
    * 1165, cardano-node `Run.hs` 555 to 565).
    */
  val LatestReleased: NtnVersion = V15

  /** Every version this node implements, in increasing order. */
  val All: Vector[NtnVersion] = values.toVector

  def fromNumber(number: Int): Option[NtnVersion] = All.find(_.number == number)

  /** The versions up to and including `max`, in increasing order. */
  def upTo(max: NtnVersion): Vector[NtnVersion] = All.takeWhile(_.ordinal <= max.ordinal)

  /** The maximum the node offers: the latest release, or the latest version when experimental
    * protocols are enabled.
    */
  def maximum(experimentalProtocols: Boolean): NtnVersion =
    if experimentalProtocols then All.last else LatestReleased

/** The node-to-client protocol versions, as the reference's `NodeToClientVersion` as consensus
  * 4.2.1.0 supports them (`supportedNodeToClientVersions`, `Cardano/Node.hs` 444 to 454): 16 to 23,
  * on the wire with bit 15 set. The version data is the same for all (magic and the query flag).
  * LocalStateQuery retains the selected version for query support and result encoding; `GetCBOR`
  * alone intentionally uses the latest internal result encoding (see docs/compatibility.md).
  */
enum NtcVersion(val number: Int):
  case V16 extends NtcVersion(16)
  case V17 extends NtcVersion(17)
  case V18 extends NtcVersion(18)
  case V19 extends NtcVersion(19)
  case V20 extends NtcVersion(20)
  case V21 extends NtcVersion(21)
  case V22 extends NtcVersion(22)
  case V23 extends NtcVersion(23)

object NtcVersion:
  given Ordering[NtcVersion] = Ordering.by(_.ordinal)

  /** `latestReleasedNodeVersion`: node-to-client 23 is released and the highest there is. */
  val LatestReleased: NtcVersion = V23

  val All: Vector[NtcVersion] = values.toVector

  def fromNumber(number: Int): Option[NtcVersion] = All.find(_.number == number)
