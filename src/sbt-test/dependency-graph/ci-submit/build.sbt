import scala.util.Properties
import sbt.internal.util.complete.Parsers._

val checkManifests = inputKey[Unit]("Check the number of manifests")

inThisBuild(
  Seq(
    organization := "ch.epfl.scala",
    version := "1.2.0-SNAPSHOT",
    useCoursier := true,
    scalaVersion := "2.12.15",
    crossScalaVersions := Seq(
      "2.12.16",
      "2.13.8",
      "3.1.3"
    )
  )
)

val a = project.in(file("a"))

val b = project
  .in(file("b"))
  .settings(
    libraryDependencies += "org.typelevel" %% "cats-core" % "2.8.0"
  )

Global / checkManifests := {
  val expectedSize: Int = (Space ~> NatBasic).parsed
  val manifests = state.value.get(githubManifestsKey).getOrElse {
    throw new MessageOnlyException(s"Not found ${githubManifestsKey.label} attribute")
  }
  assert(
    manifests.size == expectedSize,
    s"expected $expectedSize manifests, found ${manifests.size}"
  )
}
