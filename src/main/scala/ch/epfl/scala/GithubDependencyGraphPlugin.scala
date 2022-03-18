package ch.epfl.scala

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Properties
import scala.util.Try

import ch.epfl.scala.githubapi.DependencyNode
import ch.epfl.scala.githubapi.DependencyRelationship
import ch.epfl.scala.githubapi.DependencyScope
import ch.epfl.scala.githubapi.DependencySnapshot
import ch.epfl.scala.githubapi.DetectorMetadata
import ch.epfl.scala.githubapi.Job
import ch.epfl.scala.githubapi.JsonProtocol._
import ch.epfl.scala.githubapi.SnapshotResponse
import gigahorse.HttpClient
import gigahorse.support.okhttp.Gigahorse
import sbt.Scoped.richTaskSeq
import sbt._
import sbt.plugins.IvyPlugin
import sjsonnew.shaded.scalajson.ast.unsafe.JValue
import sjsonnew.support.scalajson.unsafe.CompactPrinter
import sjsonnew.support.scalajson.unsafe.Converter
import sjsonnew.support.scalajson.unsafe.Parser

object GithubDependencyGraphPlugin extends AutoPlugin {
  private lazy val http: HttpClient = Gigahorse.http(Gigahorse.config)
  private val runtimeConfigs =
    Set(
      Compile,
      Configurations.CompileInternal,
      Runtime,
      Configurations.RuntimeInternal,
      Provided,
      Optional,
      Configurations.System
    )
      .map(_.toConfigRef)

  object autoImport {
    val githubDependencyManifest: TaskKey[githubapi.Manifest] = taskKey("The dependency manifest of the project")
    val githubJob: TaskKey[Job] = taskKey("The current Github action job")
    val githubDependencySnapshot: TaskKey[DependencySnapshot] = taskKey(
      "The dependency snapshot of the build in a Github action job."
    )
    val submitGithubDependencyGraph: TaskKey[URL] = taskKey(
      "Submit the dependency snapshot of the build in a Github action job"
    )
  }

  import autoImport._

  override def trigger = allRequirements
  override def requires: Plugins = IvyPlugin

  override def globalSettings: Seq[Setting[_]] = Def.settings(
    githubJob := jobTask.value,
    githubDependencySnapshot := snapshotTask.value,
    submitGithubDependencyGraph := submitTask.value
  )

  override def projectSettings: Seq[Setting[_]] = Def.settings(
    githubDependencyManifest := manifestTask.value
  )

  private def manifestTask: Def.Initialize[Task[githubapi.Manifest]] = Def.task {
    // updateFull is needed to have information about callers
    val report = Keys.updateFull.value
    val projectRef = Keys.thisProjectRef.value
    val logger = Keys.streams.value.log
    val projectID = Keys.projectID.value
    val crossVersion = CrossVersion(Keys.scalaVersion.value, Keys.scalaBinaryVersion.value)
    val allDirectDependencies = Keys.allDependencies.value
    val useCoursier = Keys.useCoursier.value

    def getReference(module: ModuleID): String =
      crossVersion(module)
        .withConfigurations(None)
        .withExtraAttributes(Map.empty)
        .toString

    val alreadySeen = mutable.Set[String]()
    val moduleReports = mutable.Buffer[(ModuleReport, ConfigRef)]()
    val allDependencies = mutable.Buffer[(String, String)]()

    for {
      configReport <- report.configurations
      moduleReport <- configReport.modules
      moduleRef = getReference(moduleReport.module)
      if !moduleReport.evicted && !alreadySeen.contains(moduleRef)
    } {
      alreadySeen += moduleRef
      moduleReports += (moduleReport -> configReport.configuration)
      for (caller <- moduleReport.callers)
        allDependencies += (getReference(caller.caller) -> moduleRef)
    }

    val allDependenciesMap: Map[String, Vector[String]] = allDependencies.view
      .groupBy(_._1)
      .mapValues {
        _.map { case (_, dep) => dep }.toVector
      }
    val allDirectDependenciesRefs: Set[String] = allDirectDependencies.map(getReference).toSet

    val resolved =
      for ((moduleReport, configRef) <- moduleReports)
        yield {
          val module = moduleReport.module
          val moduleRef = getReference(module)
          val artifacts = moduleReport.artifacts.map { case (a, _) => a }
          val classifiers = artifacts.flatMap(_.classifier).filter(_ != "default")
          val packaging = if (classifiers.nonEmpty) "?" + classifiers.map(c => s"packaging=$c") else ""
          val isMaven =
            artifacts.flatMap(_.url).headOption.exists(_.toString.startsWith(Resolver.DefaultMavenRepositoryRoot))
          val isOtherResolver = moduleReport.resolver.contains("inter-project")
          val purl =
            // with ivy mode, url is not set so we cannot know if maven was used and we assume it was
            if (isMaven || (!useCoursier && !isOtherResolver)) {
              // purl specification: https://github.com/package-url/purl-spec/blob/master/PURL-SPECIFICATION.rst
              Some(s"pkg:/maven/${module.organization}/${module.name}@${module.revision}$packaging")
            } else None
          val dependencies = allDependenciesMap.getOrElse(moduleRef, Vector.empty)
          val relationship =
            if (allDirectDependenciesRefs.contains(moduleRef)) DependencyRelationship.direct
            else DependencyRelationship.indirect
          val scope =
            if (isRuntime(configRef)) DependencyScope.runtime
            else DependencyScope.development
          val node = DependencyNode(purl, Map.empty[String, JValue], Some(relationship), Some(scope), dependencies)
          (moduleRef -> node)
        }

    val projectModuleRef = getReference(projectID)
    githubapi.Manifest(projectModuleRef, None, Map.empty[String, JValue], resolved.toMap)
  }

