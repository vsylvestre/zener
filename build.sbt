name := "zener"

version := "0.0.1"

organization := "com.vsylvestre"

scalaVersion := "2.12.6"

libraryDependencies += "io.monix" %% "monix" % "3.0.0-RC1"
libraryDependencies += "com.github.japgolly.scalajs-react" %%% "core" % "1.1.1"

enablePlugins(ScalaJSPlugin)
