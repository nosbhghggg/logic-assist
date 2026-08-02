package logicassist;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.scene.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.scene.style.*;
import arc.struct.Seq;
import arc.util.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.logic.*;
import mindustry.logic.LStatements.*;
import mindustry.mod.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import java.util.*;

/**
 * 跳转线着色：为 jump 跳转线按目标着色，便于区分不同分支。
 *
 * 模式：0=关闭（白色），1=分散色（黄金角度 HSV），2=积木色（类别颜色提亮 1.4×）
 * JumpStatement.dest 是 public 字段，直接强转访问。
 */
public class JumpLineColor{

    private static final String SETTING_ENABLED = "la-jump-color-enabled";
    private static final String SETTING_BLOCKCOLOR = "la-jump-color-blockcolor";
    public static final String SETTING_SCROLLBAR = "la-scrollbar-enabled";

    private static final String MARKER = "la-jump-color-patched";

    private static final float GOLDEN_ANGLE = 137.508f;
    private static final Map<Integer, Color> indexColorCache = new HashMap<>();
    private static final Map<String, Color> blockColorCache = new HashMap<>();

    public static int getMode(){
        try{
            if(!Core.settings.getBool(SETTING_ENABLED, true)) return 0;
            return Core.settings.getBool(SETTING_BLOCKCOLOR, true) ? 2 : 1;
        }catch(Exception e){
            return 1;
        }
    }

    // 按目标 index 生成黄金角度 HSV 颜色（相邻 index 颜色差异最大）
    public static Color colorForIndex(int index){
        if(indexColorCache.containsKey(index)) return indexColorCache.get(index);

        Color c = new Color();
        // fromHsv 不设置 alpha，需手动补 a = 1.0（arc Color.java 已知陷阱）
        c.fromHsv(((index * GOLDEN_ANGLE) % 360f + 360f) % 360f, 0.7f, 1.0f);
        c.a = 1.0f;
        indexColorCache.put(index, c);
        return c;
    }

    // 提亮源颜色 1.4× 并确保 alpha=1.0（用于积木色模式）
    public static Color brighten(Color src){
        Color c = new Color();
        c.set(src);
        c.mul(1.4f);
        c.a = 1.0f;
        return c;
    }

    public static Color getColorForTarget(LCanvas.StatementElem dest){
        int mode = getMode();
        if(mode == 0) return Color.white;

        if(mode == 2){
            // 积木色模式：按目标积木类别颜色
            LStatement st = dest.st;
            if(st != null){
                LCategory cat = st.category();
                if(cat != null && cat.color != null){
                    String key = cat.name;
                    if(!blockColorCache.containsKey(key)){
                        blockColorCache.put(key, brighten(cat.color));
                    }
                    return blockColorCache.get(key);
                }
            }
            return Color.white;
        }

        // 分散色模式：按目标 index
        return colorForIndex(dest.index);
    }

    // 为单个 JumpCurve 注入着色回调。
    // 回调在 JumpCurve.act() 的 super.act() 之后执行，覆盖原版白色。
    public static void patchCurve(LCanvas.JumpCurve curve){
        if(curve == null || MARKER.equals(curve.userObject)) return;
        curve.userObject = MARKER;

        curve.update(() -> {
            try{
                if(getMode() == 0) return;

                LCanvas.JumpButton button = curve.button;
                if(button == null) return;
                LCanvas.StatementElem elem = button.elem;
                if(elem == null) return;
                LStatement st = elem.st;
                if(st == null) return;

                // 获取跳转目标：JumpStatement.dest 是 public 字段，直接强转
                LCanvas.StatementElem dest = null;
                if(st instanceof JumpStatement){
                    dest = ((JumpStatement)st).dest;
                }

                if(dest == null){
                    button.color.set(Color.white);
                }else if(button.hasMouse()){
                    // 悬停时高亮
                    button.color.set(Pal.place);
                }else if(dest.parent != null){
                    // 按目标着色
                    button.color.set(getColorForTarget(dest));
                    button.color.a = 1.0f;
                }else{
                    button.color.set(Color.white);
                }

                // 同步按钮样式颜色
                ImageButton.ImageButtonStyle style = button.getStyle();
                if(style != null) style.imageUpColor = button.color;

            }catch(Exception e){
                Log.warn("[LogicAssist] JumpLineColor error: " + e);
            }
        });
    }

