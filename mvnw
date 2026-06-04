#!/usr/bin/env bash
# POSIX shim for ./mvnw on Git Bash. The repo only ships mvnw.cmd, and
# Git Bash invokes it with JAVA_HOME inherited from the shell. JAVA_HOME
# is currently pinned to JDK 8 (corretto-1.8.0_322), and Spring Boot
# 3.3.4's spring-boot-maven-plugin is compiled for Java 17+ (class file
# 61.0), so Maven fails with:
#   UnsupportedClassVersionError: ... has been compiled by a more recent
#   version of the Java Runtime (class file version 61.0)
# To keep the fix local to this repo, we re-pin JAVA_HOME to a JDK >= 17
# (preferring JDK 21 to match <java.version>21</java.version> in pom.xml)
# before delegating to mvnw.cmd. Set MAVEN_JAVA_HOME in your shell to
# override.

set -e

if [ -n "${MAVEN_JAVA_HOME:-}" ]; then
  candidate="$MAVEN_JAVA_HOME"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java.exe" ]; then
  major=$( "${JAVA_HOME}/bin/java.exe" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/' )
  if [ -n "$major" ] && [ "$major" -ge 17 ] 2>/dev/null; then
    candidate="$JAVA_HOME"
  fi
fi

if [ -z "${candidate:-}" ]; then
  # Fall back to the JDK 21 already installed under the user's .jdks dir.
  candidate="C:\\Users\\nicol\\.jdks\\openjdk-21.0.1"
fi

if [ ! -x "$candidate/bin/java.exe" ]; then
  echo "mvnw shim: requested JDK not found at $candidate" >&2
  echo "Set MAVEN_JAVA_HOME to a JDK >= 17 and retry." >&2
  exit 1
fi

export JAVA_HOME="$candidate"
export PATH="$JAVA_HOME/bin:$PATH"

# Git Bash on Windows can exec .cmd scripts directly when given a .cmd
# extension; this avoids the backslash-quoting headache of `cmd //c`.
exec "$(dirname "$0")/mvnw.cmd" "$@"
