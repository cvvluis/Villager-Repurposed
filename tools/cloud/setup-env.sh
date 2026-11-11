#!/usr/bin/env bash
# Environment setup for a Linux cloud VM (Ubuntu 24.04): JDK 25 + Gradle properties. Keep it short (cloud setup scripts
# should finish in about 5 minutes). It does NOT accept the Minecraft EULA: write run/eula.txt yourself after reading
# https://aka.ms/MinecraftEULA (see tools/cloud/README.md).
# Network: needs download.oracle.com (or api.adoptium.net + github.com), services.gradle.org, plugins.gradle.org,
# repo1.maven.org, maven.fabricmc.net, api.modrinth.com, cdn.modrinth.com, libraries.minecraft.net,
# piston-meta.mojang.com, piston-data.mojang.com, launchermeta.mojang.com, resources.download.minecraft.net
set -uo pipefail
JDK_DIR=/opt/jdk25

if [[ ! -x "$JDK_DIR/bin/java" ]] || ! "$JDK_DIR/bin/java" -version 2>&1 | grep -q '"25'; then
  mkdir -p "$JDK_DIR"
  tmp="$(mktemp)"
  if curl -fsSL --retry 3 "https://download.oracle.com/java/25/latest/jdk-25_linux-x64_bin.tar.gz" -o "$tmp" \
     || curl -fsSL --retry 3 -L "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse" -o "$tmp"; then
    tar -xzf "$tmp" -C "$JDK_DIR" --strip-components=1
  else
    echo "WARN: could not download JDK 25 (check the allowed domains)" >&2
  fi
  rm -f "$tmp"
fi

mkdir -p "$HOME/.gradle"
{
  echo "org.gradle.java.home=$JDK_DIR"
  echo "org.gradle.jvmargs=-Xmx3G"
} > "$HOME/.gradle/gradle.properties"
for f in /etc/profile.d/jdk25.sh; do
  printf 'export JAVA_HOME=%s\nexport PATH=$JAVA_HOME/bin:$PATH\n' "$JDK_DIR" > "$f" 2>/dev/null || true
done
grep -q "JAVA_HOME=$JDK_DIR" "$HOME/.bashrc" 2>/dev/null || printf 'export JAVA_HOME=%s\nexport PATH=$JAVA_HOME/bin:$PATH\n' "$JDK_DIR" >> "$HOME/.bashrc"

"$JDK_DIR/bin/java" -version 2>&1 | head -n 1
command -v python3 >/dev/null && python3 --version || echo "WARN: python3 missing (tools/rcon.py needs it)"
chmod +x tools/cloud/*.sh gradlew 2>/dev/null || true
exit 0
