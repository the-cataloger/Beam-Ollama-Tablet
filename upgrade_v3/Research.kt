package com.alwarraqa.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.roundToInt

data class WitnessStudy(
    val noteId:Long, val transcription:String="", val analysis:String="",
    val machine:String="", val citations:String="[]", val model:String="",
    val machineAt:Long=0, val person:String="", val place:String="",
    val dateText:String="", val updatedAt:Long=0
){
    fun refs():List<ResearchRef> =try {
        val a=JSONArray(citations)
        (0 until a.length()).map{a.getJSONObject(it)}
            .map{ResearchRef(it.optString("title"),it.optString("url"))}
            .filter{it.url.startsWith("https://")}
    }catch(_:Exception){emptyList()}
}
data class ResearchRef(val title:String,val url:String)
data class ResearchResponse(val body:String,val refs:List<ResearchRef>,val model:String)

/** Independent handwritten transcription and scholarly assessment survive AI regeneration. */
class WitnessDb(ctx:Context):SQLiteOpenHelper(ctx,"qoyood_study_v3.db",null,1){
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE witness_study(
            note_id INTEGER PRIMARY KEY,transcription TEXT NOT NULL DEFAULT '',
            analysis TEXT NOT NULL DEFAULT '',machine TEXT NOT NULL DEFAULT '',
            citations TEXT NOT NULL DEFAULT '[]',model TEXT NOT NULL DEFAULT '',
            machine_at INTEGER NOT NULL DEFAULT 0,person TEXT NOT NULL DEFAULT '',
            place TEXT NOT NULL DEFAULT '',date_text TEXT NOT NULL DEFAULT '',
            updated_at INTEGER NOT NULL DEFAULT 0)""".trimIndent())
    }
    override fun onUpgrade(db:SQLiteDatabase,a:Int,b:Int){}
    fun get(id:Long):WitnessStudy{
        readableDatabase.rawQuery("""SELECT note_id,transcription,analysis,machine,citations,model,
        machine_at,person,place,date_text,updated_at FROM witness_study WHERE note_id=?""",arrayOf(id.toString())).use{c->
            return if(c.moveToFirst())WitnessStudy(c.getLong(0),c.getString(1),c.getString(2),
                c.getString(3),c.getString(4),c.getString(5),c.getLong(6),c.getString(7),
                c.getString(8),c.getString(9),c.getLong(10)) else WitnessStudy(id)
        }
    }
    fun save(w:WitnessStudy){
        val v=ContentValues().apply {
            put("note_id",w.noteId);put("transcription",w.transcription);put("analysis",w.analysis)
            put("machine",w.machine);put("citations",w.citations);put("model",w.model)
            put("machine_at",w.machineAt);put("person",w.person);put("place",w.place)
            put("date_text",w.dateText);put("updated_at",System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("witness_study",null,v,SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun delete(id:Long){writableDatabase.delete("witness_study","note_id=?",arrayOf(id.toString()))}
    fun toJson(w:WitnessStudy)=JSONObject().apply{
        put("transcription",w.transcription);put("analysis",w.analysis);put("machine",w.machine)
        put("citations",w.citations);put("model",w.model);put("machine_at",w.machineAt)
        put("person",w.person);put("place",w.place);put("date_text",w.dateText)
    }
    fun fromJson(id:Long,j:JSONObject)=WitnessStudy(id,
        j.optString("transcription"),j.optString("analysis"),j.optString("machine"),
        j.optString("citations","[]"),j.optString("model"),j.optLong("machine_at"),
        j.optString("person"),j.optString("place"),j.optString("date_text"))
}

/** Explicitly textual: no image is transferred or inferred from this request. */
object TextResearch {
    fun run(key:String,model:String,transcription:String,context:String,withWeb:Boolean):ResearchResponse{
        require(key.isNotBlank()){"أدخل مفتاح OpenRouter"}
        require(model.isNotBlank()){"حدد النموذج"}
        require(transcription.isNotBlank()){"أدخل قراءتك النصية قبل التحليل"}
        require(transcription.length<=18000){"النص طويل جدًا"}
        val base="""أنت باحث مساعد في تقييدات المخطوط العربي والتاريخ الإسلامي.
المدخل قراءة بشرية؛ لم تُعرض عليك الصورة، ولا يمكنك ادعاء قراءتها.
افصل بين المعطيات الصريحة والفرضيات. حلل نوع القيد، والأسماء والأماكن والتواريخ، والصيغ،
واحتمالات السياق، والأسئلة التي تحتاج تحققًا. لا تستبدل قراءة الباحث ولا تكمل [غير مقروء].
لا تختلق مراجع أو أسماء مصادر أو أرقام صفحات. اكتب بالعربية بعناوين قصيرة.
"""
        val instruction=base+(if(withWeb)
            """استخدم نتائج البحث الشبكي للعثور على فهارس ومصادر تاريخية ومؤسسات ذا صلة؛
لا تعرض المعلومات غير المدعومة باعتبارها حقائق. ميّز المصدر الأولي من الدراسة الثانوية."""
            else "هذا تحليل داخلي للنص، لا تدّعِ إجراء بحث في مصادر خارجية.")
        val payload=JSONObject()
            .put("model",model).put("temperature",0.15).put("max_tokens",2200)
            .put("messages",JSONArray()
                .put(JSONObject().put("role","system").put("content",instruction))
                .put(JSONObject().put("role","user").put("content",
                    "القراءة التي كتبها الباحث:\n"+transcription+"\n\nبيانات المصدر / تعليقات الباحث:\n"+context)))
        if(withWeb)payload.put("plugins",JSONArray().put(
            JSONObject().put("id","web").put("max_results",5)))
        val connection=URL("https://openrouter.ai/api/v1/chat/completions")
            .openConnection() as HttpURLConnection
        connection.requestMethod="POST";connection.connectTimeout=20000
        connection.readTimeout=110000;connection.doOutput=true
        connection.setRequestProperty("Authorization","Bearer "+key)
        connection.setRequestProperty("Content-Type","application/json; charset=utf-8")
        connection.setRequestProperty("X-Title","Qoyood Text Research")
        return try{
            connection.outputStream.use{it.write(payload.toString().toByteArray(Charsets.UTF_8))}
            val code=connection.responseCode
            val raw=(if(code in 200..299)connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use{it.readText()} ?: ""
            if(code !in 200..299){
                val detail=try{JSONObject(raw).optJSONObject("error")?.optString("message")?:raw}
                    catch(_:Exception){raw}
                error("OpenRouter HTTP "+code+": "+detail.take(400))
            }
            val message=JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val body=when(val part=message.opt("content")){
                is String->part
                is JSONArray->(0 until part.length()).joinToString("\n"){part.optJSONObject(it)?.optString("text")?:""}
                else->""
            }
            require(body.isNotBlank()){"أعاد النموذج استجابة فارغة"}
            val refs=mutableListOf<ResearchRef>()
            val annotations=message.optJSONArray("annotations")
            if(annotations!=null)for(i in 0 until annotations.length()){
                val ann=annotations.optJSONObject(i)?:continue
                if(ann.optString("type")!="url_citation")continue
                val cited=ann.optJSONObject("url_citation")?:continue
                val url=cited.optString("url")
                if(!url.startsWith("https://")||refs.any{it.url==url})continue
                refs.add(ResearchRef(cited.optString("title").ifBlank{url},url))
            }
            ResearchResponse(body,refs,model)
        }finally{connection.disconnect()}
    }
}

object RegionCrop{
    fun extract(page:Page,note:Note,maxPixels:Int=1600):Bitmap?{
        @Suppress("DEPRECATION")
        val dec=BitmapRegionDecoder.newInstance(page.path,false)?:return null
        try{
            val w=dec.width;val h=dec.height
            val l=(note.x*w).roundToInt().coerceIn(0,w-1)
            val t=(note.y*h).roundToInt().coerceIn(0,h-1)
            val r=((note.x+note.w)*w).roundToInt().coerceIn(l+1,w)
            val b=((note.y+note.h)*h).roundToInt().coerceIn(t+1,h)
            var sample=1
            while(max(r-l,b-t)/sample>maxPixels)sample*=2
            return dec.decodeRegion(Rect(l,t,r,b),BitmapFactory.Options().apply{
                inSampleSize=sample;inPreferredConfig=Bitmap.Config.RGB_565
            })
        }finally{dec.recycle()}
    }
    fun jpeg(page:Page,note:Note):ByteArray?{
        val bmp=extract(page,note)?:return null
        return try{ByteArrayOutputStream().use{out->
            bmp.compress(Bitmap.CompressFormat.JPEG,85,out);out.toByteArray()
        }}finally{bmp.recycle()}
    }
}
