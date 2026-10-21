package com.alwarraqa.app

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

private val ink=Color(0xFF192D38)
private val teal=Color(0xFF14565C)
private val gold=Color(0xFFC18B46)
private val paper=Color(0xFFF6F4EE)
private val noteTypes=listOf("تملك","وقف","إجازة","سماع","نسخ","حرد","مقابلة","ختم","تصحيح","تاريخ","أخرى")

/** Native, standalone scientific manuscript colophon/inscription analyzer. */
class QoyoodActivity: ComponentActivity() {
    private lateinit var store:Store
    private lateinit var docs:ResearchDocs
    private lateinit var settings:AnalyzerSettings
    private var screen by mutableStateOf("home")
    private var project by mutableStateOf<Project?>(null)
    private var page by mutableStateOf<Page?>(null)
    private var revision by mutableIntStateOf(0)
    private var selecting by mutableStateOf(false)
    private var showingMarkers by mutableStateOf(true)
    private var currentNote by mutableStateOf<Note?>(null)
    private var editNote by mutableStateOf<Note?>(null)
    private var newRect by mutableStateOf<FloatArray?>(null)
    private var noteDialog by mutableStateOf(false)
    private var projectDialog by mutableStateOf(false)
    private var settingsDialog by mutableStateOf(false)
    private var aiResult by mutableStateOf<String?>(null)
    private var aiBusy by mutableStateOf(false)
    private var imageView:RegionImageView?=null

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        store=Store(this);docs=ResearchDocs(this);settings=AnalyzerSettings(this)
        window.statusBarColor=android.graphics.Color.rgb(25,45,56)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl){
                MaterialTheme(colorScheme=lightColorScheme(primary=teal,secondary=gold,background=paper,surface=Color.White,onSurface=ink)){
                    Surface(Modifier.fillMaxSize(),color=paper){Shell()}
                }
            }
        }
    }
    private fun toast(text:String)=runOnUiThread{Toast.makeText(this,text,Toast.LENGTH_LONG).show()}
    private fun refresh(){revision++}
    private fun openProject(p:Project){project=p;screen="project"}
    private fun openPage(p:Page,focus:Note?=null){
        page=p;currentNote=focus;selecting=false;showingMarkers=true;imageView=null;screen="image"
    }
    private fun back(){
        when(screen){
            "image","writing"->{screen="project";page=null;currentNote=null}
            "project"->{screen="home";project=null}
        }
    }
    private fun runJob(label:String,job:()->Unit){
        Thread{
            try{job();runOnUiThread{refresh();toast("اكتمل "+label)}}
            catch(e:Exception){toast("تعذر "+label+": "+(e.message?:"خطأ غير معروف").take(180))}
        }.start()
    }
    @Composable private fun Shell(){
        val tick=revision
        val chooseImages=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){items->
            val p=project
            if(p!=null&&items.isNotEmpty())runJob("إضافة الصور"){items.forEach{store.addPage(p.id,it)}}
        }
        val importBackup=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri:Uri?->
            if(uri!=null)runJob("استعادة المشروع"){
                val id=store.restore(uri)
                runOnUiThread{store.projects().firstOrNull{it.id==id}?.let{p->openProject(p)}}
            }
        }
        val exportZip=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri:Uri?->
            val p=project
            if(uri!=null&&p!=null)runJob("النسخ الاحتياطي"){store.backup(p,uri)}
        }
        val exportPdf=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")){uri:Uri?->
            val p=project
            if(uri!=null&&p!=null)runJob("تقرير PDF"){Report.pdf(this,store,p,uri)}
        }
        val exportMd=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")){uri:Uri?->
            val p=project
            if(uri!=null&&p!=null)runJob("تصدير المسودة"){
                contentResolver.openOutputStream(uri)?.use{out->out.write(docs.read(p.id).toByteArray(Charsets.UTF_8))}
                    ?: error("تعذر إنشاء الملف")
            }
        }
        BackHandler(screen!="home"){back()}
        Column(Modifier.fillMaxSize()){
            Row(Modifier.fillMaxWidth().background(ink).height(72.dp).padding(horizontal=18.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                if(screen!="home")TextButton(onClick={back()}){Text("‹ رجوع",color=Color.White)}
                Column(Modifier.weight(1f)){
                    Text("مُحَلِّلُ القُيُود",color=Color.White,fontSize=23.sp,fontWeight=FontWeight.Bold)
                    Text(when(screen){
                        "home"->"التقييدات المخطوطة • القراءة والتوثيق"
                        "project"->project?.title?:""
                        "image"->"الشواهد البصرية • "+(page?.name?:"")
                        else->"المحرر البحثي • "+(project?.title?:"")
                    },fontSize=11.sp,color=Color(0xFFE2CBAA),maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                TextButton(onClick={settingsDialog=true}){Text("⚙ إعدادات AI",color=Color.White)}
            }
            when(screen){
                "home"->HomeScreen(onNew={projectDialog=true},onRestore={importBackup.launch("*/*")})
                "project"->ProjectScreen(
                    onImages={chooseImages.launch(arrayOf("image/*"))},
                    onBackup={exportZip.launch("qoyood-project.zip")},
                    onPdf={exportPdf.launch("qoyood-report.pdf")},
                    onWriting={screen="writing"})
                "image"->ImageScreen()
                "writing"->WritingScreen(onExport={exportMd.launch("qoyood-study.md")})
            }
        }
        if(projectDialog) {
            var title by remember{mutableStateOf("")}
            var description by remember{mutableStateOf("")}
            AlertDialog(onDismissRequest={projectDialog=false},title={Text("مشروع مخطوط جديد")},text={
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                    OutlinedTextField(title,{title=it},label={Text("اسم المخطوط / المجموعة")},singleLine=true)
                    OutlinedTextField(description,{description=it},label={Text("رقم الحفظ، المكتبة، الوصف")},maxLines=3)
                }
            },confirmButton={Button(enabled=title.isNotBlank(),onClick={
                val id=store.addProject(title.trim(),description.trim())
                refresh();projectDialog=false;store.projects().firstOrNull{it.id==id}?.let{openProject(it)}
            }){Text("إنشاء")}},dismissButton={TextButton(onClick={projectDialog=false}){Text("إلغاء")}})
        }
        if(noteDialog){
            QoyoodNoteDialog(initial=editNote,onDismiss={noteDialog=false},
                onDelete={
                    editNote?.let{store.deleteNote(it.id)}
                    noteDialog=false;currentNote=null;refresh()
                },onSave={kind,title,seen,reading,certainty->
                    val p=page
                    val n=editNote
                    val region=if(n!=null)floatArrayOf(n.x,n.y,n.w,n.h) else newRect
                    if(p!=null&&region!=null){
                        store.saveNote(p.id,region,kind,title,seen,reading,certainty,n?.id)
                        refresh()
                    }
                    noteDialog=false;selecting=false;currentNote=null
                })
        }
        if(settingsDialog) QoyoodSettingsDialog(settings,onDismiss={settingsDialog=false})
        if(aiBusy) AlertDialog(onDismissRequest={},title={Text("جار تحليل القيد")},text={
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                CircularProgressIndicator(Modifier.size(29.dp))
                Text("يُرسل الاقتطاع إلى OpenRouter. قد يستغرق التحليل دقيقة.")
            }
        },confirmButton={})
        aiResult?.let{result->
            AlertDialog(onDismissRequest={aiResult=null},title={Text("نتيجة التحليل • مسودة تحتاج مراجعة")},
                text={
                    Column(Modifier.heightIn(max=470.dp).verticalScroll(rememberScrollState())){
                        Text("لا تعتمد القراءة دون مقابلة الصورة الأصلية. لم يُعدّل النموذج بياناتك.",color=gold,fontSize=12.sp)
                        Spacer(Modifier.height(12.dp))
                        SelectionContainerSafe(result)
                    }
                },confirmButton={
                    Column{
                        Button(onClick={
                            val p=project
                            if(p!=null)docs.save(p.id,docs.read(p.id)+"\n\n## قراءة آلية – للمراجعة\n\n"+result+"\n")
                            aiResult=null;toast("أُضيفت المسودة إلى محرر البحث")
                        }){Text("إضافة إلى المحرر")}
                        val n=currentNote
                        if(n!=null)TextButton(onClick={
                            store.saveNote(n.pageId,floatArrayOf(n.x,n.y,n.w,n.h),n.category,n.title,
                                n.observation,n.interpretation+"\n\n[مسودة آلية – بحاجة إلى مراجعة]\n"+result,n.confidence,n.id)
                            aiResult=null;refresh();toast("أُضيفت المسودة إلى ملاحظة الشاهد")
                        }){Text("إلحاق بالشاهد")}
                    }
                },dismissButton={TextButton(onClick={aiResult=null}){Text("إغلاق")}})
        }
    }

    @Composable private fun HomeScreen(onNew:()->Unit,onRestore:()->Unit){
        val projects=remember(revision){store.projects()}
        Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            Text("المكتبة البحثية",fontSize=23.sp,fontWeight=FontWeight.Bold,color=ink)
            Text("من الصورة إلى موضع القيد، ثم القراءة والتفسير والتوثيق.",fontSize=13.sp,color=Color.Gray)
            Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){
                Button(onClick=onNew){Text("+ مشروع جديد")}
                OutlinedButton(onClick=onRestore){Text("استعادة ZIP")}
            }
            if(projects.isEmpty())Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center){
                Text("ابدأ بإنشاء مشروع لإضافة الصور وفحص التقييدات.",color=Color.Gray)
            }else LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)){
                items(projects,key={it.id}){p->
                    ElevatedCard(onClick={openProject(p)},modifier=Modifier.fillMaxWidth(),
                        colors=CardDefaults.elevatedCardColors(containerColor=Color.White)){
                        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                            Text(p.title,fontSize=18.sp,fontWeight=FontWeight.Bold)
                            if(p.description.isNotBlank())Text(p.description,fontSize=12.sp,color=Color.Gray)
                            Text(store.pages(p.id).size.toString()+" صور · "+store.allNotes(p.id).size+" قيد/شاهد",
                                color=teal,fontSize=12.sp)
                        }
                    }
                }
            }
        }
    }

    @Composable private fun ProjectScreen(onImages:()->Unit,onBackup:()->Unit,onPdf:()->Unit,onWriting:()->Unit){
        val p=project?:return
        val pages=remember(revision,p.id){store.pages(p.id)}
        val notes=remember(revision,p.id){store.allNotes(p.id)}
        var search by remember(p.id){mutableStateOf("")}
        Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(onClick=onImages){Text("+ صور")}
                Button(onClick=onWriting,colors=ButtonDefaults.buttonColors(containerColor=ink)){Text("✎ محرر البحث")}
                OutlinedButton(onClick=onPdf,enabled=notes.isNotEmpty()){Text("تقرير PDF")}
                OutlinedButton(onClick=onBackup){Text("نسخة ZIP")}
            }
            OutlinedTextField(search,{search=it},modifier=Modifier.fillMaxWidth(),
                singleLine=true,label={Text("ابحث في القراءات والتقييدات والعناوين")})
            val matches=notes.filter{(img,n)->search.isNotBlank() &&
                (img.name+" "+n.title+" "+n.category+" "+n.observation+" "+n.interpretation).contains(search,ignoreCase=true)}
            Text(if(search.isBlank())"الصور ("+pages.size+")" else "نتائج البحث ("+matches.size+")",
                fontSize=18.sp,fontWeight=FontWeight.Bold)
            LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){
                if(search.isBlank())items(pages,key={it.id}){img->
                    ElevatedCard(onClick={openPage(img)},colors=CardDefaults.elevatedCardColors(containerColor=Color.White)){
                        Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.spacedBy(12.dp)){
                            QoyoodThumbnail(img.path,Modifier.size(75.dp))
                            Column(Modifier.weight(1f)){
                                Text(img.name,fontWeight=FontWeight.Bold)
                                Text(store.notes(img.id).size.toString()+" شواهد • فتح الصورة والتحليل",fontSize=12.sp,color=teal)
                            }
                            Text("‹",fontSize=25.sp,color=gold)
                        }
                    }
                }else items(matches,key={it.second.id}){(img,n)->
                    ElevatedCard(onClick={openPage(img,n)},colors=CardDefaults.elevatedCardColors(containerColor=Color.White)){
                        Column(Modifier.padding(13.dp)){
                            Text(n.title+" · "+img.name,fontWeight=FontWeight.Bold)
                            Text(n.category+" · "+n.observation,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                            Text("اذهب إلى موضع القيد ←",color=teal,fontSize=12.sp)
                        }
                    }
                }
            }
        }
    }

    @Composable private fun ImageScreen(){
        val img=page?:return
        val notes=remember(revision,img.id){store.notes(img.id)}
        val wide=LocalConfiguration.current.screenWidthDp>=750
        var selection by remember(img.id){mutableStateOf(currentNote)}
        LaunchedEffect(currentNote?.id){selection=currentNote;currentNote?.let{n->imageView?.focus(n)}}
        @Composable fun controls(){
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal=8.dp,vertical=7.dp),
                horizontalArrangement=Arrangement.spacedBy(5.dp),verticalAlignment=Alignment.CenterVertically){
                Button(onClick={selecting=!selecting},colors=ButtonDefaults.buttonColors(containerColor=if(selecting)gold else teal)){
                    Text(if(selecting)"إلغاء" else "▣ حدد قيدًا")
                }
                OutlinedButton(onClick={imageView?.zoomBy(1.35f)},contentPadding=PaddingValues(horizontal=10.dp)){Text("＋")}
                OutlinedButton(onClick={imageView?.zoomBy(1f/1.35f)},contentPadding=PaddingValues(horizontal=10.dp)){Text("−")}
                OutlinedButton(onClick={imageView?.resetView()},contentPadding=PaddingValues(horizontal=10.dp)){Text("ملاءمة")}
                OutlinedButton(onClick={showingMarkers=!showingMarkers},
                    contentPadding=PaddingValues(horizontal=10.dp)){Text(if(showingMarkers)"إخفاء العلامات" else "إظهار")}
                Spacer(Modifier.weight(1f))
                Button(onClick={analyzeNow(img,selection)},enabled=!aiBusy){Text("✦ تحليل AI")}
            }
        }
        @Composable fun visual(modifier:Modifier){
            Column(modifier){
                controls()
                Text(if(selecting)"اسحب فوق القيد لرسم مستطيل • إلغاء للتصفح" else
                    "كبّر بإصبعين • حرّك الصورة • المس علامة لفتح الشاهد",
                    fontSize=11.sp,color=Color.Gray,modifier=Modifier.padding(horizontal=12.dp,vertical=4.dp))
                androidx.compose.runtime.key(img.id){
                    AndroidView(factory={context->
                        RegionImageView(context).also{view->
                            view.setImage(img.path)
                            imageView=view
                        }
                    },modifier=Modifier.fillMaxWidth().weight(1f),
                        update={view->
                            imageView=view
                            view.notes=if(showingMarkers)notes else emptyList()
                            view.selectMode=selecting
                            view.onRegionCreated={r->
                                newRect=r;editNote=null;selecting=false;noteDialog=true
                            }
                            view.onNoteTapped={n->currentNote=n}
                        })
                }
                Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){
                    Text(img.name+" · "+notes.size+" شاهد",fontSize=12.sp,color=Color.Gray,modifier=Modifier.weight(1f))
                    TextButton(onClick={
                        val ps=store.pages(img.projectId);val i=ps.indexOfFirst{it.id==img.id}
                        if(i>0)openPage(ps[i-1])
                    }){Text("السابق")}
                    TextButton(onClick={
                        val ps=store.pages(img.projectId);val i=ps.indexOfFirst{it.id==img.id}
                        if(i>=0&&i+1<ps.size)openPage(ps[i+1])
                    }){Text("التالي")}
                }
            }
        }
        @Composable fun noteList(modifier:Modifier){
            Column(modifier.background(Color.White).padding(10.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Text("الشواهد ("+notes.size+")",fontWeight=FontWeight.Bold,fontSize=17.sp,modifier=Modifier.weight(1f))
                    if(selection!=null)TextButton(onClick={selection=null;currentNote=null;imageView?.resetView()}){Text("الكل")}
                }
                if(notes.isEmpty())Text("اضغط «حدد قيدًا» ثم ارسم مستطيلًا على الموضع.",fontSize=12.sp,color=Color.Gray)
                LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)){
                    items(notes,key={it.id}){n->
                        Card(onClick={currentNote=n;imageView?.focus(n)},
                            colors=CardDefaults.cardColors(containerColor=if(selection?.id==n.id) Color(0xFFFFF0D8) else paper)){
                            Column(Modifier.padding(9.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
                                Text(n.title,fontWeight=FontWeight.Bold,fontSize=13.sp,maxLines=2)
                                Text(n.category+" · "+n.confidence,color=teal,fontSize=11.sp)
                                if(n.interpretation.isNotBlank())Text(n.interpretation,fontSize=12.sp,
                                    maxLines=3,overflow=TextOverflow.Ellipsis)
                                Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){
                                    TextButton(onClick={currentNote=n;imageView?.focus(n)}){Text("موضعه",fontSize=11.sp)}
                                    TextButton(onClick={editNote=n;newRect=null;noteDialog=true}){Text("تحرير",fontSize=11.sp)}
                                }
                            }
                        }
                    }
                }
            }
        }
        if(wide) Row(Modifier.fillMaxSize()){
            visual(Modifier.weight(1f).fillMaxHeight())
            noteList(Modifier.width(310.dp).fillMaxHeight())
        } else Column(Modifier.fillMaxSize()){
            visual(Modifier.weight(1f))
            noteList(Modifier.fillMaxWidth().heightIn(min=140.dp,max=215.dp))
        }
    }

    private fun analyzeNow(img:Page,n:Note?){
        val key=settings.apiKey()
        if(key.isBlank()){settingsDialog=true;toast("احفظ مفتاح OpenRouter أولًا");return}
        if(aiBusy)return
        aiBusy=true
        Thread{
            try {
                val answer=OpenRouterVision.analyze(img,n,key,settings.model,settings.prompt)
                runOnUiThread{aiBusy=false;currentNote=n;aiResult=answer}
            }catch(e:Exception){
                runOnUiThread{aiBusy=false;toast("فشل التحليل: "+(e.message?:"اتصال غير متاح").take(300))}
            }
        }.start()
    }

    @Composable private fun WritingScreen(onExport:()->Unit){
        val p=project?:return
        var draft by remember(p.id){mutableStateOf(TextFieldValue(docs.read(p.id)))}
        val history=remember(p.id){mutableStateListOf<String>()}
        val future=remember(p.id){mutableStateListOf<String>()}
        var preview by remember{mutableStateOf(false)}
        var find by remember{mutableStateOf("")}
        var replace by remember{mutableStateOf("")}
        var showReplace by remember{mutableStateOf(false)}
        LaunchedEffect(p.id,draft.text){delay(500);docs.save(p.id,draft.text)}
        fun updateText(value:TextFieldValue){
            if(value.text!=draft.text){history.add(draft.text);if(history.size>60)history.removeAt(0);future.clear()}
            draft=value
        }
        fun wrap(prefix:String,suffix:String=prefix){
            val start=draft.selection.min;val end=draft.selection.max
            val txt=draft.text;val body=txt.substring(start,end)
            updateText(TextFieldValue(txt.substring(0,start)+prefix+body+suffix+txt.substring(end),
                selection=TextRange(start+prefix.length+body.length)))
        }
        fun line(prefix:String){
            val start=draft.selection.min
            val pos=draft.text.lastIndexOf('\n',(start-1).coerceAtLeast(0))+1
            updateText(TextFieldValue(draft.text.substring(0,pos)+prefix+draft.text.substring(pos),
                selection=TextRange(start+prefix.length)))
        }
        Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(7.dp)){
                Text("محرر البحث",fontWeight=FontWeight.Bold,fontSize=20.sp,color=ink)
                Spacer(Modifier.weight(1f))
                Text(draft.text.trim().split(Regex("\\s+")).count{it.isNotBlank()}.toString()+" كلمة",fontSize=12.sp,color=Color.Gray)
                OutlinedButton(onClick={preview=!preview}){Text(if(preview)"التحرير" else "المعاينة")}
                Button(onClick={docs.save(p.id,draft.text);onExport()}){Text("تصدير Markdown")}
            }
            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){
                OutlinedButton(onClick={line("# ")},contentPadding=PaddingValues(horizontal=10.dp)){Text("عنوان")}
                OutlinedButton(onClick={line("## ")},contentPadding=PaddingValues(horizontal=10.dp)){Text("فرعي")}
                OutlinedButton(onClick={wrap("**")},contentPadding=PaddingValues(horizontal=10.dp)){Text("عريض")}
                OutlinedButton(onClick={wrap("*")},contentPadding=PaddingValues(horizontal=10.dp)){Text("مائل")}
                OutlinedButton(onClick={line("- ")},contentPadding=PaddingValues(horizontal=10.dp)){Text("قائمة")}
                OutlinedButton(onClick={line("> ")},contentPadding=PaddingValues(horizontal=10.dp)){Text("اقتباس")}
                TextButton(enabled=history.isNotEmpty(),onClick={
                    future.add(draft.text)
                    draft=TextFieldValue(history.removeAt(history.lastIndex))
                }){Text("↶")}
                TextButton(enabled=future.isNotEmpty(),onClick={
                    history.add(draft.text)
                    draft=TextFieldValue(future.removeAt(future.lastIndex))
                }){Text("↷")}
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedTextField(find,{find=it},modifier=Modifier.weight(1f),singleLine=true,
                    label={Text("بحث داخل النص")})
                Button(onClick={
                    val offset= if(draft.selection.max<draft.text.length)draft.selection.max else 0
                    val idx=if(find.isBlank())-1 else draft.text.indexOf(find,offset,ignoreCase=true)
                    val found=if(idx>=0)idx else if(find.isNotBlank())draft.text.indexOf(find,0,ignoreCase=true) else -1
                    if(found>=0)draft=draft.copy(selection=TextRange(found,found+find.length))
                    else toast("لا توجد نتيجة")
                }){Text("ابحث")}
                TextButton(onClick={showReplace=!showReplace}){Text("استبدال")}
            }
            if(showReplace)Row(verticalAlignment=Alignment.CenterVertically){
                OutlinedTextField(replace,{replace=it},modifier=Modifier.weight(1f),singleLine=true,label={Text("النص البديل")})
                Button(enabled=find.isNotBlank(),onClick={
                    val start=draft.selection.min;val end=draft.selection.max
                    if(end>start&&draft.text.substring(start,end).equals(find,ignoreCase=true)){
                        updateText(TextFieldValue(draft.text.replaceRange(start,end,replace),selection=TextRange(start+replace.length)))
                    }else toast("حدد نتيجة البحث أولًا")
                }){Text("استبدل المحدد")}
            }
            Text("مسودة البحث • تُحفظ تلقائيًا داخل الجهاز كل نصف ثانية",color=Color.Gray,fontSize=11.sp)
            if(preview)Surface(Modifier.fillMaxSize(),color=Color.White,shape=RoundedCornerShape(10.dp)){
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)){
                    Text(draft.text.ifBlank{"لا يوجد نص للمعاينة."},fontSize=16.sp,lineHeight=27.sp,color=ink)
                }
            } else OutlinedTextField(draft,onValueChange={updateText(it)},
                modifier=Modifier.fillMaxSize(),
                textStyle=androidx.compose.ui.text.TextStyle(fontSize=17.sp,lineHeight=29.sp,textAlign=TextAlign.Start),
                placeholder={Text("ابدأ بصياغة الدراسة... يمكنك إضافة نتائج تحليل الشواهد مع مراجعتها.")})
        }
    }

    @Composable private fun QoyoodThumbnail(path:String,modifier:Modifier){
        val bitmap=remember(path){
            val opts=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeFile(path,opts)
            var sample=1;while(maxOf(opts.outWidth,opts.outHeight)/sample>300)sample*=2
            BitmapFactory.decodeFile(path,BitmapFactory.Options().apply{inSampleSize=sample})
        }
        if(bitmap!=null)Image(bitmap.asImageBitmap(),contentDescription="صورة",modifier=modifier,contentScale=ContentScale.Crop)
        else Box(modifier.background(paper),contentAlignment=Alignment.Center){Text("صورة")}
    }
}

