package logicassist;

import arc.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.*;
import mindustry.mod.*;

/**
 * 模组更新检查器：通过 GitHub API 检查最新 release。
 *
 * 在模组初始化时后台请求，结果存入静态字段供设置界面读取。
 */
public class UpdateChecker{

    public static final String REPO = "nosbhghggg/logic-assist";
    public static final String API_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    public static final String RELEASE_URL = "https://github.com/" + REPO + "/releases/latest";

    public static boolean hasUpdate = false;
    public static String latestVersion = null;
    private static boolean checked = false;

    public static void check(){
        if(checked) return;
        checked = true;

        Http.get(API_URL, res -> {
            try{
                var json = new JsonReader().parse(res.getResultAsString());
                String tag = json.getString("tag_name", "");
                // tag 格式: v1.1.1
                latestVersion = tag.startsWith("v") ? tag.substring(1) : tag;

                String current = getCurrentVersion();
                if(compareVersions(latestVersion, current) > 0){
                    hasUpdate = true;
                    Log.info("[LogicAssist] Update available: " + latestVersion + " (current: " + current + ")");
                    Core.app.post(() -> JumpLineColor.onUpdateChecked());
                }else{
                    Log.info("[LogicAssist] Up to date: " + current);
                }
            }catch(Exception e){
                Log.err("[LogicAssist] Update check parse failed", e);
            }
        }, e -> Log.debug("[LogicAssist] Update check network failed: " + e));
    }

    private static String getCurrentVersion(){
        Mods.LoadedMod mod = Vars.mods.getMod(LogicAssistMod.class);
        return mod != null && mod.meta != null ? mod.meta.version : "0";
    }

    private static int compareVersions(String v1, String v2){
        String[] p1 = v1.split("\\.");
        String[] p2 = v2.split("\\.");
        int len = Math.max(p1.length, p2.length);
        for(int i = 0; i < len; i++){
            int n1 = i < p1.length ? Strings.parseInt(p1[i]) : 0;
            int n2 = i < p2.length ? Strings.parseInt(p2[i]) : 0;
            if(n1 != n2) return Integer.compare(n1, n2);
        }
        return 0;
    }
}
