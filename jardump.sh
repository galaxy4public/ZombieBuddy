#!/bin/sh
ZB_JAR=`realpath ~/"projects/zomboid/versions/unstable/osx/Project Zomboid.app/Contents/Java/projectzomboid.jar"`
CP="java/build/jdk25/libs/ZombieBuddy.jar:${ZB_JAR}"

java -cp "$CP" me.zed_0xff.zombie_buddy.jardump.Main "$@"
