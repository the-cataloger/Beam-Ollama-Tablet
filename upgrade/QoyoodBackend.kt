package com.alwarraqa.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Secure storage: API token is encrypted with a non-exportable Android Keystore AES-GCM key.
 * Only user-triggered calls transfer a cropped manuscript image to OpenRouter.
 */
class AnalyzerSettings(private val ctx: Context) {
    private val prefs=ctx.getSharedPreferences("qoyood_settings",Context.MODE_PRIVATE)
    var model:String
        get()=prefs.getString("model","google/gemini-2.5-flash") ?: "google/gemini-2.5-flash"
        set(v){ prefs.edit().putString("model",v.trim()).apply() }
    var prompt:String
        get()=prefs.getString("prompt","") ?: ""
        set(v){ prefs.edit().putString("prompt",v).apply() }
    private fun secretKey():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        val existing=store.getKey("qoyood_openrouter_key",null)
        if(existing is SecretKey)return existing
        val gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder("qoyood_openrouter_key",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }
    fun saveApiKey(value:String){
        if(value.isBlank()){prefs.edit().remove("secret").remove("iv").apply();return}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,secretKey())
        val bytes=cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        prefs.edit().putString("secret",Base64.encodeToString(bytes,Base64.NO_WRAP))
            .putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).apply()
    }
    fun apiKey():String {
        val iv=prefs.getString("iv",null) ?: return ""
        val cipherText=prefs.getString("secret",null) ?: return ""
        return try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,secretKey(),GCMParameterSpec(128,Base64.decode(iv,Base64.DEFAULT)))
            String(cipher.doFinal(Base64.decode(cipherText,Base64.DEFAULT)),Charsets.UTF_8)
        }catch(_:Exception){""}
    }
}

class ResearchDocs(private val ctx:Context) {
    private fun file(id:Long)=File(File(ctx.filesDir,"research").apply{mkdirs()},"project_"+id+".md")
    fun read(id:Long)=file(id).takeIf{it.exists()}?.readText(Charsets.UTF_8) ?: ""
    fun save(id:Long,value:String) {
        val to=file(id)
        val tmp=File(to.parentFile,to.name+".tmp")
        tmp.writeText(value,Charsets.UTF_8)
        if(!tmp.renameTo(to)){tmp.copyTo(to,overwrite=true);tmp.delete()}
    }
}

