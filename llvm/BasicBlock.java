package llvm;

import llvm.instr.Instruction;

import java.util.ArrayList;

public class BasicBlock extends Value {
    ArrayList<Instruction> instructions = new ArrayList<>();

    public BasicBlock(ValueType valueType, ReturnType Type, String name) {
        super(valueType, Type, name);
    }
}
