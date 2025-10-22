package llvm.instr;

import llvm.Builder;
import llvm.IRType;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;

public class StoreInstr extends Instruction {
    public StoreInstr(Value in, Value to) {
        super(ValueType.STORE_INST, new IRType("void"), "void");
        addUseValue(in);
        addUseValue(to);
        Builder.addInstr(this);
    }

    @Override
    public void print() {
        if (isPrint) {
            return;
        }
        isPrint = true;
        Value in = (Value) getUseValue(0);
        Value to = (Value) getUseValue(1);
        if (to instanceof GepInstr && to.before(in)) {
            to.print();
        }
        in.print();
        if (to instanceof GepInstr && in.before(to)) {
            to.print();
        }
        System.out.println("store "+in.getTypeName()+" "+in.getName()+", "+to.getTypeName()+" "+to.getName());
    }
}
