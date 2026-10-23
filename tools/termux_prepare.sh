#!/data/data/com.termux/files/usr/bin/bash
# Prepares Horizon's game files on the phone, in Termux. Needs your own copy of Minecraft Java Edition.
# Run from the repository folder:  bash tools/termux_prepare.sh
set -e
TOOLS="$(cd "$(dirname "$0")" && pwd)"
MC="$HOME/.minecraft"
SRC="$HOME/horizon"

pkg install -y python git openjdk-25 rust zip unzip
[ -d "$SRC" ] || git clone --depth 1 https://github.com/ludovic111/horizon "$SRC"
[ -d "$HOME/storage/downloads" ] || termux-setup-storage

[ -d "$PREFIX/lib/jvm/java-25-openjdk" ] && export JAVA_HOME="$PREFIX/lib/jvm/java-25-openjdk"
javac -version

VERSION=$(python3 -c "import json;print(json.load(open('$SRC/research-lock.json'))['minecraft']['version'])")
python3 "$TOOLS/fetch_minecraft.py" "$SRC/research-lock.json" "$MC"

cd "$SRC"
cargo build --release -p horizon-prepare
./target/release/horizon-prepare --prepare --server-jar "$MC/server-$VERSION.jar" --minecraft-dir "$MC" --root "$SRC"

OUT="$HOME/storage/downloads/horizon-$VERSION-files.zip"
rm -f "$OUT"
(cd "$SRC/.local/horizon-client" && zip -qr "$OUT" "$VERSION")
echo "Готово: $OUT. Импортируй его в меню приложения."
