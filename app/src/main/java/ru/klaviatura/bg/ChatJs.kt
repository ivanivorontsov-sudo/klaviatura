// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.bg

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * JS for driving a logged-in web chat inside a WebView (1.4.0 rewrite):
 * robust prompt input (textarea / ProseMirror / Quill contenteditable), real send (send button, then Enter, then form submit),
 * image capture (generated <img> incl. ones wrapped in buttons, blob:/data: URLs, CSS backgrounds), fetched as data URL.
 * The JS is plain ES5-ish and also unit-tested in node against mock pages (tools/chatjs-test.js).
 */
object ChatJs {
    val CHATS = listOf(
        "Qwen" to "https://chat.qwen.ai/",
        "ChatGPT" to "https://chatgpt.com/",
        "Gemini" to "https://gemini.google.com/app",
        "Grok" to "https://grok.com/",
        "Алиса" to "https://alice.yandex.ru/",
        "Copilot" to "https://copilot.microsoft.com/",
        "Шедеврум" to "https://shedevrum.ai/",
    )

    private const val COMMON = """
        function klVis(e){if(!e||!e.getBoundingClientRect)return false;var r=e.getBoundingClientRect();
          var s=window.getComputedStyle?getComputedStyle(e):null;return r.width>0&&(r.height>0||e.isContentEditable)&&(!s||(s.visibility!=='hidden'&&s.display!=='none'));}
        function klInput(){var sels=['#prompt-textarea','div.ProseMirror[contenteditable=true]','textarea#chat-input','#chat-input',
          'rich-textarea .ql-editor','div.ql-editor[contenteditable=true]','textarea[placeholder]','textarea',
          '[contenteditable=true][role=textbox]','[contenteditable=true]','[contenteditable=plaintext-only]'];
          for(var i=0;i<sels.length;i++){var l=document.querySelectorAll(sels[i]);for(var j=0;j<l.length;j++)if(klVis(l[j]))return l[j];}
          return null;}
        function klText(el){return el?((el.value!==undefined&&el.tagName!=='DIV')?el.value:(el.innerText||el.textContent||'')):'';}
    """

    /** "login" when the page shows a login form / has no prompt input, "ok" otherwise */
    const val STATE = """(function(){$COMMON
        var pw=document.querySelector('input[type=password]');return ((pw&&klVis(pw))||!klInput())?'login':'ok';})()"""

    /** puts the prompt into the chat input with events the page's framework (React/ProseMirror/Quill) notices */
    fun inject(prompt: String): String {
        val p = org.json.JSONObject.quote(prompt)
        return """(function(){$COMMON
            var p=$p;var el=klInput();if(!el)return 'noinput';
            el.focus();
            if(el.tagName==='TEXTAREA'||el.tagName==='INPUT'){
              var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
              var setter=Object.getOwnPropertyDescriptor(proto,'value').set;setter.call(el,p);
              try{el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:p}));}catch(e){el.dispatchEvent(new Event('input',{bubbles:true}));}
              el.dispatchEvent(new Event('change',{bubbles:true}));
            }else{
              try{var sel=window.getSelection();var r=document.createRange();r.selectNodeContents(el);sel.removeAllRanges();sel.addRange(r);}catch(e){}
              var ok=false;try{ok=document.execCommand('insertText',false,p);}catch(e){}
              if(!ok||klText(el).indexOf(p.slice(0,16))<0){
                try{el.dispatchEvent(new InputEvent('beforeinput',{bubbles:true,cancelable:true,inputType:'insertText',data:p}));}catch(e){}
                if(klText(el).indexOf(p.slice(0,16))<0){while(el.firstChild)el.removeChild(el.firstChild);var para=document.createElement('p');para.textContent=p;el.appendChild(para);}
                try{el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:p}));}catch(e){el.dispatchEvent(new Event('input',{bubbles:true}));}
              }
            }
            window.__klEl=el;window.__klPrompt=p;window.__klTries=0;
            return klText(el).indexOf(p.slice(0,16))>=0?'ok':'typed?';})()"""
    }

    /**
     * one send attempt; call repeatedly (~every 0.8 s) until it returns "sent".
     * try 1-3: enabled send button (known selectors, then the last enabled icon button near the input), then Enter, then form submit.
     */
    const val SUBMIT = """(function(){$COMMON
        var el=window.__klEl||klInput();var p=window.__klPrompt||'';
        if(!el)return 'noinput';var cur=klInput()||el;
        if(window.__klTries>0&&(!cur||klText(cur).indexOf(p.slice(0,16))<0))return 'sent';
        var t=window.__klTries=(window.__klTries||0)+1;if(t>8)return 'failed';
        function usable(b){return klVis(b)&&!b.disabled&&b.getAttribute('aria-disabled')!=='true';}
        var sels=['button[data-testid=send-button]','#composer-submit-button','#send-message-button','button[aria-label*=Send i]',
          'button[aria-label*=Отправ i]','button[aria-label*=Submit i]','button.send-button','button[class*=send i]','[data-testid*=send i]',
          'button[type=submit]','[role=button][aria-label*=Send i]','[role=button][aria-label*=Отправ i]'];
        if(t<=4){for(var i=0;i<sels.length;i++){var l=document.querySelectorAll(sels[i]);for(var j=0;j<l.length;j++)if(usable(l[j])){l[j].click();return 'clicked';}}}
        if(t===2||t===5){var c=el;for(var k=0;k<5&&c;k++){c=c.parentElement;if(!c)break;
          var bs=Array.prototype.filter.call(c.querySelectorAll('button'),function(b){return usable(b)&&b.querySelector('svg');});
          if(bs.length){bs[bs.length-1].click();return 'clicked-near';}}}
        if(el){el.focus();var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};
          el.dispatchEvent(new KeyboardEvent('keydown',o));el.dispatchEvent(new KeyboardEvent('keypress',o));el.dispatchEvent(new KeyboardEvent('keyup',o));}
        if(t>=6){var f=el&&el.closest?el.closest('form'):null;if(f){try{if(f.requestSubmit)f.requestSubmit();else f.submit();return 'form';}catch(e){}}}
        return 'enter';})()"""

