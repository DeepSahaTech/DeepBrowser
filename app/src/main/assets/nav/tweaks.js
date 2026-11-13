// Non-navigation page tweaks, moved out of SmartNav unchanged: viewport meta, auto-fullscreen on play
// (with CSS fallback), eager images, background video freezing and video layer fixes.
// Idempotent: safe to inject several times per page.
(function(){
  if(window.__dbTweaks)return;
  window.__dbTweaks=true;
  try{
    var m=document.querySelector('meta[name=viewport]');
    if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}
    var W=/^https?:\/\/([a-z0-9-]+\.)*youtube\.com\/tv/i.test(location.href)?1920:1280;
    if(m.content!=='width='+W)m.content='width='+W;
  }catch(e){}
  // TV-native sites keep their own behaviour (SmartNav returned early on these too)
  if(/^https?:\/\/([a-z0-9-]+\.)*youtube\.com\/tv/i.test(location.href))return;
  function fsEl(){return document.fullscreenElement||document.webkitFullscreenElement||null;}
  // Arrow keys now reach the page and also grant user activation, so only a recent OK/Enter or click counts as "user pressed Play"
  // (otherwise hover-preview trailers on focused cards go fullscreen).
  var pressAt=0;
  window.addEventListener('keydown',function(e){if(e.isTrusted&&e.key==='Enter')pressAt=Date.now();},true);
  window.addEventListener('click',function(e){if(e.isTrusted)pressAt=Date.now();},true);
  document.addEventListener('play',function(e){
    var v=e.target;
    if(!(v instanceof HTMLVideoElement)||fsEl())return;
    if(Date.now()-pressAt>10000)return; // slow players start after the press
    if(v.muted||v.loop)return; // banner/preview trailers autoplay muted or looping; a player the user started is not
    v.__dbUser=true; // the nav engine treats only user-started videos as players (not unmuted banner trailers)

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
  // Images load eagerly and decode off the main thread; sites' own lazy images are switched to eager near the viewport first, the rest in idle time.
  function near(i){var r=i.getBoundingClientRect();return r.bottom>-innerHeight&&r.top<innerHeight*3&&r.right>-innerWidth&&r.left<innerWidth*2;}
  function lazy(n){
    try{
      if(n.tagName==='IMG'){n.decoding='async';if(n.loading==='lazy'&&near(n))n.loading='eager';}
      else if(n.tagName==='VIDEO'&&n.paused&&n.preload!=='auto')n.preload='metadata';
      else if(n.querySelectorAll)n.querySelectorAll('img').forEach(function(i){i.decoding='async';if(i.loading==='lazy'&&near(i))i.loading='eager';});
    }catch(e){}
  }
  var idle=window.requestIdleCallback||function(f){return setTimeout(f,300);};
  function sweep(){
    idle(function(){
      var l=[].slice.call(document.querySelectorAll('img[loading=lazy]')).slice(0,60),k=0;
      l.sort(function(a,b){return Math.abs(a.getBoundingClientRect().top)-Math.abs(b.getBoundingClientRect().top);});
      for(var i=0;i<l.length&&k<8;i++,k++)l[i].loading='eager';
      setTimeout(sweep,1500);
    });
  }
  // hero/background videos (muted loops) burn the Stick's decoder and make D-pad scrolling janky: keep them paused
  function freezeBg(v){
    try{
      if(v.tagName!=='VIDEO'||fsEl()||v.controls)return;
      if(v.readyState<2)return;
      if((v.loop||(v.muted&&v.autoplay))&&!(navigator.userActivation&&navigator.userActivation.isActive)&&(!isFinite(v.duration)||v.duration<180)){v.pause();v.removeAttribute('autoplay');}
    }catch(e){}
  }
  // The video layer is composited outside the page: a CSS mask on an ancestor hides it and a transform on it draws it in the wrong size.
  function fixVideo(v){
    try{
      if(v.tagName!=='VIDEO'||fsEl())return;
      if(getComputedStyle(v).transform!=='none')v.style.setProperty('transform','none','important');
      var a=v.parentElement;
      for(var d=0;d<8&&a&&a!==document.documentElement;d++,a=a.parentElement){
        var c=getComputedStyle(a);
        if((c.webkitMaskImage||c.maskImage||'none')!=='none'){a.style.setProperty('-webkit-mask-image','none','important');a.style.setProperty('mask-image','none','important');}
      }
    }catch(e){}
  }
  ['loadedmetadata','loadeddata','play'].forEach(function(n){document.addEventListener(n,function(e){fixVideo(e.target);},true);});
  document.addEventListener('play',function(e){freezeBg(e.target);},true);
  document.addEventListener('loadeddata',function(e){freezeBg(e.target);},true);
  setInterval(function(){document.querySelectorAll('video').forEach(function(v){fixVideo(v);freezeBg(v);});},2000);
  lazy(document.documentElement);sweep();
  try{new MutationObserver(function(ms){ms.forEach(function(m){m.addedNodes.forEach(function(n){if(n.nodeType===1)lazy(n);});});}).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
  window.__dbFakeFs=function(){return fake&&fake.el;};
})();
