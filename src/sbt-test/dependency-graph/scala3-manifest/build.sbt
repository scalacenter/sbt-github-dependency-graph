import ch.epfl.scala.githubapi.DependencyRelationship
import ch.epfl.scala.githubapi.DependencyScope
import ch.epfl.scala.githubapi.DependencyNode

val checkManifest = taskKey[Unit]("Check the Github manifest of a project")

inThisBuild(
  Seq(
    organization := "ch.epfl.scala",
    version := "1.2.0-SNAPSHOT",
    scalaVersion := "3.1.0"
  )
)

lazy val p1 = project
  .in(file("p1"))
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % "0.14.1"
    ),
    checkManifest := {
      val manifest = githubDependencyManifest.value
      val resolved = manifest.resolved

      assert(manifest.name == "ch.epfl.scala:p1_3:1.2.0-SNAPSHOT")

      // all dependencies are defined
      assert(resolved.values.forall(n => n.dependencies.forall(resolved.contains)))

      val circe = resolved("io.circe:circe-core_3:0.14.1")
      checkDependencyNode(circe)(
        DependencyRelationship.direct,
        DependencyScope.runtime,
        Seq("org.scala-lang:scala3-library_3:3.1.0")
      )

      val scala3Library = resolved("org.scala-lang:scala3-library_3:3.1.0")
      checkDependencyNode(scala3Library)(
        DependencyRelationship.direct,
        DependencyScope.runtime
      )

      val scala3Compiler = resolved("org.scala-lang:scala3-compiler_3:3.1.0")
      checkDependencyNode(scala3Compiler)(
        DependencyRelationship.direct,
        DependencyScope.development
      )
    }
  )

def checkDependencyNode(node: DependencyNode)(
    relationship: DependencyRelationship,
    scope: DependencyScope,
    deps: Seq[String] = Seq.empty
): Unit = {
  assert(node.purl.isDefined)
  assert(node.relationship.contains(relationship))
  assert(node.scope.contains(scope))
  deps.foreach(d => assert(node.dependencies.contains(d)))
}