@Composable private fun SelectionContainerSafe(content:String) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(content,fontSize=14.sp,lineHeight=25.sp,color=ink)
    }
}
@Composable private fun QoyoodSettingsDialog(settings:AnalyzerSettings,onDismiss:()->Unit){
    var model by remember{mutableStateOf(settings.model)}
    var key by remember{mutableStateOf("")}
    var prompt by remember{mutableStateOf(settings.prompt)}
    var clearKey by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("اتصال OpenRouter • تحليل بصري")},text={
        Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max=530.dp),
            verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("يُرسل فقط الاقتطاع الذي تختاره؛ وعند عدم تحديد شاهد تُرسل الصفحة كاملة. لا ترسل صورًا غير مأذون بإرسالها.",
                color=Color(0xFF925F20),fontSize=12.sp,lineHeight=20.sp)
            OutlinedTextField(value=model,onValueChange={model=it},modifier=Modifier.fillMaxWidth(),
                label={Text("معرّف النموذج الداعم للصور")},singleLine=true)
            Text("مثال: google/gemini-2.5-flash • راجع دعم الصور وتكلفة النموذج قبل الاستخدام.",
                fontSize=11.sp,color=Color.Gray)
            OutlinedTextField(key,{key=it},modifier=Modifier.fillMaxWidth(),
                visualTransformation=PasswordVisualTransformation(),singleLine=true,
                label={Text(if(settings.apiKey().isNotEmpty())"مفتاح جديد (الحالي محفوظ مشفرًا)" else "مفتاح OpenRouter API")})
            Row(verticalAlignment=Alignment.CenterVertically){
                Checkbox(checked=clearKey,onCheckedChange={clearKey=it})
                Text("حذف المفتاح المحفوظ",fontSize=12.sp)
            }
            OutlinedTextField(prompt,{prompt=it},modifier=Modifier.fillMaxWidth(),
                label={Text("تعليمات إضافية للنموذج (اختياري)")},minLines=2,maxLines=4)
            Text("لا تُرسل المفاتيح ضمن نص المطالبة. النتائج مسودات وتحتاج إلى مراجعة علمية.",
                fontSize=11.sp,color=Color.Gray)
        }
    },confirmButton={Button(enabled=model.isNotBlank(),onClick={
        settings.model=model
        settings.prompt=prompt
        if(clearKey)settings.saveApiKey("")
        else if(key.isNotBlank())settings.saveApiKey(key)
        onDismiss()
    }){Text("حفظ الإعدادات")}},dismissButton={TextButton(onClick=onDismiss){Text("إلغاء")}})
}
@Composable private fun QoyoodNoteDialog(initial:Note?,onDismiss:()->Unit,onDelete:()->Unit,
    onSave:(String,String,String,String,String)->Unit){
    var kind by remember(initial?.id){mutableStateOf(initial?.category?:"تملك")}
    var title by remember(initial?.id){mutableStateOf(initial?.title?:"")}
    var seen by remember(initial?.id){mutableStateOf(initial?.observation?:"")}
    var reading by remember(initial?.id){mutableStateOf(initial?.interpretation?:"")}
    var confidence by remember(initial?.id){mutableStateOf(initial?.confidence?:"غير محسوم")}
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(initial==null)"تسجيل شاهد جديد" else "تحرير شاهد")},text={
        Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max=510.dp),
            verticalArrangement=Arrangement.spacedBy(9.dp)){
            Text("نوع القيد",fontWeight=FontWeight.Bold)
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement=Arrangement.spacedBy(5.dp)){
                items(noteTypes){cat->FilterChip(selected=kind==cat,onClick={kind=cat},label={Text(cat)})}
            }
            OutlinedTextField(title,{title=it},label={Text("عنوان الشاهد / الموضع")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(seen,{seen=it},label={Text("وصف بصري مباشر")},modifier=Modifier.fillMaxWidth(),minLines=2)
            OutlinedTextField(reading,{reading=it},label={Text("القراءة الدبلوماسية / التفسير")},modifier=Modifier.fillMaxWidth(),minLines=4)
            Text("درجة اليقين")
            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){
                listOf("مؤكد","محتمل","غير محسوم").forEach{v->
                    FilterChip(selected=confidence==v,onClick={confidence=v},label={Text(v)})
                }
            }
        }
    },confirmButton={Button(enabled=title.isNotBlank(),onClick={
        onSave(kind,title.trim(),seen.trim(),reading.trim(),confidence)
    }){Text("حفظ الشاهد")}},dismissButton={
        Row {
            if(initial!=null)TextButton(onClick=onDelete){Text("حذف",color=Color.Red)}
            TextButton(onClick=onDismiss){Text("إلغاء")}
        }
    })
}
