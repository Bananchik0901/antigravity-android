#!/usr/bin/env python3
"""
web/patch_main.py - Dynamic, idempotent patcher for web/main.js.

Applies Android WebView UI/UX parity patches cleanly and survives upstream
minifier renames by matching stable structural anchors:
  1. Appearance: injects the Desktop Mode toggle.
  2. Settings tabs: unhides Application ("App") with __APP_CONFIG__ version
     fallback and promotes Browser into a top-level Settings tab.
  3. Mobile Settings: adds Projects + "Not in Project: Conversations" sections
     and proper Application display labels.
  4. Plugins: enables pluginAuth, switches OAuth sign-in mode to loopback
     auto-callback on Android, keeps pending sign-ins polling on mount, and
     redirects single plugin installs that need setup/OAuth to plugin settings
     (and launches OAuth immediately in browser).
"""

import os
import re
import sys


def _patch_desktop_mode(content):
    if "Desktop Mode" in content or "ag_desktop_mode" in content:
        print("[web-patch] Desktop Mode toggle already present")
        return content

    hook_pattern = re.compile(
        r"(var\s+[A-Za-z0-9_$]+=[A-Za-z0-9_$]+!==3,\s*[A-Za-z0-9_$]+=[A-Za-z0-9_$]+!==2,\s*[A-Za-z0-9_$]+=[A-Za-z0-9_$]+=>\s*[A-Za-z0-9_$]+=>\{.*?\};)"
    )
    hook_match = hook_pattern.search(content)

    theme_idx = content.find('{label:"Theme"')
    theme_match = None
    react_id = "A"
    if theme_idx != -1:
        window_start = max(0, theme_idx - 100)
        window_end = min(len(content), theme_idx + 500)
        window_text = content[window_start:window_end]
        theme_pattern = re.compile(
            r'([A-Za-z0-9_$]+)\.createElement\(([A-Za-z0-9_$]+),\{label:"Theme",.*?onSelect:[A-Za-z0-9_$]+\}\)'
        )
        theme_match = theme_pattern.search(window_text)
        if theme_match:
            react_id = theme_match.group(1)

    if not hook_match or not theme_match:
        raise RuntimeError(
            f"Desktop Mode anchors missing (hook={bool(hook_match)}, theme={bool(theme_match)})"
        )

    orig_hook = hook_match.group(1)
    injected_hook = (
        f"var [isDesktop,setIsDesktop]=(0,{react_id}.useState)(()=>{{"
        "try{if(window.Android&&typeof window.Android.isDesktopMode==='function')return window.Android.isDesktopMode();"
        "var s=localStorage.getItem('ag_desktop_mode');return s===null?!1:s==='true'}catch(_){return !1}});"
        f"var onDesktopToggle=(0,{react_id}.useCallback)(v=>{{"
        "setIsDesktop(v);try{localStorage.setItem('ag_desktop_mode',String(v));"
        "if(window.Android&&typeof window.Android.setDesktopMode==='function')window.Android.setDesktopMode(v)}catch(_){}},[]);"
        + orig_hook
    )

    item_comp = "v3"
    switch_comp = "dX"
    re_idx = content.find("rightElement:")
    if re_idx != -1:
        re_window = content[max(0, re_idx - 150) : min(len(content), re_idx + 150)]
        item_m = re.search(
            r"([A-Za-z0-9_$]+)\.createElement\(([A-Za-z0-9_$]+),\{label:[^,]+,description:[^,]+,rightElement:",
            re_window,
        )
        if item_m:
            item_comp = item_m.group(2)
        sw_m = re.search(
            r"rightElement:[A-Za-z0-9_$]+\.createElement\(([A-Za-z0-9_$]+),\{checked:",
            re_window,
        )
        if sw_m:
            switch_comp = sw_m.group(1)

    orig_theme = theme_match.group(0)
    injected_theme = (
        orig_theme
        + f',{react_id}.createElement({item_comp},{{label:"Desktop Mode",description:"Use desktop viewport and user agent for the workspace",'
        + f"rightElement:{react_id}.createElement({switch_comp},{{checked:isDesktop,onCheckedChange:onDesktopToggle}})}})"
    )

    content = content.replace(orig_hook, injected_hook, 1)
    content = content.replace(orig_theme, injected_theme, 1)
    print("[web-patch] Applied Desktop Mode toggle")
    return content


