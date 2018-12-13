import sbt.Keys.{name, scalacOptions}
import sbt._
import SbtUtils._
import Dependencies._
import com.typesafe.sbt.packager.MappingsHelper

name := "server"

lazy val commonSettings = Seq(
  organization := "com.prisma",
  organizationName := "Prisma",
  scalaVersion := "2.12.7",
  parallelExecution in Test := false,
  publishArtifact in (Test, packageDoc) := false,
  publishArtifact in (Compile, packageDoc) := false,
  publishArtifact in packageDoc := false,
  publishArtifact in packageSrc := false,
  sources in (Compile,doc) := Seq.empty, // todo Somehow, after all these settings, there's STILL API docs getting generated somewhere.
  // We should gradually introduce https://tpolecat.github.io/2014/04/11/scalac-flags.html
  // These needs to separately be configured in Idea
  scalacOptions ++= Seq("-deprecation", "-feature", "-Xfatal-warnings", "-language:implicitConversions"),
  resolvers ++= Seq(
    "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
    "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"
  ),
  libraryDependencies := common
)

lazy val commonServerSettings = commonSettings ++ Seq(libraryDependencies ++= commonServerDependencies)
lazy val prerunHookFile = new java.io.File(sys.props("user.dir") + "/prerun_hook.sh")

def commonDockerImageSettings(imageName: String, baseImage: String, tag: String) = commonServerSettings ++ Seq(
  imageNames in docker := Seq(
    ImageName(s"prismagraphql/$imageName:$tag")
  ),
  sources in (Compile, doc) := Seq.empty,
  dockerfile in docker := {
    val appDir    = stage.value
    val targetDir = "/app"
    val systemLibs = "/lib"

    val libraries = (MappingsHelper.contentOf(file(absolute("libs/jdbc-native/src/main/resources"))) ++
      MappingsHelper.contentOf(file(absolute("libs/jwt-native/src/main/resources")))).foldLeft(Vector.empty[(File, String)]) { (prev, next) =>
      if (prev.exists(_._2 == next._2)) {
        prev
      } else {
        prev :+ next
      }
    }

    new Dockerfile {
      from(baseImage)
      copy(appDir, targetDir)
      libraries.foreach(f => copy(f._1, systemLibs))
      copy(prerunHookFile , s"$targetDir/prerun_hook.sh")
      runShell(s"touch", s"$targetDir/start.sh")
      runShell("echo", "'#!/bin/bash'", ">>", s"$targetDir/start.sh")
      runShell("echo", "set -e", ">>", s"$targetDir/start.sh")
      runShell("echo", s"$targetDir/prerun_hook.sh", ">>", s"$targetDir/start.sh")
      runShell("echo", s"$targetDir/bin/${executableScriptName.value}", ">>" ,s"$targetDir/start.sh")
      runShell(s"chmod", "+x", s"$targetDir/start.sh")
      env("COMMIT_SHA", sys.env.getOrElse("COMMIT_SHA", sys.error("Env var COMMIT_SHA required but not found.")))
      env("CLUSTER_VERSION", sys.env.getOrElse("CLUSTER_VERSION", sys.error("Env var CLUSTER_VERSION required but not found.")))
      entryPointShell(s"$targetDir/start.sh")
    }
  }
)

javaOptions in Universal ++= Seq("-Dorg.jooq.no-logo=true")

def imageProject(name: String, imageName: String, baseImage: String = "anapsix/alpine-java", tag: String = "latest"): Project = imageProject(name).enablePlugins(sbtdocker.DockerPlugin, JavaAppPackaging).settings(commonDockerImageSettings(imageName, baseImage, tag): _*).dependsOn(prismaImageShared)
def imageProject(name: String): Project = Project(id = name, base = file(s"./images/$name"))
def serverProject(name: String): Project = Project(id = name, base = file(s"./servers/$name")).settings(commonServerSettings: _*).dependsOn(scalaUtils).dependsOn(tracing).dependsOn(logging)
def connectorProject(name: String): Project =  Project(id = name, base = file(s"./connectors/$name")).settings(commonSettings: _*).dependsOn(scalaUtils).dependsOn(prismaConfig).dependsOn(tracing)
def integrationTestProject(name: String): Project =  Project(id = name, base = file(s"./integration-tests/$name")).settings(commonSettings: _*)
def libProject(name: String): Project =  Project(id = name, base = file(s"./libs/$name")).settings(commonSettings: _*)
def normalProject(name: String): Project = Project(id = name, base = file(s"./$name")).settings(commonSettings: _*)


// ##################
//       IMAGES
// ##################
lazy val prismaLocal = imageProject("prisma-local", imageName = "prisma")
  .settings(
    libraryDependencies ++= slick ++ Seq(postgresClient, mariaDbClient)
  )
  .dependsOn(graphQlClient)
  .dependsOn(prismaConfig)
  .dependsOn(allConnectorProjects)

