package llvm.instr;

import llvm.Builder;
import llvm.GlobalVariable;
import llvm.IRType;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;
import mips.IInstr;
import mips.LswInstr;
import mips.MipsBuilder;
import mips.Register;

public class StoreInstr extends Instruction {
    public StoreInstr(Value in, Value to) {
        super(ValueType.STORE_INST, new IRType("void"), "void");
        addUseValue(in);
        addUseValue(to);
        Builder.addInstr(this);
    }

    @Override
    public void toMips() {
        Value in = getUseValue(0);
        Value to = getUseValue(1);

        loadToReg(in, Register.T0);
        if (to instanceof GlobalVariable) {
            new LswInstr("sw", Register.T0, to.getName().substring(1));
        }
        else {
            new LswInstr("sw", Register.T0, Register.SP, to.getMemPos()- MipsBuilder.memory);
        }

    }

    @Override
    public void print() {
        Value in = (Value) getUseValue(0);
        Value to = (Value) getUseValue(1);
        System.out.println("store "+in.getTypeName()+" "+in.getName()+", "+to.getTypeName()+" "+to.getName());
    }
}
