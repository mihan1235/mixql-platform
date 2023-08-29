addSbtPlugin("com.thesamet"        % "sbt-protoc"          % "1.0.6")
addSbtPlugin("org.scalameta"       % "sbt-scalafmt"        % "2.4.6")
addSbtPlugin("org.xerial.sbt"      % "sbt-sonatype"        % "3.9.15")
addSbtPlugin("com.github.sbt"      % "sbt-pgp"             % "2.1.2")
addSbtPlugin("org.jetbrains.scala" % "sbt-ide-settings"    % "1.1.1")
addSbtPlugin("org.scoverage"       % "sbt-scoverage"       % "2.0.8")
addSbtPlugin("com.github.sbt"      % "sbt-native-packager" % "1.9.11")

libraryDependencies ++= Seq(
  "org.apache.commons" % "commons-compress" % "1.21",
  "commons-io"         % "commons-io"       % "2.11.0"
)
