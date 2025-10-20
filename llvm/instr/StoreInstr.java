package llvm.instr;

import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;

public class StoreInstr extends Instruction {
    public StoreInstr(Value in, Value to) {
        super(ValueType.STORE_INST, ReturnType.VOID, "void");
        addUseValue(in);
        addUseValue(to);
    }

    @Override
    public void print() {
        Value in = (Value) getUseValue(0);
        Value to = (Value) getUseValue(1);
        in.print();
        System.out.println("store i32 "+in.getName()+", i32* "+to.getName());
    }
}
