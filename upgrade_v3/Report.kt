package com.alwarraqa.app

import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.min

/** Unified documentary export. TXT contains every witness and its complete textual analysis.
 * DOCX additionally embeds the authentic cropped facsimile for each witness. */
object WitnessReport {
    private fun record(page:Page,n:Note,s:WitnessStudy,index:Int):String =buildString {
        append("\n──────────────────────────────────────────────────\n")
        append("الشاهد ");append(index);append(" | ");append(n.title);append("\n")
        append("الورقة: ");append(page.name);append("\n")
        append("النوع: ");append(n.category);append("\n")
        append("الموضع: x=");append(n.x);append("، y=");append(n.y)
        append("، width=");append(n.w);append("، height=");append(n.h);append("\n")
        append("الوصف المادي: ");append(n.observation.ifBlank{"لم يُدوّن"});append("\n\n")
        append("القراءة البشرية الدبلوماسية:\n");append(s.transcription.ifBlank{"لم تُدوّن"});append("\n\n")
        append("الأعلام: ");append(s.person.ifBlank{"غير محدد"});append("\n")
        append("التاريخ: ");append(s.dateText.ifBlank{"غير محدد"});append("\n")
        append("المكان: ");append(s.place.ifBlank{"غير محدد"});append("\n\n")
        append("التحليل العلمي للباحث:\n");append(s.analysis.ifBlank{"لم يُدوّن"});append("\n\n")
        if(s.machine.isNotBlank()){
            append("التحليل الآلي الاسترشادي (");append(s.model);append("):\n")
            append(s.machine);append("\n\n")
        }
        append("روابط المصادر المقترحة – تحتاج مراجعة المصدر الأصلي:\n")
        if(s.refs().isEmpty())append("لا توجد روابط مسجلة.\n")
        else s.refs().forEachIndexed{i,r->
            append(i+1);append(". ");append(r.title);append("\n")
            append(r.url);append("\n")
        }
    }
    fun exportTxt(ctx:Context,store:Store,studies:WitnessDb,project:Project,uri:Uri){
        val result=buildString {
            append("محلل القيود – بطاقة الشواهد المدمجة\n")
            append("المشروع: ");append(project.title);append("\n")
            append("المكتبة / رقم الحفظ: ");append(project.description);append("\n")
            var index=0
            for(page in store.pages(project.id))for(note in store.notes(page.id)){
                index++
                append(record(page,note,studies.get(note.id),index))
            }
            if(index==0)append("\nلا توجد شواهد بعد.\n")
            append("\nانتهى التقرير. الصور الأصلية محفوظة في التطبيق/النسخة الاحتياطية.\n")
        }
        ctx.contentResolver.openOutputStream(uri)?.use{
            it.write(byteArrayOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte()))
            it.write(result.toByteArray(Charsets.UTF_8))
        } ?: error("تعذر إنشاء التقرير")
    }

    private fun esc(s:String):String = s.replace("&","&amp;").replace("<","&lt;")
        .replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
        .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"),"")
    private fun para(s:String,bold:Boolean=false):String{
        val words=s.split("\n")
        return words.joinToString(""){line->
            """<w:p><w:pPr><w:bidi/><w:jc w:val="right"/><w:spacing w:after="120"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial" w:cs="Traditional Arabic"/><w:rtl/><w:sz w:val="28"/><w:szCs w:val="30"/>"""+
            if(bold)"""<w:b/>""" else ""+
            """</w:rPr><w:t xml:space="preserve">"""+esc(line)+"""</w:t></w:r></w:p>"""
        }
    }
    private fun line(s:String):String{
        // one independent text paragraph; avoids fragile markdown-to-docx conversions
        return """<w:p><w:pPr><w:bidi/><w:jc w:val="right"/><w:spacing w:after="120"/></w:pPr>
        <w:r><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial" w:cs="Traditional Arabic"/>
        <w:rtl/><w:sz w:val="28"/><w:szCs w:val="30"/></w:rPr><w:t xml:space="preserve">"""+
            esc(s)+"""</w:t></w:r></w:p>"""
    }
    private fun block(title:String,text:String):String =line(title)+
        (if(text.isBlank())line("غير مدون") else text.lines().joinToString(""){line(it)})
    private fun picture(index:Int,rId:String,widthPx:Int,heightPx:Int):String{
        val cx= min(4600000L,widthPx.toLong()*6000L)
        val cy=(cx*heightPx/maxOf(1,widthPx)).coerceAtLeast(30000L)
        return """<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:drawing>
        <wp:inline distT="0" distB="0" distL="0" distR="0">
        <wp:extent cx="$cx" cy="$cy"/><wp:docPr id="$index" name="صورة الشاهد $index"/>
        <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
        <pic:pic><pic:nvPicPr><pic:cNvPr id="0" name="Witness$index.jpg"/><pic:cNvPicPr/></pic:nvPicPr>
        <pic:blipFill><a:blip r:embed="$rId"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>
        <pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>
        <a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>
        </pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"""
    }
    fun exportDocx(ctx:Context,store:Store,studies:WitnessDb,project:Project,uri:Uri){
        data class ImagePart(val name:String,val id:String,val bytes:ByteArray)
        val images= mutableListOf<ImagePart>()
        val body=StringBuilder()
        body.append(block("محلل القيود – دفتر الشواهد",project.title+"\n"+project.description))
        var i=0
        for(page in store.pages(project.id))for(n in store.notes(page.id)){
            i++
            val s=studies.get(n.id)
            body.append(block("الشاهد "+i+" – "+n.title,"الورقة: "+page.name+" | النوع: "+n.category))
            try{
                val data=RegionCrop.jpeg(page,n)
                if(data!=null){
                    val info=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                    BitmapFactory.decodeByteArray(data,0,data.size,info)
                    val rel="rId"+(images.size+2)
                    images.add(ImagePart("witness"+i+".jpg",rel,data))
                    body.append(picture(i,rel,info.outWidth,info.outHeight))
                }
            }catch(_:Exception){body.append(line("[تعذر تضمين صورة هذا الشاهد]"))}
            body.append(block("وصف موضع القيد",n.observation))
            body.append(block("القراءة التي نقلها الباحث",s.transcription))
            body.append(block("بيانات مستخرجة يدويًا",
                "الأعلام: "+s.person+" | التاريخ: "+s.dateText+" | المكان: "+s.place))
            body.append(block("التحليل العلمي للباحث",s.analysis))
            if(s.machine.isNotBlank())body.append(block("نتيجة آلية استرشادية ("+s.model+")",s.machine))
            if(s.refs().isNotEmpty()){
                body.append(line("مصادر مقترحة (يجب التحقق منها):"))
                s.refs().forEach{ref->body.append(line(ref.title+" – "+ref.url))}
            }
        }
        if(i==0)body.append(line("لا توجد شواهد بعد."))
        val document="""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
        xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
        xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
        <w:body>"""+body.toString()+"""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>
        <w:pgMar w:top="1100" w:right="1050" w:bottom="1100" w:left="1050"/></w:sectPr>
        </w:body></w:document>"""
        val contentTypes="""<?xml version="1.0" encoding="UTF-8"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
        <Default Extension="xml" ContentType="application/xml"/>
        <Default Extension="jpg" ContentType="image/jpeg"/>
        <Override PartName="/word/document.xml"
        ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
        </Types>"""
        val rels="""<?xml version="1.0" encoding="UTF-8"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
        Target="word/document.xml"/></Relationships>"""
        val imageRels=images.joinToString(""){im->
            """<Relationship Id=""""+""""+im.id+""""+""" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
            Target="media/"""+im.name+""""+"""/>"""
        }
        val docRels="""<?xml version="1.0" encoding="UTF-8"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">"""+
            imageRels+"</Relationships>"
        val stream=ctx.contentResolver.openOutputStream(uri)?:error("تعذر الكتابة")
        ZipOutputStream(stream).use{zip->
            fun add(name:String,bytes:ByteArray){
                zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()
            }
            fun addText(name:String,text:String)=add(name,text.toByteArray(Charsets.UTF_8))
            addText("[Content_Types].xml",contentTypes)
            addText("_rels/.rels",rels)
            addText("word/document.xml",document)
            addText("word/_rels/document.xml.rels",docRels)
            images.forEach{add("word/media/"+it.name,it.bytes)}
        }
    }
}
