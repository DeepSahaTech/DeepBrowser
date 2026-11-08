package com.deepbrowser.tv.browser

/**
 * JavaScript injected into every page. It only *reports* focusable elements to the native side
 * (DBBridge.snap / DBBridge.cur) and executes focus/click on request; the native FocusNav picks the next
 * target and animates the focus ring, so a D-pad press never waits on page JavaScript.
 * Idempotent: safe to inject several times per page.
 */
object SmartNav {
    val SCRIPT: String = """
(function(){
  try{
    var m=document.querySelector('meta[name=viewport]');
    if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}
    var W=/^https?:\/\/([a-z0-9-]+\.)*youtube\.com\/tv/i.test(location.href)?${Viewport.TV_LAYOUT_WIDTH}:${Viewport.LAYOUT_WIDTH};
    if(m.content!=='width='+W)m.content='width='+W;
  }catch(e){}
  if(window.__dbSel||/^https?:\/\/([a-z0-9-]+\.)*youtube\.com\/tv/i.test(location.href))return; // TV-native sites keep their own navigation
  var st=document.createElement('style');
  st.textContent=':fullscreen [data-db-focus],:-webkit-full-screen [data-db-focus]{outline:4px solid #8FB4FF!important;outline-offset:3px!important;border-radius:8px}';
  (document.head||document.documentElement).appendChild(st);
  var SEL='a[href],button,input:not([type=hidden]),select,textarea,summary,[tabindex]:not([tabindex="-1"]),[role=button],[role=link],[role=tab],[role=menuitem],[role=option],[onclick],video,[contenteditable=true],[contenteditable=""]';
  var cur=null,curId=-1,ids=[],idMap=new WeakMap(),cache=null,dirty=true,timer=0,lastPush=0;
  function fsEl(){return document.fullscreenElement||document.webkitFullscreenElement||null;}
  // Same-origin iframes: their elements join the snapshot with coordinates offset by the frame rect.
  // shortcut: cross-origin iframes are unreachable (contentDocument throws) and nesting is one level; upgrade = per-frame injection.
  function frameOff(d){var w=d.defaultView,f=w&&w.frameElement;if(!f)return null;var r=f.getBoundingClientRect();return {x:r.left+f.clientLeft,y:r.top+f.clientTop};}
  function rect(el){
    var r=el.getBoundingClientRect(),o=el.ownerDocument!==document&&frameOff(el.ownerDocument);
    if(!o)return r;
    return {left:r.left+o.x,top:r.top+o.y,right:r.right+o.x,bottom:r.bottom+o.y,width:r.width,height:r.height};
  }
  function frameEls(){
    var out=[],fr=document.querySelectorAll('iframe,frame');
    for(var i=0;i<fr.length&&i<8;i++){
      try{
        var d=fr[i].contentDocument,fb=fr[i].getBoundingClientRect();
        if(!d||!d.documentElement||fb.width<10||fb.height<10)continue;
        out=out.concat(Array.prototype.slice.call(d.querySelectorAll(SEL)));
      }catch(e){}
    }
    return out;
  }
  function idOf(el){var i=idMap.get(el);if(i===undefined){i=ids.length;ids.push(new WeakRef(el));idMap.set(el,i);}return i;}
  function byId(i){var w=ids[i];var el=w&&w.deref();return el&&el.isConnected?el:null;}
  function vis(el){
    if(el.disabled)return false;
    if(el.checkVisibility)return el.checkVisibility({checkOpacity:true,checkVisibilityCSS:true});
    var cs=getComputedStyle(el);return !(cs.visibility==='hidden'||cs.display==='none'||parseFloat(cs.opacity)===0);
  }
  try{new MutationObserver(function(){dirty=true;push(600);}).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
  var cardMemo=new WeakMap(),prim=new WeakMap();
  function similar(a,b){return Math.abs(a.width-b.width)<=a.width*0.3&&Math.abs(a.height-b.height)<=a.height*0.3;}
  // A "card" = smallest ancestor that repeats as a similarly-sized sibling (poster tile, list row, tab...). Whole card is one focus target.
  function cardOf(el){
    var m=cardMemo.get(el);
    if(m&&m.isConnected&&m.contains(el))return m;
    var vw=innerWidth,vh=innerHeight,A=el,res=el;
    for(var d=0;d<6&&A&&A!==document.body&&A!==document.documentElement;d++,A=A.parentElement){
      var ar=A.getBoundingClientRect();
      if(ar.width<60||ar.height<40)continue;
      if(ar.width*ar.height>vw*vh*0.3||ar.width>vw*0.75)break;
      var P=A.parentElement;if(!P)break;
      var kids=P.children;if(kids.length>80)continue;
      var hit=false;
      for(var i=0;i<kids.length;i++){var k=kids[i];if(k===A||k.tagName!==A.tagName)continue;if(similar(ar,k.getBoundingClientRect())){hit=true;break;}}
      if(hit){res=A;break;}
    }
    cardMemo.set(el,res);return res;
  }
  var dotGroups=[];
  function isLogo(el,r){
    if(el.tagName!=='A'||(el.innerText||'').trim()||r.width>72||r.height>72||r.top>150)return false;
    var h=el.getAttribute('href')||'';
    return h===''||h==='/'||h===location.origin||h===location.origin+'/'||/^\/?[?#]/.test(h);
  }
  // rating / year / duration / quality badges are information, not actions
  var META=/^(?:\d{1,3}(?:[.,]\d+)?|(?:19|20)\d{2}|\d+\s?(?:h|m|min|hr)(?:\s?\d+\s?m)?|4K|8K|HD|UHD|FHD|HDR|CC|SD|\d{1,2}\+|PG|PG-13|R|NC-17|TV-(?:MA|14|PG|Y\d?|G)|S\d+\s?[:·]?\s?E\d+)${'$'}/i;
  function isMeta(el,r){return r.height<=44&&META.test((el.innerText||'').trim()&&(el.innerText||'').trim());}
  function isDot(el,r){
    return !(el.innerText||'').trim()&&r.width<=90&&r.height<=32&&el.tagName!=='INPUT'&&!el.querySelector('svg,img');
  }
  function activeIdx(g){
    var i,e;
    for(i=0;i<g.length;i++){e=g[i];if(e.getAttribute('aria-current')==='true'||e.getAttribute('aria-selected')==='true'||e.getAttribute('aria-pressed')==='true')return i;}
    for(i=0;i<g.length;i++){e=g[i];var c=String((e.className&&e.className.baseVal)||e.className||'')+' '+(e.firstElementChild?String(e.firstElementChild.className||''):'');if(/active|current|selected/i.test(c))return i;}
    var wi=0,wm=0;g.forEach(function(x,j){var w=x.getBoundingClientRect().width;if(w>wm){wm=w;wi=j;}});return wi;
  }
  function isControl(el){var t=el.tagName;if(t==='A'){var tx=(el.innerText||'').trim(),rr=el.getBoundingClientRect();if(tx&&tx.length<=24&&rr.height<=64&&rr.width<=260)return true;}return t==='BUTTON'||t==='INPUT'||t==='SELECT'||t==='TEXTAREA'||t==='VIDEO'||el.getAttribute('role')==='button';}
  function snap(){
    var fs=fsEl(),all;
    if(fs){all=fs.querySelectorAll(SEL);}
    else{if(dirty||!cache){cache=Array.prototype.slice.call(document.querySelectorAll(SEL));dirty=false;}all=cache.concat(frameEls());}
    var vh=innerHeight,vw=innerWidth,out=[];
    for(var i=0;i<all.length&&out.length<250;i++){
      var el=all[i],r=rect(el);
      if(r.width<10||r.height<10||r.bottom<-vh*0.3||r.top>vh*1.3||r.right<-vw*0.3||r.left>vw*1.3)continue;
      if(!vis(el))continue;
      // off-screen slides of a slider (translated away, still "visible") are not targets: at least 30% must be on screen
      if(Math.min(r.right,vw)-Math.max(r.left,0)<r.width*0.3)continue;
      out.push({el:el,r:r,a:r.width*r.height});
    }
    // pagination dots/dashes and logos are never focus targets; dots become a carousel controller instead
    var byParent=new Map(),kept=[],car=[];
    dotGroups=[];
    out.forEach(function(o){
      if(isLogo(o.el,o.r)||isMeta(o.el,o.r))return;
      if(isDot(o.el,o.r)){var p=o.el.parentElement,g=byParent.get(p);if(!g){g=[];byParent.set(p,g);}g.push(o);return;}
      kept.push(o);
    });
    byParent.forEach(function(g){
      if(g.length>=3){
        dotGroups.push(g.map(function(o){return o.el;}));
        var l=1e9,t=1e9,rr=-1e9,b=-1e9;
        g.forEach(function(o){l=Math.min(l,o.r.left);t=Math.min(t,o.r.top);rr=Math.max(rr,o.r.right);b=Math.max(b,o.r.bottom);});
        car.push([Math.round(l),Math.round(t),Math.round(rr-l),Math.round(b-t)]);
      }else g.forEach(function(o){kept.push(o);});
    });
    out=kept.filter(function(o){
      if(o.el.tagName!=='VIDEO')return true;
      return !kept.some(function(p){return p!==o&&p.el.tagName!=='VIDEO'&&p.r.left>=o.r.left&&p.r.right<=o.r.right&&p.r.top>=o.r.top&&p.r.bottom<=o.r.bottom;});
    });
    var groups=new Map();
    out.forEach(function(o){
      var tgt=isControl(o.el)?o.el:cardOf(o.el),g=groups.get(tgt);
      if(!g){groups.set(tgt,{el:tgt,p:o.el,pa:o.a});}else if(o.a>g.pa){g.p=o.el;g.pa=o.a;}
    });
    out=[];
    groups.forEach(function(g){
      prim.set(g.el,g.p);
      var r=rect(g.el);
      if(r.width<10||r.height<10)return;
      out.push({el:g.el,r:r,a:r.width*r.height});
    });
    out=out.filter(function(o){return !out.some(function(p){return p!==o&&o.el.contains(p.el)&&p.a>o.a*0.5&&!isControl(p.el);});});
    return {iw:vw,ih:vh,fs:!!fs,car:car,cur:cur&&cur.isConnected?curId:-1,
      items:out.map(function(o){return [idOf(o.el),Math.round(o.r.left),Math.round(o.r.top),Math.round(o.r.width),Math.round(o.r.height)];})};
  }
  function push(delay){
    if(timer)return;
    var wait=Math.max(delay||0,0);
    timer=setTimeout(function(){timer=0;lastPush=Date.now();try{DBBridge.snap(JSON.stringify(snap()));}catch(e){}},wait);
  }
  function hover(el){
    ['mouseover','mouseenter','mousemove'].forEach(function(t){
      try{el.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window}));}catch(e){}
    });
  }
  var raf=0;
  addEventListener('scroll',function(){
    if(raf)return;
    raf=requestAnimationFrame(function(){
      raf=0;
      if(cur&&cur.isConnected){var r=rect(cur);try{DBBridge.cur(r.left,r.top,r.width,r.height);}catch(e){}}
      push(160);
    });
  },{passive:true,capture:true});
  addEventListener('resize',function(){push(200);});
  window.__dbSel=function(i){
    var el=byId(i);if(!el)return 0;
    if(cur&&cur!==el)cur.removeAttribute('data-db-focus');
    cur=el;curId=i;el.setAttribute('data-db-focus','1');
    try{(prim.get(el)||el).focus({preventScroll:true});}catch(e){}
    var r=rect(el);
    if(!fsEl()&&(r.top<innerHeight*0.12||r.bottom>innerHeight*0.88||r.left<innerWidth*0.03||r.right>innerWidth*0.97)){
      el.scrollIntoView({block:'center',inline:'nearest',behavior:'smooth'});
    }
    hover(el);push(120);
    return 1;
  };
  window.__dbCarousel=function(d){
    if(!dotGroups.length)return 'none';
    var cy=cur&&cur.isConnected?rect(cur):null,best=null,bg=1e9;
    dotGroups.forEach(function(g){
      if(!g[0].isConnected)return;
      var r=rect(g[0]),gap=cy?Math.max(0,r.top-cy.bottom,cy.top-r.bottom):0;
      if(gap<bg){bg=gap;best=g;}
    });
    if(!best||bg>150)return 'none';
    var i=activeIdx(best),n=best.length,j=(i+(d==='right'?1:-1)+n)%n,tg=best[j];
    ['mousedown','mouseup'].forEach(function(e){try{tg.dispatchEvent(new MouseEvent(e,{bubbles:true,cancelable:true,view:window}));}catch(x){}});
    tg.click();push(250);
    return 'moved';
  };
  // Back: close what is open (fullscreen, popover/menu/dialog) before leaving the page
  window.__dbBack=function(){
    if(window.__dbUnfake&&window.__dbUnfake())return 'closed';
    if(fsEl()){try{(document.exitFullscreen||document.webkitExitFullscreen).call(document);}catch(e){}return 'closed';}
    var ex=document.querySelector('[aria-expanded="true"]');
    var inPop=false,a=cur&&cur.isConnected?cur.parentElement:null;
    while(a&&a!==document.body){
      var cs=getComputedStyle(a),z=parseInt(cs.zIndex,10)||0,t=a.tagName;
      if((cs.position==='absolute'||cs.position==='fixed')&&z>=10&&t!=='HEADER'&&t!=='NAV'&&a.getBoundingClientRect().width<innerWidth*0.8){inPop=true;break;}
      a=a.parentElement;
    }
    var dlg=document.querySelector('dialog[open],[role=dialog],[aria-modal=true]');
    if(!inPop&&!ex&&!(dlg&&vis(dlg)))return 'none';
    ['keydown','keyup'].forEach(function(n){
      var ev=new KeyboardEvent(n,{key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true,cancelable:true});
      (document.activeElement||document).dispatchEvent(ev);document.dispatchEvent(ev);
    });
    if(ex)try{ex.click();}catch(e){}
    if(cur){cur.removeAttribute('data-db-focus');}
    try{if(document.activeElement)document.activeElement.blur();}catch(e){}
    cur=null;curId=-1;push(100);
    return 'closed';
  };
  window.__dbScroll=function(d){
    var h=innerHeight*0.7,w=innerWidth*0.7;
    if(d==='down')scrollBy({top:h,behavior:'smooth'});else if(d==='up')scrollBy({top:-h,behavior:'smooth'});
    else if(d==='right')scrollBy({left:w,behavior:'smooth'});else if(d==='left')scrollBy({left:-w,behavior:'smooth'});
  };
  window.__dbReveal=function(){var f=fsEl()||document.querySelector('video');if(f)hover(f);push(0);};
  window.__dbSnap=function(){push(0);};
  window.__dbOk=function(){
    if(!cur||!cur.isConnected)return 'none';
    var tg=prim.get(cur)||cur,t=tg.tagName,ty=(tg.type||'').toLowerCase();
    if(t==='TEXTAREA'||(t==='INPUT'&&/^(text|search|url|email|password|number|tel|)${'$'}/.test(ty))||tg.isContentEditable){tg.focus();var q=rect(tg);try{DBBridge.ime(q.left+q.width/2,q.top+q.height/2);}catch(e){}return 'text';}
    if(t==='SELECT'){tg.focus();return 'select';}
    if(t==='VIDEO'){if(tg.paused)tg.play();else tg.pause();return 'video';}
    ['mousedown','mouseup'].forEach(function(n){
      try{tg.dispatchEvent(new MouseEvent(n,{bubbles:true,cancelable:true,view:window}));}catch(e){}
    });
    tg.click();
    // a click that opens a search box focuses its input from script; ask native to tap it so the TV keyboard (and its voice mic) opens
    setTimeout(function(){
      var a=document.activeElement;
      if(a&&(a.tagName==='TEXTAREA'||(a.tagName==='INPUT'&&/^(text|search|url|email|password|number|tel|)${'$'}/.test((a.type||'').toLowerCase()))||a.isContentEditable)){
        var r=a.getBoundingClientRect();try{DBBridge.ime(r.left+r.width/2,r.top+r.height/2);}catch(e){}
      }
    },350);
    return 'click';
  };
  document.addEventListener('play',function(e){
    var v=e.target;
    if(!(v instanceof HTMLVideoElement)||fsEl())return;
    if(navigator.userActivation&&!navigator.userActivation.isActive)return;
    var r=v.getBoundingClientRect();
    if(r.width<240||r.height<135)return;
    var box=v,p=v.parentElement;
    while(p&&p!==document.body&&p!==document.documentElement){
      var pr=p.getBoundingClientRect();
      if(pr.width<=r.width*1.5&&pr.height<=r.height*1.5&&p.querySelectorAll('a[href]').length<=12){box=p;p=p.parentElement;}else break;
    }
    goFull(box);
  },true);
  var fake=null;
  // Fullscreen API needs a user gesture; when refused, make the player box fill the screen with CSS instead (site controls stay inside it)
  function goFull(box){
    var f=box.requestFullscreen||box.webkitRequestFullscreen;
    var fallback=function(){
      if(fake||fsEl())return;
      fake={el:box,css:box.getAttribute('style')||''};
      box.style.cssText+=';position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;z-index:2147483000!important;background:#000!important;margin:0!important;transform:none!important;';
    };
    if(!f){fallback();return;}
    try{var pm=f.call(box);if(pm&&pm.catch)pm.catch(fallback);}catch(x){fallback();}
  }
  window.__dbUnfake=function(){if(!fake)return false;fake.el.setAttribute('style',fake.css);fake=null;return true;};
  function lazy(n){
    try{
      if(n.tagName==='IMG'){if(!n.loading)n.loading='lazy';n.decoding='async';}
      else if(n.tagName==='VIDEO'&&n.paused&&n.preload!=='auto')n.preload='metadata';
      else if(n.querySelectorAll)n.querySelectorAll('img:not([loading])').forEach(function(i){i.loading='lazy';i.decoding='async';});
    }catch(e){}
  }
  // hero/background videos (muted loops) burn the Stick's decoder and make D-pad scrolling janky: keep them paused
  function freezeBg(v){
    try{
      if(v.tagName!=='VIDEO'||fsEl()||v.controls)return;
      if((v.loop||(v.muted&&v.autoplay))&&!(navigator.userActivation&&navigator.userActivation.isActive)&&(!isFinite(v.duration)||v.duration<180)){v.pause();v.removeAttribute('autoplay');}
    }catch(e){}
  }
  document.addEventListener('play',function(e){freezeBg(e.target);},true);
  setInterval(function(){document.querySelectorAll('video').forEach(freezeBg);},3000);
  lazy(document.documentElement);
  try{new MutationObserver(function(ms){ms.forEach(function(m){m.addedNodes.forEach(function(n){if(n.nodeType===1)lazy(n);});});}).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
  addEventListener('load',function(){push(300);push(1500);});
  push(300);
})();
""".trimIndent()
}
