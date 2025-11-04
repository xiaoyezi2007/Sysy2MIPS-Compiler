package llvm.instr;

import llvm.Builder;
import llvm.IRType;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.ConstantVoid;
import mips.IInstr;
import mips.JInstr;
import mips.LswInstr;
import mips.MipsBuilder;
import mips.Register;
import mips.Syscall;
import mips.fake.LiInstr;

public class RetInstr extends Instruction {
    public RetInstr(Value returnValue) {
        super(ValueType.RETURN_INST, new IRType("void"), "return");
        addUseValue(returnValue);
        Builder.addInstr(this);
    }

    @Override
    public void toMips() {
        if (MipsBuilder.isMain) {
            new LiInstr(Register.V0, 10);
            new Syscall();
            return;
        }
        Value returnValue = getUseValue(0);
        if (!(returnValue instanceof ConstantVoid)) {
            loadToReg(returnValue, Register.V0);
        }
        new LswInstr("lw", Register.RA, Register.SP, 0);
        new IInstr("addiu", MipsBuilder.curFunc);
        new JInstr("jr", Register.RA);
    }

    @Override
    public void print() {
        Value returnValue = getUseValue(0);
        if (returnValue instanceof ConstantVoid) {
            System.out.println("ret void");
        }
        else {
            System.out.println("ret i32 "+returnValue.getName());
        }
    }

    @Override
    public boolean isDead() {
        return false;
    }
}