lazy val prismaLocalGraalVM = imageProject("prisma-local-graalvm", imageName = "prisma", baseImage = "prismagraphql/runtime-base", tag = "graalvm")
  .dependsOn(prismaLocal)


lazy val prismaProd = imageProject("prisma-prod", imageName = "prisma-prod")
  .settings(
    libraryDependencies ++= slick ++ Seq(postgresClient, mariaDbClient)
  )
  .dependsOn(graphQlClient)
  .dependsOn(prismaConfig)
  .dependsOn(allConnectorProjects)

lazy val prismaProdGraalVM = imageProject("prisma-prod-graalvm", imageName = "prisma-prod", baseImage = "prismagraphql/runtime-base", tag = "graalvm")
  .dependsOn(prismaProd)

lazy val prismaNative = imageProject("prisma-native", "prisma-native")
  .settings(
    libraryDependencies ++= Seq(registry, checks)
  )
  .dependsOn(api)
  .dependsOn(deploy)
  .dependsOn(subscriptions)
  .dependsOn(workers)
  .dependsOn(graphQlClient)
  .dependsOn(prismaConfig)
  .dependsOn(allConnectorProjects)
  .dependsOn(jdbcNative)
  .enablePlugins(PrismaGraalPlugin)
  .settings(
    nativeImageOptions ++= Seq(
      "--enable-all-security-services",
      s"-H:CLibraryPath=${absolute("libs/jdbc-native/src/main/resources")}",
      s"-H:CLibraryPath=${absolute("libs/jwt-native/src/main/resources")}",
      "--rerun-class-initialization-at-runtime=javax.net.ssl.SSLContext,java.sql.DriverManager,com.prisma.native_jdbc.CustomJdbcDriver,com.zaxxer.hikari.pool.HikariPool,com.prisma.logging.PrismaLogger$LogLevel",
      "-H:IncludeResources=playground.html|.*/.*.h$|org/joda/time/tz/data/.*|reference\\.conf,version\\.conf\\|public_suffix_trie\\\\.json|application\\.conf|resources/application\\.conf",
      s"-H:ReflectionConfigurationFiles=${absolute("images/prisma-native/reflection_config.json")}",
      "--verbose",
      "--no-server"
    ),
    unmanagedJars in Compile += file(sys.env("GRAAL_HOME") + "/jre/lib/svm/builder/svm.jar"),
    mappings in (Compile, packageBin) ~= { _.filter { case (_, path) =>
      val exclude = path.contains("mariadb") || path.contains("org.postgresql") || path.contains("micrometer") || path.contains("org.LatencyUtils") || path.contains("io.prometheus")
      if (exclude) {
        println(s"Excluded Mapping: $path")
      }

      !exclude
    }},
    excludeJars := Seq("org/latencyutils", "io/prometheus", "org\\latencyutils", "io\\prometheus")
  )

def absolute(relativePathToProjectRoot: String) = {
  s"${System.getProperty("user.dir")}/${relativePathToProjectRoot.stripPrefix("/")}"
}

lazy val prismaImageShared = imageProject("prisma-image-shared")
  .dependsOn(api)
  .dependsOn(deploy)
  .dependsOn(workers)
  .dependsOn(subscriptions)
  .dependsOn(sangriaServer)


// ###################
//       SERVERS
// ###################

lazy val deploy = serverProject("deploy")
  .dependsOn(serversShared % "compile->compile;test->test")
  .dependsOn(deployConnector)
  .dependsOn(akkaUtils)
  .dependsOn(metrics)
  .dependsOn(messageBus)
  .dependsOn(graphQlClient)
  .dependsOn(sangriaUtils)
  .dependsOn(jwtNative)
  .dependsOn(cache)
  .settings( // Drivers used for testing
    libraryDependencies ++= slick ++ Seq(postgresClient % "test", mariaDbClient % "test")
  )

lazy val api = serverProject("api")
  .dependsOn(serversShared % "compile->compile;test->test")
  .dependsOn(deploy % "test->test")
  .dependsOn(apiConnector)
  .dependsOn(messageBus)
  .dependsOn(akkaUtils)
  .dependsOn(metrics)
  .dependsOn(cache)
  .dependsOn(jwtNative)
  .dependsOn(sangriaUtils)
  .settings( // Drivers used for testing
    libraryDependencies ++= slick ++ Seq(postgresClient % "test", mariaDbClient % "test")
  )

lazy val subscriptions = serverProject("subscriptions")
  .dependsOn(serversShared % "compile->compile;test->test")
  .dependsOn(api % "compile->compile;test->test")
  .dependsOn(stubServer % "test->test")
  .settings(
    libraryDependencies ++= Seq(playStreams)
  )

