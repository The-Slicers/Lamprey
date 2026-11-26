#!/bin/sh
# Rebuilds Plugins/lamprey-plugins.jar from Plugins/src (Java 8 bytecode, so
# it runs on whatever Java the Quorum runtime ships with). Run from anywhere:
#     sh Plugins/build.sh
set -e
cd "$(dirname "$0")"
# VoronoiJava compiles against the JTS Topology Suite (see README, "Adaptive walls")
JTS=jts-core-1.20.0.jar
if [ ! -f "$JTS" ]; then
    echo "Plugins/$JTS is missing: download it from" 1>&2
    echo "  https://repo1.maven.org/maven2/org/locationtech/jts/jts-core/1.20.0/jts-core-1.20.0.jar" 1>&2
    exit 1
fi
rm -rf classes stub-classes
mkdir classes stub-classes
# stubs/ stands in for the Quorum runtime classes BitArrayFill uses; they are
# compiled only to compile against, and are not packaged (see stubs/README.md)
javac --release 8 -d stub-classes $(find stubs -name '*.java')
javac --release 8 -cp "$JTS:stub-classes" -d classes $(find src -name '*.java')
jar --create --file lamprey-plugins.jar -C classes .
rm -rf classes stub-classes
echo "Built $(pwd)/lamprey-plugins.jar"
