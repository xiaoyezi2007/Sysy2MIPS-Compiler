package optimizer;

import llvm.BasicBlock;
import llvm.Function;
import llvm.GlobalValue;
import llvm.IrModule;
import llvm.Use;
import llvm.instr.GepInstr;
import llvm.instr.Instruction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

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
        calGepCnt();
        deadCodeCheck();

        return irModule;
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
