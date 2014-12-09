#!/bin/bash

mvn clean package -DskipTests
cp examples/target/updatefx-examples-1.1.jar examples/website/builds/1.jar
sed -i .bak 's/public static int VERSION = 1;/public static int VERSION = 2;/' examples/src/main/java/ExampleApp.java
mvn clean package -DskipTests
cp examples/target/updatefx-examples-1.1.jar examples/website/builds/2.jar
mv examples/src/main/java/ExampleApp.java.bak examples/src/main/java/ExampleApp.java
java -jar app/target/updatefx-app-1.1.jar --url=http://localhost:8000/ examples/website
rm -rf examples/deploy/*
$JAVA_HOME/bin/javapackager -deploy -outdir examples/deploy/ -outfile UFXExample.dmg -name UFXExample -native dmg -appclass ExampleApp -srcfiles examples/website/builds/1.jar