def _patch_app_and_browser_settings(content):
    # 1. Unhide Application ("App") screen on WebView/mobile hosts
    app_gate_re = re.compile(
        r"App:\{feature:\{visible:!1\},applyFirst:\[\{runtimeGating:\{host:\[[^\]]+\]\},feature:\{visible:!0\}\}\]\}"
    )
    if app_gate_re.search(content):
        content = app_gate_re.sub("App:{feature:{visible:!0}}", content, count=1)
        print("[web-patch] Unhid App settings screen")

    # 2. Version card fallback to window.__APP_CONFIG__?.appVersion
    if "/*ag-app-ver*/" not in content:
        ver_re = re.compile(
            r'(function\s+[A-Za-z0-9_$]+\(\)\{var\s+\{currentVersion:([A-Za-z0-9_$]+)\}=[A-Za-z0-9_$]+\(\),\{electronNativeFeature:([A-Za-z0-9_$]+)\}=\(0,[A-Za-z0-9_$]+\.useContext\)\([A-Za-z0-9_$]+\);return\(\2=\2\|\|\3\?\.appVersion\)\?)'
        )
        m = ver_re.search(content)
        if not m:
            raise RuntimeError("App version card anchor not found")
        cv, enf = m.group(2), m.group(3)
        old_frag = f"return({cv}={cv}||{enf}?.appVersion)?"
        new_frag = f"return({cv}={cv}||{enf}?.appVersion||window.__APP_CONFIG__?.appVersion/*ag-app-ver*/)?"
        content = content.replace(old_frag, new_frag, 1)
        print("[web-patch] Patched App version fallback")

    # 3. Promote Browser to top-level Settings screen list
    if '{screen:"Browser"}' not in content:
        web_anchor = '[{screen:"General"},{screen:"App",label:"Application"},'
        if web_anchor not in content:
            raise RuntimeError("Settings screen metadata anchor not found")
        content = content.replace(
            web_anchor,
            web_anchor + '{screen:"Browser"},',
            1,
        )
        print("[web-patch] Added Browser screen to settings list")

    zeb_anchor = '=["App","Jetski Chat","Regroup Google3 Chats"]'
    if zeb_anchor in content:
        content = content.replace(
            zeb_anchor,
            '=["App","Browser","Jetski Chat","Regroup Google3 Chats"]',
            1,
        )
        print("[web-patch] Added Browser to web global screens")

    # 4. Define __agBrowserScreen and wire into fNb (mobile) and TNb (desktop)
    if "function __agBrowserScreen(" not in content:
        fnb_re = re.compile(
            r'function\s+([A-Za-z0-9_$]+)\(([A-Za-z0-9_$]+)\)\{switch\(\2\)\{case "Account":return ([A-Za-z0-9_$]+)\.createElement\('
        )
        fnb_m = fnb_re.search(content)
        if not fnb_m:
            raise RuntimeError("Settings screen switch (fNb) not found")
        react_id = fnb_m.group(3)

        # Extract symbols from General settings (uMb) and RNb
        gen_m = re.search(
            rf'return {re.escape(react_id)}\.createElement\(([A-Za-z0-9_$]+),\{{title:"General",description:"Configure agent execution',
            content,
        )
        # Dynamic symbol extraction from uMb's Browser section
        umb_head = re.search(
            rf'var ([A-Za-z0-9_$]+)=([A-Za-z0-9_$]+)\(\),\w+=\1\.settings,.*?,([A-Za-z0-9_$]+)=([A-Za-z0-9_$]+)\(\);\w+=\3\.has\("Browser"\);.*?,\{{browserDisabled:\w+\}}=\s*\(0,{re.escape(react_id)}\.useContext\)\(([A-Za-z0-9_$]+)\).*?(?:var\s+|,)([A-Za-z0-9_$]+)=([A-Za-z0-9_$]+)\(([A-Za-z0-9_$]+)\.PERMISSION_GRANTS_GLOBAL\)',
            content,
            re.S,
        )
        br_sec = re.search(
            rf'var ([A-Za-z0-9_$]+)=\w+&&{re.escape(react_id)}\.createElement\(([A-Za-z0-9_$]+),\{{title:"Browser".*?{re.escape(react_id)}\.createElement\(([A-Za-z0-9_$]+),null,\w+\.map\(\w+=>{re.escape(react_id)}\.createElement\(([A-Za-z0-9_$]+),\{{key:\w+,settingKey:\w+\}}\)\),\w+&&{re.escape(react_id)}\.createElement\(([A-Za-z0-9_$]+),\{{label:"Browser Actuation Rules",description:[^,]+,rightElement:{re.escape(react_id)}\.createElement\(([A-Za-z0-9_$]+),\{{variant:"secondary"',
            content,
            re.S,
        )
        act_m = re.search(
            rf'var ([A-Za-z0-9_$]+)=\(\{{onBack:\w+\}}\)=>.*?return {re.escape(react_id)}\.createElement\(([A-Za-z0-9_$]+),\{{title:"Browser Actuation Permissions"',
            content,
        )
        rnb_m = re.search(
            r'function\s+([A-Za-z0-9_$]+)\(\)\{var\s+[A-Za-z0-9_$]+=([A-Za-z0-9_$]+)\([A-Za-z0-9_$]+\),[A-Za-z0-9_$]+=\2\(([A-Za-z0-9_$]+)\),[A-Za-z0-9_$]+=[A-Za-z0-9_$]+\(\),[A-Za-z0-9_$]+=[A-Za-z0-9_$]+\(\),\{logEvent:[A-Za-z0-9_$]+\}=[A-Za-z0-9_$]+\(\),\[[A-Za-z0-9_$]+,[A-Za-z0-9_$]+\]=[A-Za-z0-9_$]+\("main"\);[A-Za-z0-9_$]+\(\(\)=>\{[A-Za-z0-9_$]+>0&&[A-Za-z0-9_$]+\("main"\)\},\[[A-Za-z0-9_$]+,[A-Za-z0-9_$]+\]\);[A-Za-z0-9_$]+\(\);var\s+[A-Za-z0-9_$]+=[A-Za-z0-9_$]+\([A-Za-z0-9_$]+\),[A-Za-z0-9_$]+=[A-Za-z0-9_$]+==="outside-of-project"',
            content,
        )

        if not (gen_m and umb_head and br_sec and act_m and rnb_m):
            raise RuntimeError(
                f"Browser screen symbol match failed: gen={bool(gen_m)} head={bool(umb_head)} sec={bool(br_sec)} act={bool(act_m)} rnb={bool(rnb_m)}"
            )

        page_wrap = gen_m.group(1)
        lj_fn = umb_head.group(2)
        yz_fn = umb_head.group(4)
        xy_ctx = umb_head.group(5)
        vc_fn = umb_head.group(7)
        ui_obj = umb_head.group(8)

        sec_card = br_sec.group(2)
        group_card = br_sec.group(3)
        setting_row = br_sec.group(4)
        item_row = br_sec.group(5)
        btn_comp = br_sec.group(6)

        act_comp = act_m.group(1)
        hm_fn = rnb_m.group(2)
        xx_atom = rnb_m.group(3)

        browser_fn = (
            f"function __agBrowserScreen(){{"
            f"var [__act,__setAct]=(0,{react_id}.useState)(!1),"
            f"__back={hm_fn}({xx_atom});"
            f"(0,{react_id}.useEffect)(()=>{{__back>0&&__setAct(!1)}},[__back]);"
            f"var __cfg={lj_fn}(),__reg={yz_fn}(),__brSec=__reg.get(\"Browser\")?.sections.flatMap(__s=>__s.settings??[])??[];"
            f"__brSec.length||(__brSec=[{ui_obj}.BROWSER_JS_EXECUTION_POLICY]);"
            f"var __st=__cfg.get(\"browser\")?.status??{{isAllowed:!0}},"
            f"{{browserDisabled:__dis}}=(0,{react_id}.useContext)({xy_ctx}),"
            f"__perm={vc_fn}({ui_obj}.PERMISSION_GRANTS_GLOBAL);"
            f"if(__act&&!__dis)return {react_id}.createElement({act_comp},{{onBack:()=>__setAct(!1)}});"
            f"return {react_id}.createElement({page_wrap},{{title:\"Browser\",description:\"Configure the browser subagent and actuation permissions.\"}},"
            f"{react_id}.createElement({sec_card},{{title:\"Browser\",subtitle:__dis?\"The browser subagent has been disabled by your administrator.\":__st.isAllowed?{react_id}.createElement({react_id}.Fragment,null,\"Configure the browser subagent. Invoke it with \",{react_id}.createElement(\"code\",null,\"/browser\"),\" in chat.\"):__st.settingsGatingMessage}},"
            f"!__dis&&{react_id}.createElement({group_card},null,"
            f"__brSec.map(__k=>{react_id}.createElement({setting_row},{{key:__k,settingKey:__k}})),"
            f"__perm&&{react_id}.createElement({item_row},{{label:\"Browser Actuation Rules\",description:\"Configure allowed and denied URLs for browser actuation.\",rightElement:{react_id}.createElement({btn_comp},{{variant:\"secondary\",size:\"sm\",onClick:()=>__setAct(!0)}},\"Edit\")}})"
            f")))}};"
        )

        # Insert before fNb, and add case "Browser"
        content = content[: fnb_m.start()] + browser_fn + content[fnb_m.start() :]
        fnb_case_anchor = f'case "App":return {react_id}.createElement('
        idx = content.find(fnb_case_anchor, fnb_m.start())
        if idx == -1:
            raise RuntimeError("fNb App case not found")
        app_case_end = content.find(";", idx) + 1
        content = (
            content[:app_case_end]
            + f'case "Browser":return {react_id}.createElement(__agBrowserScreen,null);'
            + content[app_case_end:]
        )

        # Wire into desktop TNb tab list
        tnb_app_re = re.compile(
            rf'({re.escape(react_id)}\.createElement\("div",\s*\{{style:\s*\{{display:\s*([A-Za-z0-9_$]+)===\s*"App"\?"block":"none"\}}\}},\s*{re.escape(react_id)}\.createElement\([A-Za-z0-9_$]+,\s*null\)\)\s*,)'
        )
        tnb_m = tnb_app_re.search(content)
        if not tnb_m:
            raise RuntimeError("Desktop settings App pane anchor not found")
        tab_var = tnb_m.group(2)
        content = content.replace(
            tnb_m.group(1),
            tnb_m.group(1)
            + f'{react_id}.createElement("div",{{style:{{display:{tab_var}==="Browser"?"block":"none"}}}},{react_id}.createElement(__agBrowserScreen,null)),',
            1,
        )
        print("[web-patch] Wired __agBrowserScreen into mobile and desktop Settings")

    return content


