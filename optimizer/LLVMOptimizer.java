package optimizer;

import llvm.BasicBlock;
import llvm.Function;
import llvm.GlobalValue;
import llvm.IrModule;
import llvm.GlobalVariable;
import llvm.IRType;
import llvm.User;
import llvm.Use;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;
import llvm.GlobalVariable;
import llvm.instr.AllocaInstr;
import llvm.instr.AluInstr;
import llvm.instr.BranchInstr;
import llvm.instr.CmpInstr;
import llvm.instr.GepInstr;
import llvm.instr.Instruction;
import llvm.instr.CallInstr;
import llvm.instr.JumpInstr;
import llvm.instr.LoadInstr;
import llvm.instr.PhiInstr;
import llvm.instr.StoreInstr;
import llvm.instr.ZextInstr;
import util.Tool;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

public class LLVMOptimizer {
    private Tool tool = new Tool();

    private IrModule irModule;
    private ArrayList<Function> functions;
    private ArrayList<BasicBlock> basicBlocks;
    private ArrayList<Instruction> instructions;
    private ArrayList<GlobalValue> globals;

    private static HashMap<GepInstr, Integer> gepCnt = new HashMap<>();

    private HashMap<String,Value> gvn = new HashMap<>();

    public LLVMOptimizer(IrModule irModule) {
        this.irModule = irModule;
    }

    public IrModule optimize() {
        gepCnt.clear();
        buildCFG();
        initDominate();
        initDF();
        insertPhi();
        renameSSA();

        DeadArgEliminate();

        DeadBranchEliminate();

        DeadLoopEliminate();

        DeadRetEliminate();

        RemoveBlocks();

        LocalArrayLift();

        SoraPass();

        ConstIdx2Value();

        calGepCnt();
        deadCodeCheck();

        initDomTree();
        rename();
        GVN();
        GCM();
        InstructionResort();

        deadCodeCheck();

        liveAnalyse();

        rmKeyEdge();
        rmPhi();
        rename();

        rmSimpleEdge();


        return irModule;
    }

    /**
     * After cross-block code motion, ensure each basic block's instruction order respects SSA def-use.
     * This backend assigns stack slots during codegen; if a use appears before its def in the same block,
     * the used value may still have the default memPos=1, producing illegal accesses like -1($sp).
     *
     * Strategy: keep Phi nodes at the top and the terminal at the end;
     * keep pinned instructions in-place as barriers; topologically sort each contiguous non-pinned region.
     */
    public void InstructionResort() {
        for (Function function : irModule.getFunctions()) {
            for (BasicBlock block : function.getBasicBlocks()) {
                ArrayList<Instruction> instrs = block.getInstructions();
                if (instrs.size() <= 2) {
                    continue;
                }

                Instruction terminal = instrs.get(instrs.size() - 1);
                int end = instrs.size() - 1; // exclusive

                ArrayList<Instruction> newList = new ArrayList<>(instrs.size());
                int idx = 0;
                while (idx < end && instrs.get(idx) instanceof PhiInstr) {
                    newList.add(instrs.get(idx));
                    idx++;
                }

                int segStart = idx;
                while (segStart < end) {
                    int segEnd = segStart;
                    while (segEnd < end && !instrs.get(segEnd).isPinned()) {
                        segEnd++;
                    }

                    if (segEnd > segStart) {
                        newList.addAll(topoSortChunk(instrs.subList(segStart, segEnd)));
                    }
                    if (segEnd < end) {
                        // Pinned instruction as a barrier; keep relative order.
                        newList.add(instrs.get(segEnd));
                        segEnd++;
                    }
                    segStart = segEnd;
                }

                newList.add(terminal);
                instrs.clear();
                instrs.addAll(newList);
            }
        }
    }

    private ArrayList<Instruction> topoSortChunk(List<Instruction> chunk) {
        ArrayList<Instruction> ordered = new ArrayList<>(chunk.size());
        if (chunk.size() <= 1) {
            ordered.addAll(chunk);
            return ordered;
        }

        HashMap<Instruction, Integer> index = new HashMap<>();
        HashMap<Instruction, Integer> indeg = new HashMap<>();
        HashMap<Instruction, ArrayList<Instruction>> adj = new HashMap<>();

        for (int i = 0; i < chunk.size(); i++) {
            Instruction ins = chunk.get(i);
            index.put(ins, i);
            indeg.put(ins, 0);
            adj.put(ins, new ArrayList<>());
        }

        for (Instruction ins : chunk) {
            for (Value op : ins.getOperands()) {
                if (op instanceof Instruction) {
                    Instruction def = (Instruction) op;
                    if (index.containsKey(def)) {
                        adj.get(def).add(ins);
                        indeg.put(ins, indeg.get(ins) + 1);
                    }
                }
            }
        }

        HashSet<Instruction> placed = new HashSet<>();
        boolean progress;
        do {
            progress = false;
            for (Instruction ins : chunk) {
                if (!placed.contains(ins) && indeg.get(ins) == 0) {
                    placed.add(ins);
                    ordered.add(ins);
                    for (Instruction succ : adj.get(ins)) {
                        indeg.put(succ, indeg.get(succ) - 1);
                    }
                    progress = true;
                }
            }
        } while (progress);

        if (ordered.size() != chunk.size()) {
            // Should not happen in well-formed SSA; fall back to original order.
            ordered.clear();
            ordered.addAll(chunk);
        }
        return ordered;
    }

    public void GVN() {
        for (Function f : irModule.getFunctions()) {
            BasicBlock root = f.getBasicBlocks().get(0);
            GVNdfs(root);
            gvn = new HashMap<>();
        }
    }

