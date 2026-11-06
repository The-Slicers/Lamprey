#!/bin/sh
# Rebuilds Plugins/lamprey-plugins.jar from Plugins/src (Java 8 bytecode, so
# it runs on whatever Java the Quorum runtime ships with). Run from anywhere:
#     sh Plugins/build.sh
set -e
cd "$(dirname "$0")"
rm -rf classes
mkdir classes
javac --release 8 -d classes $(find src -name '*.java')
jar --create --file lamprey-plugins.jar -C classes .
rm -rf classes
echo "Built $(pwd)/lamprey-plugins.jar"
