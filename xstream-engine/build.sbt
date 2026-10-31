organization := "io.github.rdcn-link"
name := "xstream-engine"
version := "0.1.0-SNAPSHOT"
scalaVersion := "2.13.16"
libraryDependencies += "io.github.rdcn-link" %% "dacp-union-protocol" % "0.1.0-SNAPSHOT"
lazy val XstreamOperatorInference = RootProject(file("../xstream-operator-inference"))
dependsOn(XstreamOperatorInference)
libraryDependencies += "io.github.rdcn-link" %% "dacp-plugin-api" % "0.1.0-SNAPSHOT"
libraryDependencies ++= Seq("com.github.docker-java" % "docker-java-core" % "3.4.1", "com.github.docker-java" % "docker-java-transport-httpclient5" % "3.4.1", "org.scalatest" %% "scalatest" % "3.2.19" % Test)
Test / fork := true
Test / javaOptions ++= Seq("--add-opens=java.base/java.nio=ALL-UNNAMED", "-Dio.netty.tryReflectionSetAccessible=true")
libraryDependencies ++= Seq("org.apache.arrow" % "arrow-memory-netty" % "18.3.0")
lazy val testDatasets = RootProject(file("../xstream-data/xstream-data-files"))
lazy val testStatic = RootProject(file("../xstream-data/xstream-data-catalog-static"))
lazy val testFormats = RootProject(file("../xstream-data/xstream-data-formats-basic"))
dependsOn(testDatasets % "test->compile", testStatic % "test->compile", testFormats % "test->compile")

libraryDependencies += "io.github.rdcn-link" %% "dacp-plugin-execution-file" % "0.1.0-SNAPSHOT" % "test"

enablePlugins(JavaAppPackaging)
Compile / mainClass := Some("dacp.xstream.Main")
executableScriptName := "xstream-runner"
Universal / javaOptions ++= Seq("-J--add-opens=java.base/java.nio=ALL-UNNAMED", "-J-Dio.netty.tryReflectionSetAccessible=true")
libraryDependencies += "io.github.rdcn-link" %% "dacp-client-scala" % "0.1.0"

lazy val bundledDatasets = RootProject(file("../xstream-data/xstream-data-files"))
lazy val bundledCatalog = RootProject(file("../xstream-data/xstream-data-catalog-static"))
lazy val bundledFormats = RootProject(file("../xstream-data/xstream-data-formats-basic"))
dependsOn(bundledDatasets % "runtime->compile", bundledCatalog % "runtime->compile", bundledFormats % "runtime->compile")
lazy val XstreamPluginOperatorExecutors = RootProject(file("../plugins/xstream-plugin-operator-executors"))
dependsOn(XstreamPluginOperatorExecutors % "runtime->compile")
libraryDependencies += "io.github.rdcn-link" %% "dacp-plugin-execution-file" % "0.1.0-SNAPSHOT" % "runtime"

lazy val dataScientificcodecs = RootProject(file("../xstream-data/xstream-data-scientific-views"))
dependsOn(dataScientificcodecs % "runtime->compile;test->compile")

lazy val dataNetcdf = RootProject(file("../xstream-data/xstream-data-netcdf"))
dependsOn(dataNetcdf % "runtime->compile;test->compile")

lazy val dataFits = RootProject(file("../xstream-data/xstream-data-fits"))
dependsOn(dataFits % "runtime->compile;test->compile")

lazy val dataFasta = RootProject(file("../xstream-data/xstream-data-fasta"))
dependsOn(dataFasta % "runtime->compile;test->compile")


lazy val dataCore = RootProject(file("../xstream-data/xstream-data-core"))
dependsOn(dataCore)
