name := "xstream-data-gdal"
organization := "io.github.rdcn-link"
version := "0.1.0-SNAPSHOT"
scalaVersion := "2.13.16"
lazy val core = RootProject(file("../xstream-data-core"))
lazy val scientific = RootProject(file("../xstream-data-scientific-views"))
lazy val files = RootProject(file("../xstream-data-files"))
lazy val basic = RootProject(file("../xstream-data-formats-basic"))
lazy val process = RootProject(file("../xstream-data-reader-process"))
dependsOn(core, process, scientific % "test->compile", files % "test->compile", basic % "test->compile")
libraryDependencies ++= Seq("org.scalatest" %% "scalatest" % "3.2.19" % Test, "org.apache.arrow" % "arrow-memory-netty" % "18.3.0" % Test)
Test / fork := true
Test / javaOptions ++= Seq("--add-opens=java.base/java.nio=ALL-UNNAMED", "-Dio.netty.tryReflectionSetAccessible=true")
