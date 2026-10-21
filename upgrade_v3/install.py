from pathlib import Path
import shutil
ROOT=Path("alwarraqa")
K=ROOT/"app/src/main/java/com/alwarraqa/app"
for f,d in [("Workspace.kt","Workspace.kt"),("Research.kt","Research.kt"),("Report.kt","WitnessReport.kt")]:
    shutil.copyfile(Path("upgrade_v3")/f,K/d)
gradle=ROOT/"app/build.gradle.kts"
text=gradle.read_text()
text=text.replace('applicationId = "com.alwarraqa.qoyood"', 'applicationId = "com.alwarraqa.qoyood.research"')
text=text.replace('versionCode = 2','versionCode = 3').replace('versionName = "2.0.0"','versionName = "3.0.0"')
gradle.write_text(text)
manifest=ROOT/"app/src/main/AndroidManifest.xml"
text=manifest.read_text()
assert 'android:name=".QoyoodActivity"' in text
text=text.replace('android:name=".QoyoodActivity"','android:name=".ResearchWorkbenchActivity"')
manifest.write_text(text)
store=K/"Store.kt"
text=store.read_text()
a='.put("interpretation",n.interpretation).put("confidence",n.confidence)) }'
assert a in text
text=text.replace(a,'.put("interpretation",n.interpretation).put("confidence",n.confidence)'+
    '.put("study",WitnessDb(ctx).toJson(WitnessDb(ctx).get(n.id)))) }')
b='''                        db.insertOrThrow("notes",null,nv)'''
assert b in text
text=text.replace(b,'''                        val noteId=db.insertOrThrow("notes",null,nv)
                        n.optJSONObject("study")?.let { studyJson ->
                            WitnessDb(ctx).save(WitnessDb(ctx).fromJson(noteId,studyJson))
                        }''')
store.write_text(text)
(ROOT/"README-V3.md").write_text("""# محلل القيود 3.0 | Qoyood Research Workbench
Native Android Kotlin + Jetpack Compose. Unified image and academic text editor.
Only author-supplied transcription is sent to OpenRouter. No image is uploaded.
Optional web source lookup. Links are taken from returned URL citation annotations
and must be reviewed by researcher. TXT and DOCX export complete witness + its
transcription, interpretation, AI assistance and links. DOCX embeds crop images.
Project ZIP contains images, annotations, and new v3 study entries.
Package: com.alwarraqa.qoyood.research (install alongside v2).
The API key is encrypted in Android Keystore and is never included in backups.
GitHub CI produces debug-signed APK, not production key signed.
""",encoding="utf-8")
print("v3 upgrade installed")