/** Sends a sample-sized crop only when the user explicitly requests image analysis. */
object OpenRouterVision {
    fun analyze(page:Page,note:Note?,key:String,model:String,extraPrompt:String):String {
        require(key.isNotBlank()){"أدخل مفتاح OpenRouter أولًا"}
        require(model.isNotBlank()){"حدد اسم نموذج يدعم الصور"}
        // Decode the native-resolution region, not a blurry thumbnail of the entire folio.
        val crop=try {
            @Suppress("DEPRECATION")
            val decoder=android.graphics.BitmapRegionDecoder.newInstance(page.path,false)
                ?: error("تعذر قراءة صورة المخطوط")
            try {
                val w=decoder.width
                val h=decoder.height
                val rect=if(note==null) Rect(0,0,w,h) else {
                    val l=(note.x*w).roundToInt().coerceIn(0,w-1)
                    val t=(note.y*h).roundToInt().coerceIn(0,h-1)
                    val r=((note.x+note.w)*w).roundToInt().coerceIn(l+1,w)
                    val b=((note.y+note.h)*h).roundToInt().coerceIn(t+1,h)
                    Rect(l,t,r,b)
                }
                var sample=1
                while(max(rect.width(),rect.height())/sample>2500)sample*=2
                decoder.decodeRegion(rect,BitmapFactory.Options().apply{
                    inSampleSize=sample
                    inPreferredConfig=Bitmap.Config.ARGB_8888
                })?:error("تعذر استخراج المنطقة")
            }finally{decoder.recycle()}
        }catch(_:Exception){
            // Fallback for formats without region decoding.
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeFile(page.path,bounds)
            require(bounds.outWidth>0&&bounds.outHeight>0){"الصورة غير صالحة"}
            var sample=1
            while(max(bounds.outWidth,bounds.outHeight)/sample>2600)sample*=2
            val full=BitmapFactory.decodeFile(page.path,BitmapFactory.Options().apply{
                inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888
            })?:error("تعذر فتح الصورة")
            if(note==null)full else{
                val l=(note.x*full.width).roundToInt().coerceIn(0,full.width-1)
                val t=(note.y*full.height).roundToInt().coerceIn(0,full.height-1)
                val r=((note.x+note.w)*full.width).roundToInt().coerceIn(l+1,full.width)
                val b=((note.y+note.h)*full.height).roundToInt().coerceIn(t+1,full.height)
                Bitmap.createBitmap(full,l,t,r-l,b-t).also{if(it!==full)full.recycle()}
            }
        }
        val jpeg=ByteArrayOutputStream()
        try{crop.compress(Bitmap.CompressFormat.JPEG,86,jpeg)}
        finally{crop.recycle()}
        val encoded="data:image/jpeg;base64,"+Base64.encodeToString(jpeg.toByteArray(),Base64.NO_WRAP)
        val instruction="""أنت باحث متخصص في علم المخطوط العربي، والتقييدات (التملك والوقف والإجازة والسماع والحرد).
حلل الصورة بأمانة دون اختلاق كلمات لا تظهر فيها. أجب بالعربية وفق الأقسام:
١. النقل الدبلوماسي: التزم بالرسم الظاهر، ضع [غير مقروء] و[؟] عند الحاجة.
٢. نوع القيد وخصائصه المادية.
٣. الأسماء والتواريخ والأماكن الظاهرة فقط.
٤. تفسير موجز يستند إلى دليل مرئي.
٥. مواضع الشك والقراءات البديلة.
٦. أسئلة تحقق للباحث.
ميز صراحة بين المشاهدة والاستنتاج، ولا تزعم تحققًا من المصادر الخارجية.
هذه النتيجة مسودة آلية لا يجوز اعتمادها دون فحص الصورة الأصلية.""".trimIndent()
        val detail= if(note==null) "حلل الصفحة كاملة." else "القسم المختار: "+note.category+"، وصف الباحث: "+note.observation+". حلل هذا الاقتطاع فقط."
        val payload=JSONObject()
            .put("model",model).put("temperature",0.1).put("max_tokens",1500)
            .put("messages",JSONArray()
                .put(JSONObject().put("role","system").put("content",instruction))
                .put(JSONObject().put("role","user").put("content",JSONArray()
                    .put(JSONObject().put("type","text").put("text",detail+
                        if(extraPrompt.isBlank())"" else "\nتعليمات الباحث: "+extraPrompt))
                    .put(JSONObject().put("type","image_url").put("image_url",
                        JSONObject().put("url",encoded))))))
        val conn=(URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection)
        conn.requestMethod="POST";conn.connectTimeout=20000;conn.readTimeout=95000
        conn.doOutput=true
        conn.setRequestProperty("Authorization","Bearer "+key)
        conn.setRequestProperty("Content-Type","application/json; charset=utf-8")
        conn.setRequestProperty("X-Title","محلل القيود")
        return try {
            conn.outputStream.use{it.write(payload.toString().toByteArray(Charsets.UTF_8))}
            val code=conn.responseCode
            val data=(if(code in 200..299)conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use{it.readText()} ?: ""
            if(code !in 200..299){
                val error=try{JSONObject(data).optJSONObject("error")?.optString("message") ?: data}catch(_:Exception){data}
                error("OpenRouter HTTP "+code+": "+error.take(450))
            }
            val response=JSONObject(data)
            val content=response.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").opt("content")
            val result=if(content is String)content else if(content is JSONArray) {
                (0 until content.length()).joinToString("\n"){i->content.optJSONObject(i)?.optString("text") ?: ""}
            }else ""
            require(result.isNotBlank()){"لم يرجع النموذج نصًا؛ افحص إعدادات النموذج"}
            result
        }finally{conn.disconnect()}
    }
}
