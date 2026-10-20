/* Beam Tablet v1 — local UI only. No API key is ever stored in browser storage. */
(() => {
  'use strict';
  const STORE = 'beam_tablet_conversations_v1';
  const SETTINGS = 'beam_tablet_preferences_v1';
  const $ = id => document.getElementById(id);
  const native = () => window.BeamNativeBridge;
  const uid = () => `${Date.now()}_${Math.random().toString(36).slice(2, 9)}`;
  let chats = [], activeId = null, activeRun = null, hasKey = false, toastTimer = 0, flushTimer = 0;
  let prefs = { temperature: 0.4, system: 'أجب بلغة المستخدم، وإذا كان السؤال بالعربية فاستخدم العربية الفصحى. كن دقيقًا وصريحًا بشأن أي شك، ولا تخترع المراجع أو النتائج.' };
  const escapeHTML = text => String(text).replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
  const nowTime = () => new Date().toLocaleTimeString('ar-EG', {hour:'2-digit', minute:'2-digit'});

  try {
    const stored = JSON.parse(localStorage.getItem(STORE) || '[]');
    if (Array.isArray(stored)) chats = stored.filter(x => x && typeof x.id === 'string' && Array.isArray(x.messages)).slice(0, 40);
    const config = JSON.parse(localStorage.getItem(SETTINGS) || '{}');
    if (config && typeof config === 'object') {
      if (typeof config.temperature === 'number') prefs.temperature = Math.max(0, Math.min(1, config.temperature));
      if (typeof config.system === 'string') prefs.system = config.system.slice(0, 1800);
    }
  } catch (_) { chats = []; }

  function saveChats() {
    try {
      chats = chats.slice(0, 40);
      // Capped to protect WebView's localStorage quota.
      const lightweight = chats.map(c => ({...c, messages: c.messages.slice(-70).map(m => ({...m, content: m.content.slice(-45000)}))}));
      localStorage.setItem(STORE, JSON.stringify(lightweight));
    } catch (_) { toast('مساحة التخزين ممتلئة؛ صدّر المحادثات واحذف القديمة.'); }
  }
  function savePrefs() { localStorage.setItem(SETTINGS, JSON.stringify(prefs)); }
  function toast(text) {
    $('toast').textContent = String(text || '').slice(0, 280);
    $('toast').classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => $('toast').classList.remove('show'), 3800);
  }
  function getChat(id) { return chats.find(x => x.id === id); }
  function currentChat() { return getChat(activeId); }
  function newChat() {
    const chat = {id:uid(), title:'محادثة جديدة', created:Date.now(), messages:[]};
    chats.unshift(chat);
    activeId = chat.id;
    saveChats(); renderAll(); closeDrawer();
    $('userInput').focus();
  }
  function selectChat(id) {
    if (!getChat(id)) return;
    activeId = id;
    renderAll(); closeDrawer();
  }
  function deleteChat(id) {
    const chat = getChat(id);
    if (!chat) return;
    if (activeRun && activeRun.chatId === id) { toast('أوقف التوليد قبل حذف المحادثة.'); return; }
    if (chat.messages.length && !confirm('حذف المحادثة المحفوظة نهائيًا من هذا الجهاز؟')) return;
    chats = chats.filter(c => c.id !== id);
    if (activeId === id) activeId = chats.length ? chats[0].id : null;
    saveChats(); renderAll();
  }
  function renderSidebar() {
    const list = $('conversationList');
    list.textContent = '';
    $('chatCount').textContent = String(chats.length);
    for (const chat of chats) {
      const item = document.createElement('button');
      item.className = `conversation-item${chat.id === activeId ? ' active' : ''}`;
      const pict = document.createElement('span'); pict.className = 'thread-icon'; pict.textContent = '▤';
      const title = document.createElement('span'); title.className = 'thread-title'; title.textContent = chat.title || 'محادثة جديدة';
      const del = document.createElement('span'); del.className = 'thread-delete'; del.title = 'حذف'; del.textContent = '×';
      del.addEventListener('click', e => {e.stopPropagation(); deleteChat(chat.id);});
      item.append(pict,title,del);
      item.addEventListener('click', () => selectChat(chat.id));
      list.append(item);
    }
  }
  function inlineMarkdown(s) {
    return escapeHTML(s)
      .replace(/`([^`\n]+)`/g, '<code>$1</code>')
      .replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
      .replace(/\*([^*\n]+)\*/g, '<em>$1</em>');
  }
  function renderTextBlock(text) {
    const lines = text.replace(/\r\n/g, '\n').split('\n');
    const out = [];
    let list = '';
    const closeList = () => { if (list) {out.push(`</${list}>`); list='';} };
    for (const line of lines) {
      if (!line.trim()) {closeList(); out.push('<div style="height:.55em"></div>'); continue;}
      let match;
      if ((match = line.match(/^(#{1,3})\s+(.+)$/))) { closeList(); const h = match[1].length; out.push(`<h${h}>${inlineMarkdown(match[2])}</h${h}>`); }
      else if (/^---+$/.test(line.trim())) { closeList(); out.push('<hr>'); }
      else if ((match = line.match(/^\s*[-*+]\s+(.+)$/))) { if (list !== 'ul') {closeList(); out.push('<ul>'); list='ul';} out.push(`<li>${inlineMarkdown(match[1])}</li>`); }
      else if ((match = line.match(/^\s*\d+[.)]\s+(.+)$/))) { if (list !== 'ol') {closeList(); out.push('<ol>'); list='ol';} out.push(`<li>${inlineMarkdown(match[1])}</li>`); }
      else {closeList(); out.push(`<div>${inlineMarkdown(line)}</div>`);}
    }
    closeList();
    return out.join('');
  }
  function markdown(input) {
    const source = String(input || '');
    const parts = source.split(/```([^\n`]*)\n([\s\S]*?)```/g);
    let html = '';
    for (let i=0; i < parts.length; i++) {
      if (i % 3 === 1) continue;
      if (i % 3 === 2) {
        html += `<div class="code-wrap"><button type="button" class="copy-code">نسخ</button><pre><code>${escapeHTML(parts[i])}</code></pre></div>`;
      } else html += renderTextBlock(parts[i]);
    }
    return html;
  }
  function updateMessageText() {
    if (!activeRun || activeRun.chatId !== activeId) return;
    const chat = getChat(activeRun.chatId); if (!chat) return;
    const message = chat.messages.find(m => m.id === activeRun.assistantId); if (!message) return;
    const body = document.querySelector(`[data-msg="${message.id}"] .message-content`);
    if (!body) return;
    body.innerHTML = message.content ? markdown(message.content) : '<span class="thinking">Beam يفكر ويكتب...</span>';
    const tail = document.querySelector(`[data-msg="${message.id}"] .message-tail`);
    if (tail) tail.textContent = message.content ? 'جارٍ التوليد…' : '';
    keepBottom();
  }
  function keepBottom(force=false) {
    const el = $('thread');
    if (force || el.scrollHeight - el.scrollTop - el.clientHeight < 230) el.scrollTop = el.scrollHeight;
  }
  function renderMessages() {
    const chat = currentChat();
    const items = chat?.messages || [];
    $('welcome').style.display = items.length ? 'none' : 'flex';
    const root = $('messages'); root.textContent='';
    for (const m of items) {
      const row = document.createElement('article'); row.className=`message ${m.role}`; row.dataset.msg = m.id;
      const avatar = document.createElement('div'); avatar.className='message-avatar'; avatar.textContent = m.role==='assistant'?'B':'أ';
      const main = document.createElement('div'); main.className='message-main';
      const top = document.createElement('div'); top.className='message-top';
      const who = document.createElement('strong'); who.textContent=m.role==='assistant'?'Beam':'أنت';
      const time = document.createElement('span'); time.textContent=m.time || '';
      top.append(who,time);
      const content = document.createElement('div'); content.className='message-content';
      if (m.role==='assistant') content.innerHTML = m.content ? markdown(m.content) : '<span class="thinking">Beam يفكر ويكتب...</span>';
      else content.textContent = m.content;
      const actions = document.createElement('div'); actions.className='message-actions';
      if (m.content) {const copy = document.createElement('button');copy.className='mini-action';copy.textContent='⎘ نسخ';copy.addEventListener('click',()=>copyText(m.content));actions.append(copy);}
      const tail = document.createElement('span'); tail.className='message-tail';
      if (m.tokens) tail.textContent=`${m.tokens.in} إدخال · ${m.tokens.out} إخراج`;
      else if (activeRun?.assistantId===m.id) tail.textContent='جارٍ التوليد…';
      else if (m.stopped) tail.textContent='تم إيقاف الاستجابة';
      actions.append(tail);
      main.append(top,content,actions);row.append(avatar,main);root.append(row);
    }
    if (items.length) keepBottom(true);
    else $('thread').scrollTop = 0;
  }
  function renderAll() {renderSidebar();renderMessages();renderSendState();}
  function copyText(text) {if (native()) {native().copy(String(text)); toast('تم النسخ إلى الحافظة');} else toast('النسخ متاح داخل تطبيق أندرويد.');}
  function renderSendState() {
    $('sendBtn').classList.toggle('busy', !!activeRun);
    $('sendBtn').title=activeRun?'إيقاف التوليد':'إرسال';
    $('sendIcon').style.display=activeRun?'none':'block';
    $('stopIcon').style.display=activeRun?'block':'none';
    $('userInput').disabled=!!activeRun;
  }
  function buildHistory(chat) {
    let selected=[], total=0;
    for (let i=chat.messages.length-1; i>=0; i--) {
      const m=chat.messages[i];
      if (!m.content || !['assistant','user'].includes(m.role)) continue;
      if (selected.length >= 28 || total + m.content.length > 130000) break;
      selected.unshift({role:m.role,content:m.content});total+=m.content.length;
    }
    return selected;
  }
  function send() {
    if (activeRun) {native()?.cancel(); toast('جارٍ إيقاف التوليد...');return;}
    const text=$('userInput').value.trim(); if (!text) return;
    if (!native()) {toast('تحتاج هذه الواجهة إلى تطبيق أندرويد الأصلي للاتصال بخدمة Ollama.');return;}
    if (!hasKey) {openSettings();toast('أضف مفتاح Ollama API أولًا.');return;}
    if (!activeId) newChat();
    const chat = currentChat();
    chat.messages.push({id:uid(),role:'user',content:text,time:nowTime()});
    if (chat.messages.filter(m=>m.role==='user').length===1) chat.title=text.replace(/\s+/g,' ').slice(0,43);
    const payload = buildHistory(chat);
    const assistantId=uid();
    chat.messages.push({id:assistantId,role:'assistant',content:'',time:nowTime()});
    const id=uid();
    activeRun={id,chatId:chat.id,assistantId};
    $('userInput').value='';resizeInput();saveChats();renderAll();
    try { native().send(JSON.stringify({id,messages:payload,system:prefs.system,temperature:prefs.temperature})); }
    catch (_) {finishRun('تعذّر استدعاء الواجهة الأصلية.');}
  }
  function finishRun(error=null, stopped=false, usage=null) {
    if (!activeRun) return;
    const chat=getChat(activeRun.chatId), msg=chat?.messages.find(m=>m.id===activeRun.assistantId);
    if (msg) {
      if (error) {msg.content += (msg.content?'\n\n':'')+`⚠ ${error}`;}
      if (stopped) {msg.stopped=true;if (!msg.content) msg.content='(تم إيقاف التوليد)';}
      if (usage) msg.tokens={in:usage.inputTokens||0,out:usage.outputTokens||0};
      if (!msg.content) msg.content='(لم يُنتج النموذج نصًا في هذه الاستجابة.)';
    }
    activeRun=null;saveChats();renderAll();
  }
  function onNativeEvent(event,data) {
    if (event==='keyStatus') {
      hasKey=!!data.saved;
      $('connectionLabel').classList.toggle('ready',hasKey);
      $('statusLabel').textContent=hasKey?'المفتاح محفوظ':'المفتاح غير مضبوط';
      $('keyInfo').textContent=hasKey?'✓ مفتاح محفوظ ومشفّر':'لا يوجد مفتاح محفوظ';
      $('keyInfo').classList.toggle('ok',hasKey);
    } else if (event==='notice') toast(data.text);
    else if (event==='error') {
      if (activeRun && data.id===activeRun.id) finishRun(data.text);
      else toast(data.text);
    } else if (event==='chunk' && activeRun && data.id===activeRun.id) {
      const chat=getChat(activeRun.chatId), msg=chat?.messages.find(m=>m.id===activeRun.assistantId);
      if (msg) {msg.content+=data.text || '';if (!flushTimer) flushTimer=setTimeout(()=>{flushTimer=0;updateMessageText();},90);}
    } else if (event==='done' && activeRun && data.id===activeRun.id) {
      clearTimeout(flushTimer);flushTimer=0;finishRun(null,false,data);
    } else if (event==='stopped' && activeRun && data.id===activeRun.id) {
      clearTimeout(flushTimer);flushTimer=0;finishRun(null,true);
    }
  }
  function openSettings() {$('settingsModal').classList.add('open');$('settingsScrim').classList.add('open');$('settingsModal').setAttribute('aria-hidden','false');$('apiKey').value='';}
  function closeSettings() {$('settingsModal').classList.remove('open');$('settingsScrim').classList.remove('open');$('settingsModal').setAttribute('aria-hidden','true');$('apiKey').value='';}
  function openDrawer() {$('sidebar').classList.add('show');$('shade').classList.add('show');}
  function closeDrawer() {$('sidebar').classList.remove('show');$('shade').classList.remove('show');}
  function back() {
    if ($('settingsModal').classList.contains('open')) closeSettings();
    else if ($('sidebar').classList.contains('show')) closeDrawer();
    else native()?.exit();
  }
  function resizeInput() {
    const el=$('userInput');el.style.height='auto';el.style.height=Math.min(el.scrollHeight,145)+'px';
    $('charCount').textContent=el.value.length>200?el.value.length.toLocaleString('en-US'):'';
  }
  function exportActive() {
    const chat=currentChat();if (!chat || !chat.messages.length) {toast('لا توجد رسائل للتصدير.');return;}
    const lines=[`Beam / Ollama Cloud`,chat.title,new Date(chat.created).toLocaleString('ar-EG'),'='.repeat(45),'',...chat.messages.map(m=>`${m.role==='assistant'?'Beam':'المستخدم'} — ${m.time}\n${m.content}\n`)];
    const text='\ufeff'+lines.join('\n\n');
    if (native()) native().exportText('beam-'+new Date().toISOString().slice(0,10)+'.txt',text);
    else toast('التصدير متاح داخل تطبيق أندرويد.');
  }

  $('menuToggle').addEventListener('click',openDrawer);
  $('closeDrawer').addEventListener('click',closeDrawer);
  $('shade').addEventListener('click',closeDrawer);
  $('newChat').addEventListener('click',newChat);
  $('openSettings').addEventListener('click',openSettings);
  $('headerSettings').addEventListener('click',openSettings);
  $('closeSettings').addEventListener('click',closeSettings);
  $('doneSettings').addEventListener('click',closeSettings);
  $('settingsScrim').addEventListener('click',closeSettings);
  $('exportBtn').addEventListener('click',exportActive);
  $('userInput').addEventListener('input',resizeInput);
  $('userInput').addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing){e.preventDefault();send();}});
  $('sendBtn').addEventListener('click',send);
  $('saveKey').addEventListener('click',()=>{const key=$('apiKey').value.trim();if(!native()){toast('يجب تشغيل الواجهة داخل تطبيق أندرويد.');return;}if(!key){toast('أدخل مفتاح API.');return;}native().saveKey(key);$('apiKey').value='';});
  $('removeKey').addEventListener('click',()=>{if(confirm('حذف مفتاح API المحفوظ من التطبيق؟'))native()?.clearKey();});
  $('toggleKey').addEventListener('click',()=>{$('apiKey').type=$('apiKey').type==='password'?'text':'password';});
  $('tempRange').addEventListener('input',e=>{prefs.temperature=Number(e.target.value);$('tempValue').textContent=prefs.temperature.toFixed(1);savePrefs();});
  $('systemPrompt').addEventListener('input',e=>{prefs.system=e.target.value.slice(0,1800);savePrefs();});
  document.querySelectorAll('.prompt-card').forEach(btn=>btn.addEventListener('click',()=>{$('userInput').value=btn.dataset.prompt;resizeInput();$('userInput').focus();}));
  $('messages').addEventListener('click',e=>{const btn=e.target.closest('.copy-code');if(btn){const text=btn.parentElement.querySelector('code')?.textContent;if(text!=null)copyText(text);}});
  document.addEventListener('keydown',e=>{if(e.key==='Escape'){closeDrawer();closeSettings();}});

  window.BeamNative={onEvent:onNativeEvent,back};
  $('tempRange').value=String(prefs.temperature);
  $('tempValue').textContent=prefs.temperature.toFixed(1);
  $('systemPrompt').value=prefs.system;
  activeId=chats[0]?.id || null;
  renderAll();resizeInput();
  if (native()) native().keyStatus();
  else { $('statusLabel').textContent='وضع استعراض الواجهة';toast('هذه الواجهة تعمل مع غلاف WebView الأصلي في APK.'); }
})();