def _patch_mobile_projects_and_conversations(content):
    if "/*ag-mob-conv*/" in content:
        print("[web-patch] Mobile Projects / Conversations already patched")
        return content

    # Extract symbols from mNb (desktop settings nav)
    mnb_re = re.compile(
        r'var [A-Za-z0-9_$]+=\(\)=>\{var a=([A-Za-z0-9_$]+)\(\),\s*b=!!a\.get\("settings"\)\?\.hideProjects;'
        r'.*?\{settingsFeature:f,\s*projectManagementFeature:g\}=\(0,\s*([A-Za-z0-9_$]+)\.useContext\)\(([A-Za-z0-9_$]+)\),'
        r'.*?n=([A-Za-z0-9_$]+)\(\),\s*q=([A-Za-z0-9_$]+)\(g\?\.projectsStateProvider\),\s*r=([A-Za-z0-9_$]+)\(\),'
        r'.*?label:\s*([A-Za-z0-9_$]+)\(F\),.*?text:\s*"Conversations",\s*onClick:\s*\(\)=>\{D\(([A-Za-z0-9_$]+)\(\{projectId:\s*"outside-of-project"\}\)\)\}',
        re.S,
    )
    mnb_m = mnb_re.search(content)
    if not mnb_m:
        raise RuntimeError("Desktop settings nav (mNb) anchor not found")

    lj_fn, react_id, xk_ctx, cs_fn, pj_fn, fsb_fn, geb_fn, sx_fn = mnb_m.groups()

    lnb_m = re.search(
        rf'[A-Za-z0-9_$]+=\(\{{sectionId:a\}}\)=>\{{\s*var b=([A-Za-z0-9_$]+)\(a\),',
        content,
    )
    rnb_m = re.search(
        r'function\s+([A-Za-z0-9_$]+)\(\)\{var\s+[A-Za-z0-9_$]+=[A-Za-z0-9_$]+\([A-Za-z0-9_$]+\),[A-Za-z0-9_$]+=[A-Za-z0-9_$]+\([A-Za-z0-9_$]+\),[A-Za-z0-9_$]+=[A-Za-z0-9_$]+\(\),[A-Za-z0-9_$]+=[A-Za-z0-9_$]+\(\),\{logEvent:[A-Za-z0-9_$]+\}=[A-Za-z0-9_$]+\(\),\[[A-Za-z0-9_$]+,[A-Za-z0-9_$]+\]=[A-Za-z0-9_$]+\("main"\);[A-Za-z0-9_$]+\(\(\)=>\{[A-Za-z0-9_$]+>0&&[A-Za-z0-9_$]+\("main"\)\},\[[A-Za-z0-9_$]+,[A-Za-z0-9_$]+\]\);[A-Za-z0-9_$]+\(\);var\s+[A-Za-z0-9_$]+=[A-Za-z0-9_$]+\([A-Za-z0-9_$]+\),[A-Za-z0-9_$]+=[A-Za-z0-9_$]+==="outside-of-project"',
        content,
    )
    if not (lnb_m and rnb_m):
        raise RuntimeError("Nm/RNb anchors for mobile Projects/Conversations not found")

    nm_fn = lnb_m.group(1)
    rnb_fn = rnb_m.group(1)

    re_r = re.escape(react_id)
    jnb_re = re.compile(
        r'var ([A-Za-z0-9_$]+)=\(\)=>\{\s*var a=([A-Za-z0-9_$]+)\(\),\s*\{\s*settingsFeature:b\s*\}=\(0,__R__\.useContext\)\(__XK__\),\s*'
        r'c=!!__LJ__\(\)\.get\("settings"\)\?\.hideShortcuts,\s*\{sharedNavItems:e,\s*globalScreens:f,\s*workspaces:g,\s*showWorkspacesSection:h\}=([A-Za-z0-9_$]+)\(\);\s*'
        r'b=\[\.\.\.e\.filter\(q=>q!=="Account"\),\s*\.\.\.f,\s*\.\.\.\(c\?\[\]:\["Shortcuts"\]\),\s*\.\.\.\(b\.hideProvideFeedback\?\[\]:\["Provide Feedback"\]\)\];\s*'
        r'var k=([A-Za-z0-9_$]+)\(!0\);c=k\.path\.length;var l=c===0,\s*m=k\.path\[0\]\?\?k\.exiting\[0\],\s*n=m\?\.startsWith\("workspace:"\);\s*'
        r'return __R__\.createElement\("div",\s*\{ref:k\.setHost,\s*className:"relative h-full w-full overflow-hidden"\},\s*'
        r'__R__\.createElement\(([A-Za-z0-9_$]+),\s*\{offset:-c,\s*active:l,\s*depth:0,\s*footer:e\.includes\("Account"\)\?__R__\.createElement\("div",\s*\{className:"flex flex-col border-t border-border p-1"\},\s*__R__\.createElement\(([A-Za-z0-9_$]+),\s*\{showChevron:!0,\s*className:"mx-1 px-3 py-2\.5",\s*onClick:\(\)=>\{a\(([A-Za-z0-9_$]+)\("Account"\)\);k\.push\("Account"\)\}\}\)\):void 0\},\s*'
        r'__R__\.createElement\(([A-Za-z0-9_$]+),\s*\{text:"Settings"\}\),\s*'
        r'b\.map\(q=>__R__\.createElement\(([A-Za-z0-9_$]+),\s*\{key:q,\s*label:q,\s*onClick:\(\)=>\{a\(\7\(q\)\);k\.push\(q\)\}\}\)\),\s*'
        r'h&&__R__\.createElement\(__R__\.Fragment,\s*null,\s*__R__\.createElement\(\8,\s*\{text:"Workspaces"\}\),\s*g\.map\(q=>__R__\.createElement\(\9,\s*\{key:q\.uri,\s*label:q\.name,\s*onClick:\(\)=>\{var r=q\.uri;a\(([A-Za-z0-9_$]+)\(\{workspaceUri:r\}\)\);k\.push\(`workspace:\$\{r\}`\)\}\}\)\)\)\),\s*'
        r'm&&__R__\.createElement\(\5,\s*\{offset:1-c,\s*active:c===1,\s*depth:1,\s*exiting:k\.exiting\.includes\(m\),\s*onExited:\(\)=>\{k\.onExited\(m\)\},\s*header:__R__\.createElement\(([A-Za-z0-9_$]+),\s*\{onBack:k\.pop\}\)\},\s*'
        r'__R__\.createElement\(([A-Za-z0-9_$]+)\.Provider,\s*\{value:!0\},\s*n\?__R__\.createElement\(([A-Za-z0-9_$]+),\s*null\):([A-Za-z0-9_$]+)\(m\)\)\)\)\};'
        .replace("__R__", re_r)
        .replace("__XK__", re.escape(xk_ctx))
        .replace("__LJ__", re.escape(lj_fn)),
        re.S,
    )
    jnb_m = jnb_re.search(content)
    if not jnb_m:
        raise RuntimeError("Mobile settings nav (jNb) anchor not found")

    (
        jnb_fn,
        gm_fn,
        enb_fn,
        y0a_fn,
        kl_comp,
        zjb_comp,
        qx_fn,
        hnb_comp,
        gnb_comp,
        rx_fn,
        inb_comp,
        ecb_ctx,
        gmb_comp,
        fnb_fn,
    ) = jnb_m.groups()

    new_jnb = (
        f"function __AgMobProjItem({{sectionId:__id,onSelect:__sel}}){{"
        f"var __n={nm_fn}(__id),{{projectManagementFeature:__p}}=(0,{react_id}.useContext)({xk_ctx}),"
        f"__st=__id?__p?.getProjectStateProvider(__id):void 0;"
        f"return {pj_fn}(__st)?.project?.isWorkspaceOnly?null:{react_id}.createElement({gnb_comp},{{label:__n??\"Project\",onClick:__sel}})"
        f"}};"
        f"var {jnb_fn}=()=>{{/*ag-mob-conv*/"
        f"var a={gm_fn}(),__cfg={lj_fn}(),__hp=!!__cfg.get(\"settings\")?.hideProjects,"
        f"{{settingsFeature:b,projectManagementFeature:__pm}}=(0,{react_id}.useContext)({xk_ctx}),"
        f"c=!!__cfg.get(\"settings\")?.hideShortcuts,"
        f"{{sharedNavItems:e,globalScreens:f,workspaces:g,showWorkspacesSection:h}}={enb_fn}(),"
        f"__pids={cs_fn}(),__pstates={pj_fn}(__pm?.projectsStateProvider),__arch={fsb_fn}(),"
        f"[__showAll,__setShowAll]=(0,{react_id}.useState)(!1),"
        f"__vis=(0,{react_id}.useMemo)(()=>{{var __wo=__pstates?new Set(__pstates.filter(G=>G.project?.isWorkspaceOnly).map(G=>G.project.id)):new Set;"
        f"return __pids.filter(G=>!__wo.has(G)&&!__arch.has(G))}},[__pids,__pstates,__arch]),"
        f"__shown=__showAll||__vis.length<=3?__vis:__vis.slice(0,3);"
        f"b=[...e.filter(q=>q!==\"Account\"),...f,...(c?[]:[\"Shortcuts\"]),...(b.hideProvideFeedback?[]:[\"Provide Feedback\"])];"
        f"var k={y0a_fn}(!0);c=k.path.length;var l=c===0,m=k.path[0]??k.exiting[0],"
        f"n=m?.startsWith(\"workspace:\"),__isProj=m?.startsWith(\"project:\");"
        f"return {react_id}.createElement(\"div\",{{ref:k.setHost,className:\"relative h-full w-full overflow-hidden\"}},"
        f"{react_id}.createElement({kl_comp},{{offset:-c,active:l,depth:0,footer:e.includes(\"Account\")?{react_id}.createElement(\"div\",{{className:\"flex flex-col border-t border-border p-1\"}},{react_id}.createElement({zjb_comp},{{showChevron:!0,className:\"mx-1 px-3 py-2.5\",onClick:()=>{{a({qx_fn}(\"Account\"));k.push(\"Account\")}}}})):void 0}},"
        f"{react_id}.createElement({hnb_comp},{{text:\"Settings\"}}),"
        f"b.map(q=>{react_id}.createElement({gnb_comp},{{key:q,label:{geb_fn}(q),onClick:()=>{{a({qx_fn}(q));k.push(q)}}}})),"
        f"h&&{react_id}.createElement({react_id}.Fragment,null,{react_id}.createElement({hnb_comp},{{text:\"Workspaces\"}}),g.map(q=>{react_id}.createElement({gnb_comp},{{key:q.uri,label:q.name,onClick:()=>{{var r=q.uri;a({rx_fn}({{workspaceUri:r}}));k.push(`workspace:${{r}}`)}}}}))),"
        f"!__hp&&__pm&&__vis.length>0&&{react_id}.createElement({react_id}.Fragment,null,{react_id}.createElement({hnb_comp},{{text:\"Projects\"}}),__shown.map(q=>{react_id}.createElement(__AgMobProjItem,{{key:q,sectionId:q,onSelect:()=>{{a({sx_fn}({{projectId:q}}));k.push(\"project:\"+q)}}}})),__vis.length>3&&{react_id}.createElement(\"button\",{{type:\"button\",className:\"text-xs text-muted-foreground hover:text-foreground px-4 py-1.5 cursor-pointer border-none bg-transparent text-left\",onClick:()=>__setShowAll(!__showAll)}},__showAll?\"Show Less\":\"Show All\")),"
        f"__pm&&{react_id}.createElement({react_id}.Fragment,null,{react_id}.createElement({hnb_comp},{{text:\"Not in Project\"}}),{react_id}.createElement({gnb_comp},{{label:\"Conversations\",onClick:()=>{{a({sx_fn}({{projectId:\"outside-of-project\"}}));k.push(\"project:outside-of-project\")}}}}))"
        f"),m&&{react_id}.createElement({kl_comp},{{offset:1-c,active:c===1,depth:1,exiting:k.exiting.includes(m),onExited:()=>{{k.onExited(m)}},header:{react_id}.createElement({inb_comp},{{onBack:k.pop}})}},{react_id}.createElement({ecb_ctx}.Provider,{{value:!0}},n?{react_id}.createElement({gmb_comp},null):__isProj?{react_id}.createElement({rnb_fn},null):{fnb_fn}(m))))}};"
    )

    content = content[: jnb_m.start()] + new_jnb + content[jnb_m.end() :]
    print("[web-patch] Added Projects and Not in Project: Conversations to mobile Settings")
    return content


