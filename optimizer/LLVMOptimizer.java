package optimizer;

import llvm.BasicBlock;
import llvm.Function;
import llvm.GlobalValue;
import llvm.IrModule;
import llvm.Use;
import llvm.User;
import llvm.Value;
import llvm.ValueType;
import llvm.instr.AllocaInstr;
import llvm.instr.BranchInstr;
import llvm.instr.GepInstr;
import llvm.instr.Instruction;
import llvm.instr.JumpInstr;
import llvm.instr.LoadInstr;
import llvm.instr.PhiInstr;
import llvm.instr.StoreInstr;
import util.Tool;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

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
        buildCFG();
        initDominate();
        initDF();
        insertPhi();
        renameSSA();

        calGepCnt();
        deadCodeCheck();

        initDomTree();
        rename();
        GVN();
        //GCM();

        liveAnalyse();

        rmKeyEdge();
        rmPhi();
        rename();

        rmSimpleEdge();


        return irModule;
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
                    if (instruction.isPinned()) {
                        vis.add(instruction);
                        instruction.earlyBlock = block;
                        for (Value value : instruction.getOperands()) {
                            if (value instanceof Instruction) {
                                scheduleEarly((Instruction) value, vis, function);
                            }
                        }
                    }
                }
            }

            vis = new HashSet<>();
            for (BasicBlock block : function.getBasicBlocks()) {
                for (Instruction instruction : block.getInstructions()) {
                    scheduleLate(instruction, vis, function);
                }
            }

            try {
                tool.setOutput("test.txt");
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
            function.print();

            for (BasicBlock block : function.getBasicBlocks()) {
                ArrayList<Instruction> rm = new ArrayList<>();
                ArrayList<Instruction> insert = new ArrayList<>();
                for (Instruction instruction : block.getInstructions()) {
                    if (!(instruction instanceof PhiInstr || instruction.isTerminal() || instruction.isFloated)) {
                        BasicBlock best = instruction.lateBlock;
                        BasicBlock curr = instruction.lateBlock;

                        if (instruction.earlyBlock == null) {
                            best = function.getBasicBlocks().get(0);
                            curr = function.getBasicBlocks().get(0);
                            instruction.earlyBlock = function.getBasicBlocks().get(0);
                        }

                        while (curr != instruction.earlyBlock) {
                            if (curr.cycleDepth < best.cycleDepth) {
                                best = curr;
                            }
                            curr = curr.directDom;
                        }
                        if (instruction.earlyBlock.cycleDepth < best.cycleDepth) {
                            best = instruction.earlyBlock;
                        }
                        if (best != block) {
                            best.insertInstrBeforeTerminal(instruction);
                            rm.add(instruction);
                        }
                        else {
                            rm.add(instruction);
                            insert.add(instruction);
                        }
                    }
                }
                for (Instruction instruction : rm) {
                    instruction.isFloated = true;
                    block.removeInstr(instruction);
                }
                for (Instruction instruction : insert) {
                    block.insertInstrBeforeTerminal(instruction);
                }
            }
        }
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
}