    public void GVNdfs(BasicBlock node) {
        ArrayList<Instruction> rm = new ArrayList<>();
        ArrayList<String> tomp = new ArrayList<>();
        for (Instruction i : node.getInstructions()) {
            // Algebraic simplification (safe identities), e.g. x*1=x, x+0=x.
            Value simplified = trySimplifyIdentity(i);
            if (simplified != null) {
                for (Value user : i.getUsers()) {
                    ((User) user).changeUse(i, simplified);
                }
                rm.add(i);
                ArrayList<String> slist = i.tripleString();
                if (!slist.isEmpty()) {
                    tomp.addAll(slist);
                    for (String s : slist) {
                        gvn.put(s, simplified);
                    }
                }
                continue;
            }

            // Constant rewrite (conservative): if instruction can be proven a constant,
            // replace all its uses with that constant and remove it.
            Constant folded = tryFoldConstant(i);
            if (folded != null) {
                Value v = folded;
                for (Value user : i.getUsers()) {
                    ((User) user).changeUse(i, v);
                }
                rm.add(i);
                // Still record this expression's VN -> constant, so later identical expressions fold too.
                ArrayList<String> slist = i.tripleString();
                if (!slist.isEmpty()) {
                    tomp.addAll(slist);
                    for (String s : slist) {
                        gvn.put(s, v);
                    }
                }
                continue;
            }
            ArrayList<String> slist = i.tripleString();
            if (slist.isEmpty()) continue;
            boolean found = false;
            for (String s : slist) {
                if (gvn.containsKey(s)) {
                    found = true;
                    Value v = gvn.get(s);
                    for (Value user : i.getUsers()) {
                        ((User) user).changeUse(i,v);
                    }
                    rm.add(i);
                    break;
                }
            }
            if (!found) {
                tomp.addAll(slist);
                for (String s : slist) {
                    gvn.put(s, i);
                }
            }
        }
        for (Instruction i : rm) {
            node.removeInstr(i);
        }
        for (BasicBlock nxt : node.domTreeNext) {
            GVNdfs(nxt);
        }
        for (String s : tomp) {
            gvn.remove(s);
        }
    }

    private Value trySimplifyIdentity(Instruction instr) {
        if (instr instanceof AluInstr) {
            AluInstr alu = (AluInstr) instr;
            Value a = alu.getUseValue(0);
            Value b = alu.getUseValue(1);
            Integer ca = getConstInt(a);
            Integer cb = getConstInt(b);
            String op = alu.getOperator();

            // Note: only apply identities that are always correct for 32-bit ints and
            // do not remove potential traps (we avoid 0/x and x/0 style transforms).
            if (op.equals("+")) {
                if (ca != null && ca == 0) return b;
                if (cb != null && cb == 0) return a;
            }
            else if (op.equals("-")) {
                if (cb != null && cb == 0) return a;
                if (a.equals(b)) return new ConstantInt(0);
            }
            else if (op.equals("*")) {
                if (ca != null && ca == 0) return new ConstantInt(0);
                if (cb != null && cb == 0) return new ConstantInt(0);
                if (ca != null && ca == 1) return b;
                if (cb != null && cb == 1) return a;
            }
            else if (op.equals("/")) {
                // x/1 = x
                if (cb != null && cb == 1) return a;
            }
            else if (op.equals("%")) {
                // x%1 = 0
                if (cb != null && cb == 1) return new ConstantInt(0);
            }
            return null;
        }

        if (instr instanceof CmpInstr) {
            CmpInstr cmp = (CmpInstr) instr;
            Value a = cmp.getUseValue(0);
            Value b = cmp.getUseValue(1);
            if (!a.equals(b)) {
                return null;
            }
            // For integers: x==x true, x!=x false, x<x false, x<=x true, etc.
            // We don't have direct access to cmp.op (private). Infer via tripleString first token.
            ArrayList<String> ts = cmp.tripleString();
            if (ts.isEmpty()) {
                return null;
            }
            String head = ts.get(0);
            if (head.startsWith("== ")) return new ConstantInt(1);
            if (head.startsWith("!= ")) return new ConstantInt(0);
            if (head.startsWith("< ")) return new ConstantInt(0);
            if (head.startsWith("<= ")) return new ConstantInt(1);
            if (head.startsWith("> ")) return new ConstantInt(0);
            if (head.startsWith(">= ")) return new ConstantInt(1);
            return null;
        }

        return null;
    }

    private Integer getConstInt(Value v) {
        Constant c = v.getValue();
        if (c instanceof ConstantInt) {
            return Integer.parseInt(c.getName());
        }
        return null;
    }

    private Constant tryFoldConstant(Instruction instr) {
        // Only fold pure ops that we are confident are side-effect-free and don't depend on memory.
        // This avoids incorrect folding for loads/stores/calls or anything with aliasing.
        if (instr instanceof AluInstr || instr instanceof CmpInstr || instr instanceof ZextInstr) {
            try {
                return instr.getValue();
            } catch (ArithmeticException ex) {
                // e.g. division/mod by zero: do not fold.
                return null;
            }
        }
        if (instr instanceof PhiInstr) {
            PhiInstr phi = (PhiInstr) instr;
            if (phi.defBlock == null || phi.defBlock.isEmpty()) {
                return null;
            }
            ConstantInt first = null;
            for (int k = 0; k < phi.defBlock.size(); k++) {
                Value v = phi.getUseValue(k);
                if (v == null) {
                    return null;
                }
                Constant c = v.getValue();
                if (!(c instanceof ConstantInt)) {
                    return null;
                }
                ConstantInt ci = (ConstantInt) c;
                if (first == null) {
                    first = ci;
                }
                else if (!first.equals(ci)) {
                    return null;
                }
            }
            if (first == null) {
                return null;
            }
            return new ConstantInt(Integer.parseInt(first.getName()));
        }
        return null;
    }

    public void rmSimpleEdge() {
        for (Function f : irModule.getFunctions()) {
            BasicBlock nxt = null;
            do {
                for (BasicBlock block : f.getBasicBlocks()) {
                    if (block.next.size() == 1) {
                        for (BasicBlock next : block.next) {
                            nxt = next;
                        }
                        if (nxt.prev.size() == 1) {
                            block.next = nxt.next;
                            for (BasicBlock b : nxt.next) {
                                b.prev.remove(nxt);
                                b.prev.add(block);
                            }
                            instructions = block.getInstructions();
                            block.getInstructions().remove(instructions.size() - 1);
                            for (Instruction i : nxt.getInstructions()) {
                                block.addInstruction(i);
                            }
                            break;
                        } else {
                            nxt = null;
                        }
                    }
                }
                if (nxt != null) f.getBasicBlocks().remove(nxt);
            } while (nxt != null);

        }
    }

    public void liveAnalyse() {
        for (Function function : irModule.getFunctions()) {
            for (BasicBlock block : function.getBasicBlocks()) {
                block.initUseDef();
            }
        }
        for (Function function : irModule.getFunctions()) {
            boolean flag = true;
            while (flag) {
                flag = false;
                for (BasicBlock block : function.getBasicBlocks()) {
                    boolean change = block.updateLive();
                    if (change) {
                        flag = true;
                    }
                }
            }
        }
    }