def _patch_plugin_auth_and_install_redirect(content):
    # 1. Always enable pluginAuth feature flag
    pa_re = re.compile(
        r'pluginAuth:\{feature:!1,applyAll:\[\{runtimeGating:\{customFlag:"enable-plugin-auth"\},feature:!0\}\]\}'
    )
    if pa_re.search(content):
        content = pa_re.sub("pluginAuth:{feature:!0}", content, count=1)
        print("[web-patch] Enabled pluginAuth flag unconditionally")

    # 2. Use loopback auto-callback (mode 1) instead of manual copy-paste code (mode 2) on Android local WebView
    rnb_re = re.compile(
        r'function ([A-Za-z0-9_$]+)\(a\)\{a=a\?\.host;return a!==void 0&&\([A-Za-z0-9_$]+\([A-Za-z0-9_$]+\.Web,a\)\|\|[A-Za-z0-9_$]+\([A-Za-z0-9_$]+\.Extension\.Cider,a\)\)\?2:1\}'
    )
    rnb_m = rnb_re.search(content)
    if rnb_m and "location.hostname===" not in rnb_m.group(0):
        rnb_fn = rnb_m.group(1)
        orig_rnb = rnb_m.group(0)
        patched_rnb = orig_rnb.replace(
            f"function {rnb_fn}(a){{",
            f'function {rnb_fn}(a){{if(typeof window!=="undefined"&&(window.Android||location.hostname==="127.0.0.1"||location.hostname==="localhost"))return 1;',
            1,
        )
        content = content.replace(orig_rnb, patched_rnb, 1)
        print("[web-patch] Enabled loopback OAuth callback mode in rnb")
    elif not rnb_m:
        rnb_fn_m = re.search(
            r'function ([A-Za-z0-9_$]+)\(a\)\{if\(typeof window!=="undefined"', content
        )
        rnb_fn = rnb_fn_m.group(1) if rnb_fn_m else None
    else:
        rnb_fn = rnb_m.group(1)

    if not rnb_fn:
        raise RuntimeError("Could not locate plugin auth mode helper (rnb)")

    # 3. Poll signInPending providers automatically when tnb mounts
    if "signInPending)" not in content or "/*ag-poll*/" not in content:
        tnb_re = re.compile(
            r'(function\s+[A-Za-z0-9_$]+\(\{pluginDir:a\}\)\{.*?\[([A-Za-z0-9_$]+),[A-Za-z0-9_$]+\]=\(0,[A-Za-z0-9_$]+\.useState\)\(\[\]\),.*?var ([A-Za-z0-9_$]+)=Object\.keys\(([A-Za-z0-9_$]+)\)\.length>0;)',
            re.S,
        )
        tnb_m = tnb_re.search(content)
        if tnb_m and "/*ag-poll*/" not in tnb_m.group(0):
            providers_var = tnb_m.group(2)
            poll_var = tnb_m.group(3)
            pending_map_var = tnb_m.group(4)
            old_decl = f"var {poll_var}=Object.keys({pending_map_var}).length>0;"
            new_decl = f"var {poll_var}=Object.keys({pending_map_var}).length>0||{providers_var}.some(__p=>__p.signInPending)/*ag-poll*/;"
            idx = content.find(old_decl, tnb_m.start())
            if idx == -1:
                raise RuntimeError("Could not patch tnb poll condition")
            content = content[:idx] + new_decl + content[idx + len(old_decl) :]
            print("[web-patch] Enabled signInPending polling in plugin Accounts section")

    # 4. Skip post-install redirect during bundle Install All (flb)
    flb_batch = "installAll:w=>v(w,\"install\",(x,y)=>{if(x.id&&!x.installed)return l(y),x.isSkill?e(x.id):f(x.id)})"
    if flb_batch in content:
        content = content.replace(
            flb_batch,
            "installAll:w=>v(w,\"install\",(x,y)=>{if(x.id&&!x.installed)return l(y),x.isSkill?e(x.id):f(x.id,!0)})",
            1,
        )
        print("[web-patch] Marked bundle installAll to skip single-plugin redirect")

    # 5. Navigate to plugin settings & start OAuth on single install when no MCP/sidecar modal opens
    if "/*ag-plugin-post-install*/" not in content:
        # Extract jW, lR, uk, ug
        jw_m = re.search(r"function ([A-Za-z0-9_$]+)\(a\)\{return [A-Za-z0-9_$]+\([A-Za-z0-9_$]+\(a\?\.path\)\)\}", content)
        lr_m = re.search(r"function ([A-Za-z0-9_$]+)\(\)\{var a=[A-Za-z0-9_$]+\(\);return [A-Za-z0-9_$]+\.useCallback\(b=>a\.navigate\(\{", content)
        tnb_uk_m = re.search(
            r"\{listPluginAuthProviders:b,signInPluginAuthProvider:c,signOutPluginAuthProvider:e,startPluginAuthSignIn:f\}=([A-Za-z0-9_$]+)\(\),\{runtimeEnvironment:g\}=([A-Za-z0-9_$]+)\(\)",
            content,
        )
        ug_m = re.search(r"function ([A-Za-z0-9_$]+)\(a,b,c,e\)\{b=[A-Za-z0-9_$]+\(b\);return b!==void 0\?a\.open\(b,c,e\):null\}", content)
        nw_re = re.compile(
            r'function NW\(\{togglePlugin:a,installPlugin:b\}\)\{([^}]*?)\{getPlugin:h,jetboxWriteState:k\}=([A-Za-z0-9_$]+)\(\),(.*?)'
            r'var w=\(([A-Za-z0-9_$]+)\.useCallback\)\(async E=>\{(.*?)if\(K\.length===0&&E\.length===0\)return!0;(.*?)\},\[b,h,g,e,m\]\)',
            re.S,
        )
        # Allow any function name (in case NW gets renamed by minifier in a future build)
        nw_re2 = re.compile(
            r'function ([A-Za-z0-9_$]+)\(\{togglePlugin:a,installPlugin:b\}\)\{(.*?)\{getPlugin:h,jetboxWriteState:k\}=([A-Za-z0-9_$]+)\(\),\{logPermissionsReview:l\}=([A-Za-z0-9_$]+)\(\),m=\(0,([A-Za-z0-9_$]+)\.useContext\)\(([A-Za-z0-9_$]+)\),(.*?)'
            r'var w=\(0,\5\.useCallback\)\(async E=>\{(.*?)if\(K\.length===0&&E\.length===0\)return!0;(.*?)\},\[b,h,g,e,m\]\)',
            re.S,
        )
        nw_m = nw_re2.search(content)
        if not (jw_m and lr_m and tnb_uk_m and ug_m and nw_m):
            raise RuntimeError(
                f"NW post-install anchors missing: jW={bool(jw_m)} lR={bool(lr_m)} uk={bool(tnb_uk_m)} ug={bool(ug_m)} NW={bool(nw_m)}"
            )

        jw_fn = jw_m.group(1)
        lr_fn = lr_m.group(1)
        uk_fn = tnb_uk_m.group(2)
        ug_fn = ug_m.group(1)

        react_id = nw_m.group(5)
        cy_fn = nw_m.group(3)
        old_cy = f"{{getPlugin:h,jetboxWriteState:k}}={cy_fn}(),"
        new_cy = (
            f"{{getPlugin:h,jetboxWriteState:k,listPluginAuthProviders:__agLap,startPluginAuthSignIn:__agSpas}}={cy_fn}(),"
            f"__agNav={lr_fn}(),{{runtimeEnvironment:__agRe}}={uk_fn}(),"
        )

        old_w_sig = f"var w=(0,{react_id}.useCallback)(async E=>{{"
        new_w_sig = f"var w=(0,{react_id}.useCallback)(async(E,__agSkip)=>{{"

        old_ret = "if(K.length===0&&E.length===0)return!0;"
        new_ret = (
            "if(K.length===0&&E.length===0){/*ag-plugin-post-install*/"
            "if(!__agSkip){try{"
            f"var __pid={jw_fn}(J)||H;"
            "if(__pid){"
            "var __pr=__agLap?await __agLap():null,"
            "__provs=(__pr?.providers||[]).filter(P=>P.plugin===__pid),"
            "__unauth=__provs.filter(P=>P.needsAuth||!P.connected);"
            "if(__provs.length>0||(J.mcpServers&&J.mcpServers.length>0)){"
            "__agNav({to:\"/customizations/plugins/$pluginId\",params:{pluginId:__pid}});"
            "}"
            "var __oauth=__unauth.find(P=>P.type===\"oauth2\")||__unauth[0];"
            "if(__oauth&&__oauth.type===\"oauth2\"&&__agSpas){"
            f"var __mode={rnb_fn}(__agRe),"
            "__sr=await __agSpas({plugin:__oauth.plugin,provider:__oauth.provider,mode:__mode});"
            f"if(__sr?.authUrl){ug_fn}(window,__sr.authUrl,\"_blank\",\"noopener,noreferrer\");"
            "}"
            "}"
            "}catch(__e){console.error(\"[ag-plugin-post-install]\",__e)}}"
            "return!0}"
        )

        old_deps = "},[b,h,g,e,m])"
        new_deps = "},[b,h,g,e,m,__agNav,__agLap,__agSpas,__agRe])"

        nw_block = nw_m.group(0)
        if old_cy not in nw_block or old_w_sig not in nw_block or old_ret not in nw_block or old_deps not in nw_block:
            raise RuntimeError("Unexpected NW block layout during post-install patch")

        patched_nw = (
            nw_block.replace(old_cy, new_cy, 1)
            .replace(old_w_sig, new_w_sig, 1)
            .replace(old_ret, new_ret, 1)
            .replace(old_deps, new_deps, 1)
        )
        content = content[: nw_m.start()] + patched_nw + content[nw_m.end() :]
        print("[web-patch] Added post-install plugin redirect & auto-OAuth")

    return content


def patch_main(file_path):
    if not os.path.isfile(file_path):
        print(f"[web-patch] file not found: {file_path}", file=sys.stderr)
        return False

    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()

    content = _patch_desktop_mode(content)
    content = _patch_app_and_browser_settings(content)
    content = _patch_mobile_projects_and_conversations(content)
    content = _patch_plugin_auth_and_install_redirect(content)

    with open(file_path, "w", encoding="utf-8") as f:
        f.write(content)

    print("[web-patch] All patches applied cleanly")
    return True


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "main.js")
    if not patch_main(path):
        sys.exit(1)
