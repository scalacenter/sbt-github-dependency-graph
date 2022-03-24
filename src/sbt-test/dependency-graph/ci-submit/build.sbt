import scala.util.Properties

val checkSubmit = taskKey[Unit]("Check the Github manifest of a project")

inThisBuild(
  Seq(
    organization := "ch.epfl.scala",
    version := "1.2.0-SNAPSHOT",
    useCoursier := true,
    scalaVersion := "2.12.15"
  )
)

Global / checkSubmit := {
  val result = submitGithubDependencyGraph.result.value
  val isCI = Properties.envOrNone("CI").isDefined
  result match {
    case Value(_) => ()
    case Inc(cause) =>
      if (isCI) throw cause
  }
}

lazy val p1 = project.in(file("p1"))