    public static void patchAllCurves(LCanvas canvas){
        if(canvas == null || canvas.statements == null) return;
        Group jumps = canvas.statements.jumps;
        if(jumps == null) return;

        for(Element child : jumps.getChildren()){
            if(child instanceof LCanvas.JumpCurve){
                patchCurve((LCanvas.JumpCurve)child);
            }
        }
    }

    /**
     * 在主设置菜单中添加 "Logic Assist" 分类。
     * 使用 settings-icon.png 作为分类图标，包含着色、滚动条、反馈按钮等设置项。
     */
    public static void setupSettings(){
        try{
            SettingsMenuDialog sd = Vars.ui.settings;
            if(sd == null) return;

            // 检查是否已添加（避免重复，同时兼容带更新提示的分类名）
            String localized = Core.bundle.get("la.settings");
            for(SettingsMenuDialog.SettingsCategory cat : sd.getCategories()){
                if(cat.name.equals("@la.settings") || cat.name.startsWith(localized)) return;
            }

            // 使用 settings-icon.png 作为分类图标，回退到模组图标，再回退到 Icon.edit
            Drawable categoryIcon = Icon.edit;
            try{
                Texture iconTexture = new Texture("settings-icon.png");
                categoryIcon = new TextureRegionDrawable(new TextureRegion(iconTexture));
            }catch(Exception e){
                Log.warn("[LogicAssist] Failed to load settings icon: " + e);
                try{
                    Mods.LoadedMod mod = Vars.mods.getMod(LogicAssistMod.class);
                    if(mod != null && mod.iconTexture != null){
                        categoryIcon = new TextureRegionDrawable(new TextureRegion(mod.iconTexture));
                    }
                }catch(Exception e2){
                    Log.warn("[LogicAssist] Failed to load mod icon for settings category: " + e2);
                }
            }

            sd.addCategory("@la.settings", categoryIcon, table -> {
                table.checkPref(SETTING_ENABLED, true, b -> {});
                table.checkPref(SETTING_BLOCKCOLOR, true, b -> {});
                table.checkPref(SETTING_SCROLLBAR, true, b -> {});

                // 反馈/更新按钮：通过 Setting 机制添加，确保 MindustryX 的 build() 重建后仍然存在
                // MindustryX 的 SettingsTable.build() 会 clearChildren() 后只按 list 重建，
                // 直接 table.button() 添加的元素会被清除
                // 每次 build() 时根据 UpdateChecker.hasUpdate 动态决定显示内容
                table.pref(new SettingsMenuDialog.SettingsTable.Setting("la-feedback"){
                    @Override
                    public void add(SettingsMenuDialog.SettingsTable t){
                        if(UpdateChecker.hasUpdate){
                            t.button("@la.update.go", Icon.link, () ->
                                Core.app.openURI(UpdateChecker.RELEASE_URL)
                            ).growX().height(50f).padTop(12f).row();
                        }else{
                            t.button("@la.feedback", Icon.github, () ->
                                Core.app.openURI("https://github.com/nosbhghggg/logic-assist/issues")
                            ).growX().height(50f).padTop(12f).row();
                        }
                    }
                });
            });
        }catch(Exception e){
            Log.warn("[LogicAssist] Failed to setup settings: " + e);
        }
    }

    /**
     * 更新检查完成后调用：将设置分类名后追加黄色"（有新版本！）"提示。
     * 移除旧分类并以新名称重新添加，触发分类列表重建。
     */
    public static void onUpdateChecked(){
        if(!UpdateChecker.hasUpdate) return;

        Core.app.post(() -> {
            try{
                SettingsMenuDialog sd = Vars.ui.settings;
                if(sd == null) return;

                Seq<SettingsMenuDialog.SettingsCategory> cats = sd.getCategories();
                for(int i = 0; i < cats.size; i++){
                    SettingsMenuDialog.SettingsCategory cat = cats.get(i);
                    if(cat.name.equals("@la.settings")){
                        String newName = Core.bundle.get("la.settings") + " " + Core.bundle.get("la.update.available");
                        Cons<SettingsMenuDialog.SettingsTable> builder = cat.builder;
                        Drawable icon = cat.icon;
                        cats.remove(i);
                        sd.addCategory(newName, icon, builder);
                        Log.info("[LogicAssist] Settings category renamed with update indicator.");
                        break;
                    }
                }
            }catch(Exception e){
                Log.warn("[LogicAssist] Failed to update settings category name: " + e);
            }
        });
    }

    // 清空颜色缓存，在编辑器关闭时调用
    public static void clearCache(){
        indexColorCache.clear();
        blockColorCache.clear();
    }
}