  private def isRuntime(config: ConfigRef): Boolean = runtimeConfigs.contains(config)

  private def jobTask: Def.Initialize[Task[Job]] = Def.task {
    val name = githubCIEnv("GITHUB_JOB")
    val id = githubCIEnv("GITHUB_RUN_ID")
    val html_url =
      for {
        serverUrl <- Properties.envOrNone("$GITHUB_SERVER_URL")
        repository <- Properties.envOrNone("GITHUB_REPOSITORY")
      } yield s"$serverUrl/$repository/actions/runs/$id"
    Job(name, id, html_url)
  }

  private def snapshotTask: Def.Initialize[Task[DependencySnapshot]] = Def.taskDyn {
    val loadedBuild = Keys.loadedBuild.value
    val job = githubJob.value
    val sha = githubCIEnv("GITHUB_SHA")
    val ref = githubCIEnv("GITHUB_REF")
    val projectRefs = loadedBuild.allProjectRefs.map(_._1)
    val detector = DetectorMetadata("sbt-github-dependency-graph", "", "")
    val scanned = Instant.now
    Def.task {
      val manifests: Map[String, githubapi.Manifest] = projectRefs
        .map(ref => (ref / githubDependencyManifest).?)
        .join
        .value
        .zip(projectRefs)
        .collect { case (Some(manifest), projectRef) => (projectRef.project, manifest) }
        .toMap

      DependencySnapshot(0, job, sha, ref, detector, Map.empty[String, JValue], manifests, scanned.toString)
    }
  }

  private def submitTask: Def.Initialize[Task[URL]] = Def.task {
    val snapshot = githubDependencySnapshot.value
    val logger = Keys.streams.value.log

    val githubApiUrl = githubCIEnv("GITHUB_API_URL")
    val repository = githubCIEnv("GITHUB_REPOSITORY")
    val username = secret("GH_USERNAME")
    val token = secret("GH_TOKEN")
    val url = new URL(s"$githubApiUrl/repos/$repository/dependency-graph/snapshots")
    val authorization = Base64.getEncoder().encodeToString(s"$username:$token".getBytes("UTF-8"))

    val snapshotJson = CompactPrinter(Converter.toJsonUnsafe(snapshot))
    val request = Gigahorse
      .url(url.toString)
      .post(snapshotJson, StandardCharsets.UTF_8)
      .addHeaders(
        "Content-Type" -> "application/json",
        // adding authorization manually because of https://github.com/eed3si9n/gigahorse/issues/77
        "Authorization" -> s"Basic $authorization"
      )

    logger.info(s"Submiting dependency snapshot to $url")
    val response = Await.result(http.run(request), Duration.Inf)
    val result = for {
      httpResp <- Try(Await.result(http.run(request), Duration.Inf))
      jsonResp <- Parser.parseFromByteBuffer(httpResp.bodyAsByteBuffer)
      response <- Converter.fromJson[SnapshotResponse](jsonResp)
    } yield new URL(url, response.id)

    result match {
      case scala.util.Success(result) =>
        logger.info(s"Submitted successfully as $result")
        result
      case scala.util.Failure(cause) =>
        throw new MessageOnlyException(
          s"Failed to submit the dependency snapshot because of ${cause.getClass.getName}: ${cause.getMessage}"
        )
    }
  }

  private def githubCIEnv(name: String): String =
    Properties.envOrNone(name).getOrElse {
      throw new MessageOnlyException(s"Missing environment variable $name. This task must run in a Github Action.")
    }

  private def secret(name: String): String =
    Properties.envOrNone(name).getOrElse {
      throw new MessageOnlyException(s"Missing secret variable $name.")
    }
}
