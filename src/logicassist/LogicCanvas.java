package logicassist;

import arc.scene.*;
import arc.struct.*;
import arc.util.*;
import logicassist.expr.*;
import logicassist.hooks.*;
import mindustry.logic.*;
import mindustry.logic.LCanvas.*;

/**
 * 继承原版 LCanvas，仅保留生命周期 dispatch。
 *
 * 5 大功能通过 CanvasHook 接口注册到 hooks Seq，
 * act/draw 按 hooks 顺序调用各阶段方法，自身不内联功能逻辑。
 * 详见 docs/adr/0002-canvas-hook-mechanism.md。
 */
public class LogicCanvas extends LCanvas{

    private final Seq<CanvasHook> hooks = new Seq<>();

    public LogicCanvas(){
        super();
        // 按执行顺序注册（afterAct: BoxSelect → ExprHook → MlogAddress → JumpButtonHook；
        //                  afterDraw: JumpLineColor → BoxSelect）
        new BoxSelectHook().register(hooks);
        new ExprHookAdapter().register(hooks);
        new MlogAddressHook().register(hooks);
        new JumpButtonHookAdapter().register(hooks);
        new JumpLineColorHook().register(hooks);
    }

    @Override
    public void load(String asm){
        super.load(asm);
        ExprHook.foldAll(this);
    }

    // save() 不再调用 unfoldAll+foldAll：ExprStatement.write() 已能输出标准 op 链 mlog，
    // 无需往返修改 canvas 状态。避免 fold/unfold 循环中 lastOps 覆盖错误表达式。
    @Override
    public String save(){
        return super.save();
    }

    // rebuild() 内部 save→load 循环会丢失 ExprStatement 原始 expr：
    // 错误表达式 write() 用 lastOps 输出，load 后 foldAll 用 rebuild(ops) 重建"正确版本"。
    // 这里在 rebuild 前保存 expr 状态，rebuild 后按顺序恢复，防止回退。
    @Override
    public void rebuild(){
        Seq<String[]> exprStates = new Seq<>();
        if(statements != null){
            for(Element child : statements.getChildren()){
                if(child instanceof StatementElem){
                    LStatement st = ((StatementElem)child).st;
                    if(st instanceof ExprStatement){
                        ExprStatement es = (ExprStatement)st;
                        exprStates.add(new String[]{es.dest, es.expr});
                    }
                }
            }
        }

        super.rebuild();

        if(exprStates.any() && statements != null){
            int idx = 0;
            for(Element child : statements.getChildren()){
                if(child instanceof StatementElem){
                    LStatement st = ((StatementElem)child).st;
                    if(st instanceof ExprStatement && idx < exprStates.size){
                        ExprStatement es = (ExprStatement)st;
                        String[] state = exprStates.get(idx++);
                        es.expr = state[1];
                        // 重新编译更新 lastOps 和 lastError
                        try{
                            es.lastOps = ExprCompiler.compile(es.dest, es.expr);
                            es.lastError = null;
                        }catch(Exception e){
                            es.lastError = e.getMessage();
                        }
                    }
                }
            }
        }
    }

    @Override
    public void act(float delta){
        for(int i = 0; i < hooks.size; i++){
            hooks.get(i).beforeAct(this, delta);
        }
        super.act(delta);
        for(int i = 0; i < hooks.size; i++){
            hooks.get(i).afterAct(this, delta);
        }
    }

    @Override
    public void draw(){
        for(int i = 0; i < hooks.size; i++){
            hooks.get(i).beforeDraw(this);
        }
        super.draw();
        for(int i = 0; i < hooks.size; i++){
            hooks.get(i).afterDraw(this);
        }
    }
}
