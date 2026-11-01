name := "xstream-operator-sdk"
organization := "io.github.rdcn-link"
version := "0.1.0-SNAPSHOT"
scalaVersion := "2.13.16"
libraryDependencies += "io.github.rdcn-link" %% "xstream-execution-api" % "0.1.0-SNAPSHOT"
libraryDependencies ++= Seq(
  "org.apache.arrow" % "arrow-memory-netty" % "18.3.0",
  "org.scalatest" %% "scalatest" % "3.2.19" % Test)
Test / fork := true
Test / javaOptions ++= Seq("--add-opens=java.base/java.nio=ALL-UNNAMED", "-Dio.netty.tryReflectionSetAccessible=true")

lazy val dataCore = RootProject(file("../../xstream-data/xstream-data-core"))
dependsOn(dataCore)