lazy val workers = serverProject("workers")
  .dependsOn(stubServer % "test->test")
  .dependsOn(errorReporting)
  .dependsOn(messageBus)
  .dependsOn(scalaUtils)

lazy val serversShared = serverProject("servers-shared")
  .dependsOn(connectorUtils % "test->test")
  .dependsOn(sangriaServer)


// ######################
//       CONNECTORS
// ######################

lazy val connectorUtils = connectorProject("utils")
  .dependsOn(deployConnectorProjects)
  .dependsOn(apiConnectorProjects)
  .dependsOn(jdbcNative)

lazy val connectorShared = connectorProject("shared")
  .settings(
    libraryDependencies ++= slick ++ jooq ++ joda
  )

lazy val deployConnector = connectorProject("deploy-connector")
  .dependsOn(sharedModels)
  .dependsOn(metrics)

lazy val deployConnectorJdbc = connectorProject("deploy-connector-jdbc")
  .dependsOn(deployConnector)
  .dependsOn(connectorShared)

lazy val deployConnectorMySql = connectorProject("deploy-connector-mysql")
  .dependsOn(deployConnectorJdbc)

lazy val deployConnectorPostgres = connectorProject("deploy-connector-postgres")
  .dependsOn(deployConnectorJdbc)

lazy val deployConnectorMongo = connectorProject("deploy-connector-mongo")
  .dependsOn(deployConnector)
  .dependsOn(mongoUtils)
  .settings(
    libraryDependencies ++= Seq(mongoClient),
    scalacOptions := {
      val oldOptions = scalacOptions.value
      oldOptions.filterNot(_ == "-Xfatal-warnings")
    })

lazy val apiConnector = connectorProject("api-connector")
  .dependsOn(sharedModels)
  .dependsOn(gcValues)
  .settings(
    libraryDependencies ++= Seq(apacheCommons)
  )

lazy val apiConnectorJdbc = connectorProject("api-connector-jdbc")
  .dependsOn(apiConnector)
  .dependsOn(metrics)
  .dependsOn(slickUtils)
  .dependsOn(connectorShared)

lazy val apiConnectorMySql = connectorProject("api-connector-mysql")
  .dependsOn(apiConnectorJdbc)

lazy val apiConnectorPostgres = connectorProject("api-connector-postgres")
  .dependsOn(apiConnectorJdbc)

lazy val apiConnectorMongo = connectorProject("api-connector-mongo")
  .dependsOn(apiConnector)
  .settings(libraryDependencies ++= Seq(mongoClient),
    scalacOptions := {
      val oldOptions = scalacOptions.value
      oldOptions.filterNot(_ == "-Xfatal-warnings")
    })



// ##################
//       SHARED
// ##################

lazy val sharedModels = normalProject("shared-models")
  .dependsOn(gcValues)
  .dependsOn(jsonUtils)
  .dependsOn(scalaUtils)
  .settings(
  libraryDependencies ++= Seq(
    cuid
  ) ++ joda
)


// #####################
//   INTEGRATION TESTS
// #####################

lazy val integrationTestsMySql = integrationTestProject("integration-tests-mysql")
  .dependsOn(deploy % "compile->compile;test->test")
  .dependsOn(api % "compile->compile;test->test")


// ################
//       LIBS
// ################

lazy val tracing = libProject("tracing")
lazy val logging = libProject("logging").settings(libraryDependencies ++= Seq(scalaLogging))
lazy val scalaUtils = libProject("scala-utils")
lazy val slickUtils = libProject("slick-utils").settings(libraryDependencies ++= slick)
lazy val prismaConfig = libProject("prisma-config").settings(libraryDependencies ++= Seq(snakeYML, scalaUri))
lazy val mongoUtils = libProject("mongo-utils").settings(libraryDependencies ++= Seq(mongoClient)).dependsOn(jsonUtils)

lazy val jdbcNative = libProject("jdbc-native")
  .settings(libraryDependencies ++= Seq(
    jna,
    scalaTest,
    playJson
  ), // todo skip compile if no native image / graal present
  unmanagedJars in Compile += file(sys.env("GRAAL_HOME") + "/jre/lib/boot/graal-sdk.jar"))

lazy val jwtNative = libProject("jwt-native")
  .settings(libraryDependencies ++= Seq(
    jna,
    scalaTest,
    playJson
  ) ++ jooq, // todo skip compile if no native image / graal present
  unmanagedJars in Compile += file(sys.env("GRAAL_HOME") + "/jre/lib/boot/graal-sdk.jar"))

lazy val gcValues = libProject("gc-values")
  .settings(libraryDependencies ++= Seq(
    playJson,
    cuid,
  ) ++ joda)

