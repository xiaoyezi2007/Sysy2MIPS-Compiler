package llvm;

import llvm.instr.Instruction;
import llvm.instr.RetInstr;
import mips.Label;

import java.util.ArrayList;

public class BasicBlock extends Value {
    private Function fatherFunction;
    ArrayList<Instruction> instructions = new ArrayList<>();

    public BasicBlock() {
        super(ValueType.BASIC_BLOCK, new IRType("void"), "block");
    }

    public void setFatherFunction(Function fatherFunction) {
        this.fatherFunction = fatherFunction;
    }

    public void addInstruction(Instruction instruction) {
        instructions.add(instruction);
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public boolean isReturn() {
        return !instructions.isEmpty() && instructions.get(instructions.size()-1) instanceof RetInstr;
    }

    public String getMipsLabel() {
        return fatherFunction.name+"."+getName();
    }

    @Override
    public void toMips() {
        for (Instruction instruction : instructions) {
            instruction.toMips();
        }
    }

    public void print() {
        for (Instruction instruction : instructions) {
            instruction.print();
        }
    }
}
