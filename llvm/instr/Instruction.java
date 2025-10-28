package llvm.instr;

import llvm.Builder;
import llvm.GlobalVariable;
import llvm.IRType;
import llvm.ReturnType;
import llvm.User;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;
import mips.IInstr;
import mips.JInstr;
import mips.LswInstr;
import mips.MipsBuilder;
import mips.Register;
import mips.fake.LiInstr;

public abstract class Instruction extends User {
    public boolean isPrint = false;

    public Instruction(ValueType valueType, IRType Type, String name) {
        super(valueType, Type, name);
    }

    public Instruction(ValueType valueType, IRType Type) {
        super(valueType, Type, Builder.getVarName());
    }

    public void print() {

    }

    public Constant getValue() {
        return new ConstantInt(0);
    }

    protected void pushToMem(Register reg) {
        new IInstr("addi", Register.SP, Register.SP, -4);
        new LswInstr("sw", reg, Register.SP, 0);
        memory = MipsBuilder.memory;
    }

    protected void loadToReg(Value value, Register to) {
        if (value instanceof ConstantInt) {
            new LiInstr(to, Integer.parseInt(value.getName()));
            return;
        }
        else if (value instanceof GlobalVariable) {
            new LswInstr("lw", to, ((GlobalVariable) value).getName().substring(1));
        }
        new LswInstr("lw", to, Register.SP, value.getMemPos() - MipsBuilder.memory);
    }

}
