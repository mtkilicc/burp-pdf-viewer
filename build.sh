#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"
MONTOYA_VERSION="2026.7"
PDFBOX_VERSION="3.0.8"
PDFBOX_SHA512="768847238f683568507bf73570a2b6fedcbe58b25c7b4f97fba536ba110b290fe96ba065aed58629d41fb94857d76bc1978c2f31d294b553c69f287f71ee9600"
DEPENDENCY_DIR="$PROJECT_DIR/.deps"
BUILD_DIR="$PROJECT_DIR/build"
DIST_DIR="$PROJECT_DIR/dist"
MONTOYA_JAR="$DEPENDENCY_DIR/montoya-api-$MONTOYA_VERSION.jar"
PDFBOX_APP_JAR="$DEPENDENCY_DIR/pdfbox-app-$PDFBOX_VERSION.jar"

mkdir -p "$DEPENDENCY_DIR"
download_dependency() (
    temporary=$(mktemp "$DEPENDENCY_DIR/.download.XXXXXXXX")
    trap 'rm -f "$temporary"' EXIT
    curl -fLsS --retry 2 -o "$temporary" "$1"
    mv "$temporary" "$2"
)

if [ ! -f "$MONTOYA_JAR" ]; then
    download_dependency "https://repo.maven.apache.org/maven2/net/portswigger/burp/extensions/montoya-api/$MONTOYA_VERSION/montoya-api-$MONTOYA_VERSION.jar" "$MONTOYA_JAR"
fi
if [ ! -f "$PDFBOX_APP_JAR" ]; then
    download_dependency "https://repo.maven.apache.org/maven2/org/apache/pdfbox/pdfbox-app/$PDFBOX_VERSION/pdfbox-app-$PDFBOX_VERSION.jar" "$PDFBOX_APP_JAR"
fi
printf '%s  %s\n' "$PDFBOX_SHA512" "$PDFBOX_APP_JAR" | shasum -a 512 -c

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/test-classes" "$BUILD_DIR/fat" "$DIST_DIR"
find src/main/java -name '*.java' -print > "$BUILD_DIR/main-sources.txt"
javac --release 17 -encoding UTF-8 -classpath "$MONTOYA_JAR:$PDFBOX_APP_JAR" \
    -d "$BUILD_DIR/classes" @"$BUILD_DIR/main-sources.txt"

cp -R "$PROJECT_DIR/src/main/resources/." "$BUILD_DIR/classes/"
find src/test/java -name '*.java' -print > "$BUILD_DIR/test-sources.txt"
javac --release 17 -encoding UTF-8 -classpath "$MONTOYA_JAR:$PDFBOX_APP_JAR:$BUILD_DIR/classes" \
    -d "$BUILD_DIR/test-classes" @"$BUILD_DIR/test-sources.txt"

(cd "$BUILD_DIR/fat" && jar -xf "$PDFBOX_APP_JAR")
rm -f "$BUILD_DIR/fat/META-INF/MANIFEST.MF" "$BUILD_DIR/fat/module-info.class"
find "$BUILD_DIR/fat/META-INF" -type f \( -name '*.SF' -o -name '*.RSA' -o -name '*.DSA' \) -delete
cp -R "$BUILD_DIR/classes/." "$BUILD_DIR/fat/"
jar --create --file "$BUILD_DIR/burp-pdf-viewer.jar" -C "$BUILD_DIR/fat" .
TEST_CLASSPATH="$BUILD_DIR/burp-pdf-viewer.jar:$BUILD_DIR/test-classes:$MONTOYA_JAR"
for test in PdfDataDecoderTest PdfRenderingTest; do
    java -ea -Djava.awt.headless=true -classpath "$TEST_CLASSPATH" "pdfviewer.$test"
done
mv "$BUILD_DIR/burp-pdf-viewer.jar" "$DIST_DIR/burp-pdf-viewer.jar"

echo "Built $DIST_DIR/burp-pdf-viewer.jar"
