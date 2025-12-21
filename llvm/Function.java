package llvm;

import llvm.constant.Constant;
import llvm.instr.Instruction;
import mips.IInstr;
import mips.Label;
import mips.LswInstr;
import mips.MipsBuilder;
import mips.Register;

import java.util.ArrayList;

public class Function extends GlobalValue {
    ArrayList<BasicBlock> basicBlocks = new ArrayList<>();
    private int num;
    private ArrayList<Value> params = new ArrayList<>();
    private int stackSpace = 0;

    public Function(String type, String name) {
        super(ValueType.FUNCTION, new IRType(type), name);
    }

    public void setParameters(ArrayList<Value> parameters) {
        this.num = parameters.size();
        this.params = parameters;
    }

    public void removeBlock(BasicBlock block) {
        ArrayList<Instruction> tmp = new ArrayList<>(block.getInstructions());
        for (Instruction ins : tmp) {
            block.removeInstr(ins);
        }
        basicBlocks.remove(block);
    }

    public boolean isReturn() {
        return getType().equals("int");
    }

    public void addBasicBlock(BasicBlock basicBlock) {
        basicBlocks.add(basicBlock);
    }

    public ArrayList<BasicBlock> getBasicBlocks() {
        return basicBlocks;
    }

    public int getStackSpace() {
        return stackSpace;
    }

    /**
     * Compute this function's stack frame size (bytes) without emitting MIPS.
     * This is required because call lowering uses the callee's stack size.
     */
    public void computeStackSpace() {
        int size = 0;
        for (BasicBlock basicBlock : basicBlocks) {
            size += basicBlock.getSpace();
        }
        for (Value param : params) {
            size += 4;
        }
        size += 4; // for $ra
        this.stackSpace = size;
    }

    public BasicBlock findInstrBlock(Instruction instr) {
        for (BasicBlock basicBlock : basicBlocks) {
            if (basicBlock.getInstructions().contains(instr)) {
                return basicBlock;
            }
        }
        return null;
    }

    @Override
    public void toMips() {
        // Ensure stackSpace is initialized and not accumulated.
        computeStackSpace();
        if (getName().equals("main")) {
            MipsBuilder.isMain = true;
        }
        MipsBuilder.curFunc = this;
        new Label(getName());
        new IInstr("subi", this);
        new LswInstr("sw", Register.RA, Register.SP, 0);
        MipsBuilder.memory -= 4;
        for (Value param : params) {
            if (param.getType().equals("ptr")) {
                param.getType().isAddr = true;
            }
            param.memory = MipsBuilder.memory;
            MipsBuilder.memory -= 4;
        }

        // Pre-assign stack slots for values before emitting instructions.
        // This avoids illegal -1($sp) accesses when a use is code-generated before its def block.
        for (BasicBlock basicBlock : basicBlocks) {
            for (Instruction instruction : basicBlock.getInstructions()) {
                if (instruction.getMemPos() != 1) {
                    continue;
                }
                int space = instruction.getSpace();
                if (space <= 0) {
                    continue;
                }
                instruction.memory = MipsBuilder.memory;
                MipsBuilder.memory -= space;
            }
        }
        // Resolve zext aliases after base slots exist.
        boolean changed;
        int guard = 0;
        do {
            changed = false;
            guard++;
            for (BasicBlock basicBlock : basicBlocks) {
                for (Instruction instruction : basicBlock.getInstructions()) {
                    if (instruction instanceof llvm.instr.ZextInstr) {
                        Value from = instruction.getUseValue(0);
                        int pos = from.getMemPos();
                        if (pos != 1 && instruction.getMemPos() == 1) {
                            instruction.memory = pos;
                            changed = true;
                        }
                    }
                }
            }
        } while (changed && guard < 8);
        for (int i = 0; i < basicBlocks.size(); i++) {
            if (i != 0) {
                new Label(getName()+"."+basicBlocks.get(i).getName());
            }
            basicBlocks.get(i).toMips();
        }
        // Keep stackSpace consistent with actual allocated memory.
        stackSpace = -MipsBuilder.memory;
        MipsBuilder.memory = 0;
    }

    public void print() {
        System.out.print("define dso_local ");
        if (isReturn()) {
            System.out.print("i32");
        }
        else {
            System.out.print("void");
        }
        System.out.print(" @"+name+"(");
        for (int i=0;i<num;i++) {
            System.out.print(params.get(i).getTypeName()+" "+params.get(i).getName());
            if (i<num-1) {
                System.out.print(", ");
            }
        }
        System.out.println(") {");
        for (int i = 0; i < basicBlocks.size(); i++) {
            if (i!=0) {
                System.out.println();
                System.out.println(basicBlocks.get(i).getName()+":");
            }
            basicBlocks.get(i).print();
        }
        System.out.println("}");
    }
}
