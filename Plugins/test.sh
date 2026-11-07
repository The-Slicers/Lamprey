#!/bin/sh
# Builds Plugins/lamprey-plugins.jar and runs the Java plugin tests.
#     sh Plugins/test.sh
set -e
cd "$(dirname "$0")"
sh build.sh
rm -rf test-classes
mkdir test-classes
javac -cp lamprey-plugins.jar -d test-classes test/PluginTests.java
status=0
java -cp lamprey-plugins.jar:test-classes PluginTests || status=$?
rm -rf test-classes
exit $status