    public void GCM() {
        for (Function function : irModule.getFunctions()) {
            BasicBlock root = function.getBasicBlocks().get(0);
            HashSet<Instruction> vis = new HashSet<>();
            for (BasicBlock block : function.getBasicBlocks()) {
                for (Instruction instruction : block.getInstructions()) {
                    scheduleEarly(instruction, vis, function);
                }
            }

            vis = new HashSet<>();
            for (BasicBlock block : function.getBasicBlocks()) {
                for (Instruction instruction : block.getInstructions()) {
                    scheduleLate(instruction, vis, function);
                }
            }

            // Collect movable instructions first to avoid mutating instruction lists while iterating them.
            ArrayList<Instruction> movable = new ArrayList<>();
            for (BasicBlock block : function.getBasicBlocks()) {
                for (Instruction instruction : block.getInstructions()) {
                    if (!(instruction instanceof PhiInstr) && !instruction.isTerminal() && !instruction.isPinned()) {
                        movable.add(instruction);
                    }
                }
            }

            for (Instruction instruction : movable) {
                if (instruction.isFloated) {
                    continue;
                }
                BasicBlock best = instruction.lateBlock;
                BasicBlock curr = instruction.lateBlock;

                if (instruction.earlyBlock == null) {
                    best = root;
                    curr = root;
                    instruction.earlyBlock = root;
                }

                // Choose best block on the dom-chain between [late, early].
                while (curr != instruction.earlyBlock) {
                    if (curr == null) {
                        // Defensive: if the chain is broken, fall back to earlyBlock.
                        best = instruction.earlyBlock;
                        break;
                    }
                    if (best != null && curr.cycleDepth < best.cycleDepth) {
                        best = curr;
                    }
                    curr = curr.directDom;
                }
                if (best == null || instruction.earlyBlock.cycleDepth < best.cycleDepth) {
                    best = instruction.earlyBlock;
                }

                // Safety: the chosen block must dominate every use. Otherwise, the backend may read
                // an uninitialized stack slot (causing wrong control flow / wrong answers).
                if (best != null && !dominatesAllUses(function, best, instruction)) {
                    best = instruction.lateBlock;
                }
                if (best != null && !dominatesAllUses(function, best, instruction)) {
                    // Give up moving this instruction if we cannot find a dominating placement.
                    instruction.isFloated = true;
                    continue;
                }
                instruction.targetBlock = best;

                BasicBlock from = function.findInstrBlock(instruction);
                if (from == null || best == null || best == from) {
                    // Don't reshuffle within the same block: it can easily create use-before-def in this IR.
                    instruction.isFloated = true;
                    continue;
                }

                int insertIndex = findSafeInsertIndex(function, best, instruction);
                if (insertIndex < 0) {
                    // If we cannot find a safe position in the target block, keep it in place.
                    instruction.isFloated = true;
                    continue;
                }

                from.removeInstr(instruction);
                best.getInstructions().add(insertIndex, instruction);
                instruction.isFloated = true;
            }
        }
    }

    /**
     * Find a safe insertion index for moving {@code instr} into {@code target}.
     * Requirements (in the target block):
     * - After all Phi instructions.
     * - After any operand definitions that are also in {@code target}.
     * - Before the first non-Phi use of {@code instr} that is also in {@code target}.
     * - Before the terminal instruction.
     * Returns -1 if no safe insertion position exists.
     */
    private int findSafeInsertIndex(Function function, BasicBlock target, Instruction instr) {
        ArrayList<Instruction> list = target.getInstructions();
        if (list.isEmpty()) {
            return -1;
        }
        int beforeTerminal = list.size() - 1;
        if (beforeTerminal < 0) {
            return -1;
        }

        int afterPhi = 0;
        while (afterPhi < list.size() && list.get(afterPhi) instanceof PhiInstr) {
            afterPhi++;
        }

        int afterOperands = afterPhi;
        for (Value op : instr.getOperands()) {
            if (op instanceof Instruction) {
                Instruction def = (Instruction) op;
                BasicBlock defBlock = function.findInstrBlock(def);
                if (defBlock == target) {
                    int defIndex = list.indexOf(def);
                    if (defIndex >= 0) {
                        afterOperands = Math.max(afterOperands, defIndex + 1);
                    }
                }
            }
        }

        int firstUse = beforeTerminal;
        boolean hasLocalUse = false;
        for (Value userV : instr.getUsers()) {
            if (userV instanceof Instruction) {
                Instruction user = (Instruction) userV;
                if (user instanceof PhiInstr) {
                    continue;
                }
                BasicBlock useBlock = function.findInstrBlock(user);
                if (useBlock == target) {
                    int useIndex = list.indexOf(user);
                    if (useIndex >= 0) {
                        hasLocalUse = true;
                        firstUse = Math.min(firstUse, useIndex);
                    }
                }
            }
        }

        int insertIndex;
        if (hasLocalUse) {
            // Must be placed strictly before the first local use.
            insertIndex = afterOperands;
            if (insertIndex > firstUse) {
                return -1;
            }
        } else {
            // No local uses: place as late as possible while keeping operand order.
            insertIndex = Math.min(beforeTerminal, Math.max(afterOperands, beforeTerminal));
        }

        if (insertIndex > beforeTerminal) {
            return -1;
        }
        return insertIndex;
    }

    private boolean dominatesAllUses(Function function, BasicBlock candidate, Instruction instr) {
        if (candidate == null) {
            return false;
        }
        for (Value userV : instr.getUsers()) {
            if (!(userV instanceof Instruction)) {
                continue;
            }
            Instruction user = (Instruction) userV;
            BasicBlock useBlock = function.findInstrBlock(user);
            if (user instanceof PhiInstr) {
                useBlock = ((PhiInstr) user).findPrevBlock(instr);
            }
            if (useBlock == null) {
                continue;
            }
            // BasicBlock.dom stores dominators of this block.
            if (!useBlock.isDominate(candidate)) {
                return false;
            }
        }
        return true;
    }

    public void scheduleLate(Instruction instr, HashSet<Instruction> vis, Function function) {
        if (vis.contains(instr)) {
            return;
        }
        vis.add(instr);
        if (instr.isPinned()) {
            instr.lateBlock = function.findInstrBlock(instr);
            return;
        }
        BasicBlock late = instr.lateBlock;
        for (Value value : instr.getUsers()) {
            if (value instanceof Instruction) {
                Instruction instr2 = (Instruction) value;
                scheduleLate(instr2, vis, function);
                BasicBlock use = instr2.lateBlock;
                if (instr2 instanceof PhiInstr) {
                    use = ((PhiInstr) instr2).findPrevBlock(instr);
                }
                if (use == null) {
                    continue;
                }
                if (late == null) {
                    late = use;
                }
                else {
                    late = findLCA(late,use);
                }
            }
        }
        instr.lateBlock = late;
    }

