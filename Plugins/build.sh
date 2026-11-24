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
rm -rf classes
mkdir classes
javac --release 8 -cp "$JTS" -d classes $(find src -name '*.java')
jar --create --file lamprey-plugins.jar -C classes .
rm -rf classes
echo "Built $(pwd)/lamprey-plugins.jar"
