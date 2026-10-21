package com.alwarraqa.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Local Markdown rendering without WebView, networking or a web runtime. */
@Composable
fun QoyoodMarkdownPreview(markdown:String){
    Column(Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState())
        .padding(horizontal=22.dp,vertical=17.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
        if(markdown.isBlank()){
            Text("لا يوجد نص للمعاينة.",color=Color.Gray)
        }else markdown.lines().forEach { raw ->
            val trimmed=raw.trimStart()
            val h=trimmed.takeWhile{it=='#'}.length
            val heading=h in 1..5 && trimmed.getOrNull(h)==' '
            val quote=trimmed.startsWith("> ")
            val bullet=trimmed.startsWith("- ") || trimmed.startsWith("* ")
            val content=when{
                heading->trimmed.drop(h+1)
                quote || bullet->trimmed.drop(2)
                else->raw
            }
            val text=if(bullet)"• "+content else content
            val size=when{
                heading && h==1->24.sp
                heading && h==2->21.sp
                heading->18.sp
                else->16.sp
            }
            if(text.isBlank())Spacer(Modifier.height(6.dp))
            else Text(inlineMarkdown(text),style=TextStyle(
                color=if(heading)Color(0xFF14565C) else Color(0xFF1F313B),
                fontWeight=if(heading)FontWeight.Bold else FontWeight.Normal,
                fontSize=size,lineHeight=if(heading)30.sp else 27.sp
            ),modifier=if(quote)Modifier.padding(start=20.dp) else Modifier)
        }
    }
}
private fun inlineMarkdown(input:String)=buildAnnotatedString{
    val pattern=Regex("""(\*\*(.+?)\*\*|\*(.+?)\*|_(.+?)_)""")
    var idx=0
    pattern.findAll(input).forEach{ m->
        append(input.substring(idx,m.range.first))
        when{
            m.groups[2]!=null->withStyle(SpanStyle(fontWeight=FontWeight.Bold)){append(m.groups[2]!!.value)}
            m.groups[3]!=null->withStyle(SpanStyle(fontStyle=FontStyle.Italic)){append(m.groups[3]!!.value)}
            else->withStyle(SpanStyle(fontStyle=FontStyle.Italic)){append(m.groups[4]?.value ?: "")}
        }
        idx=m.range.last+1
    }
    append(input.substring(idx))
}
