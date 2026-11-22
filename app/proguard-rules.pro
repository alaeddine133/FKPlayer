# FK PLAYER — règles R8
# Les bibliothèques (Media3, OkHttp, Coil, ZXing) fournissent leurs propres règles.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class com.fk.player.RatioImageView { *; }
