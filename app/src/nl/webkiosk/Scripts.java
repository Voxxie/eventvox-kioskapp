package nl.webkiosk;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.nfc.*;
import android.nfc.tech.Ndef;
import org.json.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.util.Properties;

/** JavaScript die de app in de website zet (vóór de scripts van de pagina) en eenvoudige eigen pagina's. */
final class Scripts {
    private Scripts() {}

    /**
     * navigator.vibrate via de trilmotor van Android (WebKiosk.vibrate), zoals de EventVOX-scanterminal hem per uitslag
     * aanroept (js/scan-texts.js VIBRATE). De WebView zelf trilt alleen na een tik op de pagina en met de VIBRATE-
     * toestemming; een scan met DataWedge of NFC is geen tik, dus zonder dit zou er niets trillen. Elke waarde wordt
     * 0-10000 ms, hoogstens 32 stappen, zoals in Chrome.
     */
    static final String VIBRATE_POLYFILL =
        "(function(){"
          + "if(!window.WebKiosk||typeof WebKiosk.vibrate!=='function'||window.__wkVibrate)return;window.__wkVibrate=1;"
          + "var vib=function(p){try{var a=Array.isArray(p)?p:[p];"
          + "a=a.slice(0,32).map(function(x){x=Number(x);return isFinite(x)&&x>0?Math.min(Math.round(x),10000):0;});"
          + "return WebKiosk.vibrate(JSON.stringify(a))!==false;}catch(e){return false;}};"
          + "try{Object.defineProperty(Navigator.prototype,'vibrate',{value:vib,configurable:true,writable:true});}catch(e){}"
          + "try{Object.defineProperty(navigator,'vibrate',{value:vib,configurable:true,writable:true});}catch(e){navigator.vibrate=vib;}"
          + "})();";

    /**
     * Battery Status API op basis van Android's BatteryManager (via WebKiosk.getBattery), ook als de WebView hem niet heeft.
     * Altijd deze (niet de eventuele eigen van de WebView): chargingTime blijft Infinity (onbekend), zodat een volle,
     * ladende accu niet lijkt op een toestel zonder accu (zo herkent de EventVOX-scanterminal een Raspberry Pi). Meldt de app
     * present false (geen accu, zoals een Zebra CC600), dan juist wél zoals een toestel zonder accu: vol, laden, chargingTime 0.
     * Wordt zo vroeg mogelijk in elke pagina gezet (onPageStarted/onPageCommitVisible) en nogmaals bij onPageFinished.
     */
    static final String BATTERY_POLYFILL =
        "(function(){"
          + "if(!window.WebKiosk||window.__wkBattery)return;window.__wkBattery=1;"
          + "function rd(){try{return JSON.parse(WebKiosk.getBattery());}catch(e){return {level:100,charging:false};}}"
          + "var bm={level:1,charging:false,chargingTime:Infinity,dischargingTime:Infinity,"
          + "onlevelchange:null,onchargingchange:null,onchargingtimechange:null,ondischargingtimechange:null,_l:{},"
          + "addEventListener:function(t,f){(this._l[t]=this._l[t]||[]).push(f);},"
          + "removeEventListener:function(t,f){var a=this._l[t]||[];var i=a.indexOf(f);if(i>=0)a.splice(i,1);},"
          + "dispatchEvent:function(e){this._fire(e.type);return true;},"
          + "_fire:function(t){var e={type:t,target:bm,currentTarget:bm};(this._l[t]||[]).slice().forEach(function(f){try{f.call(bm,e);}catch(x){}});"
          + "if(typeof this['on'+t]==='function')try{this['on'+t](e);}catch(x){}}};"
          + "function upd(d){if(!d||typeof d.level!=='number')return;var np=d.present===false;var l=np?1:d.level/100,c=np?true:!!d.charging,lc=l!==bm.level,cc=c!==bm.charging;"
          // Geen accu (present false, bv. een Zebra CC600): zoals een toestel zonder accu, chargingTime 0
          + "bm.chargingTime=np?0:Infinity;bm.level=l;bm.charging=c;if(lc)bm._fire('levelchange');if(cc){bm._fire('chargingchange');bm._fire('chargingtimechange');}}"
          + "upd(rd());"
          + "window.addEventListener('webkiosk-battery',function(e){upd(e.detail);});"
          + "var gb=function(){return Promise.resolve(bm);};"
          + "try{Object.defineProperty(Navigator.prototype,'getBattery',{value:gb,configurable:true,writable:true});}catch(e){}"
          + "try{Object.defineProperty(navigator,'getBattery',{value:gb,configurable:true,writable:true});}catch(e){navigator.getBattery=gb;}"
          + "})();";

    static String page(String title, String body) {
        return "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
            + "<body style='font-family:sans-serif;text-align:center;padding:40px 16px;color:#333;word-break:break-word;overflow-wrap:anywhere'>"
            + "<h2>" + title + "</h2><p>" + body + "</p></body></html>";
    }

    static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }


}
