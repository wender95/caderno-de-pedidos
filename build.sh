#!/usr/bin/env bash
# Gera o APK sem Gradle: aapt2 (recursos) -> javac -> d8 (dex) -> zipalign -> apksigner.
# Uso: ./build.sh   (aumente VERSAO_CODIGO a cada nova versão para instalar por cima)
set -euo pipefail
VERSAO_CODIGO=8
VERSAO_NOME="2.3"

cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-/c/Users/Wender/Android/sdk}"
BT="$SDK/build-tools/36.1.0"
JAR="$SDK/platforms/android-34/android.jar"
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot}"
OUT=build

rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$BT/aapt2.exe" compile --dir app/res -o "$OUT/res.zip"
"$BT/aapt2.exe" link -o "$OUT/base.apk" -I "$JAR" \
  --manifest app/AndroidManifest.xml -R "$OUT/res.zip" -A app/assets \
  --java "$OUT/gen" --auto-add-overlay \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code $VERSAO_CODIGO --version-name $VERSAO_NOME

javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath "$JAR" -classpath "$BT/core-lambda-stubs.jar" \
  -d "$OUT/classes" $(find "$OUT/gen" app/src -name '*.java')

"$BT/d8.bat" --lib "$JAR" --min-api 26 --release --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

(cd "$OUT/dex" && "$JAVA_HOME/bin/jar" uf ../base.apk classes.dex)
"$BT/zipalign.exe" -p -f 4 "$OUT/base.apk" "$OUT/alinhado.apk"

# Chave de assinatura: guarde pedidos.keystore e keystore.txt. Sem eles, versões novas não instalam por cima.
if [ ! -f pedidos.keystore ]; then
  SENHA=$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore pedidos.keystore -alias pedidos -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$SENHA" -keypass "$SENHA" -dname "CN=Caderno de Pedidos" >/dev/null 2>&1
  echo "$SENHA" > keystore.txt
fi
SENHA=$(cat keystore.txt)
"$BT/apksigner.bat" sign --ks pedidos.keystore --ks-key-alias pedidos --ks-pass "pass:$SENHA" \
  --out "Pedidos-v$VERSAO_NOME.apk" "$OUT/alinhado.apk"
"$BT/apksigner.bat" verify "Pedidos-v$VERSAO_NOME.apk"
ls -la "Pedidos-v$VERSAO_NOME.apk"
