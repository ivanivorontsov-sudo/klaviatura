// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.bg

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** shared JS / helpers for driving a logged-in web chat (visible in AiBackgroundActivity, hidden in BgGenerateActivity) */
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

    /** "login" when the page shows a login form / has no prompt input, "ok" otherwise */
    const val STATE = """(function(){return (!!document.querySelector('input[type=password]')||!document.querySelector('textarea,[contenteditable=true]'))?'login':'ok';})()"""

    /** JSON array of candidate result images (filters: min side 512, no QR/avatar/logo, not inside header/nav/login) */
    const val SCAN = """(function(){var r=[];document.querySelectorAll('img').forEach(function(i){
        var w=i.naturalWidth,h=i.naturalHeight,s=i.currentSrc||i.src,a=(i.alt||'')+' '+(i.className||'');
        if(Math.min(w,h)<512)return; if(/qr|avatar|logo|icon|favicon|emoji|sprite|badge|profile|login|captcha/i.test(s.split('?')[0].slice(-120)+' '+a))return;
        if(i.closest('header,nav,button,[role=dialog],[class*=login],[class*=Login],[class*=qr],[class*=QR],[class*=avatar]'))return;
        r.push(s);});
        var login=!!document.querySelector('input[type=password]')||!document.querySelector('textarea,[contenteditable=true]');
        return JSON.stringify(login?[]:r);})()"""

    fun inject(prompt: String, submit: Boolean): String {
        val p = org.json.JSONObject.quote(prompt)
        return """(function(){var p=$p;var el=document.querySelector('textarea')||document.querySelector('[contenteditable=true]');
            if(!el)return 'noinput';el.focus();if(el.tagName==='TEXTAREA'){var s=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set;s.call(el,p);
            el.dispatchEvent(new Event('input',{bubbles:true}));}else{document.execCommand('insertText',false,p);}
            if($submit){setTimeout(function(){var b=document.querySelector('[data-testid*=send i],button[aria-label*=Send i],button[aria-label*=Отправ i],#send-message-button,button[type=submit]');
              if(b&&!b.disabled){b.click();}else{['keydown','keypress','keyup'].forEach(function(t){el.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));});}},700);}
            return 'ok';})()"""
    }

    /** fetch an image inside the page (with its cookies) and pass it to KlBridge.onData / onFail */
    fun fetch(url: String): String {
        val u = org.json.JSONObject.quote(url)
        return """(function(){fetch($u,{credentials:'include'}).then(function(r){return r.blob();}).then(function(b){
            var f=new FileReader();f.onload=function(){KlBridge.onData(f.result);};f.readAsDataURL(b);})
            .catch(function(e){KlBridge.onFail($u);});})()"""
    }

    /** center-crop to w:h and scale */
    fun cropTo(src: Bitmap, w: Int, h: Int): Bitmap {
        val target = w.toFloat() / h
        var cw = src.width; var ch = (cw / target).roundToInt()
        if (ch > src.height) { ch = src.height; cw = (ch * target).roundToInt() }
        val cropped = Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw.coerceAtLeast(1), ch.coerceAtLeast(1))
        return Bitmap.createScaledBitmap(cropped, w, h, true)
    }

    fun pollinationsUrl(prompt: String, w: Int, h: Int, seed: Int): String =
        "https://image.pollinations.ai/prompt/" + java.net.URLEncoder.encode(prompt, "UTF-8").replace("+", "%20") +
            "?width=$w&height=$h&nologo=true&seed=$seed"
}
