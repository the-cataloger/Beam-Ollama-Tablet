#!/usr/bin/env python3
from pathlib import Path

root = Path("AlWarraqa-2.0-Android-Source")
def patch(relative, old, new):
    p=root/relative
    s=p.read_text(encoding="utf-8")
    assert old in s, "Patch anchor missing: "+relative+" / "+old[:80]
    p.write_text(s.replace(old,new),encoding="utf-8")

patch("app/build.gradle", "debug { debuggable true }", "debug { debuggable true; applicationIdSuffix '.preview' }")
patch("app/src/main/AndroidManifest.xml", 'android:label="الورّاقة"', 'android:label="@string/app_name"')
dp=root/"app/src/debug/res/values/strings.xml"
dp.parent.mkdir(parents=True,exist_ok=True)
dp.write_text('<resources><string name="app_name">الورّاقة ٢ — تجريبية</string></resources>\n',encoding="utf-8")

ex="app/src/main/java/com/alwarraqa/app/Exports.kt"
patch(ex,'private fun cropped(page:Page?,note:Note):ByteArray? {\n        if(page==null || page.path.startsWith("http"))return null\n        val file=File(page.path);if(!file.isFile)return null',
         'private fun cropped(context:Context,page:Page?,note:Note):ByteArray? {\n        if(page==null)return null\n        val file=if(page.path.startsWith("https://")) try { IIIF.cacheImage(context,page.path) }catch (_:Exception){return null} else File(page.path);if(!file.isFile)return null')
patch(ex,"fun pdf(store:Store,p:Project):ByteArray", "fun pdf(context:Context,store:Store,p:Project):ByteArray")
patch(ex,"fun docx(store:Store,p:Project):ByteArray", "fun docx(context:Context,store:Store,p:Project):ByteArray")
patch(ex,"cropped(page,n)", "cropped(context,page,n)")
patch(ex,'fun export(type:String,store:Store,p:Project)=when(type){"pdf"->pdf(store,p);"docx"->docx(store,p);"xlsx"->xlsx(store,p);"json"->json(store,p);else->csv(store,p)}',
         'fun export(type:String,context:Context,store:Store,p:Project)=when(type){"pdf"->pdf(context,store,p);"docx"->docx(context,store,p);"xlsx"->xlsx(store,p);"json"->json(store,p);else->csv(store,p)}')
iiif="app/src/main/java/com/alwarraqa/app/IIIF.kt"
patch(iiif,'val image=if(service.isNotBlank())"${service.trimEnd(\'/\')}/full/max/0/default.jpg" else id',
          'val image=if(service.isNotBlank())"${service.trimEnd(\'/\')}/full/full/0/default.jpg" else id')
main="app/src/main/java/com/alwarraqa/app/MainActivity.kt"
patch(main,"import kotlinx.coroutines.Dispatchers", "import kotlinx.coroutines.delay\nimport kotlinx.coroutines.Dispatchers")
patch(main,"Exports.export(exportType,store,p)", "Exports.export(exportType,context,store,p)")
patch(main,'references=v.optString("references")));historyNote=null;',
         'references=v.optString("references"),researcher=v.optString("researcher"),x=v.optDouble("x",n.x.toDouble()).toFloat(),y=v.optDouble("y",n.y.toDouble()).toFloat(),w=v.optDouble("w",n.w.toDouble()).toFloat(),h=v.optDouble("h",n.h.toDouble()).toFloat(),geometry=v.optString("geometry",n.geometry),vertices=v.optString("vertices")));historyNote=null;')
old='''    var title by remember(existing?.id){mutableStateOf(existing?.title?:"")}
    var category by remember(existing?.id){mutableStateOf(existing?.category?:"تقييد")}
    var observation by remember(existing?.id){mutableStateOf(existing?.observation?:"")}
    var reading by remember(existing?.id){mutableStateOf(existing?.reading?:"")}
    var interpretation by remember(existing?.id){mutableStateOf(existing?.interpretation?:"")}
    var confidence by remember(existing?.id){mutableStateOf(existing?.confidence?:"غير محسوم")}
    var references by remember(existing?.id){mutableStateOf(existing?.references?:"")}
    var researcher by remember(existing?.id){mutableStateOf(existing?.researcher?:"")}'''
new='''    val context=LocalContext.current
    val preferences=remember {context.getSharedPreferences("note_drafts",android.content.Context.MODE_PRIVATE)}
    val draftKey=remember(existing?.id,pageId){"draft_${existing?.id?:"new_$pageId"}"}
    val storedDraft=remember(draftKey){try{JSONObject(preferences.getString(draftKey,"")?:"")}catch(_:Exception){JSONObject()}}
    fun initField(key:String,fallback:String)=storedDraft.optString(key,fallback)
    var title by remember(existing?.id){mutableStateOf(initField("title",existing?.title?:""))}
    var category by remember(existing?.id){mutableStateOf(initField("category",existing?.category?:"تقييد"))}
    var observation by remember(existing?.id){mutableStateOf(initField("observation",existing?.observation?:""))}
    var reading by remember(existing?.id){mutableStateOf(initField("reading",existing?.reading?:""))}
    var interpretation by remember(existing?.id){mutableStateOf(initField("interpretation",existing?.interpretation?:""))}
    var confidence by remember(existing?.id){mutableStateOf(initField("confidence",existing?.confidence?:"غير محسوم"))}
    var references by remember(existing?.id){mutableStateOf(initField("references",existing?.references?:""))}
    var researcher by remember(existing?.id){mutableStateOf(initField("researcher",existing?.researcher?:""))}
    LaunchedEffect(draftKey,title,category,observation,reading,interpretation,confidence,references,researcher){
        delay(650)
        preferences.edit().putString(draftKey,JSONObject().apply{
            put("title",title);put("category",category);put("observation",observation);put("reading",reading)
            put("interpretation",interpretation);put("confidence",confidence);put("references",references);put("researcher",researcher)
        }.toString()).apply()
    }'''
patch(main,old,new)
patch(main,'researcher=researcher))}){Text("حفظ الشاهد")}',
           'researcher=researcher));preferences.edit().remove(draftKey).apply()}){Text("حفظ الشاهد")}')
patch(main,'onClick={deleteConfirm=false;onDelete?.invoke()}',
           'onClick={deleteConfirm=false;preferences.edit().remove(draftKey).apply();onDelete?.invoke()}')
print("PATCHED: preview package, IIIF crops, autosave drafts, complete history")
