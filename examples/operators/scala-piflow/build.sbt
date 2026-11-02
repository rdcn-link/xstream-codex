name := "xstream-scala-piflow"
organization := "io.github.rdcn-link"
version := "2.0"
scalaVersion := "2.13.16"
lazy val sdk = RootProject(file("../../../xstream-operator-sdk/jvm"))
dependsOn(sdk)
libraryDependencies ++= Seq(
  "org.apache.commons" % "commons-csv" % "1.13.0",
  "org.xerial" % "sqlite-jdbc" % "3.49.1.0",
  "org.scalatest" %% "scalatest" % "3.2.19" % Test)
Test / fork := true
Test / javaOptions ++= Seq("--add-opens=java.base/java.nio=ALL-UNNAMED", "-Dio.netty.tryReflectionSetAccessible=true")
