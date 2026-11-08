package com.deepbrowser.tv.browser

/** Page script injected after load: cosmetic ad CSS, YouTube ad skipper, then the merged [OverlayRules.JS]. Idempotent. */
object AdScript {
    val SCRIPT: String = """
(function(){
  if(window.__dbAds)return;window.__dbAds=1;
  var css='.ytp-ad-module,.ytp-ad-overlay-container,.ytp-ad-player-overlay,.ytp-ad-image-overlay,#player-ads,'
   +'ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,ytd-display-ad-renderer,ytd-promoted-sparkles-web-renderer,'
   +'ytd-banner-promo-renderer,ytd-companion-slot-renderer,ytd-action-companion-ad-renderer,.ytd-merch-shelf-renderer,'
   +'ytlr-ad-slot-renderer,.ad-container,.ad-banner,.adsbygoogle,ins.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],'
   +'[class*="advert"],[id*="advert"],[class^="ad-"],[class*=" ad-"],[id^="ad-"],[class*="sponsor"],iframe[src*="doubleclick"],'
   +'iframe[src*="googlesyndication"],iframe[src*="adsterra"],.popup-ad,.pop-under{display:none!important}';
  var s=document.createElement('style');s.textContent=css;(document.head||document.documentElement).appendChild(s);
  function yt(){
    var p=document.querySelector('#movie_player,.html5-video-player');
    var v=document.querySelector('video.html5-main-video,video');
    if(!p||!v)return;
    if(p.classList.contains('ad-showing')||document.querySelector('.ytp-ad-player-overlay,.ytp-ad-text')){
      v.muted=true;
      if(isFinite(v.duration)&&v.duration>0&&v.duration<180)v.currentTime=v.duration;
      var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,button[class*="skip"]');
      if(b)b.click();
    }
    var c=document.querySelector('.ytp-ad-overlay-close-button');if(c)c.click();
  }
  if(/youtube\.com|youtu\.be/.test(location.hostname)){setInterval(yt,300);}
  // overlays / scam popups: one scan, one debounced observer, one slow timer
"""+OverlayRules.JS+"""
})();
"""
}
