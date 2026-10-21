package com.alwarraqa.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private val navy=Color(0xFF13333C)
private val petrol=Color(0xFF13666A)
private val amber=Color(0xFFB48247)
private val soft=Color(0xFFF5F3ED)
private val pale=Color(0xFFE4E0D7)
private val categories=listOf("غير محدد","تملك","وقف","إجازة","سماع","حرد","نسخ","تعقيبة","ختم","غير ذلك","ليس قيدًا")

class ResearchWorkbenchActivity:ComponentActivity(){
    private lateinit var store:Store
    private lateinit var studies:WitnessDb
    private lateinit var settings:AnalyzerSettings
    private var project by mutableStateOf<Project?>(null)
    private var page by mutableStateOf<Page?>(null)
    private var active by mutableStateOf<Note?>(null)
    private var study by mutableStateOf(WitnessStudy(-1))
    private var busy by mutableStateOf(false)
    private var marking by mutableStateOf(false)
    private var replacing by mutableStateOf(false)
    private var rev by mutableIntStateOf(0)
    private var settingsOpen by mutableStateOf(false)
    private var projectsOpen by mutableStateOf(false)
    private var newProjectOpen by mutableStateOf(false)
    private var markers by mutableStateOf(true)
    private var viewer:RegionImageView?=null

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        store=Store(this);studies=WitnessDb(this);settings=AnalyzerSettings(this)
        project=store.projects().firstOrNull()?:run{
            val id=store.addProject("دراسة القيود","مشروع محلي")
            store.projects().firstOrNull{it.id==id}
        }
        page=project?.let{store.pages(it.id).firstOrNull()}
        page?.let{p->store.notes(p.id).firstOrNull()?.let(::selectNote)}
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl){
                MaterialTheme(colorScheme=lightColorScheme(primary=petrol,background=soft,
                    surface=Color.White,onSurface=navy)){
                    Surface(Modifier.fillMaxSize(),color=soft){MainView()}
                }
            }
        }
    }
    private fun notice(s:String)=runOnUiThread{Toast.makeText(this,s,Toast.LENGTH_LONG).show()}
    private fun save(){if(study.noteId>0)studies.save(study)}
    private fun selectNote(n:Note?){
        save();active=n
        study=if(n==null)WitnessStudy(-1)else studies.get(n.id).let{
            if(it.updatedAt==0&&n.interpretation.isNotBlank())it.copy(transcription=n.interpretation)else it
        }
        if(n!=null)viewer?.focus(n)
    }
    private fun selectPage(p:Page){
        save();page=p;active=null;study=WitnessStudy(-1);viewer=null
        marking=false;replacing=false;rev++
        store.notes(p.id).firstOrNull()?.let(::selectNote)
    }
    private fun selectProject(p:Project){
        save();project=p;page=null;active=null;study=WitnessStudy(-1);viewer=null;rev++
        store.pages(p.id).firstOrNull()?.let(::selectPage)
    }
    private fun research(web:Boolean){
        val n=active?:run{notice("حدد موضع القيد");return}
        val current=study
        if(current.transcription.isBlank()){notice("أدخل قراءتك أولًا");return}
        val key=settings.apiKey()
        if(key.isBlank()){settingsOpen=true;notice("أدخل مفتاح OpenRouter");return}
        if(busy)return
        save();busy=true
        val ctx="العنوان: "+n.title+"\nنوع الشاهد: "+n.category+
            "\nالمشروع: "+(project?.title?:"")+"\nالمكتبة: "+(project?.description?:"")+
            "\nالوصف: "+n.observation+"\nالأعلام: "+current.person+
            "\nالتاريخ: "+current.dateText+"\nالمكان: "+current.place+
            "\nتحليل الباحث: "+current.analysis
        Thread{
            try{
                val out=TextResearch.run(key,settings.model,current.transcription,ctx,web)
                val refs=JSONArray().apply{out.refs.forEach{r->
                    put(JSONObject().put("title",r.title).put("url",r.url))
                }}.toString()
                val next=studies.get(n.id).copy(machine=out.body,citations=refs,
                    model=out.model,machineAt=System.currentTimeMillis())
                studies.save(next)
                runOnUiThread {
                    if(active?.id==n.id)study=next
                    busy=false;rev++
                    notice(if(web)"انتهى البحث؛ تحقق من المراجع" else "اكتمل تحليل النص")
                }
            }catch(e:Exception){runOnUiThread{
                busy=false;notice("خطأ التحليل: "+(e.message?:"غير معروف").take(160))
            }}
        }.start()
    }
    @Composable private fun MainView(){
        val tick=rev
        val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->
            val p=project
            if(p!=null&&uris.isNotEmpty())Thread {
                try{
                    val id=uris.map{store.addPage(p.id,it)}.last()
                    runOnUiThread{
                        rev++;store.pages(p.id).firstOrNull{it.id==id}?.let(::selectPage)
                    }
                }catch(e:Exception){notice(e.message?:"فشل إضافة الصور")}
            }.start()
        }
        val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri:Uri?->
            if(uri!=null)Thread{
                try{
                    val id=store.restore(uri)
                    runOnUiThread{
                        rev++;store.projects().firstOrNull{it.id==id}?.let(::selectProject)
                        notice("استعيد المشروع؛ راجع البيانات")
                    }
                }catch(e:Exception){notice(e.message?:"فشلت الاستعادة")}
            }.start()
        }
        val backup=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri:Uri?->
            val p=project
            if(uri!=null&&p!=null)Thread{
                try{save();store.backup(p,uri);notice("تم الحفظ")}
                catch(e:Exception){notice(e.message?:"فشل النسخ")}
            }.start()
        }
        val txt=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri:Uri?->
            val p=project
            if(uri!=null&&p!=null)Thread{
                try{save();WitnessReport.exportTxt(this,store,studies,p,uri);notice("تم تصدير النص المتكامل")}
                catch(e:Exception){notice(e.message?:"خطأ التصدير")}
            }.start()
        }
        val word=rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")){uri:Uri?->
            val p=project
            if(uri!=null&&p!=null)Thread{
                try{save();WitnessReport.exportDocx(this,store,studies,p,uri);notice("تم تصدير التقرير بالصور")}
                catch(e:Exception){notice(e.message?:"خطأ Word")}
            }.start()
        }
        BackHandler(marking){marking=false;replacing=false}
        Column(Modifier.fillMaxSize()){
            Row(Modifier.fillMaxWidth().background(navy).padding(7.dp),
                horizontalArrangement=Arrangement.spacedBy(5.dp),
                verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){
                    Text("مُحَلِّلُ القُيُود",fontSize=21.sp,color=Color.White,fontWeight=FontWeight.Bold)
                    Text("Qoyood • Manuscript Research",fontSize=10.sp,color=Color(0xFFCEE2E3))
                }
                HeaderButton("مشروع","Project"){projectsOpen=true}
                HeaderButton("+ صورة","Add"){pick.launch(arrayOf("image/*"))}
                HeaderButton("نص","TXT"){txt.launch("Qoyood-study.txt")}
                HeaderButton("تقرير","DOCX"){word.launch("Qoyood-report.docx")}
                HeaderButton("نسخة","Backup"){backup.launch("Qoyood-backup.zip")}
                HeaderButton("إعدادات","Settings"){settingsOpen=true}
            }
            Row(Modifier.fillMaxSize().padding(9.dp),
                horizontalArrangement=Arrangement.spacedBy(9.dp)){
                ImagePanel(Modifier.weight(1.07f).fillMaxHeight(),onAdd={pick.launch(arrayOf("image/*"))})
                StudyPanel(Modifier.weight(1f).fillMaxHeight())
            }
        }
        LaunchedEffect(study){val s=study;if(s.noteId>0){delay(550);studies.save(s)}}
        if(projectsOpen){
            val projects=remember(tick){store.projects()}
            AlertDialog(onDismissRequest={projectsOpen=false},title={Text("المشروعات | Projects")},text={
                Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())){
                    projects.forEach{p->
                        TextButton(onClick={projectsOpen=false;selectProject(p)},
                            modifier=Modifier.fillMaxWidth()){
                            Text(p.title,modifier=Modifier.weight(1f))
                            Text(store.pages(p.id).size.toString()+" صورة",fontSize=11.sp)
                        }
                    }
                    HorizontalDivider()
                    TextButton(onClick={projectsOpen=false;newProjectOpen=true}){Text("+ مشروع جديد")}
                    TextButton(onClick={projectsOpen=false;restore.launch(arrayOf("application/zip","application/octet-stream"))}){
                        Text("استعادة مشروع ZIP")
                    }
                }
            },confirmButton={TextButton(onClick={projectsOpen=false}){Text("إغلاق")}})
        }
        if(newProjectOpen){
            var title by remember{mutableStateOf("")}
            var details by remember{mutableStateOf("")}
            AlertDialog(onDismissRequest={newProjectOpen=false},title={Text("دراسة جديدة")},text={
                Column{
                    OutlinedTextField(title,{title=it},label={Text("عنوان المشروع")})
                    OutlinedTextField(details,{details=it},label={Text("المكتبة / رقم الحفظ")})
                }
            },confirmButton={Button(enabled=title.isNotBlank(),onClick={
                val id=store.addProject(title.trim(),details.trim())
                selectProject(store.projects().first{it.id==id});newProjectOpen=false
            }){Text("إنشاء")}},dismissButton={TextButton(onClick={newProjectOpen=false}){Text("إلغاء")}})
        }
        if(settingsOpen)SettingsDialog()
    }
    @Composable private fun HeaderButton(ar:String,en:String,onClick:()->Unit){
        OutlinedButton(onClick,modifier=Modifier.height(46.dp),
            contentPadding=PaddingValues(horizontal=11.dp,vertical=2.dp),
            colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.White),
            border=BorderStroke(1.dp,Color(0xFF54747F))){
            Column(horizontalAlignment=Alignment.CenterHorizontally){
                Text(ar,fontSize=12.sp);Text(en,fontSize=9.sp)
            }
        }
    }
    @Composable private fun ImagePanel(modifier:Modifier,onAdd:()->Unit){
        val p=page
        val pages=remember(rev,project?.id){project?.let{store.pages(it.id)}?:emptyList()}
        val notes=remember(rev,p?.id){p?.let{store.notes(it.id)}?:emptyList()}
        Surface(modifier,color=Color.White,shape=RoundedCornerShape(12.dp),shadowElevation=2.dp){
            Column{
                Row(Modifier.fillMaxWidth().padding(6.dp),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(4.dp)){
                    Text(p?.name?:"الصور",fontWeight=FontWeight.Bold,fontSize=14.sp,modifier=Modifier.weight(1f))
                    Tool(if(marking)"إلغاء" else "تحديد","Mark"){
                        if(p==null)onAdd()else{marking=!marking;replacing=false}
                    }
                    Tool("＋","Zoom"){viewer?.zoomBy(1.3f)}
                    Tool("−","Out"){viewer?.zoomBy(1f/1.3f)}
                    Tool("احتواء","Fit"){viewer?.resetView()}
                    Tool(if(markers)"إخفاء" else "إظهار","Pins"){markers=!markers}
                }
                if(marking)Text("ارسم منطقة القيد • AI لا يقرأ الصورة",fontSize=11.sp,
                    color=amber,modifier=Modifier.padding(horizontal=10.dp))
                if(p==null)Box(Modifier.fillMaxWidth().weight(1f).background(soft),
                    contentAlignment=Alignment.Center){
                    Column(horizontalAlignment=Alignment.CenterHorizontally){
                        Text("أضف صور المخطوط",fontSize=19.sp)
                        Spacer(Modifier.height(9.dp))
                        Button(onClick=onAdd){Text("إضافة صور | Add Images")}
                    }
                }else androidx.compose.runtime.key(p.id){
                    AndroidView(factory={ctx->
                        RegionImageView(ctx).also{it.setImage(p.path);viewer=it}
                    },modifier=Modifier.weight(1f).fillMaxWidth(),update={view->
                        viewer=view
                        view.notes=if(markers)notes else emptyList()
                        view.selectMode=marking
                        view.onRegionCreated={rect->
                            val previous=active
                            val id=if(replacing && previous!=null)
                                store.saveNote(p.id,rect,previous.category,previous.title,
                                    previous.observation,previous.interpretation,previous.confidence,previous.id)
                            else store.saveNote(p.id,rect,"غير محدد","الشاهد "+(notes.size+1),
                                "","","غير محسوم")
                            marking=false;replacing=false;rev++
                            store.notes(p.id).firstOrNull{it.id==id}?.let(::selectNote)
                        }
                        view.onNoteTapped={selectNote(it)}
                    })
                }
                LazyRow(Modifier.fillMaxWidth().height(88.dp).background(soft).padding(5.dp),
                    horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    items(pages,key={it.id}){pg->
                        val chosen=pg.id==p?.id
                        Surface(onClick={selectPage(pg)},shape=RoundedCornerShape(6.dp),
                            color=if(chosen)Color(0xFFEEE1C5)else Color.White,
                            border=BorderStroke(if(chosen)2.dp else 1.dp,if(chosen)amber else pale)){
                            Column(Modifier.width(68.dp).padding(3.dp),
                                horizontalAlignment=Alignment.CenterHorizontally){
                                val bmp=remember(pg.path){
                                    val opt=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                                    BitmapFactory.decodeFile(pg.path,opt)
                                    var sample=1
                                    while(maxOf(opt.outWidth,opt.outHeight)/sample>250)sample*=2
                                    BitmapFactory.decodeFile(pg.path,BitmapFactory.Options().apply{inSampleSize=sample})
                                }
                                if(bmp!=null)Image(bmp.asImageBitmap(),null,
                                    Modifier.height(58.dp).fillMaxWidth(),contentScale=ContentScale.Fit)
                                Text(pg.name,fontSize=10.sp,maxLines=1)
                            }
                        }
                    }
                }
            }
        }
    }
    @Composable private fun StudyPanel(modifier:Modifier){
        val n=active
        val p=page
        val notes=remember(rev,p?.id){p?.let{store.notes(it.id)}?:emptyList()}
        Surface(modifier,shape=RoundedCornerShape(12.dp),color=Color.White,shadowElevation=2.dp){
            Column(Modifier.verticalScroll(rememberScrollState()).padding(10.dp),
                verticalArrangement=Arrangement.spacedBy(9.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Text("القيد المحدد | Witness",fontWeight=FontWeight.Bold,
                        fontSize=17.sp,modifier=Modifier.weight(1f))
                    if(n!=null)Tool("تعديل الموضع","Re-mark"){marking=true;replacing=true}
                }
                if(n==null||p==null){
                    Surface(color=soft,shape=RoundedCornerShape(10.dp)){
                        Column(Modifier.fillMaxWidth().padding(24.dp)){
                            Text("حدّد منطقة على الصورة لبدء الدراسة",fontSize=18.sp,
                                fontWeight=FontWeight.Bold)
                            Spacer(Modifier.height(9.dp))
                            Text("ستظهر صورة القيد ثم قراءتك الشخصية وتحليلك العلمي والبحث في المصادر.",
                                color=Color.Gray,fontSize=13.sp)
                            Text("لن يُطلب من الذكاء الاصطناعي قراءة صورة المخطوط.",
                                fontSize=12.sp,color=petrol)
                        }
                    }
                }else{
                    val crop=remember(p.id,n.id,n.x,n.y,n.w,n.h){
                        try{RegionCrop.extract(p,n)}catch(_:Exception){null}
                    }
                    Surface(color=soft,shape=RoundedCornerShape(7.dp),border=BorderStroke(1.dp,pale)){
                        Column(Modifier.fillMaxWidth().padding(7.dp)){
                            Text("صورة القيد المقتطعة • Original Crop",fontSize=12.sp,
                                color=petrol,fontWeight=FontWeight.SemiBold)
                            if(crop!=null)Image(crop.asImageBitmap(),"القيد",
                                Modifier.fillMaxWidth().height(145.dp),contentScale=ContentScale.Fit)
                        }
                    }
                    var title by remember(n.id){mutableStateOf(n.title)}
                    var category by remember(n.id){mutableStateOf(n.category)}
                    var observation by remember(n.id){mutableStateOf(n.observation)}
                    LaunchedEffect(n.id,title,category,observation){
                        delay(650)
                        if(active?.id==n.id && (title!=n.title||category!=n.category||observation!=n.observation)){
                            store.saveNote(n.pageId,floatArrayOf(n.x,n.y,n.w,n.h),
                                category,title,observation,n.interpretation,n.confidence,n.id)
                        }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        OutlinedTextField(title,{title=it},Modifier.weight(1.2f),singleLine=true,
                            label={Text("الشاهد | Title")},textStyle=TextStyle(fontSize=14.sp))
                        var showCategories by remember{mutableStateOf(false)}
                        Box(Modifier.weight(.8f)){
                            OutlinedButton(onClick={showCategories=true},
                                modifier=Modifier.fillMaxWidth().height(56.dp)){
                                Text(category+" ▾",fontSize=12.sp,maxLines=1)
                            }
                            DropdownMenu(showCategories,onDismissRequest={showCategories=false}){
                                categories.forEach{cat->
                                    DropdownMenuItem(text={Text(cat)},onClick={category=cat;showCategories=false})
                                }
                            }
                        }
                    }
                    OutlinedTextField(observation,{observation=it},
                        modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=3,
                        label={Text("وصف القيد وموقعه | Description")},
                        textStyle=TextStyle(fontSize=14.sp,lineHeight=22.sp))
                    Section("قراءتي البشرية | Transcription")
                    Editor(study.transcription,{study=study.copy(transcription=it)},
                        "انقل النص الذي قرأته بنفسك...",5)
                    Section("تحليلي العلمي | Scholarly Analysis")
                    Editor(study.analysis,{study=study.copy(analysis=it)},
                        "اكتب تفسيرك التاريخي ودليلك وملاحظاتك...",4)
                    Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){
                        OutlinedTextField(study.person,{study=study.copy(person=it)},
                            Modifier.weight(1f),label={Text("الأعلام")},singleLine=true,
                            textStyle=TextStyle(fontSize=12.sp))
                        OutlinedTextField(study.dateText,{study=study.copy(dateText=it)},
                            Modifier.weight(.8f),label={Text("التاريخ")},singleLine=true,
                            textStyle=TextStyle(fontSize=12.sp))
                        OutlinedTextField(study.place,{study=study.copy(place=it)},
                            Modifier.weight(.8f),label={Text("المكان")},singleLine=true,
                            textStyle=TextStyle(fontSize=12.sp))
                    }
                    Section("البحث بالذكاء الاصطناعي | Text Research")
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        Button(onClick={research(false)},enabled=!busy&&study.transcription.isNotBlank()){
                            Text("✦ تحليل النص | Analyze",fontSize=12.sp)
                        }
                        Button(onClick={research(true)},enabled=!busy&&study.transcription.isNotBlank(),
                            colors=ButtonDefaults.buttonColors(containerColor=navy)){
                            Text("⌕ بحث تاريخي | Sources",fontSize=12.sp)
                        }
                    }
                    if(busy)Row(verticalAlignment=Alignment.CenterVertically){
                        CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=3.dp)
                        Text("جار البحث... قد تُحتسب تكلفة OpenRouter",fontSize=11.sp)
                    }
                    if(study.machine.isNotBlank()){
                        Surface(color=Color(0xFFEAF5F1),shape=RoundedCornerShape(8.dp)){
                            Column(Modifier.fillMaxWidth().padding(12.dp)){
                                Text("تحليل آلي استرشادي • "+study.model,color=petrol,fontSize=12.sp)
                                Spacer(Modifier.height(6.dp))
                                SelectionContainer{
                                    Text(study.machine,fontSize=14.sp,lineHeight=24.sp)
                                }
                            }
                        }
                    }
                    Section("مراجع محتملة للتحقق | Sources")
                    val refs=study.refs()
                    if(refs.isEmpty())Text("لا توجد روابط مصادر مستخرجة من نتائج البحث. لا تعتمد استشهادًا غير متحقق.",
                        color=Color.Gray,fontSize=12.sp)
                    else refs.forEachIndexed{idx,ref->
                        Surface(onClick={
                            try{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(ref.url)))}
                            catch(_:Exception){notice("تعذر فتح المصدر")}
                        },color=soft,shape=RoundedCornerShape(7.dp)){
                            Column(Modifier.fillMaxWidth().padding(9.dp)){
                                Text((idx+1).toString()+". "+ref.title,fontSize=12.sp,fontWeight=FontWeight.Bold)
                                Text(ref.url,fontSize=10.sp,color=petrol,maxLines=2)
                            }
                        }
                    }
                    Text("افتح الروابط وتحقق من نص المصدر وبياناته قبل الاستشهاد.",
                        color=amber,fontSize=11.sp)
                    HorizontalDivider()
                    Text("شواهد الصفحة | Witnesses ("+notes.size+")",
                        fontWeight=FontWeight.Bold,fontSize=13.sp)
                    LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        items(notes,key={it.id}){other->
                            FilterChip(selected=other.id==n.id,onClick={selectNote(other)},
                                label={Text(other.title.take(16))})
                        }
                    }
                    Text("حفظ آلي • TXT وDOCX يدمجان كل شاهد بنصه وتحليله.",
                        color=Color.Gray,fontSize=11.sp)
                }
            }
        }
    }
    @Composable private fun Section(label:String){
        Surface(color=soft,shape=RoundedCornerShape(6.dp)){
            Text(label,modifier=Modifier.fillMaxWidth().padding(horizontal=9.dp,vertical=7.dp),
                fontWeight=FontWeight.Bold,fontSize=14.sp)
        }
    }
    @Composable private fun Tool(ar:String,en:String,onClick:()->Unit){
        OutlinedButton(onClick,modifier=Modifier.height(40.dp),
            contentPadding=PaddingValues(horizontal=8.dp,vertical=1.dp)){
            Column(horizontalAlignment=Alignment.CenterHorizontally){
                Text(ar,fontSize=11.sp,maxLines=1);Text(en,fontSize=9.sp)
            }
        }
    }
    @Composable private fun SettingsDialog(){
        var key by remember{mutableStateOf("")}
        var model by remember{mutableStateOf(settings.model)}
        var clear by remember{mutableStateOf(false)}
        AlertDialog(onDismissRequest={settingsOpen=false},title={Text("OpenRouter | Settings")},text={
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text("الذكاء الاصطناعي لا يقرأ الصورة مطلقًا. يُرسل فقط النص الذي كتبته والسياق البحثي. البحث الشبكي قد يكون مدفوعًا.",
                    fontSize=12.sp,color=amber)
                OutlinedTextField(model,{model=it},modifier=Modifier.fillMaxWidth(),
                    label={Text("نموذج نصي | Model")},singleLine=true)
                OutlinedTextField(key,{key=it},modifier=Modifier.fillMaxWidth(),
                    visualTransformation=PasswordVisualTransformation(),
                    label={Text(if(settings.apiKey().isBlank())"API Key" else "مفتاح جديد (الحالي مشفر)")},
                    singleLine=true)
                Row(verticalAlignment=Alignment.CenterVertically){
                    Checkbox(clear,{clear=it})
                    Text("إزالة المفتاح المحفوظ",fontSize=12.sp)
                }
            }
        },confirmButton={Button(enabled=model.isNotBlank(),onClick={
            settings.model=model.trim()
            if(clear)settings.saveApiKey("")
            else if(key.isNotBlank())settings.saveApiKey(key)
            settingsOpen=false
        }){Text("حفظ")}},dismissButton={TextButton(onClick={settingsOpen=false}){Text("إلغاء")}})
    }
}

