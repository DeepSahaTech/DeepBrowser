package com.deepbrowser.tv.browser

/**
 * Overlay / scam-popup removal: the rules as data, the same rules as pure Kotlin predicates (JVM-testable),
 * and the single injected JS function built from that data so the two cannot drift.
 * Ad scripts inject (a) a full-screen max-z-index about:blank iframe showing a fake "verify you are human / scan QR"
 * page, (b) small text overlays with the same message, (c) full-page max-z-index click-trap divs.
 */
object OverlayRules {
    /** JS regex source (also valid java.util.regex); case-insensitive. */
    const val SCAM_PATTERN = "not a robot|scan the qr|qr-?code with your phone|verify (that )?you are (a )?human|click allow|press allow"
    const val MIN_IFRAME_Z = 2_147_483_000L
    const val TRAP_Z_STYLE = "2147483647"
    /** A text overlay is only a scam candidate if its visible text is shorter than this. */
    const val MAX_TEXT = 500
    /** A max-z-index div is a click trap if it covers more than this fraction of the viewport in both axes. */
    const val TRAP_COVER = 0.8
    const val DEBOUNCE_MS = 200
    const val FALLBACK_MS = 2500

    private val scam = Regex(SCAM_PATTERN, RegexOption.IGNORE_CASE)

    fun isScamText(text: String): Boolean = text.isNotEmpty() && text.length < MAX_TEXT && scam.containsMatchIn(text)

    /** Fixed, max-z-index iframe with no real source. [zIndex] is the computed value (NaN/null when `auto`). */
    fun isOverlayIframe(position: String, zIndex: Long?, src: String): Boolean =
        position == "fixed" && zIndex != null && zIndex >= MIN_IFRAME_Z &&
            (src.isEmpty() || src.startsWith("about:") || src.startsWith("javascript:"))

    fun isClickTrap(w: Double, h: Double, viewW: Double, viewH: Double, hasVideo: Boolean): Boolean =
        !hasVideo && w > viewW * TRAP_COVER && h > viewH * TRAP_COVER

    /**
     * One scan(root) over iframes, max-z divs and text overlays; ONE debounced MutationObserver feeding it the added
     * nodes; ONE slow timer rescanning the whole document. Nodes containing a <video> are never removed.
     */
    val JS: String = """
  var SCAM=new RegExp('$SCAM_PATTERN','i');
  function blankSrc(s){return !s||s.indexOf('about:')===0||s.indexOf('javascript:')===0;}
  function scan(root){
    try{
      var list=[root].concat([].slice.call(root.querySelectorAll('div,section,aside,iframe,dialog')));
      for(var i=0;i<list.length;i++){
        var n=list[i];
        if(!n.isConnected)continue;
        if(n.tagName==='IFRAME'){
          var st=getComputedStyle(n);
          if(st.position==='fixed'&&parseInt(st.zIndex,10)>=$MIN_IFRAME_Z&&blankSrc(n.getAttribute('src')||'')){console.log('DBADS removed overlay iframe');n.remove();}
          continue;
        }
        if(n.tagName==='DIV'){
          var sa=n.getAttribute('style')||'';
          if(sa.indexOf('z-index: $TRAP_Z_STYLE')>=0||sa.indexOf('z-index:$TRAP_Z_STYLE')>=0){
            var r=n.getBoundingClientRect();
            if(r.width>innerWidth*$TRAP_COVER&&r.height>innerHeight*$TRAP_COVER&&!n.querySelector('video')){n.remove();continue;}
          }
        }
        var t=n.innerText||'';
        if(t.length>0&&t.length<$MAX_TEXT&&SCAM.test(t)&&!n.querySelector('video')){
          var el=n;
          while(el.parentElement&&el.parentElement!==document.body&&el.parentElement.innerText.length<$MAX_TEXT)el=el.parentElement;
          console.log('DBADS removed '+el.tagName+'#'+el.id+'.'+el.className+' html='+el.outerHTML.slice(0,300));
          el.remove();
        }
      }
    }catch(e){}
  }
  var pend=[],timer=0;
  function flushPending(){timer=0;var p=pend;pend=[];for(var i=0;i<p.length;i++)if(p[i].isConnected)scan(p[i]);}
  try{new MutationObserver(function(ms){
    for(var i=0;i<ms.length;i++){var a=ms[i].addedNodes;for(var j=0;j<a.length;j++)if(a[j].nodeType===1)pend.push(a[j]);}
    if(pend.length&&!timer)timer=setTimeout(flushPending,$DEBOUNCE_MS);
  }).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
  function scanAll(){scan(document.body||document.documentElement);}
  scanAll();setInterval(scanAll,$FALLBACK_MS);
"""
}
