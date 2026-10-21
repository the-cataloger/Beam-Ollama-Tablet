from pathlib import Path
import shutil

ROOT=Path("alwarraqa")
KOTLIN=ROOT/"app/src/main/java/com/alwarraqa/app"
RES=ROOT/"app/src/main/res"
shutil.copy("upgrade/QoyoodActivity.kt", KOTLIN/"QoyoodActivity.kt")
shutil.copy("upgrade/QoyoodBackend.kt", KOTLIN/"QoyoodBackend.kt")
for name in ["QoyoodMarkdown.kt"]:
    if (Path("upgrade")/name).exists():
        shutil.copy(Path("upgrade")/name,KOTLIN/name)

gradle=ROOT/"app/build.gradle.kts"
data=gradle.read_text()
data=data.replace('applicationId = "com.alwarraqa.app"', 'applicationId = "com.alwarraqa.qoyood"')
data=data.replace("versionCode = 1","versionCode = 2").replace('versionName = "1.0.0"','versionName = "2.0.0"')
gradle.write_text(data)

manifest=ROOT/"app/src/main/AndroidManifest.xml"
data=manifest.read_text()
data=data.replace("<application",'<uses-permission android:name="android.permission.INTERNET"/>\n    <application')
data=data.replace('android:label="الورّاقة"','android:label="محلل القيود" android:icon="@drawable/ic_qoyood"')
data=data.replace('android:name=".MainActivity"','android:name=".QoyoodActivity"')
manifest.write_text(data)

reg=KOTLIN/"RegionImageView.kt"
data=reg.read_text()
anchor="    private fun localX(screenX:Float)"
assert anchor in data
data=data.replace(anchor,'''    fun zoomBy(factor: Float) {
        val old=scale()
        zoom=(zoom*factor).coerceIn(1f,8f)
        val ratio=scale()/old
        left=width/2f-(width/2f-left)*ratio
        top=height/2f-(height/2f-top)*ratio
        invalidate()
    }
    fun resetView(){zoom=1f;fit()}
'''+anchor)
reg.write_text(data)

backend=KOTLIN/"QoyoodBackend.kt"
data=backend.read_text().replace('error("OpenRouter HTTP "+code+": "+error.take(450))','kotlin.error("OpenRouter HTTP "+code+": "+error.take(450))')
backend.write_text(data)

store=KOTLIN/"Store.kt"
data=store.read_text()
anchor='''            ps.forEachIndexed { i,p ->
                z.putNextEntry(ZipEntry("images/$i.img"));'''
assert anchor in data
data=data.replace(anchor,'''            val study = ResearchDocs(ctx).read(project.id)
            if (study.isNotBlank()) {
                z.putNextEntry(ZipEntry("research.md"))
                z.write(study.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
'''+anchor)
assert 'var manifest: JSONObject?=null' in data
data=data.replace('var manifest: JSONObject?=null','var manifest: JSONObject?=null\n            var researchText=""')
anchor='''                        } else if (Regex("images/[0-9]+\\\\.img").matches(entry.name)) {'''
assert anchor in data, anchor
data=data.replace(anchor,'''                        } else if (entry.name == "research.md") {
                            require(entry.size <= 2_000_000 || entry.size < 0) { "ملف البحث كبير" }
                            researchText=zip.readBytes().also { require(it.size<=2_000_000) }.toString(Charsets.UTF_8)
'''+anchor)
data=data.replace('''                db.setTransactionSuccessful()
                return projectId''','''                db.setTransactionSuccessful()
                if(researchText.isNotBlank())ResearchDocs(ctx).save(projectId,researchText)
                return projectId''')
store.write_text(data)

(RES/"drawable").mkdir(parents=True,exist_ok=True)
(RES/"drawable/ic_qoyood.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
  <path android:fillColor="#173C47" android:pathData="M0,0h108v108h-108z"/>
  <path android:fillColor="#CF9957" android:pathData="M25,14 L71,14 Q76,14 76,19 L76,89 Q76,94 71,94 L25,94 Q20,94 20,89 L20,19 Q20,14 25,14z"/>
  <path android:fillColor="#FFF7E7" android:pathData="M29,20 L65,20 Q70,20 70,25 L70,83 Q70,88 65,88 L29,88 Q25,88 25,84 L25,25 Q25,20 29,20z"/>
  <path android:fillColor="#B07A3D" android:pathData="M34,37h26v3h-26z M34,45h26v3h-26z M34,53h20v3h-20z M34,61h16v3h-16z"/>
  <path android:fillColor="#1B6068" android:pathData="M72,80L89,27 94,23 95,30 78,83z"/>
  <path android:fillColor="#F8E0B2" android:pathData="M89,27L94,23 95,30 92,35z"/>
  <path android:fillColor="#132D37" android:pathData="M70,86L77,83 72,79z"/>
</vector>
''',encoding="utf-8")
(ROOT/"README-QOYOOD.md").write_text('''# محلل القيود – Android 2.0
تطبيق أصلي بـ Kotlin / Jetpack Compose، منفصل عن الورّاقة في الحزمة (com.alwarraqa.qoyood).
- حفظ صور المخطوطات ومناطق الشواهد في SQLite.
- أدوات تكبير وتحريك وإظهار/إخفاء العلامات، بحث وتصنيف.
- محرر بحث يدعم Markdown مع حفظ تلقائي، تراجع وإعادة، بحث واستبدال وتصدير.
- تحليل بصري بالصور عبر OpenRouter API بعد ضغط صريح من المستخدم.
- المفتاح محمي بواسطة Android Keystore AES-GCM؛ لا يُرفع للسجل أو النسخ الاحتياطي.
- تقرير PDF من الشواهد، ونسخة ZIP تتضمن صور المشروع والشواهد ونص البحث.
**خصوصية:** ترسل أداة التحليل الصورة/الاقتطاع إلى طرف خارجي؛ تأكد من حقك في مشاركتها.
**تنبيه:** النص الناتج عن النموذج يحتاج مراجعة يدوية علمية.
''',encoding="utf-8")
print("Applied native Qoyood upgrade to",ROOT)
