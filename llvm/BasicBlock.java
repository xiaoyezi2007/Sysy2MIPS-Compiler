package llvm;

import llvm.instr.Instruction;
import llvm.instr.RetInstr;

import java.util.ArrayList;

public class BasicBlock extends Value {
    ArrayList<Instruction> instructions = new ArrayList<>();

    public BasicBlock() {
        super(ValueType.BASIC_BLOCK, new IRType("void"), "block");
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

    public void print() {
        for (Instruction instruction : instructions) {
            instruction.print();
        }
    }
}
