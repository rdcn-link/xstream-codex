name := "xstream-item-sources"
organization := "io.github.rdcn-link"
version := "1.0"
scalaVersion := "2.13.16"
libraryDependencies ++= Seq("io.github.rdcn-link" %% "xstream-operator-sdk" % "0.1.0-SNAPSHOT")
libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test
Test / fork := true
Test / javaOptions ++= Seq("--add-opens=java.base/java.nio=ALL-UNNAMED", "-Dio.netty.tryReflectionSetAccessible=true")
