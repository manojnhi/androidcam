PASSPORT CAMERA (Android app)

Ye app phone ke camera se photo khinchkar PC ke Passport Photo tool tak bhejti hai.
PC par phone-bridge.ps1 (bridge) chalu hona chahiye. App PC ko apne aap dhoondh leti hai.

APK BANANE KA SABSE AASAN TARIKA (GitHub, free):
1. github.com par free account banayein -> New repository (naam: passport-camera, Public ya Private).
2. "uploading an existing file" par click karke is folder ke ANDAR ki saari files/folders upload karein
   (.github folder ke saath). Commit karein.
   Agar .github folder upload na ho, to "Add file -> Create new file" mein naam likhein:
   .github/workflows/build.yml  aur build.yml ka text paste karein.
3. Repository mein "Actions" tab kholein -> "Build APK" -> kuch minute mein hara tick aayega.
4. Us run par click karein -> neeche "Artifacts" mein "Passport-Camera-APK" download karein (zip), khol kar app-debug.apk nikalein.
5. APK phone mein bhejein, kholein, "Install unknown apps" allow karein, install karein.

ANDROID STUDIO SE: folder open karein, Build -> Build APK(s). (Wrapper nahi hai, Studio khud Gradle maangega.)

PEHLI BAAR JODNA: PC par tool mein Mobile icon dabayein (2 minute ka pairing khulta hai), phir phone par app kholein.
Uske baad hamesha apne aap jud jayegi.