    public BasicBlock findLCA(BasicBlock A, BasicBlock B) {
        BasicBlock tmpA = A;
        BasicBlock tmpB = B;
        while (tmpA.domDepth > tmpB.domDepth) {
            tmpA = tmpA.directDom;
        }
        while (tmpB.domDepth > tmpA.domDepth) {
            tmpB = tmpB.directDom;
        }
        while (tmpA != tmpB) {
            tmpA = tmpA.directDom;
            tmpB = tmpB.directDom;
        }
        return tmpA;
    }

    public void scheduleEarly(Instruction instr, HashSet<Instruction> vis, Function function) {
        if (vis.contains(instr)) {
            return;
        }
        vis.add(instr);
        BasicBlock root = function.getBasicBlocks().get(0);
        if (instr.isPinned()) {
            instr.earlyBlock = function.findInstrBlock(instr);
        }
        else {
            instr.earlyBlock = root;
        }
        for (Value value : instr.getOperands()) {
            if (value instanceof Instruction) {
                Instruction x = (Instruction) value;
                scheduleEarly(x, vis, function);
                if (instr.earlyBlock.domDepth < x.earlyBlock.domDepth) {
                    instr.earlyBlock = x.earlyBlock;
                }
            }
        }
    }

    public void initDomTree() {
        for (Function function : irModule.getFunctions()) {
            for (BasicBlock basicBlock : function.getBasicBlocks()) {
                if (basicBlock.directDom != null) {
                    basicBlock.directDom.domTreeNext.add(basicBlock);
                }
            }
            function.getBasicBlocks().get(0).updateDomDepth(0);
        }
    }

    public void rename() {
        for (Function function : irModule.getFunctions()) {
            int cnt = 0;
            for (BasicBlock block : function.getBasicBlocks()) {
                block.setName("b"+cnt);
                cnt++;
                for (Instruction instruction : block.getInstructions()) {
                    instruction.setName("%v"+cnt);
                    cnt++;
                }
            }
        }
    }

    public void rmPhi() {
        functions = irModule.getFunctions();
        for (Function f : functions) {
            basicBlocks = f.getBasicBlocks();
            for (BasicBlock b : basicBlocks) {
                ArrayList<PhiInstr> phiInstrs = new ArrayList<>();
                for (Instruction i : b.getInstructions()) {
                    if (i instanceof PhiInstr) {
                        phiInstrs.add((PhiInstr) i);
                    }
                    else break;
                }
                for (PhiInstr p : phiInstrs) {
                    p.addMove();
                }
            }
        }
    }

    public void rmKeyEdge() {
        functions = irModule.getFunctions();
        for (Function f : functions) {
            basicBlocks = new ArrayList<>(f.getBasicBlocks());
            for (BasicBlock A : basicBlocks) {
                ArrayList<BasicBlock> Anext = new ArrayList<>(A.next);
                for (BasicBlock B : Anext) {
                    if(A.next.size() > 1 && B.prev.size() > 1) {
                        BasicBlock T = new BasicBlock();
                        A.replaceNextBlock(B,T);
                        B.replacePrevBlock(A,T);
                        f.addBasicBlock(T);
                        T.setFatherFunction(f);
                        T.addInstruction(new JumpInstr(B));

                        A.addNextBlock(T);
                        T.addPrevBlock(A);
                        T.addNextBlock(B);
                        B.addPrevBlock(T);
                        A.removeNextBlock(B);
                        B.removePrevBlock(A);
                    }
                }
            }
        }
    }

    public void renameSSA() {
        for (Function function : irModule.getFunctions()) {
            for (BasicBlock basicBlock : function.getBasicBlocks()) {
                Iterator<Instruction> iterator = basicBlock.getInstructions().iterator();
                while (iterator.hasNext()) {
                    Instruction instruction = iterator.next();
                    if (instruction instanceof StoreInstr && ((StoreInstr) instruction).toVar()) {
                        ((StoreInstr) instruction).store(basicBlock);
                        iterator.remove();
                    }
                    else if (instruction instanceof LoadInstr && ((LoadInstr) instruction).fromVar()) {
                        ((LoadInstr) instruction).load(basicBlock);
                        iterator.remove();
                    }
                    else if (instruction instanceof AllocaInstr && instruction.getType().ptTo().toString().equals("i32")) {
                        iterator.remove();
                    }
                    else if (instruction instanceof PhiInstr) {
                        ((PhiInstr) instruction).initPhi(basicBlock);
                    }
                }
            }
            for (BasicBlock basicBlock : function.getBasicBlocks()) {
                for (Instruction instruction : basicBlock.getInstructions()) {
                    if (instruction instanceof PhiInstr) {
                        ((PhiInstr) instruction).updatePhi(basicBlock);
                    }
                }
            }
            int cnt = 0;
            for (BasicBlock block : function.getBasicBlocks()) {
                block.setName("b"+cnt);
                cnt++;
                for (Instruction instruction : block.getInstructions()) {
                    instruction.setName("%v"+cnt);
                    cnt++;
                }
            }
        }
    }

    /**
     * LocalArrayLift: promote const local arrays to globals to shrink stack usage and reuse global data.
     * Safety: only lift arrays that are const (declared const) and never written via store.
     */
    public void LocalArrayLift() {
        int liftId = 0;
        for (Function function : irModule.getFunctions()) {
            for (BasicBlock block : function.getBasicBlocks()) {
                Iterator<Instruction> it = block.getInstructions().iterator();
                while (it.hasNext()) {
                    Instruction instr = it.next();
                    if (!(instr instanceof AllocaInstr)) {
                        continue;
                    }
                    AllocaInstr alloca = (AllocaInstr) instr;
                    IRType pt = alloca.getType().ptTo();
                    if (pt == null || !pt.isArray()) {
                        continue;
                    }
                    if (!alloca.isConst) {
                        continue;
                    }
                    if (isArrayWritten(function, alloca)) {
                        continue;
                    }

                    String gName = function.getName() + ".liftarr." + liftId;
                    liftId++;
                    GlobalVariable gv = new GlobalVariable(gName, new IRType("ptr", pt));
                    gv.setIsConst(true);

                    int len = pt.size;
                    ArrayList<Value> init = new ArrayList<>();
                    ArrayList<Value> src = alloca.getValues();
                    for (int i = 0; i < len; i++) {
                        Value v = (src != null && i < src.size()) ? src.get(i) : null;
                        if (v == null || v.getValue() == null) {
                            v = new ConstantInt(0);
                        }
                        init.add(v);
                    }
                    gv.setValue(init);
                    irModule.addGlobal(gv);

                    ArrayList<Value> users = new ArrayList<>(alloca.getUsers());
                    for (Value u : users) {
                        if (u instanceof User) {
                            ((User) u).changeUse(alloca, gv);
                            alloca.rmUser((User) u);
                        }
                    }
                    it.remove();
                }
            }
        }
    }