lazy val akkaUtils = libProject("akka-utils")
  .dependsOn(stubServer % "test->test")
  .dependsOn(errorReporting)
  .dependsOn(scalaUtils)
  .settings(libraryDependencies ++= Seq(
    akkaStream,
    akkaHttp,
    akkaTestKit,
    akkaHttpCors,
    playJson,
    specs2,
    caffeine
  ))
  .settings(
    scalacOptions := {
      val oldOptions = scalacOptions.value
      oldOptions.filterNot(_ == "-Xfatal-warnings")
    })

lazy val metrics = libProject("metrics")
  .dependsOn(errorReporting)
  .dependsOn(akkaUtils)
  .settings(
    libraryDependencies ++= Seq(
      microMeter,
    )
  )

lazy val rabbitProcessor = libProject("rabbit-processor")
  .settings(
    libraryDependencies ++= Seq(
      amqp
    ) ++ jackson
  )
  .dependsOn(errorReporting)

lazy val messageBus = libProject("message-bus")
  .dependsOn(errorReporting)
  .dependsOn(akkaUtils)
  .dependsOn(rabbitProcessor)
  .settings(libraryDependencies ++= Seq(
    akka,
    specs2,
    akkaTestKit,
    playJson
  ))

lazy val graphQlClient = libProject("graphql-client")
  .settings(commonSettings: _*)
  .settings(libraryDependencies ++= Seq(
    playJson,
    akkaStream
  ))
  .dependsOn(stubServer % "test->test")
  .dependsOn(akkaUtils)


lazy val stubServer = libProject("stub-server")
    .settings(
      libraryDependencies ++= Seq(
        jettyServer,
        scalaUri,
        parserCombinators,
        scalajHttp,
        specs2
      )
    )

lazy val errorReporting = libProject("error-reporting")
    .settings(libraryDependencies ++= Seq(
      bugsnagClient,
      playJson
    ))

lazy val sangriaUtils = libProject("sangria-utils")
    .dependsOn(errorReporting)
    .settings(libraryDependencies ++= Seq(
      akkaHttp,
      akkaStream
    ) ++ sangria)

lazy val jsonUtils = libProject("json-utils")
    .settings(libraryDependencies ++= Seq(
      playJson
    ) ++ joda)

lazy val cache = libProject("cache")
    .settings(libraryDependencies ++= Seq(
      caffeine,
      jsr305
    ))


// #######################
//       AGGREGATORS
// #######################

lazy val sangriaServer = libProject("sangria-server")
  .dependsOn(sangriaUtils)
  .dependsOn(scalaUtils)
  .settings(libraryDependencies ++= Seq(
    akkaHttpPlayJson,
    cuid,
    scalajHttp % Test,
    akkaHttpCors
  ) ++ http4s ++ ujson,
    unmanagedJars in Compile += file(sys.env("GRAAL_HOME") + "/jre/lib/svm/builder/svm.jar")) // todo remove (debug)

val allDockerImageProjects = List(
  prismaNative,
  prismaLocal,
  prismaLocalGraalVM,
  prismaProd,
  prismaProdGraalVM
)

val allServerProjects = List(
  api,
  deploy,
  subscriptions,
  workers,
  serversShared,
  sharedModels
)

lazy val deployConnectorProjects = List(
  deployConnector,
  deployConnectorJdbc,
  deployConnectorMySql,
  deployConnectorPostgres,
  deployConnectorMongo
)

lazy val apiConnectorProjects = List(
  apiConnector,
  apiConnectorJdbc,
  apiConnectorMySql,
  apiConnectorPostgres,
  apiConnectorMongo
)

lazy val allConnectorProjects = deployConnectorProjects ++ apiConnectorProjects ++ Seq(connectorUtils, connectorShared)

val allLibProjects = List(
  akkaUtils,
  metrics,
  rabbitProcessor,
  messageBus,
  graphQlClient,
  stubServer,
  scalaUtils,
  jsonUtils,
  cache,
  errorReporting,
  sangriaUtils,
  prismaConfig,
  mongoUtils,
  jdbcNative,
  jwtNative,
  logging
)

val allIntegrationTestProjects = List(
  integrationTestsMySql
)

lazy val images = (project in file("images")).dependsOn(allDockerImageProjects)
lazy val servers = (project in file("servers")).dependsOn(allServerProjects)
lazy val connectors = (project in file("connectors")).dependsOn(allConnectorProjects)
lazy val integrationTests = (project in file("integration-tests")).dependsOn(allIntegrationTestProjects)
lazy val libs = (project in file("libs")).dependsOn(allLibProjects)

lazy val root = (project in file("."))
  .aggregate((allServerProjects ++ allDockerImageProjects ++ allConnectorProjects ++ allIntegrationTestProjects).map(Project.projectToRef): _*)
  .settings(
    publish := { } // do not publish a JAR for the root project
  )
