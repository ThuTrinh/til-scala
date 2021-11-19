import sbt.Keys.libraryDependencies


name := "til-scala"
version := "0.1"
scalaVersion := "2.13.7"

libraryDependencies ++= Seq(
  "io.monix" %% "monix" % "3.1.0",
  "org.typelevel" %% "cats-core" % "2.1.0",

  // Test
  "org.scalatest" %% "scalatest" % "3.1.0" % "test"
)
