package optimizer;

import llvm.BasicBlock;
import llvm.Function;
import llvm.GlobalValue;
import llvm.IrModule;
import llvm.Use;
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
    private IrModule irModule;
    private ArrayList<Function> functions;
    private ArrayList<BasicBlock> basicBlocks;
    private ArrayList<Instruction> instructions;
    private ArrayList<GlobalValue> globals;

    private static HashMap<GepInstr, Integer> gepCnt = new HashMap<>();

    public LLVMOptimizer(IrModule irModule) {
        this.irModule = irModule;
    }

    public IrModule optimize() {

        buildCFG();
        initDominate();
        initDF();
        insertPhi();
        renameSSA();
        rmKeyEdge();
        rmPhi();
        rename();
        //calGepCnt();
        //deadCodeCheck();

        return irModule;
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
