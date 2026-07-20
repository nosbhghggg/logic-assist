// ============================================================
// jump-line-color v9
// Color jump lines in logic editor by target destination
// ============================================================

"use strict";

// Boolc helper (global.js does not define this)
// Rhino Java interop syntax - IDE may warn, but works in Mindustry
// @ts-ignore
const boolc = method => new Boolc(){ get: method }

// ---------- Settings ----------
const SETTING_ENABLED = "jlc-enabled";
const SETTING_BLOCKCOLOR = "jlc-blockcolor";

function getMode(){
    try{
        if(!Core.settings.getBool(SETTING_ENABLED, true)) return 0;
        return Core.settings.getBool(SETTING_BLOCKCOLOR, false) ? 2 : 1;
    }catch(e){ return 1; }
}

// ---------- Color cache ----------
const indexColorCache = {};
const blockColorCache = {};
const GOLDEN_ANGLE = 137.508;

function colorForIndex(index){
    if(indexColorCache[index] != null) return indexColorCache[index];
    const c = new Color();
    c.fromHsv(((index * GOLDEN_ANGLE) % 360 + 360) % 360, 0.7, 1.0);
    c.a = 1.0;
    indexColorCache[index] = c;
    return c;
}

// Brighten a source color (for block color mode)
function brighten(src){
    const c = new Color();
    c.set(src);
    c.mul(1.4);
    c.a = 1.0;
    return c;
}

function getColorForTarget(dest){
    const mode = getMode();
    if(mode === 0) return Color.white;
    if(mode === 2){
        const st = dest.st;
        if(st != null){
            const cat = st.category();
            if(cat != null && cat.color != null){
                const key = cat.name;
                if(blockColorCache[key] == null) blockColorCache[key] = brighten(cat.color);
                return blockColorCache[key];
            }
        }
        return Color.white;
    }
    return colorForIndex(dest.index);
}

// ---------- Reflection fallback for JumpStatement.dest ----------
let destField = null;
let destFieldChecked = false;

function getDestField(st){
    if(destField != null) return destField;
    if(destFieldChecked) return null;
    destFieldChecked = true;
    try{
        destField = st.getClass().getField("dest");
    }catch(e){
        print("[jump-line-color] Failed to get dest field: " + e);
    }
    return destField;
}

// ---------- Patch JumpCurve ----------
const MARKER = "jlc-patched";

function patchCurve(curve){
    if(curve == null || curve.userObject == MARKER) return;
    curve.userObject = MARKER;

    curve.update(run(() => {
        try{
            if(getMode() === 0) return;
            const button = curve.button;
            if(button == null) return;
            const elem = button.elem;
            if(elem == null) return;
            const st = elem.st;
            if(st == null) return;

            let dest = st.dest;
            if(dest === undefined || dest === null){
                const field = getDestField(st);
                if(field != null) dest = field.get(st);
            }

            if(dest == null){
                button.color.set(Color.white);
            }else if(button.hasMouse()){
                button.color.set(Pal.place);
            }else if(dest.parent != null){
                button.color.set(getColorForTarget(dest));
                button.color.a = 1.0;
            }else{
                button.color.set(Color.white);
            }

            const style = button.getStyle();
            if(style != null) style.imageUpColor = button.color;
        }catch(e){
            print("[jump-line-color] Color error: " + e);
        }
    }));
}

function patchAllCurves(canvas){
    if(canvas == null || canvas.statements == null) return;
    const jumps = canvas.statements.jumps;
    if(jumps == null) return;
    const children = jumps.getChildren();
    for(let i = 0; i < children.size; i++) patchCurve(children.get(i));
}

// ---------- Settings category ----------
function setupSettings(){
    try{
        const sd = Vars.ui.settings;
        if(sd == null) return;
        const cats = sd.getCategories();
        for(let i = 0; i < cats.size; i++){
            if(cats.get(i).name === "@jlc.settings") return;
        }
        sd.addCategory("@jlc.settings", Icon.edit, cons(table => {
            table.checkPref(SETTING_ENABLED, true, boolc(b => {}));
            table.checkPref(SETTING_BLOCKCOLOR, false, boolc(b => {}));
        }));
    }catch(e){
        print("[jump-line-color] Failed to setup settings: " + e);
    }
}

// ---------- Main loop ----------
function startLoop(dialog){
    const tick = run(() => {
        try{
            Core.app.post(tick);
            if(!dialog.isShown() || dialog.canvas == null) return;
            patchAllCurves(dialog.canvas);
        }catch(e){
            print("[jump-line-color] Tick error: " + e);
            try{ Core.app.post(tick); }catch(e2){}
        }
    });
    Core.app.post(tick);
}

function init(){
    const dialog = Vars.ui.logic;
    if(dialog == null) return;
    setupSettings();
    startLoop(dialog);
}

if(typeof Vars !== "undefined" && Vars.ui != null && Vars.ui.logic != null && Vars.ui.settings != null){
    init();
}else{
    Events.on(ClientLoadEvent, cons(e => init()));
}