    private boolean isArrayWritten(Function function, AllocaInstr alloca) {
        for (BasicBlock b : function.getBasicBlocks()) {
            for (Instruction ins : b.getInstructions()) {
                if (ins instanceof StoreInstr) {
                    Value dst = ins.getUseValue(1);
                    if (dst == alloca) {
                        return true;
                    }
                    if (dst instanceof GepInstr) {
                        Value base = ((GepInstr) dst).getUseValue(0);
                        if (base == alloca) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * DeadBranchEliminate: fold constant branches and drop unreachable basic blocks.
     * - If branch condition is constant, redirect to the taken target and ignore the other.
     * - If true/false targets are identical, treat as unconditional.
     * - Rebuild CFG and prune blocks unreachable from entry.
     */
    public void DeadBranchEliminate() {
        for (Function f : irModule.getFunctions()) {
            // 1) Fold constant branches to a single successor.
            for (BasicBlock b : f.getBasicBlocks()) {
                ArrayList<Instruction> instrs = b.getInstructions();
                if (instrs.isEmpty()) continue;
                Instruction term = instrs.get(instrs.size() - 1);
                if (!(term instanceof BranchInstr)) continue;

                BranchInstr br = (BranchInstr) term;
                Value cond = br.getUseValue(0);
                Constant c = cond.getValue();

                BasicBlock t = br.getTrueBlock();
                BasicBlock fblk = br.getFalseBlock();

                if (c instanceof ConstantInt) {
                    int v = Integer.parseInt(c.getName());
                    if (v == 0) {
                        br.setUseValue(0, new ConstantInt(0));
                        br.setUseValue(1, fblk);
                        br.setUseValue(2, fblk);
                        removePhiIncoming(t, b);
                    } else {
                        br.setUseValue(0, new ConstantInt(1));
                        br.setUseValue(2, t);
                        br.setUseValue(1, t);
                        removePhiIncoming(fblk, b);
                    }
                } else if (t == fblk) {
                    br.setUseValue(0, new ConstantInt(1));
                }
            }

            // 2) Rebuild CFG edges from terminals.
            rebuildCFGEdges(f);

            // 3) Remove unreachable blocks.
            ArrayList<BasicBlock> unreachable = findUnreachable(f);
            for (BasicBlock ub : unreachable) {
                removeBlockAndPhiRefs(f, ub);
            }
            if (!unreachable.isEmpty()) {
                rebuildCFGEdges(f);
            }
        }
    }

    private void rebuildCFGEdges(Function f) {
        for (BasicBlock b : f.getBasicBlocks()) {
            b.next.clear();
            b.prev.clear();
        }
        for (BasicBlock b : f.getBasicBlocks()) {
            ArrayList<Instruction> instrs = b.getInstructions();
            if (instrs.isEmpty()) continue;
            Instruction term = instrs.get(instrs.size() - 1);
            if (term instanceof BranchInstr) {
                BasicBlock t = ((BranchInstr) term).getTrueBlock();
                BasicBlock fl = ((BranchInstr) term).getFalseBlock();
                b.addNextBlock(t);
                b.addNextBlock(fl);
                t.addPrevBlock(b);
                fl.addPrevBlock(b);
            } else if (term instanceof JumpInstr) {
                BasicBlock nx = ((JumpInstr) term).getBlock();
                b.addNextBlock(nx);
                nx.addPrevBlock(b);
            }
        }
    }

    private ArrayList<BasicBlock> findUnreachable(Function f) {
        ArrayList<BasicBlock> res = new ArrayList<>();
        if (f.getBasicBlocks().isEmpty()) return res;
        HashSet<BasicBlock> vis = new HashSet<>();
        ArrayList<BasicBlock> q = new ArrayList<>();
        BasicBlock entry = f.getBasicBlocks().get(0);
        q.add(entry);
        vis.add(entry);
        for (int i = 0; i < q.size(); i++) {
            BasicBlock cur = q.get(i);
            for (BasicBlock nx : cur.next) {
                if (!vis.contains(nx)) {
                    vis.add(nx);
                    q.add(nx);
                }
            }
        }
        for (BasicBlock b : new ArrayList<>(f.getBasicBlocks())) {
            if (!vis.contains(b)) {
                res.add(b);
            }
        }
        return res;
    }

    private void removeBlockAndPhiRefs(Function f, BasicBlock ub) {
        // Remove incoming references in successors' Phi nodes.
        for (BasicBlock succ : new ArrayList<>(ub.next)) {
            removePhiIncoming(succ, ub);
            succ.removePrevBlock(ub);
        }
        for (BasicBlock pred : new ArrayList<>(ub.prev)) {
            pred.removeNextBlock(ub);
        }
        f.removeBlock(ub);
    }

    private void removePhiIncoming(BasicBlock block, BasicBlock pred) {
        ArrayList<Instruction> instrs = block.getInstructions();
        for (Instruction ins : instrs) {
            if (!(ins instanceof PhiInstr)) break;
            PhiInstr phi = (PhiInstr) ins;
            ArrayList<BasicBlock> defs = phi.defBlock;
            for (int i = defs.size() - 1; i >= 0; i--) {
                if (defs.get(i).equals(pred)) {
                    defs.remove(i);
                    // remove operand i
                    Value v = phi.getUseValue(i);
                    if (v != null) {
                        v.rmUser(phi);
                    }
                    phi.getUseList().remove(i);
                    phi.values.remove(i);
                }
            }
        }
    }

    /**
     * RemoveBlocks: prune unreachable blocks and orphan blocks after previous cleanups.
     * Steps: rebuild CFG edges, BFS from entry, remove non-reachable blocks and clean Phi incoming edges.
     */
    public void RemoveBlocks() {
        for (Function f : irModule.getFunctions()) {
            if (f.getBasicBlocks().isEmpty()) continue;

            rebuildCFGEdges(f);

            HashSet<BasicBlock> vis = new HashSet<>();
            ArrayList<BasicBlock> q = new ArrayList<>();
            BasicBlock entry = f.getBasicBlocks().get(0);
            q.add(entry);
            vis.add(entry);
            for (int i = 0; i < q.size(); i++) {
                BasicBlock cur = q.get(i);
                for (BasicBlock nx : cur.next) {
                    if (!vis.contains(nx)) {
                        vis.add(nx);
                        q.add(nx);
                    }
                }
            }

            ArrayList<BasicBlock> dead = new ArrayList<>();
            for (BasicBlock b : new ArrayList<>(f.getBasicBlocks())) {
                if (!vis.contains(b)) {
                    dead.add(b);
                }
            }
            for (BasicBlock b : dead) {
                removeBlockAndPhiRefs(f, b);
            }
            if (!dead.isEmpty()) {
                rebuildCFGEdges(f);
            }
        }
    }

    /**
     * DeadLoopEliminate: remove loops that have no observable effect (no store/call/side effects) and whose
     * exit does not depend on external state (fixed iteration known to terminate but results unused).
     * Conservative heuristic:
     * - Loop detected by a backedge (block that dominates one of its successors).
     * - All instructions inside loop are side-effect free (no Store, no Call, no phi with external use).
     * - Values produced in loop are not used outside the loop.
     */
    public void DeadLoopEliminate() {
        // Refresh dominators once before scanning all functions.
        initDominate();
        for (Function f : irModule.getFunctions()) {
            if (f.getBasicBlocks().isEmpty()) continue;
            HashSet<BasicBlock> toRemove = new HashSet<>();
            for (BasicBlock b : f.getBasicBlocks()) {
                for (BasicBlock nx : b.next) {
                    if (nx.isDominate(b)) { // backedge b -> nx forming a loop
                        HashSet<BasicBlock> loopBlocks = collectLoop(b, nx);
                        if (loopBlocks.contains(f.getBasicBlocks().get(0))) {
                            continue; // never remove entry block loops conservatively
                        }
                        if (isLoopSideEffectFree(loopBlocks) && loopDefsNotUsedOutside(loopBlocks, f)) {
                            toRemove.addAll(loopBlocks);
                        }
                    }
                }
            }
            if (toRemove.isEmpty()) continue;
            for (BasicBlock lb : toRemove) {
                removeBlockAndPhiRefs(f, lb);
            }
            rebuildCFGEdges(f);
        }
    }

    private HashSet<BasicBlock> collectLoop(BasicBlock tail, BasicBlock header) {
        HashSet<BasicBlock> loop = new HashSet<>();
        ArrayList<BasicBlock> stack = new ArrayList<>();
        stack.add(tail);
        while (!stack.isEmpty()) {
            BasicBlock cur = stack.remove(stack.size() - 1);
            if (!loop.add(cur)) continue;
            for (BasicBlock p : cur.prev) {
                if (p == header || header.isDominate(p)) {
                    stack.add(p);
                }
            }
        }
        loop.add(header);
        return loop;
    }

    private boolean isLoopSideEffectFree(HashSet<BasicBlock> loop) {
        for (BasicBlock b : loop) {
            for (Instruction ins : b.getInstructions()) {
                if (ins instanceof StoreInstr) return false;
                if (ins instanceof CallInstr) return false;
                if (ins instanceof BranchInstr || ins instanceof JumpInstr || ins instanceof PhiInstr) {
                    continue;
                }
                // Other instructions are assumed pure.
            }
        }
        return true;
    }

    private boolean loopDefsNotUsedOutside(HashSet<BasicBlock> loop, Function f) {
        for (BasicBlock b : loop) {
            for (Instruction ins : b.getInstructions()) {
                for (Value u : ins.getUsers()) {
                    if (u instanceof Instruction) {
                        BasicBlock ub = f.findInstrBlock((Instruction) u);
                        if (ub != null && !loop.contains(ub)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * DeadRetEliminate: for calls whose return value is unused, avoid generating return storage.
     * We keep the call (side effects) but drop its result handling.
     */
    public void DeadRetEliminate() {
        for (Function f : irModule.getFunctions()) {
            for (BasicBlock b : f.getBasicBlocks()) {
                for (Instruction ins : b.getInstructions()) {
                    if (ins instanceof CallInstr) {
                        CallInstr call = (CallInstr) ins;
                        if (call.getUserList().isEmpty()) {
                            call.discardReturn();
                        }
                    }
                }
            }
        }
    }

    /**
     * DeadArgEliminate: remove parameters that are not used in function body and update call sites.
     * Scope: skip built-in I/O functions (putint/putstr/putch/getint).
     */
    public void DeadArgEliminate() {
        ArrayList<Function> funcs = irModule.getFunctions();
        for (Function f : funcs) {
            if (isBuiltinFunc(f)) {
                continue;
            }
            ArrayList<Value> params = new ArrayList<>(f.getParameters());
            ArrayList<Integer> deadIdx = new ArrayList<>();
            for (int i = 0; i < params.size(); i++) {
                Value p = params.get(i);
                if (p.getUserList().isEmpty()) {
                    deadIdx.add(i);
                }
            }
            if (deadIdx.isEmpty()) {
                continue;
            }

            // Update call sites: remove args in descending index order.
            for (Function g : funcs) {
                for (BasicBlock b : g.getBasicBlocks()) {
                    for (Instruction ins : new ArrayList<>(b.getInstructions())) {
                        if (ins instanceof CallInstr) {
                            CallInstr call = (CallInstr) ins;
                            Value callee = call.getUseValue(0);
                            if (callee == f) {
                                for (int k = deadIdx.size() - 1; k >= 0; k--) {
                                    call.removeArg(deadIdx.get(k));
                                }
                            }
                        }
                    }
                }
            }

            // Remove params from function signature in descending order.
            for (int k = deadIdx.size() - 1; k >= 0; k--) {
                f.removeParameter(deadIdx.get(k));
            }
        }
    }

    private boolean isBuiltinFunc(Function f) {
        String n = f.getName();
        return n.equals("putint") || n.equals("putstr") || n.equals("putch") || n.equals("getint");
    }

    /**
     * SoraPass: scalarize small, immutable local arrays accessed only by constant indices in entry block.
     * Assumptions for safety:
     * - Alloca array (fixed length) in the function.
     * - All stores to this array happen in the entry block via GEP with constant index.
     * - No other writes and no address escapes (only GEP->load/store users).
     * Transformation: replace loads with the SSA value stored for that index (or zero if never stored),
     * remove corresponding loads/stores/GEPs/alloca.
     */
    public void SoraPass() {
        for (Function function : irModule.getFunctions()) {
            if (function.getBasicBlocks().isEmpty()) {
                continue;
            }
            BasicBlock entry = function.getBasicBlocks().get(0);

            ArrayList<AllocaInstr> arrays = new ArrayList<>();
            for (BasicBlock b : function.getBasicBlocks()) {
                for (Instruction ins : b.getInstructions()) {
                    if (ins instanceof AllocaInstr) {
                        IRType pt = ((AllocaInstr) ins).getType().ptTo();
                        if (pt != null && pt.isArray() && pt.size > 0) {
                            arrays.add((AllocaInstr) ins);
                        }
                    }
                }
            }

            for (AllocaInstr alloca : arrays) {
                if (!canScalarizeArray(function, entry, alloca)) {
                    continue;
                }

                int len = alloca.getType().ptTo().size;
                ArrayList<Value> idxValue = new ArrayList<>(len);
                for (int i = 0; i < len; i++) idxValue.add(new ConstantInt(0));

                // Record last store per index in entry order.
                for (Instruction ins : entry.getInstructions()) {
                    if (!(ins instanceof StoreInstr)) continue;
                    Value dst = ins.getUseValue(1);
                    if (!(dst instanceof GepInstr)) continue;
                    GepInstr gep = (GepInstr) dst;
                    if (gep.getUseValue(0) != alloca) continue;
                    Constant cidx = gep.getUseValue(1).getValue();
                    if (cidx == null) continue;
                    int id = Integer.parseInt(cidx.getName());
                    if (id < 0 || id >= len) continue;
                    idxValue.set(id, ins.getUseValue(0));
                }

                // Replace loads and mark stores/geps for removal.
                ArrayList<Instruction> removeList = new ArrayList<>();
                for (BasicBlock b : function.getBasicBlocks()) {
                    for (Instruction ins : new ArrayList<>(b.getInstructions())) {
                        if (ins instanceof LoadInstr) {
                            Value src = ins.getUseValue(0);
                            if (src instanceof GepInstr && ((GepInstr) src).getUseValue(0) == alloca) {
                                Constant cidx = ((GepInstr) src).getUseValue(1).getValue();
                                if (cidx == null) continue;
                                int id = Integer.parseInt(cidx.getName());
                                if (id < 0 || id >= len) continue;
                                Value repl = idxValue.get(id);
                                for (Value u : ins.getUsers()) {
                                    ((User) u).changeUse(ins, repl);
                                }
                                removeList.add(ins);
                            }
                        }
                        else if (ins instanceof StoreInstr) {
                            Value dst = ins.getUseValue(1);
                            if (dst instanceof GepInstr && ((GepInstr) dst).getUseValue(0) == alloca) {
                                removeList.add(ins);
                            }
                        }
                    }
                }

                for (Instruction rm : removeList) {
                    BasicBlock b = function.findInstrBlock(rm);
                    if (b != null) {
                        removeInstrAndUnlink(b, rm);
                    }
                }

                // Remove dead GEPs tied to this alloca.
                for (BasicBlock b : function.getBasicBlocks()) {
                    ArrayList<Instruction> deadGep = new ArrayList<>();
                    for (Instruction ins : new ArrayList<>(b.getInstructions())) {
                        if (ins instanceof GepInstr && ((GepInstr) ins).getUseValue(0) == alloca && ins.getUsers().isEmpty()) {
                            deadGep.add(ins);
                        }
                    }
                    for (Instruction rm : deadGep) {
                        removeInstrAndUnlink(b, rm);
                    }
                }

                // Remove the alloca if unused.
                if (alloca.getUserList().isEmpty()) {
                    BasicBlock b = function.findInstrBlock(alloca);
                    if (b != null) {
                        removeInstrAndUnlink(b, alloca);
                    }
                }
            }
        }
    }

    private boolean canScalarizeArray(Function function, BasicBlock entry, AllocaInstr alloca) {
        // Reject if any non-GEP use or address escape.
        for (BasicBlock b : function.getBasicBlocks()) {
            for (Instruction ins : b.getInstructions()) {
                ArrayList<Value> ops = ins.getOperands();
                boolean touches = false;
                for (Value v : ops) {
                    if (v == alloca) {
                        touches = true;
                        break;
                    }
                }
                if (!touches) {
                    continue;
                }

                if (ins instanceof GepInstr) {
                    GepInstr gep = (GepInstr) ins;
                    if (gep.getUseValue(0) != alloca) {
                        return false;
                    }
                    Constant cidx = gep.getUseValue(1).getValue();
                    if (cidx == null) {
                        return false;
                    }
                    int id = Integer.parseInt(cidx.getName());
                    int len = alloca.getType().ptTo().size;
                    if (id < 0 || id >= len) {
                        return false;
                    }
                    // Users of this GEP must be load or store.
                    for (Value u : gep.getUsers()) {
                        if (!(u instanceof LoadInstr || u instanceof StoreInstr)) {
                            return false;
                        }
                    }
                }
                else if (ins instanceof StoreInstr) {
                    // Direct store to alloca without GEP -> not supported.
                    Value dst = ins.getUseValue(1);
                    if (dst == alloca) {
                        return false;
                    }
                }
                else if (ins instanceof LoadInstr) {
                    // Direct load from alloca -> not supported.
                    Value src = ins.getUseValue(0);
                    if (src == alloca) {
                        return false;
                    }
                }
                else {
                    // Any other use (call/phi/etc) is escape.
                    return false;
                }
            }
        }

        // All stores must be in entry block.
        for (BasicBlock b : function.getBasicBlocks()) {
            for (Instruction ins : b.getInstructions()) {
                if (!(ins instanceof StoreInstr)) continue;
                Value dst = ins.getUseValue(1);
                if (dst instanceof GepInstr && ((GepInstr) dst).getUseValue(0) == alloca) {
                    if (b != entry) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * ConstIdx2Value: fold array accesses with constant index into the element value.
     * Pattern: load (gep base, constIdx) where base is a const global/alloca array with known elements.
     * Action: replace uses of the load with the pre-known element value and remove the load (and dead gep).
     */
    public void ConstIdx2Value() {
        for (Function function : irModule.getFunctions()) {
            for (BasicBlock block : function.getBasicBlocks()) {
                ArrayList<Instruction> toRemove = new ArrayList<>();

                for (Instruction instr : block.getInstructions()) {
                    if (!(instr instanceof LoadInstr)) {
                        continue;
                    }
                    Value from = instr.getUseValue(0);
                    if (!(from instanceof GepInstr)) {
                        continue;
                    }
                    GepInstr gep = (GepInstr) from;
                    Constant idxConst = gep.getUseValue(1).getValue();
                    if (idxConst == null) {
                        continue;
                    }

                    Value base = gep.getUseValue(0);
                    Value elem = null;
                    if (base instanceof AllocaInstr) {
                        elem = ((AllocaInstr) base).getKthEle(idxConst);
                    } else if (base instanceof GlobalVariable) {
                        elem = ((GlobalVariable) base).getKthEle(idxConst);
                    }
                    if (elem == null) {
                        continue;
                    }

                    for (Value userV : instr.getUsers()) {
                        ((User) userV).changeUse(instr, elem);
                    }
                    toRemove.add(instr);
                }

                for (Instruction rm : toRemove) {
                    removeInstrAndUnlink(block, rm);
                }

                // Clean up dead GEPs that became unused after load removal.
                ArrayList<Instruction> deadGeps = new ArrayList<>();
                for (Instruction instr : new ArrayList<>(block.getInstructions())) {
                    if (instr instanceof GepInstr && instr.getUsers().isEmpty()) {
                        deadGeps.add(instr);
                    }
                }
                for (Instruction rm : deadGeps) {
                    removeInstrAndUnlink(block, rm);
                }
            }
        }
    }

    public void insertPhi() {
        for (Function function : irModule.getFunctions()) {
            ArrayList<PhiInstr> phiInstrs = new ArrayList<>();
            for (BasicBlock basicBlock : function.getBasicBlocks()) {
                for (Instruction instruction : basicBlock.getInstructions()) {
                    if (instruction instanceof AllocaInstr &&
                        instruction.getType().ptTo().toString().equals("i32")) {
                        phiInstrs.addAll(insertPhiOfVar(function, (AllocaInstr) instruction));
                    }
                }
            }
            for (PhiInstr phiInstr : phiInstrs) {
                phiInstr.getBlock().insertPhi(phiInstr);
            }
        }
    }

    public ArrayList<PhiInstr> insertPhiOfVar(Function function, AllocaInstr instr) {
        ArrayList<PhiInstr> ans = new ArrayList<>();
        basicBlocks = function.getBasicBlocks();
        HashSet<BasicBlock> Def = new HashSet<>();
        HashSet<BasicBlock> W = new HashSet<>();
        HashSet<BasicBlock> F = new HashSet<>();
        for (BasicBlock basicBlock : basicBlocks) {
            for (Instruction instruction : basicBlock.getInstructions()) {
                if (instruction.isDef(instr)) {
                    W.add(basicBlock);
                    Def.add(basicBlock);
                    break;
                }
            }
        }
        while (!W.isEmpty()) {
            BasicBlock block = W.iterator().next();
            W.remove(block);
            for (BasicBlock Y : block.dominanceFrontier) {
                if (!F.contains(Y)) {
                    PhiInstr phiInstr = new PhiInstr(instr, Y);
                    instr.store(Y, phiInstr);
                    ans.add(phiInstr);
                    F.add(Y);
                    if (!Def.contains(Y)) {
                        W.add(Y);
                    }
                }
            }
        }
        return ans;
    }

    public void initDF() {
        for (Function function : irModule.getFunctions()) {
            BasicBlock x = null;
            for (BasicBlock b : function.getBasicBlocks()) {
                for (BasicBlock a : b.prev) {
                    if (a == b) {
                        continue;
                    }
                    x = a;
                    while (!b.isStrictDominate(x)) {
                        x.addDF(b);
                        x = x.directDom;
                        if (x == null) {
                            break;
                        }
                    }
                }
            }
        }
    }

    public void initDominate() {
        for (Function f : irModule.getFunctions()) {
            for (BasicBlock b : f.getBasicBlocks()) {
                b.initDom(f.getBasicBlocks());
            }
            boolean flag = true;
            while (flag) {
                flag = false;
                for (BasicBlock b : f.getBasicBlocks()) {
                    boolean update = b.updateDom();
                    if (update) {
                        flag = true;
                    }
                }
            }
            for (BasicBlock b : f.getBasicBlocks()) {
                b.updateDirectDom();
            }
        }
    }

    public void buildCFG() {
        for (Function function : irModule.getFunctions()) {
            for (BasicBlock basicBlock : function.getBasicBlocks()) {
                for (Instruction instruction : basicBlock.getInstructions()) {
                    if (instruction instanceof JumpInstr) {
                        basicBlock.addNextBlock(((JumpInstr) instruction).getBlock());
                        ((JumpInstr) instruction).getBlock().addPrevBlock(basicBlock);
                    }
                    else if (instruction instanceof BranchInstr) {
                        BasicBlock trueBlock = ((BranchInstr) instruction).getTrueBlock();
                        BasicBlock falseBlock = ((BranchInstr) instruction).getFalseBlock();
                        basicBlock.addNextBlock(trueBlock);
                        basicBlock.addNextBlock(falseBlock);
                        trueBlock.addPrevBlock(basicBlock);
                        falseBlock.addPrevBlock(basicBlock);
                    }
                }
            }
        }
    }

    public void deadCodeCheck() {
        for (Function function : irModule.getFunctions()) {
            /*
            ArrayList<BasicBlock> rm = new ArrayList<>(function.getBasicBlocks());
            while (!rm.isEmpty()) {
                rm = new ArrayList<>();
                basicBlocks = function.getBasicBlocks();
                for (int i=1;i<basicBlocks.size();i++) {
                    if (basicBlocks.get(i).directDom == null) {
                        rm.add(basicBlocks.get(i));
                    }
                }
                for (BasicBlock block : rm) {
                    function.removeBlock(block);
                }
            }*/
            for (BasicBlock basicBlock : function.getBasicBlocks()) {
                instructions = basicBlock.getInstructions();
                for (int i = instructions.size() - 1; i >= 0; i--) {
                    Instruction instruction = instructions.get(i);
                    if (instruction.isDead()) {
                        basicBlock.rmInstruction(i);
                        int sz = instruction.getUseList().size();
                        for (int j = 0;j < sz; j++) {
                            instruction.getUseValue(j).rmUser(instruction);
                        }
                    }
                }
            }
        }
    }

    public void calGepCnt() {
        for (Function function : irModule.getFunctions()) {
            for (BasicBlock basicBlock : function.getBasicBlocks()) {
                for (Instruction instruction : basicBlock.getInstructions()) {
                    if (instruction instanceof GepInstr && gepCnt.containsKey((GepInstr) instruction)) {
                        int x = gepCnt.get(instruction);
                        gepCnt.put((GepInstr) instruction, x+1);
                    }
                    else if (instruction instanceof GepInstr) {
                        gepCnt.put((GepInstr) instruction, 1);
                    }
                }
            }
        }
    }

    public static boolean gepUseOneTime(GepInstr instr) {
        return gepCnt.containsKey(instr) && gepCnt.get(instr) == 1;
    }

    private void removeInstrAndUnlink(BasicBlock block, Instruction instr) {
        block.rmInstruction(instr);
        int sz = instr.getUseList().size();
        for (int i = 0; i < sz; i++) {
            Value v = instr.getUseValue(i);
            if (v != null) {
                v.rmUser(instr);
            }
        }
    }
}