    /** JSON array of {s: url, w, h} of candidate result images (big, loaded, not avatars/logos/QR codes, not login UI) */
    const val SCAN = """(function(){$COMMON
        var r=[],seen={};var bad=/qr|avatar|logo|icon|favicon|emoji|sprite|badge|profile|captcha|placeholder/i;
        function ok(e,s){if(!s||seen[s])return false;if(bad.test(s.split('?')[0].slice(-120)+' '+(e.alt||'')+' '+(typeof e.className==='string'?e.className:'')))return false;
          if(e.closest&&e.closest('header,nav,[role=dialog],[class*=login i],[class*=avatar i],[class*=qr i]'))return false;return true;}
        var imgs=document.querySelectorAll('img');
        for(var i=0;i<imgs.length;i++){var e=imgs[i];var s=e.currentSrc||e.src;var w=e.naturalWidth,h=e.naturalHeight;
          if(!e.complete||Math.min(w,h)<256)continue;var b=e.getBoundingClientRect();if(b.width<100||b.height<80)continue;
          if(!ok(e,s))continue;seen[s]=1;r.push({s:s,w:w,h:h});}
        var divs=document.querySelectorAll('[style*=background-image]');
        for(var i=0;i<divs.length;i++){var e=divs[i];var m=/url\(["']?([^"')]+)["']?\)/.exec(e.style.backgroundImage||'');if(!m)continue;
          var b=e.getBoundingClientRect();if(b.width<200||b.height<150)continue;if(!ok(e,m[1]))continue;seen[m[1]]=1;r.push({s:m[1],w:Math.round(b.width),h:Math.round(b.height)});}
        var pw=document.querySelector('input[type=password]');var login=(pw&&klVis(pw))||!klInput();
        return JSON.stringify(login?[]:r);})()"""

    /** fetch an image inside the page (its cookies / blob: URLs) and pass it to KlBridge.onData / onFail */
    fun fetch(url: String): String {
        val u = org.json.JSONObject.quote(url)
        return """(function(){var u=$u;
            function send(b){var f=new FileReader();f.onload=function(){KlBridge.onData(f.result);};f.onerror=function(){KlBridge.onFail(u);};f.readAsDataURL(b);}
            function viaCanvas(){try{var im=Array.prototype.filter.call(document.images,function(i){return (i.currentSrc||i.src)===u;})[0];
              if(!im)throw 0;var c=document.createElement('canvas');c.width=im.naturalWidth;c.height=im.naturalHeight;c.getContext('2d').drawImage(im,0,0);
              KlBridge.onData(c.toDataURL('image/jpeg',0.92));}catch(e){KlBridge.onFail(u);}}
            var same=u.indexOf('blob:')===0||u.indexOf(location.origin)===0;
            fetch(u,same?{credentials:'include'}:{mode:'cors',credentials:'omit'}).then(function(r){if(!r.ok)throw r.status;return r.blob();})
              .then(send).catch(function(){fetch(u,{credentials:'include'}).then(function(r){return r.blob();}).then(send).catch(viaCanvas);});})()"""
    }

    /** parses the SCAN result into (url, minSide) pairs */
    fun parseScan(res: String?): List<Pair<String, Int>> = try {
        val arr = org.json.JSONArray(org.json.JSONTokener(res).nextValue() as String)
        (0 until arr.length()).map { val o = arr.getJSONObject(it); o.getString("s") to minOf(o.optInt("w"), o.optInt("h")) }
    } catch (e: Exception) { emptyList() }

    /** center-crop to w:h and scale */
    fun cropTo(src: Bitmap, w: Int, h: Int, bottomCut: Float = 0f): Bitmap {
        val usableH = (src.height * (1f - bottomCut)).roundToInt().coerceAtLeast(1)
        val target = w.toFloat() / h
        var cw = src.width; var ch = (cw / target).roundToInt()
        if (ch > usableH) { ch = usableH; cw = (ch * target).roundToInt() }
        val cropped = Bitmap.createBitmap(src, (src.width - cw) / 2, (usableH - ch) / 2, cw.coerceAtLeast(1), ch.coerceAtLeast(1))
        return Bitmap.createScaledBitmap(cropped, w, h, true)
    }

    fun pollinationsUrl(prompt: String, w: Int, h: Int, seed: Int): String =
        "https://image.pollinations.ai/prompt/" + java.net.URLEncoder.encode(prompt, "UTF-8").replace("+", "%20") +
            "?width=$w&height=$h&nologo=true&seed=$seed"

    /** Pollinations draws from English prompts (a Russian prompt gave unrelated pictures): translation request */
    fun translateUrl(text: String): String =
        "https://text.pollinations.ai/" + java.net.URLEncoder.encode("Translate to English. Reply with the translation only, no quotes: $text", "UTF-8").replace("+", "%20")

    fun hasCyrillic(s: String) = s.any { it in '\u0400'..'\u04FF' }
}
