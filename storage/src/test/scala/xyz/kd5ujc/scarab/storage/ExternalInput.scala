// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.nio.file.{Files, Path}

/** An optional external test input named by an environment variable: a pinned database or capture
  * too large for the repository. Unset, the test that reads it is skipped and names the variable
  * that would run it, an environmental skip reported with its scope. Set to something that is not
  * the expected input, the test fails: a misconfigured acceptance run must not pass as a skip.
  */
enum ExternalInput:
  case Unset(variable: String)
  case Invalid(variable: String, value: String, problem: ExternalInput.Problem)
  case Present(variable: String, path: Path)

object ExternalInput:
  /** Why a set value is not the expected input. */
  enum Problem:
    case Blank
    case NotADirectory(path: Path)
    case MissingDirectory(path: Path)

  /** The directory `variable` names in `env`, which must contain each of `required` as a
    * subdirectory.
    */
  def directory(variable: String, env: Map[String, String], required: String*): ExternalInput =
    env.get(variable) match
      case None                         => Unset(variable)
      case Some(value) if value.isBlank => Invalid(variable, value, Problem.Blank)
      case Some(value) =>
        val root = Path.of(value)
        if !Files.isDirectory(root) then Invalid(variable, value, Problem.NotADirectory(root))
        else
          required.map(root.resolve).find(!Files.isDirectory(_)) match
            case Some(missing) => Invalid(variable, value, Problem.MissingDirectory(missing))
            case None          => Present(variable, root)

/** Runs a test body on an [[ExternalInput]]: skipped when unset, failed when set but invalid. */
trait ExternalInputs:
  self: munit.FunSuite =>

  def withInput(input: ExternalInput)(body: Path => Unit)(using munit.Location): Unit =
    input match
      case ExternalInput.Present(_, path) => body(path)
      case ExternalInput.Unset(variable)  => assume(false, s"$variable is not set")
      case ExternalInput.Invalid(variable, value, problem) =>
        fail(s"$variable is set to '$value', which is not the required input: $problem")
