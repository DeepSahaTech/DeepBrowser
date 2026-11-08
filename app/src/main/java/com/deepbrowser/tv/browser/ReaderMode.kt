package com.deepbrowser.tv.browser

/**
 * TV reader mode: restyles the main text of an article page for 10-foot reading (big type, one column, dark).
 * Inject [SCRIPT] on demand, then call `window.__dbReader()` to toggle; it returns "on", "off" or "none" (no article found).
 * shortcut: picks <article>/<main>/the densest text block, no Readability-grade cleanup; upgrade to Mozilla Readability if pages look wrong.
 */
object ReaderMode {
    val SCRIPT: String = """
(function(){
  if(window.__dbReader)return;
  var box=null;
  function best(){
    var c=document.querySelector('article')||document.querySelector('main')||document.querySelector('[role=main]');
    if(c&&(c.innerText||'').length>400)return c;
    var bestEl=null,bs=0;
    document.querySelectorAll('div,section').forEach(function(d){
      var n=0;for(var i=0;i<d.children.length;i++){if(d.children[i].tagName==='P')n+=(d.children[i].innerText||'').length;}
      if(n>bs){bs=n;bestEl=d;}
    });
    return bs>400?bestEl:null;
  }
  window.__dbReaderScroll=function(dy){if(box)box.scrollBy(0,dy);};
  window.__dbReader=function(){
    if(box){box.remove();box=null;document.documentElement.style.overflow='';return 'off';}
    var src=best();if(!src)return 'none';
    box=document.createElement('div');
    box.setAttribute('style','position:fixed;inset:0;z-index:2147483000;overflow:auto;background:#0B1020;color:#EAF0FF;padding:5vh 12vw;font:30px/1.6 system-ui,sans-serif');
    var h=document.createElement('h1');h.textContent=document.title;h.setAttribute('style','font-size:48px;line-height:1.2;margin:0 0 32px');
    box.appendChild(h);
    src.querySelectorAll('h1,h2,h3,p,li,blockquote,img').forEach(function(e){
      if(e.tagName==='IMG'){if(e.naturalWidth<300)return;var im=document.createElement('img');im.src=e.currentSrc||e.src;im.setAttribute('style','max-width:100%;border-radius:12px;margin:16px 0');box.appendChild(im);return;}
      var t=(e.innerText||'').trim();if(!t||e.closest('nav,aside,footer,header'))return;
      var x=document.createElement(e.tagName);x.textContent=t;
      x.setAttribute('style','margin:0 0 22px;'+(e.tagName[0]==='H'?'font-size:38px;margin-top:36px':''));
      box.appendChild(x);
    });
    document.body.appendChild(box);box.tabIndex=0;box.focus();
    return 'on';
  };
})();
""".trimIndent()
}
