// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.nio.file.Path

/** The reference's ledger states of the public Leios testnet that are not in the repository: the
  * start of the G1 window and the states of the replay windows P and G (`docs/leios.md`, the replay
  * windows). `SCARAB_LEIOS_REFERENCE_STATES` names their directory, laid out as the windows' own:
  * `g1/<slot>`, `window-p/<slot>` and `window-g/<slot>`, each named after its state's tip slot and
  * holding the reference's `state`, `tables` and `meta`.
  *
  * A test that reads one names its captures ([[input]]): unset, it is skipped and names the
  * variable, an environmental skip that #54 tracks
  * (https://github.com/KD5UJC-XYZ/scarab/issues/54#issuecomment-7070543699); set to a directory
  * that does not hold one of them, it fails ([[ExternalInput]]).
  */
object LeiosReferenceStates:
  val variable: String = "SCARAB_LEIOS_REFERENCE_STATES"

  /** A window, by the directory that holds its states. */
  enum Window(val directory: String):
    case G1 extends Window("g1")
    case P extends Window("window-p")
    case G extends Window("window-g")

  /** A state of a window, by its tip slot. */
  final case class Capture(window: Window, slot: Long):
    /** Its directory, relative to the variable's. */
    def name: String = s"${window.directory}/$slot"

    /** Its `state` under the variable's directory `root`. */
    def state(root: Path): Path = root.resolve(name).resolve("state")

  /** The G1 window's start, the end of epoch 89. */
  val g1Start: Capture = Capture(Window.G1, 1943999L)

  /** Window P's states, at the ends of epochs 46 to 49. */
  val windowP: Vector[Capture] =
    Vector(1015165L, 1036794L, 1058391L, 1079994L).map(Capture(Window.P, _))

  /** Window G's states, at the ends of epochs 69 to 74. */
  val windowG: Vector[Capture] =
    Vector(1511985L, 1533582L, 1555188L, 1576750L, 1598375L, 1619990L).map(Capture(Window.G, _))

  /** The variable's directory, which must hold each of `captures`. */
  def input(captures: Capture*): ExternalInput =
    ExternalInput.directory(variable, sys.env, captures.map(_.name)*)
