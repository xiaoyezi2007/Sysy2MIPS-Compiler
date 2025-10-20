package llvm;

import llvm.instr.Instruction;

import java.util.ArrayList;

public class BasicBlock extends Value {
    ArrayList<Instruction> instructions = new ArrayList<>();

    public BasicBlock() {
        super(ValueType.BASIC_BLOCK, ReturnType.VOID, "block");
    }

    public void addInstruction(Instruction instruction) {
        instructions.add(instruction);
    }

    public void print() {
        System.out.println("{");
        for (Instruction instruction : instructions) {
            instruction.print();
        }
        System.out.println("}");
    }
}
