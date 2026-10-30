organization := "io.github.rdcn-link"
version := "0.1.0-SNAPSHOT"
name := "xstream-plugin-operator-executors"
scalaVersion := "2.13.16"
libraryDependencies += "io.github.rdcn-link" %% "dacp-plugin-api" % "0.1.0-SNAPSHOT"
libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test

lazy val operatorSdk = RootProject(file("../../xstream-operator-sdk/jvm"))
dependsOn(operatorSdk)
Test / fork := true
Test / javaOptions ++= Seq("--add-opens=java.base/java.nio=ALL-UNNAMED", "-Dio.netty.tryReflectionSetAccessible=true")

Compile / resourceGenerators += Def.task {
  Seq("__init__.py" -> "group-sdk.py", "inference.py" -> "inference.py").map { case (file, name) =>
    val source = baseDirectory.value / "../../xstream-operator-sdk/python/xstream_operator" / file
    val destination = (Compile / resourceManaged).value / name
    IO.copyFile(source, destination)
    destination
  }
}.taskValue

lazy val dataCore = RootProject(file("../../xstream-data/xstream-data-core"))
dependsOn(dataCore)
