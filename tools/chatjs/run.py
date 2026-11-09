#!/usr/bin/env python3
"""Tests the WebView chat JS (ChatJs.kt) in headless Chrome against mock chat pages.
   python3 tools/chatjs/run.py   (needs google-chrome)"""
import json, os, re, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
KT = os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'java', 'ru', 'klaviatura', 'bg', 'ChatJs.kt')
src = open(KT, encoding='utf-8').read()

def raw(name):
    m = re.search(r'const val ' + name + r' = """(.*?)"""', src, re.S)
    return m.group(1)
COMMON = raw('COMMON')
def sub(js): return js.replace('$COMMON', COMMON)
STATE, SUBMIT, SCAN = sub(raw('STATE')), sub(raw('SUBMIT')), sub(raw('SCAN'))
inj = re.search(r'fun inject\(prompt: String\): String \{.*?return """(.*?)"""', src, re.S).group(1)
PROMPT = 'Сгенерируй изображение: северное сияние'
INJECT = sub(inj).replace('$p', json.dumps(PROMPT, ensure_ascii=False))

MOCKS = {
 'chatgpt': '''<div id="prompt-textarea" class="ProseMirror" contenteditable="true"><p></p></div>
   <button data-testid="send-button" disabled><svg></svg></button><div id="out"></div>
   <script>var ed=document.getElementById('prompt-textarea'),b=document.querySelector('[data-testid=send-button]');
   ed.addEventListener('input',function(){b.disabled=!ed.innerText.trim();});
   b.onclick=function(){if(b.disabled)return;window.SENT=ed.innerText;ed.innerHTML='<p></p>';b.disabled=true;setTimeout(gen,300);};</script>''',
 'qwen': '''<textarea id="chat-input" placeholder="Как я могу помочь?"></textarea><button id="send-message-button" disabled><svg></svg></button>
   <script>var t=document.getElementById('chat-input'),b=document.getElementById('send-message-button');var state='';
   var d=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value');
   t.addEventListener('input',function(){state=d.get.call(t);b.disabled=!state;});
   b.onclick=function(){if(!state)return;window.SENT=state;d.set.call(t,'');state='';b.disabled=true;setTimeout(gen,300);};</script>''',
 'gemini': '''<rich-textarea><div class="ql-editor" contenteditable="true"><p><br></p></div></rich-textarea>
   <div class="box"><button class="send-button" aria-label="Send message" aria-disabled="true"><svg></svg></button></div>
   <script>var ed=document.querySelector('.ql-editor'),b=document.querySelector('.send-button');
   ed.addEventListener('input',function(){b.setAttribute('aria-disabled',ed.innerText.trim()?'false':'true');});
   b.onclick=function(){if(b.getAttribute('aria-disabled')==='true')return;window.SENT=ed.innerText;ed.innerHTML='<p><br></p>';setTimeout(gen,300);};</script>''',
 'enter-only': '''<form><textarea placeholder="Ask"></textarea><button type="button" aria-label="Attach"><svg></svg></button></form>
   <script>var t=document.querySelector('textarea');t.addEventListener('keydown',function(e){if(e.key==='Enter'&&t.value){window.SENT=t.value;t.value='';setTimeout(gen,300);}});</script>''',
 'login': '''<form><input type="text" name="user"><input type="password" name="pw"></form>''',
}
GEN = '''<script>function gen(){var c=document.createElement('canvas');c.width=1024;c.height=1024;var g=c.getContext('2d');g.fillStyle='#3a6';g.fillRect(0,0,1024,1024);
  var btn=document.createElement('button');var im=new Image();im.width=400;im.alt='Generated image';im.src=c.toDataURL('image/png');btn.appendChild(im);document.body.appendChild(btn);}
  </script><img src="data:image/gif;base64,R0lGODlhAQABAAAAACw=" class="avatar" width="40">'''

HARNESS = '''<script>window.KlBridge={onData:function(d){window.DATA=d.length;},onFail:function(u){window.FAIL=u;}};
var log=[];function ev(js){try{return eval(js);}catch(e){return 'ERR '+e;}}
setTimeout(function(){log.push('state='+ev(%s));log.push('inject='+ev(%s));var n=0;
 var iv=setInterval(function(){var r=ev(%s);log.push('submit='+r);if(r==='sent'||r==='failed'||++n>10){clearInterval(iv);
   setTimeout(function(){var sc=ev(%s);log.push('scan='+(sc&&sc.length>200?sc.slice(0,60)+'…['+JSON.parse(sc).length+']':sc));log.push('SENT='+(window.SENT||''));
     var pre=document.createElement('pre');pre.id='result';pre.textContent=log.join('\\n');document.body.appendChild(pre);},800);}},400);},200);</script>'''

def run():
    ok = True
    for name, body in MOCKS.items():
        html = '<!doctype html><html><body>' + GEN + body + (HARNESS % (json.dumps(STATE), json.dumps(INJECT), json.dumps(SUBMIT), json.dumps(SCAN))) + '</body></html>'
        f = tempfile.NamedTemporaryFile('w', suffix='.html', delete=False, encoding='utf-8'); f.write(html); f.close()
        out = subprocess.run(['google-chrome', '--headless=new', '--no-sandbox', '--disable-gpu', '--virtual-time-budget=15000',
                              '--dump-dom', 'file://' + f.name], capture_output=True, text=True, timeout=120).stdout
        os.unlink(f.name)
        m = re.search(r'<pre id="result">(.*?)</pre>', out, re.S)
        res = m.group(1) if m else 'NO RESULT'
        print('==', name); print(res)
        if name == 'login':
            ok &= 'state=login' in res
        else:
            ok &= ('SENT=' + PROMPT) in res and '[1]' in res
    print('ALL OK' if ok else 'FAILURES'); return ok

if __name__ == '__main__':
    sys.exit(0 if run() else 1)
