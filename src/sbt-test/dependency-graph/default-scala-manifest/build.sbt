import ch.epfl.scala.githubapi.DependencyRelationship
import ch.epfl.scala.githubapi.DependencyScope
import ch.epfl.scala.githubapi.DependencyNode

val checkManifest = taskKey[Unit]("Check the Github manifest of a project")

// using the default scalaVersion
inThisBuild(
  Seq(
    organization := "ch.epfl.scala",
    version := "1.2.0-SNAPSHOT"
  )
)

lazy val p1 = project
  .in(file("p1"))
  .settings(
    checkManifest := {
      val manifest = githubDependencyManifest.value
      val resolved = manifest.resolved

      assert(manifest.name == "ch.epfl.scala:p1_2.12:1.2.0-SNAPSHOT")

      // all dependencies are defined
      assert(resolved.values.forall(n => n.dependencies.forall(resolved.contains)))

      val scalaLibrary = resolved("org.scala-lang:scala-library:2.12.14")
      // no url when using the default scala version because the scala-library is taken from the sbt classpath
      checkDependencyNode(scalaLibrary)(
        isUrlDefined = false,
        DependencyRelationship.direct,
        DependencyScope.runtime
      )

      val scalaCompiler = resolved("org.scala-lang:scala-compiler:2.12.14")
      // same for the compiler, it does not have  an url
      checkDependencyNode(scalaCompiler)(
        isUrlDefined = false,
        DependencyRelationship.direct,
        DependencyScope.development
      )
    }
  )

def checkDependencyNode(node: DependencyNode)(
    isUrlDefined: Boolean,
    relationship: DependencyRelationship,
    scope: DependencyScope,
    deps: Seq[String] = Seq.empty
): Unit = {
  assert(node.purl.isDefined == isUrlDefined)
  assert(node.relationship.contains(relationship))
  assert(node.scope.contains(scope))
  deps.foreach(d => assert(node.dependencies.contains(d)))
}
