package com.agy.ui.webview;

import android.util.Log;

import com.agy.bridge.AndroidBridge;
import com.agy.theme.ThemeManager;

import org.json.JSONObject;

/** Injects the boot config and the startup_ready script ahead of the app UI. */
public final class AppHtmlInjector {

    private static final String TAG = "WebViewHost";

    /** Web layer generates the ready signal under generated/patches. */
    private static final String READY_SCRIPT = "./generated/patches/startup_ready.js";
    private static final String CSP = "default-src 'self' https: data: blob:; "
            + "script-src 'self' 'unsafe-inline' 'unsafe-eval' blob:; "
            + "style-src 'self' 'unsafe-inline' https:; "
            + "img-src 'self' https: data: blob:; font-src 'self' https: data:; "
            + "connect-src 'self' https: wss:; frame-src https: data: blob:; "
            + "worker-src 'self' blob:; object-src 'none'; base-uri 'self'";

    private AppHtmlInjector() {
    }

    public static String inject(String html, AndroidBridge bridge, ThemeManager theme) {
        String boot;
        try {
            JSONObject config = new JSONObject(bridge.getConfig());
            JSONObject endpoints = new JSONObject();
            String base = bridge.getEngineBaseUrl();
            endpoints.put("core", base);
            endpoints.put("core_http", base);
            endpoints.put("enginePort", bridge.getEnginePort());
            boot = "<meta http-equiv=\"Content-Security-Policy\" content=\"" + CSP + "\">"
                    + "<script>(function(){"
                    + "window.__APP_CONFIG__=" + config.toString() + ";"
                    + "window.__AG_ENDPOINTS__=" + endpoints.toString() + ";"
                    + "window.__AG_CSRF__=" + JSONObject.quote(bridge.getCsrfToken()) + ";"
                    + "window.__AG_HOST_THEME__="
                    + JSONObject.quote(theme != null && theme.mode() == com.agy.theme.ThemeColors.MODE_DARK ? "dark"
                            : (theme != null && theme.mode() == com.agy.theme.ThemeColors.MODE_LIGHT ? "light" : "system")) + ";"
                    + "window.__AG_RESOLVED_THEME__="
                    + JSONObject.quote(theme != null ? theme.getTheme() : "light") + ";"
                    + "window.__AG_SYSTEM_THEME__=window.__AG_RESOLVED_THEME__;"
                    + "(function(){"
                    + "var origMM=window.matchMedia?window.matchMedia.bind(window):null;"
                    + "var listeners=[];"
                    + "function evalScheme(q){var s=String(q||'');var dk=window.__AG_SYSTEM_THEME__==='dark';"
                    + "if(s.indexOf('prefers-color-scheme')>=0){"
                    + "if(s.indexOf('dark')>=0)return dk;"
                    + "if(s.indexOf('light')>=0)return !dk;}"
                    + "return null;}"
                    + "window.matchMedia=function(query){"
                    + "var ev=evalScheme(query);"
                    + "if(ev===null)return origMM?origMM(query):{matches:false,media:String(query||''),onchange:null,addListener:function(){},removeListener:function(){},addEventListener:function(){},removeEventListener:function(){},dispatchEvent:function(){return false;}};"
                    + "var qStr=String(query||'');"
                    + "var mql={media:qStr,onchange:null,get matches(){return evalScheme(qStr)===true;},"
                    + "addEventListener:function(t,fn){if(t==='change'&&typeof fn==='function')listeners.push({q:qStr,fn:fn,mql:mql});},"
                    + "removeEventListener:function(t,fn){if(t==='change')listeners=listeners.filter(function(x){return !(x.fn===fn&&x.q===qStr);});},"
                    + "addListener:function(fn){if(typeof fn==='function')listeners.push({q:qStr,fn:fn,mql:mql});},"
                    + "removeListener:function(fn){listeners=listeners.filter(function(x){return !(x.fn===fn&&x.q===qStr);});},"
                    + "dispatchEvent:function(){return true;}};"
                    + "return mql;};"
                    + "window.__agOnSystemThemeChanged=function(nextSys){"
                    + "if(nextSys==='dark'||nextSys==='light'){window.__AG_SYSTEM_THEME__=nextSys;}"
                    + "var copy=listeners.slice();"
                    + "for(var i=0;i<copy.length;i++){try{var it=copy[i];var m=evalScheme(it.q)===true;"
                    + "var evt={matches:m,media:it.q,type:'change',target:it.mql,currentTarget:it.mql};"
                    + "it.fn.call(it.mql,evt);if(typeof it.mql.onchange==='function')it.mql.onchange(evt);}catch(e){}}"
                    + "if(window.__agThemeSync&&typeof window.__agThemeSync.sync==='function'){try{window.__agThemeSync.sync();}catch(e){}}"
                    + "};})();"
                    + "window.__AG_NATIVE_BRIDGE_TOKEN__="
                    + JSONObject.quote(bridge.getBlobBridgeToken()) + ";"
                    + "window.AndroidBridge=window.Android;"
                    + "(function(){var tok=window.__AG_NATIVE_BRIDGE_TOKEN__;"
                    + "if(!window.Android||!tok||typeof window.Android.readClipboardText!=='function')return;"
                    + "var orig=navigator.clipboard;"
                    + "var origWrite=orig&&typeof orig.write==='function'?orig.write.bind(orig):null;"
                    + "var cb={"
                    + "readText:function(){try{return Promise.resolve(String(window.Android.readClipboardText(tok)||''));}catch(e){return Promise.reject(e);}},"
                    + "writeText:function(t){try{window.Android.writeClipboardText(tok,t==null?'':String(t));return Promise.resolve();}catch(e){return Promise.reject(e);}},"
                    + "read:function(){try{var t=String(window.Android.readClipboardText(tok)||'');"
                    + "if(t&&typeof ClipboardItem!=='undefined'&&typeof Blob!=='undefined'){return Promise.resolve([new ClipboardItem({'text/plain':new Blob([t],{type:'text/plain'})})]);}"
                    + "return Promise.resolve([]);}catch(e){return Promise.resolve([]);}},"
                    + "write:function(items){try{if(items&&items.length){var it=items[0];"
                    + "if(it&&typeof it.getType==='function'&&it.types){"
                    + "var ty=it.types.indexOf('text/plain')>=0?'text/plain':(it.types.indexOf('text/html')>=0?'text/html':null);"
                    + "if(ty){return it.getType(ty).then(function(b){return b.text();}).then(function(t){window.Android.writeClipboardText(tok,String(t));});}}}"
                    + "return origWrite?origWrite(items):Promise.resolve();}catch(e){return Promise.resolve();}}"
                    + "};"
                    + "try{Object.defineProperty(navigator,'clipboard',{value:cb,configurable:true,enumerable:true});}catch(e){"
                    + "try{if(orig){orig.readText=cb.readText;orig.writeText=cb.writeText;orig.read=cb.read;orig.write=cb.write;}}catch(e2){}}"
                    + "try{var origExec=Document.prototype.execCommand;"
                    + "if(typeof origExec==='function'){Document.prototype.execCommand=function(cmd,ui,val){"
                    + "if(String(cmd).toLowerCase()==='paste'){var t=String(window.Android.readClipboardText(tok)||'');"
                    + "if(t)return origExec.call(this,'insertText',false,t);return false;}"
                    + "return origExec.apply(this,arguments);};}}catch(e){}"
                    + "})();"
                    + "window.dialog={showOpenDialog:function(){return new Promise(function(resolve){window.__onDirectoryPicked=function(path){resolve(path);};if(window.Android&&typeof window.Android.openDirectoryPicker==='function'){window.Android.openDirectoryPicker();}else{resolve(null);}});}};"
                    + "})();</script>"
                    // Signals first paint back to native (AndroidBridge.onUiReady)
                    // so the splash is dismissed on real content, not a timer.
                    + "<script src=\"" + READY_SCRIPT + "\"></script>";
        } catch (Exception e) {
            Log.w(TAG, "config injection failed", e);
            return html;
        }
        int head = html.indexOf("<head>");
        if (head >= 0) {
            int at = head + "<head>".length();
            return html.substring(0, at) + "\n" + boot + html.substring(at);
        }
        return boot + html;
    }
}
