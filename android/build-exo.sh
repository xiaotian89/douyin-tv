#!/bin/bash
# ExoPlayer(Media3) 版构建：javac/d8 都挂上 exowork/jars 里的 jar
set -e
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
export ANDROID_HOME=/home/nieadmin/android
BT=$ANDROID_HOME/android-14
PLATFORM=$ANDROID_HOME/android-9/android.jar
PROJ=/vol1/1000/docker/compose/openclaw/data/workspace/projects/douyin-tv
JARS=/vol1/1000/docker/compose/openclaw/exowork/jars
NECESSARY="media3-exoplayer media3-exoplayer-hls media3-common media3-container media3-datasource media3-decoder media3-extractor media3-database annotation collection guava core exifinterface"
JL=""
for n in $NECESSARY; do JL="$JL $JARS/$n.jar"; done
OUT=$PROJ/build
rm -rf $OUT && mkdir -p $OUT/compiled $OUT/gen $OUT/classes $OUT/dex

echo "[1/6] aapt2"
$BT/aapt2 compile --dir $PROJ/res -o $OUT/compiled/res.zip
$BT/aapt2 link -o $OUT/app-unsigned.apk -I $PLATFORM \
  --manifest $PROJ/AndroidManifest.xml --java $OUT/gen \
  --min-sdk-version 21 --target-sdk-version 28 $OUT/compiled/res.zip

echo "[2/6] javac"
find $PROJ/src $OUT/gen -name "*.java" > $OUT/sources.txt
javac -source 8 -target 8 -encoding UTF-8 -bootclasspath $PLATFORM \
  -classpath "$PLATFORM:$(ls $JARS/*.jar | paste -sd:)" -d $OUT/classes @$OUT/sources.txt 2>&1 \
  | grep -v "bootstrap class path" | head -20 || true
[ -d $OUT/classes/com/clawton ] || { echo "!! 自己代码没编出来"; exit 1; }

echo "[3/6] d8（含 media3）"
find $OUT/classes -name "*.class" > $OUT/classes.txt
$BT/d8 --release --min-api 21 --lib $PLATFORM --output $OUT/dex \
  @$OUT/classes.txt $JL 2>&1 | grep -viE "^warning" | head -10 || true
ls -l $OUT/dex/ | awk '{print $9, $5}'

echo "[4/6] 打包 dex + 库"
python3 - "$OUT/app-unsigned.apk" "$OUT/dex" <<'PY'
import sys, zipfile, shutil, os, glob
apk, dexdir = sys.argv[1], sys.argv[2]
tmp = apk + ".tmp"
with zipfile.ZipFile(apk) as zin, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
    for it in zin.infolist():
        zout.writestr(it, zin.read(it.filename))
    for d in sorted(glob.glob(os.path.join(dexdir, "classes*.dex"))):
        zout.write(d, os.path.basename(d))
        print("  +", os.path.basename(d), os.path.getsize(d))
shutil.move(tmp, apk)
print("  apk MB:", round(os.path.getsize(apk)/1048576, 1))
PY

echo "[5/6] zipalign"
$BT/zipalign -f -p 4 $OUT/app-unsigned.apk $OUT/app-aligned.apk

echo "[6/6] 签名"
$BT/apksigner sign --ks $PROJ/keystore.jks --ks-pass pass:douyintv --key-pass pass:douyintv \
  --v1-signing-enabled true --v2-signing-enabled true --out $PROJ/DouyinTV-N1.apk $OUT/app-aligned.apk
$BT/aapt2 dump badging $PROJ/DouyinTV-N1.apk | head -1