@Composable private fun Editor(value:String,onChange:(String)->Unit,hint:String,lines:Int){
    var editing by remember{mutableStateOf(TextFieldValue(value))}
    val history=remember{mutableStateListOf<String>()}
    val future=remember{mutableStateListOf<String>()}
    LaunchedEffect(value){if(editing.text!=value)editing=TextFieldValue(value)}
    fun update(next:TextFieldValue){
        if(next.text!=editing.text){
            history.add(editing.text)
            if(history.size>70)history.removeAt(0)
            future.clear()
        }
        editing=next;onChange(next.text)
    }
    fun insert(str:String){
        val a=editing.selection.min;val b=editing.selection.max
        update(TextFieldValue(editing.text.replaceRange(a,b,str),
            selection=TextRange(a+str.length)))
    }
    Column{
        Row(verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(4.dp)){
            OutlinedButton(enabled=history.isNotEmpty(),onClick={
                future.add(editing.text);editing=TextFieldValue(history.removeAt(history.lastIndex));onChange(editing.text)
            },contentPadding=PaddingValues(horizontal=9.dp)){Text("↶")}
            OutlinedButton(enabled=future.isNotEmpty(),onClick={
                history.add(editing.text);editing=TextFieldValue(future.removeAt(future.lastIndex));onChange(editing.text)
            },contentPadding=PaddingValues(horizontal=9.dp)){Text("↷")}
            OutlinedButton(onClick={insert("[غير مقروء]")},contentPadding=PaddingValues(horizontal=9.dp)){
                Text("[غير مقروء]",fontSize=11.sp)
            }
            OutlinedButton(onClick={insert("[؟]")},contentPadding=PaddingValues(horizontal=9.dp)){
                Text("[؟]",fontSize=11.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(value.trim().split(Regex("\\s+")).count{it.isNotBlank()}.toString()+" كلمة",
                fontSize=10.sp,color=Color.Gray)
        }
        OutlinedTextField(editing,onValueChange={update(it)},
            modifier=Modifier.fillMaxWidth(),minLines=lines,maxLines=15,
            placeholder={Text(hint,fontSize=14.sp)},
            textStyle=TextStyle(fontSize=17.sp,lineHeight=29.sp,textAlign=TextAlign.Start),
            shape=RoundedCornerShape(8.dp))
    }
}
